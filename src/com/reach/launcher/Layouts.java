package com.reach.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Every theme keeps its own home layout: the tile pages (tiles, widgets and where they sit), which
 * pages show, the home page and per-tile styles. Switching theme saves the layout being left and
 * brings back the new theme's one. A theme that has none yet starts from its own designed layout
 * (built-in themes) or from the current one (themes you made). Minimal never gets a designed layout:
 * it keeps exactly what was on the home screen.
 *
 * Widgets a layout doesn't use are "parked": still bound (so nothing is lost), just not shown.
 */
final class Layouts {
    static final String CURRENT = "layoutTheme", PARKED = "wparked";
    private static final String[] STR_KEYS = {"tilepages", "homePage", "tilestyles"};
    private static final String[] BOOL_KEYS = {"hideToday", "hideAppPages", "tilesSeeded"};

    private Layouts() { }

    /** Calm starting layouts: a few tiles, lots of air, each matching its theme's character. */
    static String[] designed(String themeId) {
        switch (themeId) {
            case "graphite":  return new String[]{"date|2|4|0|0", "battery|2|4|2|0"};
            case "silver":    return new String[]{"week|4|4|0|0"};
            case "vividglass":return new String[]{"day|2|4|0|0", "date|2|4|2|0"};
            case "inkline":   return new String[]{"date|2|4|0|0", "day|2|4|2|0", "week|4|4|0|4", "events|4|6|0|8"};
            case "glass":     return new String[]{"events|4|5|0|0"};
            case "porcelain": return new String[]{"date|2|4|0|0", "events|2|4|2|0"};
            case "monolith":  return new String[]{"day|4|3|0|0"};
            case "apricot":   return new String[]{"battery|2|4|0|0", "day|2|4|2|0"};
            case "editorial": return new String[]{"date|4|5|0|0"};
            case "terminal":  return new String[]{"battery|2|3|0|0", "day|2|3|2|0", "events|4|5|0|3"};
            default:          return null; // minimal and your own themes: no designed layout
        }
    }

    static JSONObject capture(Prefs p) {
        JSONObject o = new JSONObject();
        try {
            for (String k : STR_KEYS) { String v = p.str(k, null); if (v != null) o.put(k, v); }
            for (String k : BOOL_KEYS) o.put(k, p.bool(k, false));
        } catch (Exception ignored) { }
        return o;
    }

    private static void restore(Prefs p, JSONObject o) {
        for (String k : STR_KEYS) {
            if (o.has(k)) p.set(k, o.optString(k));
            else if (k.equals("tilestyles")) p.set(k, "{}");
        }
        for (String k : BOOL_KEYS) if (o.has(k)) p.set(k, o.optBoolean(k));
    }

    private static void applyDesigned(Prefs p, String[] specs) {
        JSONArray page = new JSONArray();
        for (String s : specs) page.put(s);
        JSONArray pages = new JSONArray();
        pages.put(page);
        p.set("tilepages", pages.toString());
        p.set("tilestyles", "{}");
        p.set("tilesSeeded", true);
        p.set("hideToday", false);
        p.set("inklineSeeded", true); // the designed layout already has Inkline's tiles
    }

    /**
     * Call when the active theme may have changed (not during a preview). Returns true when the
     * home layout was swapped.
     */
    static boolean sync(Prefs p) {
        String active = p.activeTheme();
        if (active.isEmpty()) return false;
        String cur = p.str(CURRENT, null);
        if (cur == null) { // first run with layouts: whatever is on screen belongs to the active theme
            p.setQuiet(CURRENT, active);
            return false;
        }
        if (cur.equals(active)) return false;
        p.setQuiet("layout:" + cur, capture(p).toString());
        String saved = p.str("layout:" + active, null);
        if (saved != null) {
            try { restore(p, new JSONObject(saved)); } catch (Exception ignored) { }
        } else {
            String[] d = designed(active);
            if (d != null) applyDesigned(p, d);
            // otherwise the theme starts from the layout that's on screen now
        }
        p.setQuiet(CURRENT, active);
        park(p);
        return true;
    }

    /** Back to the theme's designed layout (or, for themes without one, leave as is). */
    static boolean reset(Prefs p, String themeId) {
        String[] d = designed(themeId);
        if (d == null) return false;
        if (themeId.equals(p.str(CURRENT, ""))) { applyDesigned(p, d); park(p); }
        else p.setQuiet("layout:" + themeId, designedJson(d).toString());
        return true;
    }

    /** Gives another theme a copy of the layout that's on the home screen now. */
    static void copyCurrentTo(Prefs p, String themeId) {
        if (themeId.equals(p.str(CURRENT, ""))) return;
        p.setQuiet("layout:" + themeId, capture(p).toString());
    }

    private static JSONObject designedJson(String[] specs) {
        JSONObject o = new JSONObject();
        try {
            JSONArray page = new JSONArray();
            for (String s : specs) page.put(s);
            o.put("tilepages", new JSONArray().put(page).toString());
            o.put("tilestyles", "{}");
            o.put("tilesSeeded", true);
            o.put("hideToday", false);
        } catch (Exception ignored) { }
        return o;
    }

    /** Widgets bound but not used by the current layout. */
    static void park(Prefs p) {
        Set<Integer> used = new HashSet<>();
        for (List<String> l : Tiles.load(p)) for (String s : l) if (Tiles.isWidget(s)) used.add(Tiles.widgetId(s));
        StringBuilder b = new StringBuilder();
        for (String slot : p.widgetSlots()) {
            List<Integer> ids = Prefs.idsOf(slot);
            if (ids.isEmpty() || used.contains(ids.get(0))) continue;
            if (b.length() > 0) b.append(',');
            b.append(ids.get(0));
        }
        p.setQuiet(PARKED, b.toString());
    }

    static Set<Integer> parked(Prefs p) {
        Set<Integer> out = new HashSet<>();
        for (String s : p.str(PARKED, "").split(",")) {
            try { if (!s.isEmpty()) out.add(Integer.parseInt(s.trim())); } catch (Exception ignored) { }
        }
        return out;
    }

    /** A widget the user just added is never parked. */
    static void unpark(Prefs p, int id) {
        Set<Integer> s = parked(p);
        if (!s.remove(id)) return;
        StringBuilder b = new StringBuilder();
        for (int x : s) { if (b.length() > 0) b.append(','); b.append(x); }
        p.setQuiet(PARKED, b.toString());
    }
}
