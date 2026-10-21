package com.alex.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * The remote's apps button (KEYCODE_ALL_APPS) is handled by the system before
 * any app sees it, and on this firmware it goes to Settings. An accessibility
 * service sees keys first, so it catches that one button and opens the app
 * grid of the launcher instead. Every other key passes through untouched.
 */
public class KeyService extends AccessibilityService {

    static final String SHOW_APPS = "com.alex.tvhome.SHOW_APPS";

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_ALL_APPS) return false;
        if (e.getAction() == KeyEvent.ACTION_UP) {
            Intent i = new Intent(SHOW_APPS).setClass(this, HomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        }
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
