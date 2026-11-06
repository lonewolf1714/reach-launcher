package com.reach.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Every theme keeps its own home setup: the tile pages (tiles, widgets, app icons and where they
 * sit), which pages show, the home page, dock size, grid columns and per-tile styles. Switching theme
 * saves the setup being left and brings back the new theme's one. A built-in theme that has none yet
 * starts from its own designed setup; Minimal and themes you made keep what was on the home screen.
 *
 * Widgets a layout doesn't use are "parked": still bound (so nothing is lost), just not shown.
 */
final class Layouts {
    static final String CURRENT = "layoutTheme", PARKED = "wparked";
    private static final String[] STR_KEYS = {"tilepages", "homePage", "tilestyles", "dockCount"};
    private static final String[] BOOL_KEYS = {"hideToday", "hideAppPages", "tilesSeeded"};
    private static final String[] INT_KEYS = {Prefs.COLUMNS};

    private Layouts() { }

    /** A designed setup: tiles ("ICONS:n@row" expands to a row-by-row grid of n app icons), dock size, pages. */
    private static final class Design {
        final String[] tiles;
        final int dock;
        final boolean appPages;

        Design(int dock, boolean appPages, String... tiles) { this.tiles = tiles; this.dock = dock; this.appPages = appPages; }
    }

    private static Design design(String id) {
        switch (id) {
            // classic Android: date + battery, two rows of apps, app pages behind
            case "graphite":   return new Design(5, true, "date|2|3|0|0", "battery|2|3|2|0", "ICONS:8@3");
            // phone-like: big clock, one week card, two rows of icons, four in the dock
            case "silver":     return new Design(4, true, "week|4|4|0|0", "ICONS:8@4");
            // no clock: date and day cards up top, three rows of bright icons
            case "vividglass": return new Design(4, true, "date|2|4|0|0", "day|2|4|2|0", "ICONS:12@4");
            // paper: outlined date, day, week and agenda
            case "inkline":    return new Design(5, true, "date|2|4|0|0", "day|2|4|2|0", "week|4|4|0|4", "events|4|6|0|8");
            // calm: thin clock, one agenda card, a single row of apps
            case "glass":      return new Design(5, true, "events|4|5|0|0", "ICONS:4@5");
            // reader: every app as one quiet A–Z list, no app pages
            case "porcelain":  return new Design(4, false, "applist:all|4|12|0|0");
            // bold: huge clock, day progress line, favourites list
            case "monolith":   return new Design(4, true, "day|4|2|0|0", "applist:fav|4|8|0|2");
            // grid: battery + day, four rows of tile icons
            case "apricot":    return new Design(5, true, "battery|2|3|0|0", "day|2|3|2|0", "ICONS:16@3");
            // magazine: serif clock, big date, agenda
            case "editorial":  return new Design(4, true, "date|4|4|0|0", "events|4|5|0|4");
            // console: seconds clock, status line, everything in one A–Z list
            case "terminal":   return new Design(5, false, "battery|2|2|0|0", "day|2|2|2|0", "applist:all|4|10|0|2");
            default:           return null; // Minimal and your own themes: no designed setup
        }
    }

    static boolean hasDesign(String themeId) { return design(themeId) != null; }

    /** Kept for callers that only need to know a design exists. */
    static String[] designed(String themeId) { Design d = design(themeId); return d == null ? null : d.tiles; }

    /** App keys for icon tiles: the home grid order, skipping what's already in the dock. */
    private static List<String> iconKeys(Prefs p, int n) {
        List<String> out = new ArrayList<>();
        Set<String> dock = new HashSet<>(p.favorites());
        for (String k : p.str("apporder", "").split("\n")) {
            if (out.size() >= n) break;
            if (k.isEmpty() || dock.contains(k)) continue;
            out.add(k);
        }
        return out;
    }

    private static JSONObject designJson(Prefs p, Design d) {
        JSONArray page = new JSONArray();
        for (String t : d.tiles) {
            if (t.startsWith("ICONS:")) {
                int n = Integer.parseInt(t.substring(6, t.indexOf('@'))), row = Integer.parseInt(t.substring(t.indexOf('@') + 1));
                List<String> keys = iconKeys(p, n);
                for (int i = 0; i < keys.size(); i++) page.put("app:" + keys.get(i) + "|1|2|" + (i % 4) + "|" + (row + (i / 4) * 2));
            } else {
                page.put(t);
            }
        }
        JSONObject o = new JSONObject();
        try {
            o.put("tilepages", new JSONArray().put(page).toString());
            o.put("tilestyles", "{}");
            o.put("tilesSeeded", true);
            o.put("hideToday", false);
            o.put("hideAppPages", !d.appPages);
            o.put("homePage", "0");
            o.put("dockCount", String.valueOf(d.dock));
            o.put(Prefs.COLUMNS, 4);
        } catch (Exception ignored) { }
        return o;
    }

    static JSONObject capture(Prefs p) {
        JSONObject o = new JSONObject();
        try {
            for (String k : STR_KEYS) { String v = p.str(k, null); if (v != null) o.put(k, v); }
            for (String k : BOOL_KEYS) o.put(k, p.bool(k, false));
            for (String k : INT_KEYS) o.put(k, p.integer(k, 4));
        } catch (Exception ignored) { }
        return o;
    }

    static void restore(Prefs p, JSONObject o) {
        for (String k : STR_KEYS) {
            if (o.has(k)) p.set(k, o.optString(k));
            else if (k.equals("tilestyles")) p.set(k, "{}");
        }
        for (String k : BOOL_KEYS) if (o.has(k)) p.set(k, o.optBoolean(k));
        for (String k : INT_KEYS) if (o.has(k)) p.set(k, o.optInt(k));
        p.set("inklineSeeded", true);
        park(p);
    }

    /** The setup a theme should show: its saved one, its designed one, or null (keep what's on screen). */
    private static JSONObject setupFor(Prefs p, String themeId) {
        String saved = p.str("layout:" + themeId, null);
        if (saved != null) { try { return new JSONObject(saved); } catch (Exception ignored) { } }
        Design d = design(themeId);
        return d == null ? null : designJson(p, d);
    }

    /**
     * Call when the active theme may have changed (not during a preview). Returns true when the
     * home setup was swapped.
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
        JSONObject next = setupFor(p, active);
        if (next != null) restore(p, next);
        p.setQuiet(CURRENT, active);
        park(p);
        return true;
    }

    // ---- theme preview: show each theme's own setup, put everything back on cancel ----

    static String previewStart(Prefs p) { return capture(p).toString(); }

    static void previewShow(Prefs p, String themeId, String backup) {
        try {
            JSONObject next = themeId.equals(p.str(CURRENT, "")) ? new JSONObject(backup) : setupFor(p, themeId);
            restore(p, next != null ? next : new JSONObject(backup));
        } catch (Exception ignored) { }
    }

    static void previewCancel(Prefs p, String backup) {
        try { restore(p, new JSONObject(backup)); } catch (Exception ignored) { }
    }

    /** The previewed setup is already live; file the old one under the theme being left. */
    static void previewApply(Prefs p, String themeId, String backup) {
        String cur = p.str(CURRENT, null);
        if (cur != null && !cur.equals(themeId)) p.setQuiet("layout:" + cur, backup);
        p.setQuiet(CURRENT, themeId);
        park(p);
    }

    /** Back to the theme's designed setup (themes without one are left as they are). */
    static boolean reset(Prefs p, String themeId) {
        Design d = design(themeId);
        if (d == null) return false;
        JSONObject o = designJson(p, d);
        if (themeId.equals(p.str(CURRENT, ""))) restore(p, o);
        else p.setQuiet("layout:" + themeId, o.toString());
        return true;
    }

    /** Gives another theme a copy of the setup that's on the home screen now. */
    static void copyCurrentTo(Prefs p, String themeId) {
        if (themeId.equals(p.str(CURRENT, ""))) return;
        p.setQuiet("layout:" + themeId, capture(p).toString());
    }

    /** Forget a theme's saved setup so it starts from its design again (used when themes are redesigned). */
    static void forget(Prefs p, String themeId) {
        if (themeId.equals(p.str(CURRENT, ""))) return;
        p.setQuiet("layout:" + themeId, null);
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
