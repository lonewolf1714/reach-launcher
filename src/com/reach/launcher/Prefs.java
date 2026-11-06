package com.reach.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.text.TextUtils;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** All persistent launcher state. Every write bumps "rev" so the home screen knows to refresh. */
final class Prefs {
    static final String LEFT = "left", ICON = "icon", LABEL = "label", CLOCK = "clock", SEARCHBAR = "searchbar",
            PREVIEW = "preview", DIM = "dim", ACCENT = "accent", FONT = "font", SWIPE_DOWN = "swipedown",
            ICON_PACK = "iconpack", LAYOUT = "layout", COLUMNS = "columns",
            ICON_STYLE = "iconstyle", ICON_COLOR = "iconcolor", TEXT_COLOR = "textcolor",
            DEPTH = "depth", THEME = "theme", MODE = "mode";

    // ---- home element look (all part of themes) ----
    static final String CLOCK_SHOW = "clockshow", CLOCK_SIZE = "clocksize", DATE_SIZE = "datesize",
            CLOCK_FONT = "clockfont", CLOCK_ALIGN = "clockalign", CLOCK_FORMAT = "clockformat",
            CLOCK_SECONDS = "clockseconds", DATE_STYLE = "datestyle", CLOCK_COLOR = "clockcolor",
            GRID_LABELS = "gridlabels", GRID_LABEL_SIZE = "gridlabelsize", DOCK_ICON = "dockicon", DOCK_BG = "dockbg",
            SEARCH_SHOW = "searchshow", SEARCH_H = "searchh", SEARCH_SHAPE = "searchshape",
            SEARCH_OPACITY = "searchopacity", SEARCH_POS = "searchpos", SEARCH_TEXT = "searchtext", DOTS = "dots",
            WIDGET_BG = "widgetbg", WIDGET_RADIUS = "widgetradius",
            BG = "bg", BG_COLOR = "bgcolor", SURFACE = "surface", ICON_WEIGHT = "iconweight", ICON_TILE = "icontile",
            TILE_STYLE = "tilestyle";

    static final int[] ACCENTS = {0xFF8FD3C3, 0xFFF2B880, 0xFF9DB4FF, 0xFFFF8A9A, 0xFFC6E07A, 0xFFD7A6FF,
            0xFFFFD166, 0xFFFFFFFF};

    /** Defaults for every theme-able key (Integer or String). */
    static final Map<String, Object> DEF = new HashMap<>();

    static {
        DEF.put(ACCENT, ACCENTS[0]);
        DEF.put(DIM, 15);
        DEF.put(ICON, 44);
        DEF.put(LABEL, 20);
        DEF.put(DEPTH, 1);
        DEF.put(FONT, "sans-serif");
        DEF.put(TEXT_COLOR, "white");
        DEF.put(ICON_STYLE, IconStyler.ORIGINAL);
        DEF.put(ICON_COLOR, "white");
        DEF.put(ICON_PACK, "");
        DEF.put(CLOCK_SHOW, "on");
        DEF.put(CLOCK_SIZE, 64);
        DEF.put(DATE_SIZE, 17);
        DEF.put(CLOCK_FONT, "theme");
        DEF.put(CLOCK_ALIGN, "start");
        DEF.put(CLOCK_FORMAT, "system");
        DEF.put(CLOCK_SECONDS, "off");
        DEF.put(DATE_STYLE, "long");
        DEF.put(CLOCK_COLOR, "text");
        DEF.put(GRID_LABELS, "on");
        DEF.put(GRID_LABEL_SIZE, 12);
        DEF.put(DOCK_ICON, 56);
        DEF.put(DOCK_BG, "on");
        DEF.put(SEARCH_SHOW, "on");
        DEF.put(SEARCH_H, 44);
        DEF.put(SEARCH_SHAPE, "pill");
        DEF.put(SEARCH_OPACITY, 18);
        DEF.put(SEARCH_POS, "bottom");
        DEF.put(DOTS, "on");
        DEF.put(WIDGET_BG, "none");
        DEF.put(WIDGET_RADIUS, 22);
        DEF.put(BG, "wallpaper");
        DEF.put(BG_COLOR, 0xFFE4E4E4);
        DEF.put(SURFACE, 0xFFF2F2F2);
        DEF.put(ICON_WEIGHT, "regular");
        DEF.put(ICON_TILE, "on");
        DEF.put(TILE_STYLE, "filled");
    }

    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("reach", Context.MODE_PRIVATE);
    }

    long rev() { return sp.getLong("rev", 0); }

    private SharedPreferences.Editor ed() { return sp.edit().putLong("rev", rev() + 1); }

    boolean bool(String k, boolean d) { return sp.getBoolean(k, d); }
    int integer(String k, int d) { return sp.getInt(k, d); }
    String str(String k, String d) { return sp.getString(k, d); }
    void set(String k, boolean v) { ed().putBoolean(k, v).apply(); }
    void set(String k, int v) { ed().putInt(k, v).apply(); }
    void set(String k, String v) { ed().putString(k, v).apply(); }
    /** Write without bumping rev (bookkeeping the home screen doesn't need to redraw for). */
    void setQuiet(String k, String v) { sp.edit().putString(k, v).apply(); }

    /** Saves without telling the home screen to rebuild (UI state such as which stack card is showing). */
    void setQuietInt(String k, int v) { sp.edit().putInt(k, v).apply(); }

    /** Theme-able int with its default. */
    int i(String k) {
        Object d = DEF.get(k);
        return integer(k, d instanceof Integer ? (Integer) d : 0);
    }

    /** Theme-able string with its default. */
    String s(String k) {
        Object d = DEF.get(k);
        return str(k, d instanceof String ? (String) d : "");
    }

    boolean on(String k) { return !"off".equals(s(k)); }

    long firstRun() {
        long t = sp.getLong("firstRun", 0);
        if (t == 0) { t = System.currentTimeMillis(); sp.edit().putLong("firstRun", t).apply(); }
        return t;
    }

    boolean rareFolder() { return bool("rarefolder", true); }
    String rareName() { return str("rarename", "Rarely used"); }

    boolean leftHanded() { return bool(LEFT, false); }
    int iconDp() { return i(ICON); }
    int labelSp() { return i(LABEL); }
    /** Older installs stored the clock/search switches as booleans; those still count. */
    boolean showClock() { return on(CLOCK_SHOW) && bool(CLOCK, true); }
    boolean showSearchBar() { return on(SEARCH_SHOW) && bool(SEARCHBAR, true); }
    boolean showPreviews() { return bool(PREVIEW, true); }
    int dimPercent() { return i(DIM); }
    int accent() { return i(ACCENT); }
    String font() { return s(FONT); }
    String swipeDown() { return str(SWIPE_DOWN, "shade"); }
    /** "" = system icons, otherwise the icon pack's package name. */
    String iconPack() { return s(ICON_PACK); }
    /** "pages" = paged icon grid with dock (default), "list" = one-handed list. */
    boolean pagedLayout() { return !"list".equals(str(LAYOUT, "pages")); }
    int columns() { return integer(COLUMNS, 4); }
    String iconStyle() { return s(ICON_STYLE); }
    /** "white", "black", "accent" or "surface". */
    String iconColor() { return s(ICON_COLOR); }
    /** 0 flat, 1 soft, 2 medium, 3 deep: icon shadows, highlight and text/dock depth. */
    int depth() { return i(DEPTH); }
    String activeTheme() { return str(THEME, ""); }
    int ink() { return "black".equals(s(TEXT_COLOR)) ? 0xFF111111 : 0xFFFFFFFF; }
    boolean solidBg() { return "solid".equals(s(BG)) || metalBg(); }
    /** "silver": the solid color with a diagonal brushed-metal sheen. */
    boolean metalBg() { return "silver".equals(s(BG)); }
    int surface() { return i(SURFACE); }

    /** "light", "dark" or "auto" (follow the phone). */
    /** "theme" (the theme's own look), "light", "dark" or "auto" (follow the phone). */
    String mode() { return str(MODE, "theme"); }

    static boolean systemDark(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    boolean darkNow(Context c) {
        String m = mode();
        return "dark".equals(m) || (("auto".equals(m) || "theme".equals(m)) && systemDark(c));
    }

    /** A light or dark version of a theme that doesn't come with one. */
    static JSONObject derive(JSONObject t, boolean dark) {
        try {
            JSONObject d = new JSONObject(t.toString());
            d.remove("dark");
            d.put(TEXT_COLOR, dark ? "white" : "black");
            boolean solid = !"wallpaper".equals(Themes.s(t, BG, false));
            if (solid) d.put(BG_COLOR, dark ? 0xFF0E0E10 : 0xFFF0F0F2);
            d.put(SURFACE, dark ? 0xFF1E1E21 : 0xFFFFFFFF);
            if (!solid) d.put(DIM, dark ? Math.max(Themes.i(t, DIM, false), 35) : 0);
            String ic = Themes.s(t, ICON_COLOR, false);
            if (ic.equals("white") || ic.equals("black")) d.put(ICON_COLOR, dark ? "white" : "black");
            String cc = Themes.s(t, CLOCK_COLOR, false);
            if (cc.equals("white") || cc.equals("black")) d.put(CLOCK_COLOR, "text");
            if (!dark && "dark".equals(Themes.s(t, WIDGET_BG, false))) d.put(WIDGET_BG, "surface");
            return d;
        } catch (Exception e) {
            return t;
        }
    }

    /**
     * Copies a theme's values into the live prefs in one write and marks it active. Keys the
     * theme doesn't set fall back to defaults; a theme's "dark" block overrides in dark mode.
     */
    void applyLook(JSONObject t, boolean dark) {
        JSONObject over = dark ? t.optJSONObject("dark") : null;
        SharedPreferences.Editor e = ed();
        for (String k : Themes.KEYS) {
            Object v = over != null && over.has(k) ? over.opt(k) : t.has(k) ? t.opt(k) : DEF.get(k);
            if (v == null) continue;
            if (Themes.isInt(k)) e.putInt(k, v instanceof Number ? ((Number) v).intValue() : (Integer) DEF.get(k));
            else e.putString(k, String.valueOf(v));
        }
        e.remove(CLOCK).remove(SEARCHBAR); // switches now live in the theme
        e.putString(THEME, t.optString("id")).apply();
    }

    /**
     * Applies a theme in the current appearance mode. "theme" keeps the theme as designed (its own
     * dark version follows the phone); "light"/"dark"/"auto" turn any theme light or dark, using its
     * dark version when it has one and a worked-out version when it doesn't.
     */
    void applyLook(JSONObject t, Context c) {
        String m = mode();
        boolean has = t.optJSONObject("dark") != null, sys = systemDark(c);
        if ("theme".equals(m)) { applyLook(t, has && sys); return; }
        boolean dark = "dark".equals(m) || ("auto".equals(m) && sys);
        boolean themeDark = "white".equals(Themes.s(t, TEXT_COLOR, false));
        if (has) { applyLook(t, dark); return; }
        if (dark == themeDark) { applyLook(t, false); return; }
        applyLook(derive(t, dark), false);
    }

    /** Saves the live look back into the active theme (edits on the home screen belong to it). */
    void syncActiveTheme(Context c) {
        List<JSONObject> list = Themes.load(this);
        JSONObject t = Themes.find(list, activeTheme());
        if (t == null) return;
        JSONObject target = t;
        if (darkNow(c) && t.optJSONObject("dark") != null) target = t.optJSONObject("dark");
        try {
            for (String k : Themes.KEYS) {
                Object v = Themes.isInt(k) ? (Object) i(k) : (Object) s(k);
                if (target != t && !target.has(k) && v.equals(t.opt(k))) continue; // keep the dark block minimal
                target.put(k, v);
            }
        } catch (Exception ignored) { }
        Themes.save(this, list);
    }

    // ---- favorites (ordered keys, see AppRepo for the key format) ----
    List<String> favorites() { return split(str("favorites", "")); }

    void setFavorites(List<String> l) { ed().putString("favorites", TextUtils.join("\n", l)).apply(); }

    boolean isFavorite(String k) { return favorites().contains(k); }

    void addFavorite(String k) {
        List<String> l = favorites();
        if (!l.contains(k)) { l.add(k); setFavorites(l); }
    }

    void removeFavorite(String k) {
        List<String> l = favorites();
        if (l.remove(k)) setFavorites(l);
    }

    void moveFavorite(String k, int delta) {
        List<String> l = favorites();
        int i = l.indexOf(k), j = i + delta;
        if (i < 0 || j < 0 || j >= l.size()) return;
        Collections.swap(l, i, j);
        setFavorites(l);
    }

    boolean seeded() { return bool("seeded", false); }
    void markSeeded() { sp.edit().putBoolean("seeded", true).apply(); }

    // ---- hidden apps and renames ----
    Set<String> hidden() { return new HashSet<>(sp.getStringSet("hidden", Collections.<String>emptySet())); }

    boolean isHidden(String k) { return sp.getStringSet("hidden", Collections.<String>emptySet()).contains(k); }

    void setHidden(String k, boolean h) {
        Set<String> s = hidden();
        if (h) s.add(k); else s.remove(k);
        ed().putStringSet("hidden", s).apply();
    }

    String rename(String k) { return sp.getString("name:" + k, null); }

    void setRename(String k, String name) {
        if (name == null || name.trim().isEmpty()) ed().remove("name:" + k).apply();
        else ed().putString("name:" + k, name.trim()).apply();
    }

    // ---- widgets: ordered slots; a slot is one widget id or a stack "12+15+19" ----
    List<String> widgetSlots() { return split(str("widgets", "")); }

    void setWidgetSlots(List<String> slots) { ed().putString("widgets", TextUtils.join("\n", slots)).apply(); }

    static List<Integer> idsOf(String slot) {
        List<Integer> out = new ArrayList<>();
        for (String p : slot.split("\\+")) {
            try { out.add(Integer.parseInt(p.trim())); } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    static String slotOf(List<Integer> ids) { return TextUtils.join("+", ids); }

    /** Every widget id in every slot. */
    List<Integer> widgets() {
        List<Integer> out = new ArrayList<>();
        for (String slot : widgetSlots()) out.addAll(idsOf(slot));
        return out;
    }

    /** New slot at the end (also used by PinActivity). */
    void addWidget(int id) {
        if (widgets().contains(id)) return;
        List<String> l = widgetSlots();
        l.add(String.valueOf(id));
        setWidgetSlots(l);
    }

    /** Adds id on top of the stack at slot index. */
    void stackWidget(int slot, int id) {
        List<String> l = widgetSlots();
        if (slot < 0 || slot >= l.size()) { addWidget(id); return; }
        List<Integer> ids = idsOf(l.get(slot));
        if (!ids.contains(id)) ids.add(id);
        l.set(slot, slotOf(ids));
        setWidgetSlots(l);
    }

    /** Removes id wherever it is; empty slots disappear. */
    void removeWidget(int id) {
        List<String> l = widgetSlots(), out = new ArrayList<>();
        for (String slot : l) {
            List<Integer> ids = idsOf(slot);
            ids.remove(Integer.valueOf(id));
            if (!ids.isEmpty()) out.add(slotOf(ids));
        }
        ed().putString("widgets", TextUtils.join("\n", out)).remove("wh:" + id).apply();
    }

    int widgetHeight(int id) { return sp.getInt("wh:" + id, 0); }
    void setWidgetHeight(int id, int px) { ed().putInt("wh:" + id, px).apply(); }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        for (String p : s.split("\n")) if (!p.isEmpty()) out.add(p);
        return out;
    }
}
