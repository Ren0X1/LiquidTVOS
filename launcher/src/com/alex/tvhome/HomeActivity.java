package com.alex.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Apple TV style home screen. All focus is handled here, not by the Android
 * focus system, so D-pad moves are deterministic: Up/Down move exactly one
 * row, the ends of a row stop, Up from the dock goes to the top bar.
 */
public class HomeActivity extends Activity {

    // Layout grid, in dp, for a 960x540dp screen
    private static final int MARGIN = 48;
    private static final int BAND_LEFT = 32;
    private static final int BAND_PAD = 24;
    private static final int GAP = 24;
    private static final int TILE_W = 194;
    private static final int TILE_H = 109;
    private static final int BAND_BOTTOM_GAP = 32;
    private static final int ROW_H = TILE_H + 52;
    private static final int COLS = 4;
    private static final int PICKER_TOP = 112;
    private static final float FOCUS_SCALE = 1.08f;

    private static final int TOP = 0, DOCK = 1, GRID = 2;
    private static final String EDIT = "edit", CLEAN = "clean", WIFI = "wifi", SETTINGS = "settings";

    private static final int TEXT = 0xFFF5F5F7;
    private static final int TEXT_2 = 0xFFAEAEB2;
    private static final int TILE_EMPTY = 0xE61C1C1E;
    private static final int SHADOW = 0x99000000;

    static class App {
        String pkg;
        String label;
        ComponentName component;
        boolean leanback;
        Drawable banner;
        Drawable icon;
        View tile;
        TextView caption;
    }

    static class Photo {
        String file, blur, place, city, author;
    }

    private final List<App> dock = new ArrayList<>();
    private final List<App> grid = new ArrayList<>();
    private final List<Photo> photos = new ArrayList<>();

    private FrameLayout root, page;
    private View highlight;
    private View addTile;
    private TextView greeting, clock, date, dockTitle, notice;
    private GlassDrawable bandGlass;
    private LinearLayout weatherRow;
    private ImageView weatherIcon;
    private TextView weatherTemp, weatherText;
    private static final long WEATHER_MS = 30 * 60_000L;
    private static final long WEATHER_RETRY_MS = 30_000L;
    private String weatherTown = "";
    private boolean noticeIsResult, idle;
    private static final long IDLE_MS = 60_000L;
    private TextView wifiLabel, placeLabel, authorLabel;
    private View creditRow;
    private final List<View> pills = new ArrayList<>();
    private final List<View> glassHosts = new ArrayList<>();

    private ImageView bgBack, bgFront, bgBlur;
    private View gridDim;
    private final GlassDrawable.Backdrop backdrop = new GlassDrawable.Backdrop();
    private int photoIndex;
    private boolean startedOnce;

    private int section = DOCK;
    private int topIndex = 2, dockIndex = 0, gridIndex = 0;
    private boolean editMode;
    private View focusedTile;
    private ValueAnimator highlightAnim, backdropAnim;
    private boolean longPressHandled;

    // dock editor: a glass sheet listing the apps to choose from
    private FrameLayout picker, pickerPage;
    private View pickerHighlight;
    private TextView pickerTitle;
    private final List<App> pickList = new ArrayList<>();
    private final List<View> pickTiles = new ArrayList<>();
    private final List<TextView> pickCaptions = new ArrayList<>();
    private int pickIndex, editSlot;

    private float density;
    private int bandTop, bandH, gridTop;
    private final Map<Integer, Typeface> fonts = new HashMap<>();
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String appsSignature = "";

    // ---------------------------------------------------------------- setup

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        backdrop.screenW = getResources().getDisplayMetrics().widthPixels;
        backdrop.screenH = getResources().getDisplayMetrics().heightPixels;

        int screenH = Math.round(backdrop.screenH / density);
        bandH = TILE_H + 2 * BAND_PAD;
        bandTop = screenH - BAND_BOTTOM_GAP - bandH;
        gridTop = bandTop + bandH + 36;

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        buildBackground();

        page = new FrameLayout(this);
        page.setClipChildren(false);
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        buildChrome();
        buildCredit();
        buildPicker();

        loadApps();
        buildTiles();

        setContentView(root);
        loadPhotoIndex();
        photoIndex = prefs.getInt("photo", 0);
        showPhoto(false);
        restoreFocus();
        if (KeyService.SHOW_APPS.equals(getIntent().getAction()) && !grid.isEmpty()) section = GRID;
        page.post(() -> moveFocus(false));

        // listen all the time, not only while visible: apps are usually
        // installed or removed while another screen is in front
        IntentFilter pf = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pf.addDataScheme("package");
        registerReceiver(pkgReceiver, pf);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(pkgReceiver);
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void buildBackground() {
        bgBack = photoView();
        bgFront = photoView();
        bgFront.setAlpha(0f);
        root.addView(bgBack, new FrameLayout.LayoutParams(-1, -1));
        root.addView(bgFront, new FrameLayout.LayoutParams(-1, -1));

        // the grid sits on the blurred photo, like tvOS
        bgBlur = new ImageView(this);
        bgBlur.setScaleType(ImageView.ScaleType.FIT_XY);
        bgBlur.setAlpha(0f);
        root.addView(bgBlur, new FrameLayout.LayoutParams(-1, -1));
        gridDim = new View(this);
        gridDim.setBackgroundColor(0x73000000);
        gridDim.setAlpha(0f);
        root.addView(gridDim, new FrameLayout.LayoutParams(-1, -1));

        // keep the clock and the dock readable on bright photos
        View top = new View(this);
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x99000000, 0x40000000, 0x00000000}));
        root.addView(top, new FrameLayout.LayoutParams(-1, dp(300), Gravity.TOP));
        // reaches up past the place name above the dock so it always reads
        View bottom = new View(this);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xC7000000, 0x8C000000, 0x59000000, 0x00000000}));
        root.addView(bottom, new FrameLayout.LayoutParams(-1, dp(430), Gravity.BOTTOM));
    }

    private ImageView photoView() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    private void buildChrome() {
        greeting = text(18, 500, TEXT);
        greeting.setAlpha(0.85f);
        page.addView(greeting, at(MARGIN, 36, -2, -2));

        // one line under the greeting: icon, temperature, sky
        weatherRow = new LinearLayout(this);
        weatherRow.setOrientation(LinearLayout.HORIZONTAL);
        weatherRow.setGravity(Gravity.CENTER_VERTICAL);
        weatherRow.setVisibility(View.INVISIBLE);
        weatherIcon = new ImageView(this);
        weatherRow.addView(weatherIcon, new LinearLayout.LayoutParams(dp(22), dp(22)));
        weatherTemp = text(18, 500, TEXT);
        weatherTemp.setSingleLine(true);
        LinearLayout.LayoutParams wt = new LinearLayout.LayoutParams(-2, -2);
        wt.leftMargin = dp(8);
        weatherRow.addView(weatherTemp, wt);
        weatherText = text(18, 400, TEXT);
        weatherText.setSingleLine(true);
        weatherText.setAlpha(0.85f);
        LinearLayout.LayoutParams wd = new LinearLayout.LayoutParams(-2, -2);
        wd.leftMargin = dp(8);
        weatherRow.addView(weatherText, wd);
        page.addView(weatherRow, at(MARGIN, 64, -2, -2));

        clock = text(88, 300, TEXT);
        clock.setIncludeFontPadding(false);
        clock.setLetterSpacing(-0.04f);
        page.addView(clock, at(MARGIN - 4, 92, -2, -2));
        date = text(18, 400, TEXT);
        date.setAlpha(0.85f);
        page.addView(date, at(MARGIN, 208, -2, -2));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setClipChildren(false);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        blp.topMargin = dp(44);
        blp.rightMargin = dp(MARGIN);
        page.addView(bar, blp);
        addPill(bar, R.drawable.ic_edit, null, EDIT);
        addPill(bar, R.drawable.ic_trash, null, CLEAN);
        View wifi = addPill(bar, R.drawable.ic_wifi, getString(R.string.wifi_generic), WIFI);
        wifiLabel = (TextView) ((ViewGroup) wifi).getChildAt(1);
        addPill(bar, R.drawable.ic_settings, getString(R.string.settings), SETTINGS);

        // names the focused pill, and carries the trash result, on smoked glass
        notice = text(15, 400, TEXT);
        notice.setShadowLayer(0, 0, 0, 0);
        notice.setPadding(dp(16), dp(8), dp(16), dp(9));
        notice.setBackground(new GlassDrawable(notice, backdrop, dp(14), 0x66000000, 0x80FFFFFF));
        notice.setAlpha(0f);
        glassHosts.add(notice);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        nlp.topMargin = dp(108);
        nlp.rightMargin = dp(MARGIN);
        page.addView(notice, nlp);

        dockTitle = text(40, 500, TEXT);
        dockTitle.setLetterSpacing(-0.02f);
        dockTitle.setSingleLine(true);
        page.addView(dockTitle, at(MARGIN, bandTop - 104, 860, -2));

        View band = new View(this);
        bandGlass = new GlassDrawable(band, backdrop, dp(28), 0x1FFFFFFF, 0x99FFFFFF);
        band.setBackground(bandGlass);
        band.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        glassHosts.add(band);
        page.addView(band, at(BAND_LEFT, bandTop, 960 - 2 * BAND_LEFT, bandH));

        highlight = new View(this);
        highlight.setBackground(new GlassDrawable(highlight, null, dp(14), 0x30FFFFFF, 0xE6FFFFFF));
        highlight.setElevation(dp(8));
    }

    private View addPill(LinearLayout bar, int icon, String label, String action) {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER);
        if (label == null) p.setPadding(dp(11), dp(11), dp(11), dp(11));
        else p.setPadding(dp(18), dp(11), dp(20), dp(11));
        // smoked glass: the sky behind the pills is often the brightest part of the photo
        p.setBackground(new GlassDrawable(p, backdrop, -1, 0x52000000, 0xB3FFFFFF));
        glassHosts.add(p);
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        p.addView(iv, new LinearLayout.LayoutParams(dp(20), dp(20)));
        if (label != null) {
            TextView t = text(16, 400, TEXT);
            t.setShadowLayer(0, 0, 0, 0);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
            tlp.leftMargin = dp(10);
            t.setText(label);
            p.addView(t, tlp);
        }
        p.setTag(action);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        if (!pills.isEmpty()) lp.leftMargin = dp(12);
        bar.addView(p, lp);
        pills.add(p);
        return p;
    }

    private void buildCredit() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView pin = new ImageView(this);
        pin.setImageResource(R.drawable.ic_place);
        pin.setAlpha(0.9f);
        row.addView(pin, new LinearLayout.LayoutParams(dp(16), dp(16)));
        placeLabel = text(17, 500, TEXT);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, -2);
        plp.leftMargin = dp(6);
        row.addView(placeLabel, plp);
        authorLabel = text(13, 400, TEXT_2);
        authorLabel.setSingleLine(true);
        authorLabel.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, -2);
        alp.leftMargin = dp(14);
        row.addView(authorLabel, alp);
        creditRow = row;
        // sits under the app name, just above the dock glass
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        lp.leftMargin = dp(MARGIN + 2);
        lp.topMargin = dp(bandTop - 36);
        root.addView(row, lp);
    }

    private void buildPicker() {
        picker = new FrameLayout(this);
        picker.setVisibility(View.GONE);
        picker.setClipChildren(false);
        ImageView blur = new ImageView(this);
        blur.setScaleType(ImageView.ScaleType.FIT_XY);
        blur.setTag("blur");
        picker.addView(blur, new FrameLayout.LayoutParams(-1, -1));
        View dim = new View(this);
        dim.setBackgroundColor(0xA6000000);
        picker.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        pickerPage = new FrameLayout(this);
        pickerPage.setClipChildren(false);
        picker.addView(pickerPage, new FrameLayout.LayoutParams(-1, -1));
        pickerTitle = text(30, 500, TEXT);
        picker.addView(pickerTitle, at(MARGIN, 40, -2, -2));
        pickerHighlight = new View(this);
        pickerHighlight.setBackground(new GlassDrawable(pickerHighlight, null, dp(14), 0x30FFFFFF, 0xE6FFFFFF));
        pickerHighlight.setElevation(dp(8));
        root.addView(picker, new FrameLayout.LayoutParams(-1, -1));
    }

    // --------------------------------------------------------------- photos

    private void loadPhotoIndex() {
        try (InputStream in = getAssets().open("photos/index.json")) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
            JSONArray arr = new JSONArray(buf.toString("UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Photo p = new Photo();
                p.file = o.getString("file");
                p.blur = o.getString("blur");
                p.place = o.getString("place");
                p.city = o.optString("city", "");
                p.author = o.optString("author", "");
                photos.add(p);
            }
        } catch (Exception ignored) {
            // no photos: the background stays black
        }
        creditRow.setVisibility(photos.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void nextPhoto() {
        if (photos.size() < 2) return;
        photoIndex = (photoIndex + 1) % photos.size();
        showPhoto(true);
    }

    private final Runnable rotate = new Runnable() {
        @Override
        public void run() {
            nextPhoto();
            handler.postDelayed(this, getResources().getInteger(R.integer.photo_minutes) * 60_000L);
        }
    };

    private void showPhoto(boolean animate) {
        if (photos.isEmpty()) return;
        photoIndex %= photos.size();
        prefs.edit().putInt("photo", photoIndex).apply();
        final Photo p = photos.get(photoIndex);
        new Thread(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap full = decode("photos/" + p.file, o);
            Bitmap blur = decode("photos/" + p.blur, new BitmapFactory.Options());
            if (full == null || blur == null) return;
            runOnUiThread(() -> applyPhoto(p, full, blur, animate));
        }).start();
    }

    private Bitmap decode(String asset, BitmapFactory.Options o) {
        try (InputStream in = getAssets().open(asset)) {
            return BitmapFactory.decodeStream(in, null, o);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Keep the dock glass at a steady brightness: over a bright part of the
     * photo it is smoked, over a dark part it is lightly frosted.
     */
    private void adaptDockGlass(Bitmap blur) {
        int screenH = Math.round(backdrop.screenH / density);
        int y0 = blur.getHeight() * bandTop / screenH;
        int y1 = Math.min(blur.getHeight(), blur.getHeight() * (bandTop + bandH) / screenH);
        double sum = 0;
        int n = 0;
        for (int y = y0; y < y1; y += 4) {
            for (int x = 0; x < blur.getWidth(); x += 4) {
                int c = blur.getPixel(x, y);
                sum += (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)) / 255.0;
                n++;
            }
        }
        double lum = n == 0 ? 0.3 : sum / n;
        int tint;
        if (lum < 0.28) tint = 0x1FFFFFFF;
        else tint = Math.min(0x99, (int) ((lum - 0.15) * 380)) << 24;
        bandGlass.setGlassTint(tint);
    }

    private void applyPhoto(Photo p, Bitmap full, Bitmap blur, boolean animate) {
        if (isDestroyed()) return;
        bgBlur.setImageBitmap(blur);
        View pb = picker.findViewWithTag("blur");
        ((ImageView) pb).setImageBitmap(blur);
        String place = p.city.isEmpty() || p.place.equals(p.city) ? p.place : p.place + ", " + p.city;
        String author = p.author.isEmpty() ? "" : getString(R.string.photo_credit, p.author);
        adaptDockGlass(blur);

        if (!animate || backdrop.blur == null) {
            bgBack.setImageBitmap(full);
            backdrop.blur = blur;
            backdrop.previous = null;
            backdrop.fade = 1f;
            placeLabel.setText(place);
            authorLabel.setText(author);
            invalidateGlass();
            return;
        }
        bgFront.setImageBitmap(full);
        bgFront.setAlpha(0f);
        bgFront.animate().alpha(1f).setDuration(1200).setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                bgBack.setImageBitmap(full);
                bgFront.setAlpha(0f);
                bgFront.setImageDrawable(null);
                bgFront.animate().setListener(null);
            }
        }).start();

        if (backdropAnim != null) backdropAnim.cancel();
        backdrop.previous = backdrop.blur;
        backdrop.blur = blur;
        backdrop.fade = 0f;
        backdropAnim = ValueAnimator.ofFloat(0f, 1f);
        backdropAnim.setDuration(1200);
        backdropAnim.addUpdateListener(va -> {
            backdrop.fade = (float) va.getAnimatedValue();
            invalidateGlass();
        });
        backdropAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                backdrop.previous = null;
            }
        });
        backdropAnim.start();

        creditRow.animate().alpha(0f).setDuration(400).withEndAction(() -> {
            placeLabel.setText(place);
            authorLabel.setText(author);
            creditRow.animate().alpha(section == GRID ? 0f : 1f).setDuration(600).start();
        }).start();
    }

    private void invalidateGlass() {
        for (View v : glassHosts) v.invalidate();
    }

    // ----------------------------------------------------------------- apps

    private List<ResolveInfo> launchables() {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> all = new ArrayList<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            all.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0));
        }
        return all;
    }

    private String signature(List<ResolveInfo> list) {
        Set<String> s = new TreeSet<>();
        for (ResolveInfo ri : list) s.add(ri.activityInfo.packageName);
        return s.toString();
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Set<String> hidden = new HashSet<>(Arrays.asList(getResources().getStringArray(R.array.hidden_packages)));
        hidden.add(getPackageName());
        Map<String, App> byPkg = new HashMap<>();
        List<ResolveInfo> all = launchables();
        appsSignature = signature(all);

        for (ResolveInfo ri : all) {
            ActivityInfo ai = ri.activityInfo;
            if (hidden.contains(ai.packageName) || byPkg.containsKey(ai.packageName)) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.component = new ComponentName(ai.packageName, ai.name);
            a.leanback = ri.filter != null && ri.filter.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    || pm.getLeanbackLaunchIntentForPackage(a.pkg) != null;
            a.banner = ai.loadBanner(pm);
            if (a.banner == null) a.banner = ai.applicationInfo.loadBanner(pm);
            if (a.banner == null) a.icon = ri.loadIcon(pm);
            byPkg.put(a.pkg, a);
        }

        dock.clear();
        grid.clear();
        for (String pkg : dockPackages()) {
            App a = byPkg.remove(pkg);
            if (a != null && dock.size() < COLS) dock.add(a);
        }
        grid.addAll(byPkg.values());
        sortByLabel(grid);
    }

    private List<String> dockPackages() {
        String saved = prefs.getString("dock_order", null);
        if (saved != null) return Arrays.asList(TextUtils.split(saved, ","));
        return Arrays.asList(getResources().getStringArray(R.array.dock_packages));
    }

    private static void sortByLabel(List<App> list) {
        Collator c = Collator.getInstance(new Locale("es"));
        Collections.sort(list, (x, y) -> c.compare(x.label, y.label));
    }

    private void buildTiles() {
        for (int i = 0; i < dock.size(); i++) {
            App a = dock.get(i);
            a.tile = tile(a);
            page.addView(a.tile, at(colX(i), bandTop + BAND_PAD, TILE_W, TILE_H));
        }
        // empty slot, only visible while editing a dock with room left
        addTile = null;
        if (dock.size() < COLS) {
            TextView plus = text(40, 200, TEXT);
            plus.setText("+");
            plus.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x14FFFFFF);
            bg.setStroke(dp(1), 0x40FFFFFF);
            bg.setCornerRadius(dp(10));
            plus.setBackground(bg);
            plus.setAlpha(editMode ? 1f : 0f);
            addTile = plus;
            page.addView(plus, at(colX(dock.size()), bandTop + BAND_PAD, TILE_W, TILE_H));
        }
        for (int i = 0; i < grid.size(); i++) {
            App a = grid.get(i);
            int x = colX(i % COLS), y = gridTop + (i / COLS) * ROW_H;
            a.tile = tile(a);
            page.addView(a.tile, at(x, y, TILE_W, TILE_H));
            a.caption = caption(a.label);
            a.caption.setAlpha(0f);
            page.addView(a.caption, at(x - 20, y + TILE_H + 12, TILE_W + 40, -2));
        }
        // the page is as tall as its content, so tiles below the screen get a real size
        int rows = (grid.size() + COLS - 1) / COLS;
        int pageH = Math.round(backdrop.screenH / density);
        page.getLayoutParams().height = dp(Math.max(pageH, gridTop + rows * ROW_H + 40));
        page.requestLayout();
        if (highlight.getParent() != null) page.removeView(highlight);
        page.addView(highlight, at(0, 0, TILE_W, TILE_H));
        highlight.setAlpha(0f);
    }

    private TextView caption(String label) {
        TextView t = text(15, 400, TEXT);
        t.setText(label);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private View tile(App a) {
        FrameLayout t = new FrameLayout(this);
        final float r = dp(10);
        t.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
            }
        });
        t.setClipToOutline(true);
        t.setElevation(dp(3));
        if (a.banner != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(a.banner);
            t.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            t.setBackgroundColor(TILE_EMPTY);
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(a.icon);
            FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER_HORIZONTAL | Gravity.TOP);
            ilp.topMargin = dp(18);
            t.addView(iv, ilp);
            TextView l = text(13, 400, TEXT_2);
            l.setShadowLayer(0, 0, 0, 0);
            l.setText(a.label);
            l.setSingleLine(true);
            l.setGravity(Gravity.CENTER_HORIZONTAL);
            FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            llp.bottomMargin = dp(14);
            l.setPadding(dp(8), 0, dp(8), 0);
            t.addView(l, llp);
        }
        return t;
    }

    private void rebuild() {
        if (picker.getVisibility() == View.VISIBLE) closePicker();
        editMode = false;
        String keep = currentPkg();
        for (App a : dock) page.removeView(a.tile);
        for (App a : grid) {
            page.removeView(a.tile);
            page.removeView(a.caption);
        }
        if (addTile != null) page.removeView(addTile);
        focusedTile = null;
        loadApps();
        buildTiles();
        selectPkg(keep);
        page.post(() -> moveFocus(false));
    }

    // ---------------------------------------------------------------- focus

    private int swallowUp = -1;
    private static final long LONG_PRESS_MS = 700;

    private final Runnable longPressTimer = () -> {
        longPressHandled = true;
        longPress();
    };

    private final Runnable goIdle = () -> {
        if (picker.getVisibility() == View.VISIBLE || editMode) return;
        idle = true;
        page.animate().alpha(0f).setDuration(1500).start();
        bgBlur.animate().alpha(0f).setDuration(1500).start();
        gridDim.animate().alpha(0f).setDuration(1500).start();
        creditRow.animate().alpha(1f).translationY(dp(48 - (bandTop - 36))).setDuration(1500).start();
    };

    private void scheduleIdle() {
        handler.removeCallbacks(goIdle);
        handler.postDelayed(goIdle, IDLE_MS);
    }

    /** Any key brings the screen back; that key does nothing else. */
    private void wake() {
        idle = false;
        page.animate().alpha(1f).setDuration(300).start();
        creditRow.animate().translationY(0).setDuration(300).start();
        moveFocus(false);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_HOME) return super.dispatchKeyEvent(e);
        if (idle) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                wake();
                swallowUp = k;
                scheduleIdle();
            }
            return true;
        }
        if (swallowUp == k) {
            if (e.getAction() == KeyEvent.ACTION_UP) swallowUp = -1;
            return true;
        }
        scheduleIdle();
        boolean center = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A;
        boolean inPicker = picker.getVisibility() == View.VISIBLE;
        if (center) {
            // timed, not counted: the Xiaomi BLE remote sends no key repeats
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) {
                    longPressHandled = false;
                    handler.removeCallbacks(longPressTimer);
                    if (!inPicker) handler.postDelayed(longPressTimer, LONG_PRESS_MS);
                }
            } else if (e.getAction() == KeyEvent.ACTION_UP) {
                handler.removeCallbacks(longPressTimer);
                if (!longPressHandled) {
                    if (inPicker) choosePicked();
                    else activate();
                }
            }
            return true;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) {
            return k == KeyEvent.KEYCODE_BACK || super.dispatchKeyEvent(e);
        }
        if (inPicker) {
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_UP: pickMove(-COLS); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: pickMove(COLS); return true;
                case KeyEvent.KEYCODE_DPAD_LEFT: pickMove(-1); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: pickMove(1); return true;
                case KeyEvent.KEYCODE_BACK: closePicker(); return true;
            }
            return true;
        }
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: up(); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: down(); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: side(-1); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: side(1); return true;
            case KeyEvent.KEYCODE_MENU: longPress(); return true;
            case KeyEvent.KEYCODE_BACK:
                if (editMode) setEditMode(false);
                else if (section != DOCK && !dock.isEmpty()) {
                    section = DOCK;
                    moveFocus(true);
                }
                return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private int dockSlots() {
        return editMode && dock.size() < COLS ? dock.size() + 1 : dock.size();
    }

    private void up() {
        if (editMode) setEditMode(false);
        if (section == GRID) {
            if (gridIndex >= COLS) gridIndex -= COLS;
            else if (!dock.isEmpty()) {
                section = DOCK;
                dockIndex = Math.min(gridIndex % COLS, dock.size() - 1);
            } else section = TOP;
        } else if (section == DOCK) {
            section = TOP;
        } else return;
        moveFocus(true);
    }

    private void down() {
        if (editMode) return;
        if (section == TOP) {
            if (!dock.isEmpty()) section = DOCK;
            else if (!grid.isEmpty()) section = GRID;
            else return;
        } else if (section == DOCK) {
            if (grid.isEmpty()) return;
            section = GRID;
            gridIndex = Math.min(dockIndex, grid.size() - 1);
        } else {
            int lastRow = (grid.size() - 1) / COLS;
            if (gridIndex / COLS >= lastRow) return;
            gridIndex = Math.min(gridIndex + COLS, grid.size() - 1);
        }
        moveFocus(true);
    }

    private void side(int d) {
        if (section == TOP) {
            int n = topIndex + d;
            if (n < 0 || n >= pills.size()) return;
            topIndex = n;
        } else if (section == DOCK) {
            int n = dockIndex + d;
            if (n < 0 || n >= dockSlots()) return;
            dockIndex = n;
        } else {
            int n = gridIndex + d;
            if (n < 0 || n >= grid.size() || n / COLS != gridIndex / COLS) return;
            gridIndex = n;
        }
        moveFocus(true);
    }

    private void moveFocus(boolean animate) {
        if (section == DOCK && dockSlots() == 0) section = grid.isEmpty() ? TOP : GRID;
        if (section == GRID && grid.isEmpty()) section = TOP;

        View target;
        App app = null;
        if (section == TOP) target = pills.get(topIndex);
        else if (section == DOCK) {
            if (dockIndex >= dock.size()) target = addTile;
            else target = (app = dock.get(dockIndex)).tile;
        } else target = (app = grid.get(gridIndex)).tile;

        // scroll: dock and top bar live at rest; the grid pulls the page up
        float ty = 0;
        if (section == GRID) {
            int row = gridIndex / COLS;
            ty = -dp(bandTop - 36) - dp(ROW_H) * Math.max(0, row - 1);
        }
        if (animate && page.getTranslationY() != ty) {
            page.animate().translationY(ty).setDuration(380).setInterpolator(new DecelerateInterpolator(2f))
                    .setUpdateListener(a -> invalidateGlass()).start();
        } else if (!animate) {
            page.setTranslationY(ty);
        }
        boolean gridMode = section == GRID;
        bgBlur.animate().alpha(gridMode ? 1f : 0f).setDuration(400).start();
        gridDim.animate().alpha(gridMode ? 1f : 0f).setDuration(400).start();
        creditRow.animate().alpha(gridMode ? 0f : 1f).setDuration(300).start();

        if (focusedTile != null && focusedTile != target) {
            focusedTile.animate().scaleX(1f).scaleY(1f).translationZ(0).setDuration(200).start();
        }
        for (App a : grid) {
            float alpha = a == app ? 1f : 0f;
            if (a.caption.getAlpha() != alpha) a.caption.animate().alpha(alpha).setDuration(180).start();
        }
        // edit mode: the other dock tiles step back, the pencil stays lit
        for (App a : dock) {
            float alpha = editMode && a.tile != target ? 0.45f : 1f;
            if (a.tile.getAlpha() != alpha) a.tile.animate().alpha(alpha).setDuration(200).start();
        }
        ((GlassDrawable) pills.get(0).getBackground()).setGlassTint(editMode ? 0x80FFFFFF : 0x52000000);
        if (section != TOP) {
            target.animate().scaleX(FOCUS_SCALE).scaleY(FOCUS_SCALE).translationZ(dp(6)).setDuration(240)
                    .setInterpolator(new OvershootInterpolator(2f)).start();
        }
        focusedTile = section == TOP ? null : target;

        Rect r = rectIn(target, page);
        if (section != TOP) {
            int dx = Math.round(r.width() * (FOCUS_SCALE - 1) / 2) + dp(5);
            int dy = Math.round(r.height() * (FOCUS_SCALE - 1) / 2) + dp(5);
            r.inset(-dx, -dy);
        }
        GlassDrawable hg = (GlassDrawable) highlight.getBackground();
        hg.setRadius(section == TOP ? -1 : dp(14));
        // over a pill the focus has to read against glass, not a banner
        hg.setGlassTint(section == TOP ? 0x6BFFFFFF : 0x30FFFFFF);
        hg.setRing(section != TOP);
        if (section == TOP) showPillName();
        else if (!noticeIsResult) hideNotice();
        if (highlight.getAlpha() == 0f) animate = false;
        highlight.animate().alpha(1f).setDuration(150).start();
        highlightAnim = liquidMove(highlight, r, animate, highlightAnim);

        // dock title
        float titleAlpha = section == DOCK ? 1f : 0f;
        dockTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, editMode ? 30 : 44);
        if (editMode) dockTitle.setText((app == null ? getString(R.string.edit_add) : getString(R.string.edit_pick_slot))
                + " · " + getString(R.string.edit_hint));
        else if (section == DOCK && app != null) dockTitle.setText(app.label);
        else if (dockTitle.getText().length() == 0 && !dock.isEmpty()) dockTitle.setText(dock.get(0).label);
        dockTitle.animate().alpha(titleAlpha).setDuration(220).start();

        float headAlpha = gridMode ? 0f : 1f;
        weatherRow.animate().alpha(headAlpha).setDuration(220).start();
        for (View v : new View[]{greeting, clock, date}) {
            if (v.getAlpha() != headAlpha * (v == clock ? 1f : 0.85f)) {
                v.animate().alpha(headAlpha * (v == clock ? 1f : 0.85f)).setDuration(220).start();
            }
        }
        saveFocus();
    }

    private Rect rectIn(View v, View ancestor) {
        int x = 0, y = 0;
        View cur = v;
        while (cur != null && cur != ancestor) {
            x += cur.getLeft();
            y += cur.getTop();
            cur = (View) cur.getParent();
        }
        return new Rect(x, y, x + v.getWidth(), y + v.getHeight());
    }

    /**
     * Moves the glass like a drop of liquid: the edge facing the move leaves
     * first and overshoots, the trailing edge follows a beat later, so the
     * glass stretches on the way and springs back into shape on arrival.
     */
    private ValueAnimator liquidMove(View v, Rect to, boolean animate, ValueAnimator running) {
        if (running != null) running.cancel();
        final Rect from = new Rect(v.getLeft(), v.getTop(), v.getRight(), v.getBottom());
        if (!animate || from.width() == 0) {
            place(v, to.left, to.top, to.right, to.bottom);
            return null;
        }
        final Interpolator lead = new OvershootInterpolator(1.3f);
        final Interpolator trail = new DecelerateInterpolator(1.6f);
        final boolean right = to.centerX() > from.centerX();
        final boolean downward = to.centerY() > from.centerY();
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(420);
        va.addUpdateListener(an -> {
            float t = (float) an.getAnimatedValue();
            float fl = lead.getInterpolation(Math.min(1f, t * 1.25f));
            float ft = trail.getInterpolation(Math.max(0f, (t - 0.12f) / 0.88f));
            int l = lerp(from.left, to.left, right ? ft : fl);
            int rr = lerp(from.right, to.right, right ? fl : ft);
            int tp = lerp(from.top, to.top, downward ? ft : fl);
            int b = lerp(from.bottom, to.bottom, downward ? fl : ft);
            place(v, Math.min(l, rr - dp(8)), Math.min(tp, b - dp(8)), rr, b);
        });
        va.start();
        return va;
    }

    private void place(View v, int l, int t, int r, int b) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
        lp.leftMargin = l;
        lp.topMargin = t;
        lp.width = r - l;
        lp.height = b - t;
        // move without a full relayout of the page on every frame
        v.layout(l, t, r, b);
    }

    private String currentPkg() {
        if (section == DOCK && dockIndex < dock.size()) return dock.get(dockIndex).pkg;
        if (section == GRID && !grid.isEmpty()) return grid.get(Math.min(gridIndex, grid.size() - 1)).pkg;
        return null;
    }

    private void selectPkg(String pkg) {
        dockIndex = Math.min(dockIndex, Math.max(0, dock.size() - 1));
        gridIndex = Math.min(gridIndex, Math.max(0, grid.size() - 1));
        if (pkg == null) return;
        for (int i = 0; i < dock.size(); i++) if (dock.get(i).pkg.equals(pkg)) { section = DOCK; dockIndex = i; return; }
        for (int i = 0; i < grid.size(); i++) if (grid.get(i).pkg.equals(pkg)) { section = GRID; gridIndex = i; return; }
    }

    private void saveFocus() {
        SharedPreferences.Editor e = prefs.edit().putInt("top", topIndex);
        if (dockIndex < dock.size()) e.putString("dock", dock.get(dockIndex).pkg);
        if (!grid.isEmpty()) e.putString("grid", grid.get(gridIndex).pkg);
        e.apply();
    }

    /** Start on the dock, with the last tile of each row remembered. */
    private void restoreFocus() {
        topIndex = Math.min(prefs.getInt("top", 2), pills.size() - 1);
        selectPkg(prefs.getString("grid", null));
        selectPkg(prefs.getString("dock", null));
        section = DOCK;
    }

    // -------------------------------------------------------------- actions

    private void activate() {
        if (section == TOP) {
            String action = (String) pills.get(topIndex).getTag();
            switch (action) {
                case EDIT: setEditMode(true); return;
                case CLEAN: clean(); return;
                case WIFI: launch(new Intent(Settings.ACTION_WIFI_SETTINGS), "Wi-Fi"); return;
                default: launch(new Intent(Settings.ACTION_SETTINGS), getString(R.string.settings)); return;
            }
        }
        if (editMode && section == DOCK) {
            openPicker(dockIndex);
            return;
        }
        if (section == DOCK && dockIndex >= dock.size()) return;
        App a = section == DOCK ? dock.get(dockIndex) : grid.get(gridIndex);
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(a.leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER)
                .setComponent(a.component);
        launch(i, a.label);
    }

    private void launch(Intent i, String what) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(this, getString(R.string.cannot_open, what), Toast.LENGTH_SHORT).show();
        }
    }

    private void longPress() {
        if (section == TOP) {
            // Android 9 only lets the system clear other apps' caches:
            // Storage > Cached data clears all of them at once
            if (CLEAN.equals(pills.get(topIndex).getTag())) {
                launch(new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS), getString(R.string.settings));
            }
            return;
        }
        String pkg = currentPkg();
        if (pkg == null) return;
        launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)), pkg);
    }

    /** Close the apps left running in the background and say how much memory came back. */
    private void clean() {
        final ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        final ActivityManager.MemoryInfo before = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(before);
        Set<String> targets = new HashSet<>();
        for (App a : dock) targets.add(a.pkg);
        for (App a : grid) targets.add(a.pkg);
        targets.add("com.android.vending");
        for (String pkg : targets) am.killBackgroundProcesses(pkg);
        handler.postDelayed(() -> {
            ActivityManager.MemoryInfo after = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(after);
            long freed = (after.availMem - before.availMem) / (1024 * 1024);
            long avail = after.availMem / (1024 * 1024);
            String msg = freed >= 5 ? getString(R.string.cleaned, (int) freed)
                    : getString(R.string.cleaned_none, (int) avail);
            showNotice(msg + "\n" + getString(R.string.clean_hint));
        }, 900);
    }

    private void showNotice(String msg) {
        noticeIsResult = true;
        notice.setText(msg);
        notice.animate().cancel();
        notice.animate().alpha(1f).setStartDelay(0).setDuration(200).withEndAction(() ->
                notice.animate().alpha(0f).setStartDelay(4000).setDuration(500)
                        .withEndAction(() -> noticeIsResult = false).start()).start();
    }

    private void showPillName() {
        if (noticeIsResult) return;
        int[] names = {R.string.pill_edit, R.string.pill_clean, R.string.pill_wifi, R.string.pill_settings};
        notice.setText(names[topIndex]);
        notice.animate().cancel();
        notice.animate().alpha(1f).setStartDelay(0).setDuration(180).withEndAction(null).start();
    }

    private void hideNotice() {
        if (notice.getAlpha() == 0f) return;
        notice.animate().cancel();
        notice.animate().alpha(0f).setStartDelay(0).setDuration(150).withEndAction(null).start();
    }

    private void setEditMode(boolean on) {
        if (editMode == on) return;
        editMode = on;
        if (addTile != null) addTile.animate().alpha(on ? 1f : 0f).setDuration(200).start();
        if (on) {
            section = DOCK;
            if (dock.isEmpty()) dockIndex = 0;
        } else if (dockIndex >= dock.size()) {
            dockIndex = Math.max(0, dock.size() - 1);
        }
        moveFocus(true);
    }

    // --------------------------------------------------------- dock editor

    private void openPicker(int slot) {
        editSlot = slot;
        pickList.clear();
        Set<String> inDock = new HashSet<>();
        for (App a : dock) inDock.add(a.pkg);
        for (App a : grid) if (!inDock.contains(a.pkg)) pickList.add(a);
        sortByLabel(pickList);
        // first choice when replacing: take the app out and leave the slot empty
        if (slot < dock.size()) pickList.add(0, REMOVE);
        if (pickList.isEmpty()) return;

        pickerPage.removeAllViews();
        pickTiles.clear();
        pickCaptions.clear();
        for (int i = 0; i < pickList.size(); i++) {
            int x = colX(i % COLS), y = PICKER_TOP + (i / COLS) * ROW_H;
            App a = pickList.get(i);
            View t = a == REMOVE ? removeTile() : tile(a);
            pickerPage.addView(t, at(x, y, TILE_W, TILE_H));
            pickTiles.add(t);
            TextView c = caption(a == REMOVE ? getString(R.string.edit_remove) : a.label);
            c.setAlpha(0.55f);
            pickerPage.addView(c, at(x - 20, y + TILE_H + 12, TILE_W + 40, -2));
            pickCaptions.add(c);
        }
        int rows = (pickList.size() + COLS - 1) / COLS;
        pickerPage.getLayoutParams().height = dp(Math.max(540, PICKER_TOP + rows * ROW_H + 40));
        pickerPage.addView(pickerHighlight, at(0, 0, TILE_W, TILE_H));
        pickerHighlight.setAlpha(0f);
        pickerTitle.setText(slot < dock.size() ? getString(R.string.edit_pick_app, dock.get(slot).label)
                : getString(R.string.edit_pick_new));

        picker.setAlpha(0f);
        picker.setVisibility(View.VISIBLE);
        picker.animate().alpha(1f).setDuration(250).start();
        pickIndex = 0;
        pickerPage.setTranslationY(0);
        pickerPage.post(() -> pickFocus(false));
    }

    private static final App REMOVE = new App();

    private View removeTile() {
        TextView t = text(44, 200, TEXT);
        t.setShadowLayer(0, 0, 0, 0);
        t.setText("−");
        t.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x26FFFFFF);
        bg.setStroke(dp(1), 0x59FFFFFF);
        bg.setCornerRadius(dp(10));
        t.setBackground(bg);
        return t;
    }

    private void pickMove(int d) {
        int n = pickIndex + d;
        if (n < 0 || n >= pickList.size()) {
            // Down into a shorter last row lands on its last tile
            if (d == COLS && pickIndex / COLS < (pickList.size() - 1) / COLS) n = pickList.size() - 1;
            else return;
        }
        if ((d == 1 || d == -1) && n / COLS != pickIndex / COLS) return;
        View prev = pickTiles.get(pickIndex);
        prev.animate().scaleX(1f).scaleY(1f).translationZ(0).setDuration(200).start();
        pickCaptions.get(pickIndex).animate().alpha(0.55f).setDuration(180).start();
        pickIndex = n;
        pickFocus(true);
    }

    private void pickFocus(boolean animate) {
        View t = pickTiles.get(pickIndex);
        t.animate().scaleX(FOCUS_SCALE).scaleY(FOCUS_SCALE).translationZ(dp(6)).setDuration(240)
                .setInterpolator(new OvershootInterpolator(2f)).start();
        pickCaptions.get(pickIndex).animate().alpha(1f).setDuration(180).start();
        float ty = -dp(ROW_H) * Math.max(0, pickIndex / COLS - 1);
        pickerPage.animate().translationY(ty).setDuration(animate ? 360 : 0).start();
        Rect r = rectIn(t, pickerPage);
        int dx = Math.round(r.width() * (FOCUS_SCALE - 1) / 2) + dp(5);
        int dy = Math.round(r.height() * (FOCUS_SCALE - 1) / 2) + dp(5);
        r.inset(-dx, -dy);
        ((GlassDrawable) pickerHighlight.getBackground()).setRing(true);
        if (pickerHighlight.getAlpha() == 0f) animate = false;
        pickerHighlight.animate().alpha(1f).setDuration(150).start();
        liquidMove(pickerHighlight, r, animate, null);
    }

    private void choosePicked() {
        App chosen = pickList.get(pickIndex);
        List<String> order = new ArrayList<>();
        for (App a : dock) order.add(a.pkg);
        if (chosen == REMOVE) order.remove(editSlot);
        else if (editSlot < order.size()) order.set(editSlot, chosen.pkg);
        else order.add(chosen.pkg);
        prefs.edit().putString("dock_order", TextUtils.join(",", order)).apply();
        closePicker();
        rebuild();
        section = DOCK;
        dockIndex = Math.max(0, Math.min(editSlot, dock.size() - 1));
        page.post(() -> moveFocus(true));
    }

    private void closePicker() {
        picker.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            picker.setVisibility(View.GONE);
            pickerPage.removeAllViews();
        }).start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: back to the dock
        if (idle) wake();
        if (picker.getVisibility() == View.VISIBLE) closePicker();
        if (editMode) setEditMode(false);
        // the remote's apps button (see KeyService): open the grid, or close it again
        if (KeyService.SHOW_APPS.equals(intent.getAction())) {
            if (section == GRID || grid.isEmpty()) section = dock.isEmpty() ? TOP : DOCK;
            else section = GRID;
            moveFocus(true);
            return;
        }
        if (Intent.ACTION_MAIN.equals(intent.getAction()) && section != DOCK && !dock.isEmpty()) {
            section = DOCK;
            moveFocus(true);
        }
    }

    // ------------------------------------------------------ clock and wi-fi

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            updateClock();
        }
    };

    private final BroadcastReceiver netReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            updateWifi();
            NetworkInfo ni = ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).getActiveNetworkInfo();
            if (ni != null && ni.isConnected() && weatherRow.getVisibility() != View.VISIBLE) {
                handler.removeCallbacks(weatherTask);
                handler.post(weatherTask);
            }
        }
    };

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            rebuild();
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, tf);
        IntentFilter nf = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        nf.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        registerReceiver(netReceiver, nf);
        updateClock();
        updateWifi();
        // a change missed while the process was dead still shows up here
        if (!signature(launchables()).equals(appsSignature)) rebuild();
        // a new photo each time you come back home, and every few minutes
        if (startedOnce) nextPhoto();
        startedOnce = true;
        if (idle) wake();
        scheduleIdle();
        if (weatherRow.getVisibility() != View.VISIBLE) showCachedWeather();
        handler.removeCallbacks(weatherTask);
        handler.post(weatherTask);
        handler.removeCallbacks(rotate);
        handler.postDelayed(rotate, getResources().getInteger(R.integer.photo_minutes) * 60_000L);
    }

    @Override
    protected void onStop() {
        unregisterReceiver(timeReceiver);
        unregisterReceiver(netReceiver);
        handler.removeCallbacks(rotate);
        handler.removeCallbacks(goIdle);
        handler.removeCallbacks(weatherTask);
        super.onStop();
    }

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR_OF_DAY);
        String hello = getString(h >= 6 && h < 13 ? R.string.greeting_morning
                : h >= 13 && h < 21 ? R.string.greeting_afternoon : R.string.greeting_night);
        // the weather's town sits beside the greeting once it is known
        greeting.setText(weatherTown.isEmpty() ? hello : hello + " · " + weatherTown);
        clock.setText(DateFormat.format(DateFormat.is24HourFormat(this) ? "H:mm" : "h:mm", now));
        String d = new SimpleDateFormat("EEEE, d 'de' MMMM", new Locale("es", "ES")).format(now.getTime());
        date.setText(Character.toUpperCase(d.charAt(0)) + d.substring(1));
    }

    @SuppressWarnings("deprecation")
    private void updateWifi() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm.getActiveNetworkInfo();
        String label;
        if (ni == null || !ni.isConnected()) label = getString(R.string.wifi_none);
        else if (ni.getType() == ConnectivityManager.TYPE_ETHERNET) label = getString(R.string.ethernet);
        else {
            WifiInfo wi = ((WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE)).getConnectionInfo();
            String ssid = wi == null ? null : wi.getSSID();
            if (ssid == null || ssid.contains("unknown ssid")) label = getString(R.string.wifi_generic);
            else label = ssid.replace("\"", "");
        }
        if (label.contentEquals(wifiLabel.getText())) return;
        wifiLabel.setText(label);
        if (section == TOP) page.post(() -> moveFocus(false));
    }

    // -------------------------------------------------------------- weather

    private final Runnable weatherTask = new Runnable() {
        @Override
        public void run() {
            final String town = getString(R.string.weather_city);
            final Runnable self = this;
            new Thread(() -> {
                try {
                    Weather.Place place = cachedPlace(town);
                    Weather.Now now = Weather.current(place);
                    runOnUiThread(() -> {
                        showWeather(place.name, now);
                        prefs.edit().putInt("w_temp", now.temp).putInt("w_code", now.code)
                                .putBoolean("w_day", now.day).apply();
                        handler.postDelayed(self, WEATHER_MS);
                    });
                } catch (Exception e) {
                    // at boot home starts before Wi-Fi: try again soon, not in 30 minutes
                    runOnUiThread(() -> handler.postDelayed(self, WEATHER_RETRY_MS));
                }
            }).start();
        }
    };

    /** The place is looked up once a day, not on every refresh. */
    private Weather.Place cachedPlace(String town) throws Exception {
        long age = System.currentTimeMillis() - prefs.getLong("w_time", 0);
        if (town.equals(prefs.getString("w_town", null)) && age < 24 * 3600_000L) {
            Weather.Place p = new Weather.Place();
            p.name = prefs.getString("w_name", "");
            p.lat = Double.longBitsToDouble(prefs.getLong("w_lat", 0));
            p.lon = Double.longBitsToDouble(prefs.getLong("w_lon", 0));
            return p;
        }
        Weather.Place p = Weather.locate(town);
        prefs.edit().putString("w_town", town).putLong("w_time", System.currentTimeMillis())
                .putString("w_name", p.name).putLong("w_lat", Double.doubleToLongBits(p.lat))
                .putLong("w_lon", Double.doubleToLongBits(p.lon)).apply();
        return p;
    }

    /** Last known weather, shown at once until the first refresh lands. */
    private void showCachedWeather() {
        if (!prefs.contains("w_temp")) return;
        Weather.Now now = new Weather.Now();
        now.temp = prefs.getInt("w_temp", 0);
        now.code = prefs.getInt("w_code", 0);
        now.day = prefs.getBoolean("w_day", true);
        showWeather(prefs.getString("w_name", ""), now);
    }

    private void showWeather(String town, Weather.Now now) {
        weatherIcon.setImageResource(Weather.icon(now.code, now.day));
        weatherTemp.setText(now.temp + "°");
        String what = Weather.describe(now.code);
        weatherText.setText(what);
        weatherRow.setVisibility(View.VISIBLE);
        weatherTown = town;
        updateClock();
    }

    // -------------------------------------------------------------- helpers

    private TextView text(float sizeDp, int weight, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        t.setTextColor(color);
        t.setTypeface(font(weight));
        // every label sits on a photo
        t.setShadowLayer(dp(8), 0, dp(1), SHADOW);
        return t;
    }

    private Typeface font(int weight) {
        Typeface tf = fonts.get(weight);
        if (tf == null) {
            // Fira Code is variable from 300 to 700
            int w = Math.max(300, Math.min(700, weight));
            try {
                tf = new Typeface.Builder(getAssets(), "fonts/FiraCode.ttf")
                        .setFontVariationSettings("'wght' " + w).build();
            } catch (Exception e) {
                tf = Typeface.create("sans-serif-light", Typeface.NORMAL);
            }
            fonts.put(weight, tf);
        }
        return tf;
    }

    private int colX(int col) {
        return BAND_LEFT + BAND_PAD + col * (TILE_W + GAP);
    }

    private FrameLayout.LayoutParams at(int x, int y, int w, int h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h));
        lp.leftMargin = dp(x);
        lp.topMargin = dp(y);
        return lp;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private static int lerp(int a, int b, float f) {
        return Math.round(a + (b - a) * f);
    }
}
