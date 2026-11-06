package com.reach.launcher;

import android.app.Notification;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Normalizer;
import java.util.Locale;

final class Ui {
    private Ui() { }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static Drawable ripple(Context c, Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, round(Color.WHITE, dp(c, 14)));
    }

    static void shadow(TextView t) {
        t.setShadowLayer(6f, 0f, 1f, 0x88000000);
    }

    /** Text color for home-screen text, with a shadow that suits it (dark shadow for light text). */
    /** Theme depth (0..3): scales text shadows on the home screen. */
    static int depth = 1;
    /** How long a finger must rest on an app, widget or tile before it lifts for editing (ms). */
    static int holdMs = 600;

    /** A brushed-metal panel: the base color with a light diagonal sheen and a darker lower edge. */
    static android.graphics.drawable.GradientDrawable metal(int base, float radius) {
        base |= 0xFF000000;
        int hi = blend(base, 0xFFFFFFFF, 0.42f), mid = base, lo = blend(base, 0xFF000000, 0.22f);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[]{blend(base, 0xFFFFFFFF, 0.12f), hi, mid, lo});
        g.setCornerRadius(radius);
        return g;
    }

    static int blend(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int gg = Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bb = Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | (r << 16) | (gg << 8) | bb;
    }

    static int holdMs(Prefs p) {
        try { return Integer.parseInt(p.str("holdMs", "600")); } catch (Exception e) { return 600; }
    }

    static void ink(TextView t, int color) {
        t.setTextColor(color);
        if (depth == 0) t.setShadowLayer(0f, 0f, 0f, 0);
        else if (isLight(color)) t.setShadowLayer(3f + 3f * depth, 0f, depth, (0x50 + 0x18 * depth) << 24);
        else t.setShadowLayer(4f, 0f, 0f, 0x55FFFFFF);
    }

    static boolean isLight(int c) {
        return (((c >> 16) & 0xFF) * 299 + ((c >> 8) & 0xFF) * 587 + (c & 0xFF) * 114) / 1000 > 140;
    }

    static int alpha(int color, int a) { return (color & 0xFFFFFF) | (a << 24); }

    private static final java.util.Map<String, android.graphics.Typeface> FACES = new java.util.HashMap<>();

    /** Font pref value -> Typeface. "res:name" loads res/font/name.ttf; anything else is a system family. */
    static synchronized android.graphics.Typeface typeface(Context c, String value, int style) {
        String key = value + "#" + style;
        android.graphics.Typeface t = FACES.get(key);
        if (t != null) return t;
        android.graphics.Typeface base = null;
        if (value.startsWith("res:")) {
            int id = c.getResources().getIdentifier(value.substring(4), "font", c.getPackageName());
            try { if (id != 0) base = c.getResources().getFont(id); } catch (Exception ignored) { }
        }
        t = base != null ? android.graphics.Typeface.create(base, style) : android.graphics.Typeface.create(value, style);
        FACES.put(key, t);
        return t;
    }

    /** True when the system will really blur behind our windows (Android 12+, and enabled on this device). */
    static boolean blurOn(Context c) {
        if (android.os.Build.VERSION.SDK_INT < 31) return false;
        try {
            return c.getSystemService(android.view.WindowManager.class).isCrossWindowBlurEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Frosted backdrop for devices without cross-window blur: a soft gradient built from the
     * wallpaper's own colors, darkened so white text stays readable.
     */
    static android.graphics.drawable.Drawable frosted(Context c) {
        int a = 0xFF1A1D22, b = 0xFF0E1013;
        try {
            android.app.WallpaperColors wc = android.app.WallpaperManager.getInstance(c)
                    .getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM);
            if (wc != null) {
                a = mix(wc.getPrimaryColor().toArgb(), 0xFF0E1013, 0.62f);
                android.graphics.Color sec = wc.getSecondaryColor();
                b = mix(sec != null ? sec.toArgb() : wc.getPrimaryColor().toArgb(), 0xFF0E1013, 0.80f);
            }
        } catch (Exception ignored) { }
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{a, mix(a, b, 0.5f), b});
        return g;
    }

    /** Blend from x toward y by t (0..1), fully opaque. */
    static int mix(int x, int y, float t) {
        int r = (int) (((x >> 16) & 0xFF) * (1 - t) + ((y >> 16) & 0xFF) * t);
        int g = (int) (((x >> 8) & 0xFF) * (1 - t) + ((y >> 8) & 0xFF) * t);
        int b = (int) ((x & 0xFF) * (1 - t) + (y & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    static void toast(Context c, CharSequence s) {
        Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }

    /** Lowercase, accent-free form used for sorting and search. */
    static String norm(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).trim();
    }

    static String sectionOf(String norm) {
        if (norm.isEmpty()) return "#";
        char c = Character.toUpperCase(norm.charAt(0));
        return c >= 'A' && c <= 'Z' ? String.valueOf(c) : "#";
    }

    /**
     * Search score for a normalized label against a normalized query; -1 means no match.
     * prefix > word prefix > initials ("gm" -> "google maps") > substring > in-order letters.
     */
    static int score(String label, String q) {
        if (q.isEmpty()) return -1;
        if (label.startsWith(q)) return 100;
        StringBuilder initials = new StringBuilder();
        boolean wordStart = true;
        for (int i = 0; i < label.length(); i++) {
            char ch = label.charAt(i);
            boolean alnum = Character.isLetterOrDigit(ch);
            if (alnum && wordStart) {
                initials.append(ch);
                if (i > 0 && label.startsWith(q, i)) return 85;
            }
            wordStart = !alnum;
        }
        if (q.length() > 1 && initials.toString().startsWith(q)) return 70;
        if (label.contains(q)) return 50;
        int j = 0;
        for (int i = 0; i < label.length() && j < q.length(); i++) if (label.charAt(i) == q.charAt(j)) j++;
        return j == q.length() && q.length() > 1 ? 20 : -1;
    }

    static CharSequence preview(StatusBarNotification sbn) {
        Bundle ex = sbn.getNotification().extras;
        CharSequence title = ex.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = ex.getCharSequence(Notification.EXTRA_TEXT);
        if (title == null || title.length() == 0) return text == null ? "" : text;
        if (text == null || text.length() == 0) return title;
        return title + ": " + text;
    }
}
