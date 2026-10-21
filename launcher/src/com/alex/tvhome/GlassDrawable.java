package com.alex.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * Liquid-glass surface without runtime blur: the backdrop photo is blurred
 * once at build time, and each glass surface draws the part of that blurred
 * photo that sits behind it on screen. On top: a tint, a sheen across the top
 * half, a soft shade at the bottom and a rim lit from above.
 */
class GlassDrawable extends Drawable {

    /** The blurred backdrop shared by every glass surface. */
    static class Backdrop {
        Bitmap blur, previous;
        float fade = 1f;
        int screenW, screenH;
    }

    private final View host;
    private final Backdrop backdrop;
    private final float density;
    private float radius;
    private final int rimTop;
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    // dark inner ring: keeps the focus visible over white banners
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean showRing;
    private final Path clip = new Path();
    private final RectF box = new RectF();
    private final int[] loc = new int[2];

    /**
     * @param backdrop null for glass that only lightens what is under it (the focus)
     * @param radius   corner radius in px, or -1 for a pill
     */
    GlassDrawable(View host, Backdrop backdrop, float radius, int tint, int rimTop) {
        this.host = host;
        this.backdrop = backdrop;
        this.radius = radius;
        this.rimTop = rimTop;
        this.density = host.getResources().getDisplayMetrics().density;
        fill.setColor(tint);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.25f * density);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2f * density);
        ring.setColor(0x66000000);
    }

    void setRing(boolean on) {
        if (showRing == on) return;
        showRing = on;
        rim.setStrokeWidth((on ? 2f : 1.25f) * density);
        onBoundsChange(getBounds());
        invalidateSelf();
    }

    void setGlassTint(int tint) {
        if (fill.getColor() == tint) return;
        fill.setColor(tint);
        invalidateSelf();
    }

    void setRadius(float r) {
        radius = r;
        onBoundsChange(getBounds());
        invalidateSelf();
    }

    @Override
    protected void onBoundsChange(Rect b) {
        float r = radius < 0 ? b.height() / 2f : radius;
        box.set(b);
        clip.reset();
        clip.addRoundRect(box, r, r, Path.Direction.CW);
        int h = Math.max(1, b.height());
        sheen.setShader(new LinearGradient(0, b.top, 0, b.top + h * 0.5f,
                0x24FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        shade.setShader(new LinearGradient(0, b.top + h * 0.6f, 0, b.bottom,
                0x00000000, 0x22000000, Shader.TileMode.CLAMP));
        int bottomBounce = (rimTop >>> 24) / 3 << 24 | 0xFFFFFF;
        rim.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                new int[]{rimTop, 0x26FFFFFF, 0x0FFFFFFF, bottomBounce},
                new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        float r = radius < 0 ? b.height() / 2f : radius;
        c.save();
        c.clipPath(clip);
        if (backdrop != null && backdrop.blur != null && backdrop.screenW > 0) {
            host.getLocationOnScreen(loc);
            c.save();
            c.translate(-loc[0], -loc[1]);
            if (backdrop.previous != null && backdrop.fade < 1f) {
                drawScaled(c, backdrop.previous, 255);
                drawScaled(c, backdrop.blur, Math.round(255 * backdrop.fade));
            } else {
                drawScaled(c, backdrop.blur, 255);
            }
            c.restore();
        }
        c.drawRect(box, fill);
        c.drawRect(box, sheen);
        c.drawRect(box, shade);
        c.restore();
        float in = rim.getStrokeWidth() / 2f;
        if (showRing) {
            float rin = rim.getStrokeWidth() + ring.getStrokeWidth() / 2f;
            c.drawRoundRect(box.left + rin, box.top + rin, box.right - rin, box.bottom - rin, r - rin, r - rin, ring);
        }
        c.drawRoundRect(box.left + in, box.top + in, box.right - in, box.bottom - in, r - in, r - in, rim);
    }

    private void drawScaled(Canvas c, Bitmap bmp, int alpha) {
        c.save();
        c.scale(backdrop.screenW / (float) bmp.getWidth(), backdrop.screenH / (float) bmp.getHeight());
        bmpPaint.setAlpha(alpha);
        c.drawBitmap(bmp, 0, 0, bmpPaint);
        c.restore();
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
