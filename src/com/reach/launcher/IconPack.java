package com.reach.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads third-party icon packs that use the widely shared "appfilter.xml" format
 * (<item component="ComponentInfo{pkg/cls}" drawable="name"/>, plus optional
 * <iconback>, <iconmask>, <iconupon> and <scale> for apps the pack doesn't cover).
 */
final class IconPack {
    /** Intent actions/categories icon packs declare so launchers can find them. */
    private static final String[] ACTIONS = {
            "org.adw.launcher.THEMES", "com.novalauncher.THEME", "com.gau.go.launcherex.theme",
            "org.adw.launcher.icons.ACTION_PICK_ICON", "com.dlto.atom.launcher.THEME",
    };
    private static final String CATEGORY = "com.anddoes.launcher.THEME";

    final String pkg;
    private final Resources res;
    private final int density;
    private final Map<String, String> map = new HashMap<>();
    private final List<Bitmap> backs = new ArrayList<>();
    private Bitmap mask, upon;
    private float scale = 1f;

    /** Installed packs: package -> label, sorted by label. */
    static Map<String, String> installed(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, String> found = new HashMap<>();
        List<ResolveInfo> all = new ArrayList<>();
        for (String a : ACTIONS) all.addAll(pm.queryIntentActivities(new Intent(a), PackageManager.GET_META_DATA));
        all.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(CATEGORY), 0));
        for (ResolveInfo r : all) {
            String p = r.activityInfo.packageName;
            if (!found.containsKey(p)) found.put(p, String.valueOf(r.activityInfo.applicationInfo.loadLabel(pm)));
        }
        List<Map.Entry<String, String>> l = new ArrayList<>(found.entrySet());
        l.sort((x, y) -> Ui.norm(x.getValue()).compareTo(Ui.norm(y.getValue())));
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : l) out.put(e.getKey(), e.getValue());
        return out;
    }

    /** Returns null if the pack is missing or unreadable. Call off the main thread. */
    static IconPack load(Context c, String pkg) {
        try {
            IconPack p = new IconPack(c, pkg);
            return p.map.isEmpty() && p.backs.isEmpty() ? null : p;
        } catch (Exception e) {
            return null;
        }
    }

    private IconPack(Context c, String pkg) throws Exception {
        this.pkg = pkg;
        res = c.getPackageManager().getResourcesForApplication(pkg);
        density = c.getResources().getDisplayMetrics().densityDpi;
        parse();
    }

    private void parse() throws Exception {
        XmlPullParser xp;
        InputStream in = null;
        int id = res.getIdentifier("appfilter", "xml", pkg);
        if (id != 0) {
            xp = res.getXml(id);
        } else {
            in = res.getAssets().open("appfilter.xml");
            xp = Xml.newPullParser();
            xp.setInput(in, "UTF-8");
        }
        try {
            for (int ev = xp.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = xp.next()) {
                if (ev != XmlPullParser.START_TAG) continue;
                switch (xp.getName()) {
                    case "item": {
                        String comp = xp.getAttributeValue(null, "component");
                        String draw = xp.getAttributeValue(null, "drawable");
                        String key = componentKey(comp);
                        if (key != null && draw != null && !map.containsKey(key)) map.put(key, draw);
                        break;
                    }
                    case "iconback":
                        for (int i = 0; i < xp.getAttributeCount(); i++) {
                            Bitmap b = bitmap(xp.getAttributeValue(i));
                            if (b != null) backs.add(b);
                        }
                        break;
                    case "iconmask":
                        mask = bitmap(xp.getAttributeValue(null, "img1"));
                        break;
                    case "iconupon":
                        upon = bitmap(xp.getAttributeValue(null, "img1"));
                        break;
                    case "scale":
                        try { scale = Float.parseFloat(xp.getAttributeValue(null, "factor")); } catch (Exception ignored) { }
                        break;
                }
            }
        } finally {
            if (in != null) in.close();
        }
    }

    /** "ComponentInfo{pkg/cls}" -> "pkg/full.Cls" (same form as ComponentName.flattenToString()). */
    private static String componentKey(String raw) {
        if (raw == null) return null;
        int a = raw.indexOf('{'), b = raw.lastIndexOf('}');
        String inner = a >= 0 && b > a ? raw.substring(a + 1, b) : raw;
        ComponentName cn = ComponentName.unflattenFromString(inner.trim());
        return cn == null ? null : cn.flattenToString();
    }

    private Drawable drawable(String name) {
        if (name == null) return null;
        int id = res.getIdentifier(name, "drawable", pkg);
        if (id == 0) return null;
        try { return res.getDrawableForDensity(id, density, null); } catch (Exception e) { return null; }
    }

    private Bitmap bitmap(String name) {
        Drawable d = drawable(name);
        if (d instanceof BitmapDrawable) return ((BitmapDrawable) d).getBitmap();
        if (d == null) return null;
        int w = Math.max(1, d.getIntrinsicWidth()), h = Math.max(1, d.getIntrinsicHeight());
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, w, h);
        d.draw(new Canvas(b));
        return b;
    }

    /** The pack's icon for cn; otherwise the original drawn onto the pack's backing (or the original). */
    Drawable icon(ComponentName cn, Drawable original) {
        Drawable themed = drawable(map.get(cn.flattenToString()));
        if (themed != null) return themed;
        if (backs.isEmpty() || original == null) return original;

        Bitmap back = backs.get(Math.abs(cn.getPackageName().hashCode()) % backs.size());
        int w = back.getWidth(), h = back.getHeight();
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        int sw = (int) (w * scale), sh = (int) (h * scale);
        original.setBounds((w - sw) / 2, (h - sh) / 2, (w + sw) / 2, (h + sh) / 2);
        original.draw(cv);
        Rect full = new Rect(0, 0, w, h);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (mask != null) {
            p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
            cv.drawBitmap(mask, null, full, p);
        }
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OVER));
        cv.drawBitmap(back, null, full, p);
        p.setXfermode(null);
        if (upon != null) cv.drawBitmap(upon, null, full, p);
        return new BitmapDrawable(res, out);
    }
}
