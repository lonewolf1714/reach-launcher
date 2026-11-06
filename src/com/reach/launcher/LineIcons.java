package com.reach.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Build;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.provider.Settings;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reach's own monoline icon set (round caps and joins, drawn on a 24-unit grid), plus the logic
 * that decides which icon an app gets: the system's own roles (default dialer, browser, camera…),
 * the app's declared category, then words in its name/package. Brand apps aren't redrawn; they
 * fall back to a line tracing of their own icon.
 */
final class LineIcons {
    private LineIcons() { }

    /** Stroke width in grid units for "thin", "regular", "bold". */
    static float weight(String w) {
        switch (w) {
            case "thin": return 1.25f;
            case "bold": return 2.35f;
            default: return 1.7f;
        }
    }

    // ------------------------------------------------------------------ drawing

    /** White line drawing of kind on a transparent size x size bitmap, or null if unknown. */
    static Bitmap draw(String kind, int size, float weightUnits) {
        Path p = path(kind);
        if (p == null) return null;
        Matrix m = new Matrix();
        float s = size / 24f;
        m.setScale(s, s);
        p.transform(m);
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(weightUnits * s);
        paint.setColor(0xFFFFFFFF);
        new Canvas(b).drawPath(p, paint);
        return b;
    }

    private static void rr(Path p, float l, float t, float r, float b, float rad) {
        p.addRoundRect(new RectF(l, t, r, b), rad, rad, Path.Direction.CW);
    }

    private static void line(Path p, float x1, float y1, float x2, float y2) {
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
    }

    private static void dot(Path p, float x, float y) { p.addCircle(x, y, 0.35f, Path.Direction.CW); }

    static Path path(String kind) {
        if (kind == null) return null;
        Path p = new Path();
        switch (kind) {
            case "phone":
                p.moveTo(7.2f, 3.5f); p.lineTo(9.4f, 3.5f); p.lineTo(10.9f, 7.6f); p.lineTo(9f, 9.1f);
                p.cubicTo(10f, 11.4f, 12.6f, 14f, 14.9f, 15f); p.lineTo(16.4f, 13.1f); p.lineTo(20.5f, 14.6f);
                p.lineTo(20.5f, 16.8f); p.cubicTo(20.5f, 18.9f, 18.9f, 20.5f, 16.8f, 20.4f);
                p.cubicTo(9.6f, 19.8f, 4.2f, 14.4f, 3.6f, 7.2f); p.cubicTo(3.5f, 5.1f, 5.1f, 3.5f, 7.2f, 3.5f); p.close();
                return p;
            case "message":
                p.moveTo(7.5f, 17.5f); p.lineTo(5f, 20.5f); p.lineTo(5f, 17.2f);
                p.cubicTo(3.8f, 16.6f, 3.5f, 15.6f, 3.5f, 14.5f); p.lineTo(3.5f, 7.5f);
                p.cubicTo(3.5f, 5.3f, 5.3f, 3.5f, 7.5f, 3.5f); p.lineTo(16.5f, 3.5f);
                p.cubicTo(18.7f, 3.5f, 20.5f, 5.3f, 20.5f, 7.5f); p.lineTo(20.5f, 13.5f);
                p.cubicTo(20.5f, 15.7f, 18.7f, 17.5f, 16.5f, 17.5f); p.close();
                line(p, 8f, 9f, 16f, 9f); line(p, 8f, 12.5f, 13f, 12.5f);
                return p;
            case "chat":
                p.moveTo(12f, 3.5f); p.cubicTo(16.7f, 3.5f, 20.5f, 7f, 20.5f, 11.5f);
                p.cubicTo(20.5f, 16f, 16.7f, 19.5f, 12f, 19.5f); p.cubicTo(10.8f, 19.5f, 9.7f, 19.3f, 8.7f, 18.9f);
                p.lineTo(4f, 20.5f); p.lineTo(5.2f, 16.4f); p.cubicTo(4.1f, 15f, 3.5f, 13.3f, 3.5f, 11.5f);
                p.cubicTo(3.5f, 7f, 7.3f, 3.5f, 12f, 3.5f); p.close();
                dot(p, 8.5f, 11.5f); dot(p, 12f, 11.5f); dot(p, 15.5f, 11.5f);
                return p;
            case "camera":
                rr(p, 3f, 7f, 21f, 19.5f, 3f);
                p.addCircle(12f, 13.2f, 3.6f, Path.Direction.CW);
                p.moveTo(8.3f, 7f); p.lineTo(9.6f, 4.5f); p.lineTo(14.4f, 4.5f); p.lineTo(15.7f, 7f);
                return p;
            case "photos":
                rr(p, 3.5f, 4f, 20.5f, 20f, 3f);
                p.addCircle(15.2f, 9f, 1.8f, Path.Direction.CW);
                p.moveTo(3.8f, 17f); p.lineTo(9f, 11.8f); p.lineTo(15.5f, 18.3f);
                p.moveTo(13.4f, 16.2f); p.lineTo(15.6f, 14f); p.lineTo(20.2f, 18.6f);
                return p;
            case "mail":
                rr(p, 3f, 5.5f, 21f, 18.5f, 2.8f);
                p.moveTo(4f, 7.2f); p.lineTo(12f, 12.8f); p.lineTo(20f, 7.2f);
                return p;
            case "globe":
                p.addCircle(12f, 12f, 8.8f, Path.Direction.CW);
                p.addOval(new RectF(8.2f, 3.2f, 15.8f, 20.8f), Path.Direction.CW);
                line(p, 3.4f, 12f, 20.6f, 12f);
                return p;
            case "pin":
                p.moveTo(12f, 21f); p.cubicTo(12f, 21f, 5f, 14.6f, 5f, 9.8f);
                p.cubicTo(5f, 5.9f, 8.1f, 3f, 12f, 3f); p.cubicTo(15.9f, 3f, 19f, 5.9f, 19f, 9.8f);
                p.cubicTo(19f, 14.6f, 12f, 21f, 12f, 21f); p.close();
                p.addCircle(12f, 9.8f, 2.6f, Path.Direction.CW);
                return p;
            case "music":
                p.moveTo(4f, 15f); p.lineTo(4f, 12f); p.arcTo(new RectF(4f, 4f, 20f, 20f), 180f, 180f); p.lineTo(20f, 15f);
                rr(p, 3f, 13.5f, 7.6f, 20.5f, 2f);
                rr(p, 16.4f, 13.5f, 21f, 20.5f, 2f);
                return p;
            case "calendar":
                rr(p, 3.5f, 5f, 20.5f, 20.5f, 2.8f);
                line(p, 3.5f, 9.8f, 20.5f, 9.8f);
                line(p, 8f, 3f, 8f, 6.5f); line(p, 16f, 3f, 16f, 6.5f);
                dot(p, 8f, 14f); dot(p, 12f, 14f); dot(p, 16f, 14f); dot(p, 8f, 17.2f); dot(p, 12f, 17.2f);
                return p;
            case "clock":
                p.addCircle(12f, 12.5f, 8.3f, Path.Direction.CW);
                p.moveTo(12f, 8f); p.lineTo(12f, 12.5f); p.lineTo(15f, 14.4f);
                line(p, 4.5f, 4.8f, 6.6f, 3.2f); line(p, 19.5f, 4.8f, 17.4f, 3.2f);
                return p;
            case "gear":
                p.addCircle(12f, 12f, 3f, Path.Direction.CW);
                p.addCircle(12f, 12f, 6.8f, Path.Direction.CW);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    line(p, (float) (12 + 6.8 * Math.cos(a)), (float) (12 + 6.8 * Math.sin(a)),
                            (float) (12 + 9.2 * Math.cos(a)), (float) (12 + 9.2 * Math.sin(a)));
                }
                return p;
            case "folder":
                p.moveTo(3.5f, 17.5f); p.lineTo(3.5f, 6.8f); p.cubicTo(3.5f, 5.8f, 4.3f, 5f, 5.3f, 5f);
                p.lineTo(9.2f, 5f); p.lineTo(11.2f, 7.5f); p.lineTo(18.7f, 7.5f);
                p.cubicTo(19.7f, 7.5f, 20.5f, 8.3f, 20.5f, 9.3f); p.lineTo(20.5f, 17.5f);
                p.cubicTo(20.5f, 18.5f, 19.7f, 19.3f, 18.7f, 19.3f); p.lineTo(5.3f, 19.3f);
                p.cubicTo(4.3f, 19.3f, 3.5f, 18.5f, 3.5f, 17.5f); p.close();
                line(p, 3.5f, 10.5f, 20.5f, 10.5f);
                return p;
            case "notes":
                p.moveTo(11.5f, 4.5f); p.lineTo(6.5f, 4.5f); p.cubicTo(5.4f, 4.5f, 4.5f, 5.4f, 4.5f, 6.5f);
                p.lineTo(4.5f, 17.5f); p.cubicTo(4.5f, 18.6f, 5.4f, 19.5f, 6.5f, 19.5f); p.lineTo(17.5f, 19.5f);
                p.cubicTo(18.6f, 19.5f, 19.5f, 18.6f, 19.5f, 17.5f); p.lineTo(19.5f, 12.5f);
                p.moveTo(9.8f, 14.2f); p.lineTo(10.3f, 11.4f); p.lineTo(17.6f, 4.1f);
                p.cubicTo(18.2f, 3.5f, 19.2f, 3.5f, 19.9f, 4.1f); p.cubicTo(20.5f, 4.8f, 20.5f, 5.8f, 19.9f, 6.4f);
                p.lineTo(12.6f, 13.7f); p.close();
                return p;
            case "person":
                p.addCircle(12f, 8f, 3.8f, Path.Direction.CW);
                p.moveTo(4.8f, 20f); p.cubicTo(5.3f, 16.1f, 8.2f, 14f, 12f, 14f);
                p.cubicTo(15.8f, 14f, 18.7f, 16.1f, 19.2f, 20f); p.close();
                return p;
            case "bag":
                p.moveTo(5.5f, 8f); p.lineTo(18.5f, 8f); p.lineTo(19.3f, 18.4f);
                p.cubicTo(19.4f, 19.6f, 18.5f, 20.5f, 17.3f, 20.5f); p.lineTo(6.7f, 20.5f);
                p.cubicTo(5.5f, 20.5f, 4.6f, 19.6f, 4.7f, 18.4f); p.close();
                p.moveTo(9f, 10.5f); p.lineTo(9f, 6.5f); p.cubicTo(9f, 4.8f, 10.3f, 3.5f, 12f, 3.5f);
                p.cubicTo(13.7f, 3.5f, 15f, 4.8f, 15f, 6.5f); p.lineTo(15f, 10.5f);
                return p;
            case "cloud":
                p.moveTo(7f, 18.5f); p.lineTo(17f, 18.5f); p.cubicTo(19.2f, 18.5f, 21f, 16.8f, 21f, 14.7f);
                p.cubicTo(21f, 12.6f, 19.3f, 11f, 17.2f, 11f); p.cubicTo(16.6f, 8.3f, 14.3f, 6.5f, 11.6f, 6.5f);
                p.cubicTo(8.5f, 6.5f, 6.1f, 8.8f, 6f, 11.8f); p.cubicTo(4.3f, 12.2f, 3f, 13.6f, 3f, 15.3f);
                p.cubicTo(3f, 17.1f, 4.6f, 18.5f, 6.5f, 18.5f); p.close();
                return p;
            case "play":
                rr(p, 3f, 5f, 21f, 19f, 4f);
                p.moveTo(10f, 9.2f); p.lineTo(15f, 12f); p.lineTo(10f, 14.8f); p.close();
                return p;
            case "heart":
                p.moveTo(12f, 19.8f); p.cubicTo(5.2f, 15.4f, 3.2f, 12.4f, 3.2f, 9.3f);
                p.cubicTo(3.2f, 6.7f, 5.2f, 4.8f, 7.5f, 4.8f); p.cubicTo(9.5f, 4.8f, 11f, 6f, 12f, 7.6f);
                p.cubicTo(13f, 6f, 14.5f, 4.8f, 16.5f, 4.8f); p.cubicTo(18.8f, 4.8f, 20.8f, 6.7f, 20.8f, 9.3f);
                p.cubicTo(20.8f, 12.4f, 18.8f, 15.4f, 12f, 19.8f); p.close();
                return p;
            case "book":
                p.moveTo(12f, 6.5f); p.cubicTo(10f, 5f, 7f, 4.5f, 3.5f, 5f); p.lineTo(3.5f, 18.5f);
                p.cubicTo(7f, 18f, 10f, 18.5f, 12f, 20f); p.cubicTo(14f, 18.5f, 17f, 18f, 20.5f, 18.5f);
                p.lineTo(20.5f, 5f); p.cubicTo(17f, 4.5f, 14f, 5f, 12f, 6.5f); p.lineTo(12f, 20f);
                return p;
            case "calculator":
                rr(p, 5f, 3f, 19f, 21f, 2.8f);
                rr(p, 8f, 6f, 16f, 9.6f, 1f);
                dot(p, 9f, 13f); dot(p, 12f, 13f); dot(p, 15f, 13f);
                dot(p, 9f, 17f); dot(p, 12f, 17f); dot(p, 15f, 17f);
                return p;
            case "bank":
                p.moveTo(3.5f, 9f); p.lineTo(12f, 4f); p.lineTo(20.5f, 9f); p.close();
                line(p, 6.5f, 11.5f, 6.5f, 17f); line(p, 10f, 11.5f, 10f, 17f);
                line(p, 14f, 11.5f, 14f, 17f); line(p, 17.5f, 11.5f, 17.5f, 17f);
                line(p, 3.5f, 20f, 20.5f, 20f);
                return p;
            case "game":
                rr(p, 3f, 7f, 21f, 17.5f, 5.2f);
                line(p, 8f, 10.2f, 8f, 14.2f); line(p, 6f, 12.2f, 10f, 12.2f);
                p.addCircle(15.2f, 11f, 0.9f, Path.Direction.CW);
                p.addCircle(17.4f, 13.4f, 0.9f, Path.Direction.CW);
                return p;
            case "mic":
                rr(p, 9f, 3f, 15f, 14f, 3f);
                p.moveTo(5.5f, 11f); p.cubicTo(5.5f, 14.9f, 8.4f, 17.5f, 12f, 17.5f);
                p.cubicTo(15.6f, 17.5f, 18.5f, 14.9f, 18.5f, 11f);
                line(p, 12f, 17.5f, 12f, 21f);
                return p;
            case "lock":
                rr(p, 5f, 10.5f, 19f, 20.5f, 2.8f);
                p.moveTo(8f, 10.5f); p.lineTo(8f, 7.5f); p.cubicTo(8f, 5.3f, 9.8f, 3.5f, 12f, 3.5f);
                p.cubicTo(14.2f, 3.5f, 16f, 5.3f, 16f, 7.5f); p.lineTo(16f, 10.5f);
                line(p, 12f, 14.3f, 12f, 16.6f);
                return p;
            case "send":
                p.moveTo(20.5f, 3.5f); p.lineTo(3.5f, 10.6f); p.lineTo(10.4f, 13.6f); p.lineTo(13.4f, 20.5f); p.close();
                line(p, 20.5f, 3.5f, 10.4f, 13.6f);
                return p;
            case "news":
                rr(p, 3.5f, 4.5f, 17.5f, 19.5f, 2.2f);
                p.moveTo(17.5f, 8.5f); p.lineTo(19f, 8.5f); p.cubicTo(19.8f, 8.5f, 20.5f, 9.2f, 20.5f, 10f);
                p.lineTo(20.5f, 17.5f); p.cubicTo(20.5f, 18.6f, 19.6f, 19.5f, 18.5f, 19.5f); p.lineTo(17.5f, 19.5f);
                rr(p, 6.5f, 7.5f, 11f, 11.5f, 0.8f);
                line(p, 13f, 8.5f, 14.5f, 8.5f); line(p, 13f, 11f, 14.5f, 11f);
                line(p, 6.5f, 14.5f, 14.5f, 14.5f); line(p, 6.5f, 17f, 12f, 17f);
                return p;
            case "download":
                line(p, 12f, 3.5f, 12f, 14.5f);
                p.moveTo(7.5f, 10.5f); p.lineTo(12f, 15f); p.lineTo(16.5f, 10.5f);
                p.moveTo(4f, 16f); p.lineTo(4f, 18.5f); p.cubicTo(4f, 19.6f, 4.9f, 20.5f, 6f, 20.5f);
                p.lineTo(18f, 20.5f); p.cubicTo(19.1f, 20.5f, 20f, 19.6f, 20f, 18.5f); p.lineTo(20f, 16f);
                return p;
            case "torch":
                p.moveTo(7f, 3.5f); p.lineTo(17f, 3.5f); p.lineTo(17f, 7f); p.lineTo(14.5f, 10.5f);
                p.lineTo(14.5f, 20.5f); p.lineTo(9.5f, 20.5f); p.lineTo(9.5f, 10.5f); p.lineTo(7f, 7f); p.close();
                line(p, 12f, 13f, 12f, 15f);
                return p;
            case "grid":
                rr(p, 4f, 4f, 10.5f, 10.5f, 2f); rr(p, 13.5f, 4f, 20f, 10.5f, 2f);
                rr(p, 4f, 13.5f, 10.5f, 20f, 2f); rr(p, 13.5f, 13.5f, 20f, 20f, 2f);
                return p;
            case "wallet":
                rr(p, 3.5f, 6f, 20.5f, 19.5f, 3f);
                p.moveTo(3.5f, 9f); p.lineTo(16.5f, 4.2f);
                p.moveTo(20.5f, 11f); p.lineTo(16f, 11f); p.cubicTo(14.9f, 11f, 14f, 11.9f, 14f, 13f);
                p.cubicTo(14f, 14.1f, 14.9f, 15f, 16f, 15f); p.lineTo(20.5f, 15f);
                return p;
            case "car":
                p.moveTo(4f, 16.5f); p.lineTo(4f, 12.5f); p.lineTo(6f, 7.8f); p.cubicTo(6.4f, 6.8f, 7.3f, 6.2f, 8.3f, 6.2f);
                p.lineTo(15.7f, 6.2f); p.cubicTo(16.7f, 6.2f, 17.6f, 6.8f, 18f, 7.8f); p.lineTo(20f, 12.5f); p.lineTo(20f, 16.5f);
                p.close();
                line(p, 4f, 12.5f, 20f, 12.5f);
                p.addCircle(7.8f, 18.4f, 1.6f, Path.Direction.CW);
                p.addCircle(16.2f, 18.4f, 1.6f, Path.Direction.CW);
                return p;
        }
        return null;
    }

    // ------------------------------------------------------------------ which icon an app gets

    /** The system's default handlers: component (or package) -> kind. Call off the main thread. */
    static Map<String, String> roles(Context c) {
        Map<String, String> m = new HashMap<>();
        PackageManager pm = c.getPackageManager();
        Object[][] probes = {
                {new Intent(Intent.ACTION_DIAL), "phone"},
                {new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "camera"},
                {new Intent(Settings.ACTION_SETTINGS), "gear"},
                {new Intent(AlarmClock.ACTION_SHOW_ALARMS), "clock"},
                {sel(Intent.CATEGORY_APP_MESSAGING), "message"},
                {sel(Intent.CATEGORY_APP_BROWSER), "globe"},
                {sel(Intent.CATEGORY_APP_EMAIL), "mail"},
                {sel(Intent.CATEGORY_APP_MAPS), "pin"},
                {sel(Intent.CATEGORY_APP_MUSIC), "music"},
                {sel(Intent.CATEGORY_APP_CALENDAR), "calendar"},
                {sel(Intent.CATEGORY_APP_CONTACTS), "person"},
                {sel(Intent.CATEGORY_APP_CALCULATOR), "calculator"},
                {sel(Intent.CATEGORY_APP_GALLERY), "photos"},
                {sel(Intent.CATEGORY_APP_MARKET), "bag"},
        };
        for (Object[] pr : probes) put(m, pm, (Intent) pr[0], (String) pr[1]);
        if (Build.VERSION.SDK_INT >= 29) put(m, pm, sel(Intent.CATEGORY_APP_FILES), "folder");
        if (Build.VERSION.SDK_INT >= 31) {
            put(m, pm, sel(Intent.CATEGORY_APP_WEATHER), "cloud");
            put(m, pm, sel(Intent.CATEGORY_APP_FITNESS), "heart");
        }
        return m;
    }

    private static Intent sel(String category) { return Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category); }

    private static void put(Map<String, String> m, PackageManager pm, Intent i, String kind) {
        try {
            List<ResolveInfo> l = pm.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY);
            for (ResolveInfo r : l) {
                if (r.activityInfo == null) continue;
                String pkg = r.activityInfo.packageName;
                if (!m.containsKey(pkg)) m.put(pkg, kind);
            }
        } catch (Exception ignored) { }
    }

    /** Whole words (or phrases) in the app's name or package. */
    private static final String[][] WORDS = {
            {"phone", "dialer", "phone", "call", "calls"}, {"message", "messages", "messaging", "message", "sms", "mms"},
            {"camera", "camera", "cam"}, {"photos", "gallery", "photos", "photo", "pictures"},
            {"mail", "mail", "gmail", "email", "outlook"},
            {"globe", "browser", "chrome", "firefox", "internet", "opera", "brave", "edge", "sbrowser"},
            {"pin", "maps", "map", "navigation", "uber", "ola", "rapido", "waze"},
            {"music", "music", "spotify", "audio", "radio", "podcast", "podcasts", "gaana", "wynk", "jiosaavn", "soundcloud"},
            {"calendar", "calendar"}, {"clock", "clock", "alarm", "deskclock", "timer"}, {"gear", "settings"},
            {"folder", "files", "file manager", "myfiles", "documentsui", "drive", "docs", "documents"},
            {"notes", "notes", "keep", "memo", "note", "notion", "evernote", "onenote"},
            {"person", "contacts", "people"},
            {"bag", "store", "shop", "shopping", "amazon", "flipkart", "myntra", "meesho", "blinkit", "zepto", "swiggy", "zomato", "vending", "district", "decathlon"},
            {"cloud", "weather"},
            {"play", "video", "videos", "youtube", "netflix", "prime video", "hotstar", "jiocinema", "tv", "player", "crunchyroll", "mxplayer"},
            {"heart", "health", "fit", "fitness", "steps", "shealth"},
            {"book", "books", "kindle", "read", "reader", "learn", "education", "acrobat", "pdf", "bookmyshow"},
            {"calculator", "calculator", "calc"},
            {"bank", "bank", "banking", "hdfc", "icici", "sbi", "axis", "kotak", "finance", "invest", "zerodha", "groww", "dhan", "bajaj"},
            {"wallet", "pay", "wallet", "upi", "paytm", "phonepe", "gpay", "bhim", "airtel"},
            {"game", "game", "games", "pool", "ludo", "chess", "pubg", "bgmi", "freefire"},
            {"chat", "whatsapp", "telegram", "messenger", "signal", "chat", "discord", "slack", "teams", "snapchat",
                    "instagram", "threads", "facebook", "katana", "twitter", "reddit", "linkedin", "chatgpt", "claude", "gemini"},
            {"mic", "recorder", "voice", "record"},
            {"lock", "secure", "vault", "authenticator", "password", "aadhaar", "digilocker", "knox"},
            {"news", "news", "inshorts", "times"}, {"download", "download", "downloads"}, {"torch", "torch", "flashlight"},
            {"car", "car", "parking", "fastag"}, {"send", "share", "quick share"},
            {"globe", "google", "googlequicksearchbox"},
    };

    /**
     * Icon kind for an app, or null to fall back to tracing its own icon. Brand social apps map to
     * the generic "chat" bubble only when they have no usable icon of their own; see IconStyler.
     */
    static String kindFor(ComponentName cn, String label, int category, Map<String, String> roles) {
        String role = roles.get(cn.getPackageName());
        if (role != null) return role;
        // whole words only: "facebook" must not match "book"
        String hay = " " + (label + " " + cn.getPackageName()).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ") + " ";
        for (String[] w : WORDS) {
            for (int i = 1; i < w.length; i++) if (hay.contains(" " + w[i] + " ")) return w[0];
        }
        if (Build.VERSION.SDK_INT >= 26) {
            switch (category) {
                case ApplicationInfo.CATEGORY_GAME: return "game";
                case ApplicationInfo.CATEGORY_AUDIO: return "music";
                case ApplicationInfo.CATEGORY_VIDEO: return "play";
                case ApplicationInfo.CATEGORY_IMAGE: return "photos";
                case ApplicationInfo.CATEGORY_SOCIAL: return "chat";
                case ApplicationInfo.CATEGORY_NEWS: return "news";
                case ApplicationInfo.CATEGORY_MAPS: return "pin";
                case ApplicationInfo.CATEGORY_PRODUCTIVITY: return "notes";
            }
        }
        return null;
    }

    /**
     * Every app keeps a line tracing of its own logo, so apps of the same kind still look different.
     * Only the phone's own system apps (default dialer, messages, camera, clock…) get the drawn glyph.
     */
    static boolean preferOwnLogo(String kind, ComponentName cn, Map<String, String> roles) {
        String pkg = cn.getPackageName();
        if (roles != null && roles.containsKey(pkg)) return false;
        String[] system = {"com.android.", "com.samsung.", "com.sec.", "com.oneplus.", "com.oplus.", "com.coloros.", "com.miui.",
                "com.xiaomi.", "com.motorola.", "com.nothing.", "com.google.android.dialer", "com.google.android.contacts",
                "com.google.android.apps.messaging", "com.google.android.GoogleCamera", "com.google.android.deskclock",
                "com.google.android.calculator", "com.google.android.calendar", "com.google.android.documentsui"};
        for (String sp : system) if (pkg.startsWith(sp)) return false;
        return true;
    }
}
