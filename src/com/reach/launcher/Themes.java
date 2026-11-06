package com.reach.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A theme is a named snapshot of every "look" preference: colors, fonts, icons, depth, and how
 * the clock, search bar, dock, grid, widgets and background look. Stored as JSON in prefs.
 * A theme may carry a "dark" block that overrides values when the phone (or Reach) is in dark mode.
 */
final class Themes {
    static final String[] KEYS = {Prefs.ACCENT, Prefs.DIM, Prefs.ICON, Prefs.LABEL, Prefs.DEPTH,
            Prefs.FONT, Prefs.TEXT_COLOR, Prefs.ICON_STYLE, Prefs.ICON_COLOR, Prefs.ICON_PACK,
            Prefs.CLOCK_SHOW, Prefs.CLOCK_SIZE, Prefs.DATE_SIZE, Prefs.CLOCK_FONT, Prefs.CLOCK_ALIGN,
            Prefs.CLOCK_FORMAT, Prefs.CLOCK_SECONDS, Prefs.DATE_STYLE, Prefs.CLOCK_COLOR,
            Prefs.GRID_LABELS, Prefs.GRID_LABEL_SIZE, Prefs.DOCK_ICON, Prefs.DOCK_BG,
            Prefs.SEARCH_SHOW, Prefs.SEARCH_H, Prefs.SEARCH_SHAPE, Prefs.SEARCH_OPACITY, Prefs.SEARCH_POS,
            Prefs.DOTS, Prefs.WIDGET_BG, Prefs.WIDGET_RADIUS, Prefs.BG, Prefs.BG_COLOR, Prefs.SURFACE, Prefs.ICON_WEIGHT, Prefs.ICON_TILE, Prefs.TILE_STYLE};

    private static final Set<String> INTS = new HashSet<>(Arrays.asList(Prefs.ACCENT, Prefs.DIM, Prefs.ICON,
            Prefs.LABEL, Prefs.DEPTH, Prefs.CLOCK_SIZE, Prefs.DATE_SIZE, Prefs.GRID_LABEL_SIZE, Prefs.DOCK_ICON,
            Prefs.SEARCH_H, Prefs.SEARCH_OPACITY, Prefs.WIDGET_RADIUS, Prefs.BG_COLOR, Prefs.SURFACE));

    private Themes() { }

    static boolean isInt(String k) { return INTS.contains(k); }

    static List<JSONObject> load(Prefs p) {
        List<JSONObject> out = new ArrayList<>();
        String raw = p.str("themes", null);
        if (raw != null) {
            try {
                JSONArray a = new JSONArray(raw);
                for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i));
            } catch (Exception ignored) { }
        }
        if (out.isEmpty()) out = builtIns();
        // themes added in later versions show up for existing users too
        JSONObject min = find(out, "minimal");
        if (min != null && !p.bool("minimalLine", false)) { // Minimal now uses the line icon set
            try { min.put(Prefs.ICON_STYLE, IconStyler.LINE); min.put(Prefs.ICON_WEIGHT, "regular"); min.put(Prefs.ICON_TILE, "off"); } catch (Exception ignored) { }
            p.set("minimalLine", true);
            save(p, out);
        }
        if (min != null && !p.bool("minimalBare", false)) { // ...drawn bare, without tiles
            try { min.put(Prefs.ICON_TILE, "off"); } catch (Exception ignored) { }
            p.set("minimalBare", true);
            save(p, out);
        }
        if (find(out, "inkline") == null && !p.bool("inklineAdded", false)) {
            out.add(Math.min(2, out.size()), inkline());
            p.set("inklineAdded", true);
            save(p, out);
        }
        if (find(out, "silver") == null && !p.bool("silverAdded", false)) {
            out.add(Math.min(1, out.size()), silver());
            p.set("silverAdded", true);
            save(p, out);
        }
        if (find(out, "vividglass") == null && !p.bool("vividAdded", false)) {
            out.add(Math.min(2, out.size()), vividGlass());
            p.set("vividAdded", true);
            save(p, out);
        }
        if (find(out, "minimal") == null && !p.bool("minimalAdded", false)) {
            out.add(1, minimal());
            p.set("minimalAdded", true);
            save(p, out);
        }
        return out;
    }

    static void save(Prefs p, List<JSONObject> list) {
        JSONArray a = new JSONArray();
        for (JSONObject t : list) a.put(t);
        p.set("themes", a.toString());
    }

    static JSONObject find(List<JSONObject> list, String id) {
        for (JSONObject t : list) if (t.optString("id").equals(id)) return t;
        return null;
    }

    /** The current live look as a new theme. */
    static JSONObject capture(Prefs p, String name) {
        JSONObject t = new JSONObject();
        try {
            t.put("id", UUID.randomUUID().toString().substring(0, 8));
            t.put("name", name);
            for (String k : KEYS) t.put(k, isInt(k) ? (Object) p.i(k) : (Object) p.s(k));
        } catch (Exception ignored) { }
        return t;
    }

    static JSONObject copy(JSONObject t, String name) {
        try {
            JSONObject c = new JSONObject(t.toString());
            c.put("id", UUID.randomUUID().toString().substring(0, 8));
            c.put("name", name);
            return c;
        } catch (Exception e) {
            return t;
        }
    }

    /** Value of k for this theme in the given mode (dark block, then theme, then default). */
    static Object value(JSONObject t, String k, boolean dark) {
        JSONObject d = dark ? t.optJSONObject("dark") : null;
        if (d != null && d.has(k)) return d.opt(k);
        if (t.has(k)) return t.opt(k);
        return Prefs.DEF.get(k);
    }

    static int i(JSONObject t, String k, boolean dark) {
        Object v = value(t, k, dark);
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }

    static String s(JSONObject t, String k, boolean dark) {
        Object v = value(t, k, dark);
        return v == null ? "" : String.valueOf(v);
    }

    private static JSONObject theme(String id, String name, Object... kv) {
        JSONObject t = new JSONObject();
        try {
            t.put("id", id);
            t.put("name", name);
            for (int i = 0; i + 1 < kv.length; i += 2) t.put((String) kv[i], kv[i + 1]);
        } catch (Exception ignored) { }
        return t;
    }

    /** Soft greys, outline icons on surface tiles, info tiles instead of a big clock. Light + dark. */
    static JSONObject minimal() {
        JSONObject t = theme("minimal", "Minimal",
                Prefs.ACCENT, 0xFF2B2B2B, Prefs.DIM, 0, Prefs.DEPTH, 0, Prefs.FONT, "res:inter",
                Prefs.TEXT_COLOR, "black", Prefs.ICON_STYLE, IconStyler.LINE, Prefs.ICON_COLOR, "surface", Prefs.ICON_WEIGHT, "regular", Prefs.ICON_TILE, "off",
                Prefs.BG, "solid", Prefs.BG_COLOR, 0xFFE3E3E3, Prefs.SURFACE, 0xFFF0F0F0,
                Prefs.CLOCK_SHOW, "off", Prefs.SEARCH_SHOW, "off", Prefs.DOCK_BG, "off", Prefs.DOCK_ICON, 60,
                Prefs.WIDGET_BG, "surface", Prefs.WIDGET_RADIUS, 26, Prefs.GRID_LABEL_SIZE, 12);
        try {
            JSONObject dark = new JSONObject();
            dark.put(Prefs.TEXT_COLOR, "white");
            dark.put(Prefs.ACCENT, 0xFFE6E6E6);
            dark.put(Prefs.BG_COLOR, 0xFF0E0E0F);
            dark.put(Prefs.SURFACE, 0xFF1E1E20);
            t.put("dark", dark);
        } catch (Exception ignored) { }
        return t;
    }

    /** Black outlines on white cards, header bars, framed line icons, a text-only search. Light + dark. */
    static JSONObject inkline() {
        JSONObject t = theme("inkline", "Inkline",
                Prefs.ACCENT, 0xFF111111, Prefs.DIM, 0, Prefs.DEPTH, 0, Prefs.FONT, "res:inter",
                Prefs.TEXT_COLOR, "black", Prefs.ICON_STYLE, IconStyler.LINE, Prefs.ICON_COLOR, "surface",
                Prefs.ICON_WEIGHT, "regular", Prefs.ICON_TILE, "frame", Prefs.TILE_STYLE, "outlined",
                Prefs.BG, "solid", Prefs.BG_COLOR, 0xFFEFEFEF, Prefs.SURFACE, 0xFFFFFFFF,
                Prefs.CLOCK_SHOW, "off", Prefs.SEARCH_SHOW, "on", Prefs.SEARCH_SHAPE, "text", Prefs.SEARCH_H, 32,
                Prefs.DOCK_BG, "off", Prefs.DOCK_ICON, 64, Prefs.WIDGET_BG, "surface", Prefs.WIDGET_RADIUS, 24,
                Prefs.GRID_LABEL_SIZE, 12);
        try {
            JSONObject dark = new JSONObject();
            dark.put(Prefs.TEXT_COLOR, "white");
            dark.put(Prefs.ACCENT, 0xFFF2F2F2);
            dark.put(Prefs.BG_COLOR, 0xFF0B0B0B);
            dark.put(Prefs.SURFACE, 0xFF000000);
            t.put("dark", dark);
        } catch (Exception ignored) { }
        return t;
    }

    /** Brushed silver, a tall condensed clock, glass cards and black / white / orange duo icon tiles. Light + dark. */
    static JSONObject silver() {
        JSONObject t = theme("silver", "Silver Glass",
                Prefs.ACCENT, 0xFFF26A1B, Prefs.DIM, 0, Prefs.DEPTH, 2, Prefs.FONT, "res:inter",
                Prefs.TEXT_COLOR, "black", Prefs.ICON_STYLE, IconStyler.DUO, Prefs.ICON_COLOR, "accent", Prefs.ICON_TILE, "on",
                Prefs.BG, "silver", Prefs.BG_COLOR, 0xFFA9ADB2, Prefs.SURFACE, 0xFFE9EAEC,
                Prefs.CLOCK_SHOW, "on", Prefs.CLOCK_FONT, "res:big_shoulders", Prefs.CLOCK_SIZE, 104, Prefs.DATE_SIZE, 15,
                Prefs.CLOCK_ALIGN, "center", Prefs.CLOCK_FORMAT, "24", Prefs.CLOCK_COLOR, "text",
                Prefs.SEARCH_SHOW, "off", Prefs.DOCK_BG, "on", Prefs.DOCK_ICON, 58,
                Prefs.WIDGET_BG, "glass", Prefs.WIDGET_RADIUS, 30, Prefs.GRID_LABEL_SIZE, 11);
        try {
            JSONObject dark = new JSONObject();
            dark.put(Prefs.TEXT_COLOR, "white");
            dark.put(Prefs.BG_COLOR, 0xFF3B3E43);
            dark.put(Prefs.SURFACE, 0xFF26282C);
            t.put("dark", dark);
        } catch (Exception ignored) { }
        return t;
    }

    /** Silver Glass with bright gradient icon tiles (near-black tiles with colored symbols in dark mode). */
    static JSONObject vividGlass() {
        JSONObject t = silver();
        try {
            t.put("id", "vividglass");
            t.put("name", "Vivid Glass");
            t.put(Prefs.ICON_STYLE, IconStyler.VIVID);
            t.put(Prefs.ACCENT, 0xFF1A73F0);
        } catch (Exception ignored) { }
        return t;
    }

    static List<JSONObject> builtIns() {
        List<JSONObject> l = new ArrayList<>();
        l.add(silver());
        l.add(vividGlass());
        l.add(theme("graphite", "Graphite", Prefs.ACCENT, 0xFF8FD3C3, Prefs.DIM, 20, Prefs.DEPTH, 1));
        l.add(minimal());
        l.add(inkline());
        l.add(theme("glass", "Soft Glass", Prefs.ACCENT, 0xFF9DB4FF, Prefs.DIM, 25, Prefs.DEPTH, 3,
                Prefs.FONT, "res:jakarta", Prefs.ICON_STYLE, IconStyler.SQUIRCLE, Prefs.CLOCK_ALIGN, "center",
                Prefs.CLOCK_FONT, "sans-serif-thin", Prefs.CLOCK_SIZE, 88, Prefs.WIDGET_BG, "glass"));
        l.add(theme("porcelain", "Porcelain", Prefs.ACCENT, 0xFF3A6EA5, Prefs.DIM, 0, Prefs.DEPTH, 2,
                Prefs.FONT, "res:dm_sans", Prefs.TEXT_COLOR, "black", Prefs.ICON_STYLE, IconStyler.SQUIRCLE));
        l.add(theme("monolith", "Monolith", Prefs.ACCENT, 0xFFFFFFFF, Prefs.DIM, 40, Prefs.DEPTH, 1,
                Prefs.FONT, "res:inter", Prefs.ICON_STYLE, IconStyler.MONO, Prefs.CLOCK_FONT, "bold"));
        l.add(theme("apricot", "Apricot Tiles", Prefs.ACCENT, 0xFFF2B880, Prefs.DIM, 25, Prefs.DEPTH, 2,
                Prefs.FONT, "res:outfit", Prefs.ICON_STYLE, IconStyler.TILE, Prefs.ICON_COLOR, "accent"));
        l.add(theme("editorial", "Editorial", Prefs.ACCENT, 0xFFD7A6FF, Prefs.DIM, 35, Prefs.DEPTH, 1,
                Prefs.FONT, "res:playfair", Prefs.ICON_STYLE, IconStyler.LETTER, Prefs.CLOCK_ALIGN, "center",
                Prefs.DATE_STYLE, "short"));
        l.add(theme("terminal", "Terminal", Prefs.ACCENT, 0xFFC6E07A, Prefs.DIM, 60, Prefs.DEPTH, 0,
                Prefs.FONT, "res:space_mono", Prefs.ICON_STYLE, IconStyler.MONO, Prefs.ICON_COLOR, "accent",
                Prefs.CLOCK_SECONDS, "on", Prefs.CLOCK_FORMAT, "24", Prefs.DATE_STYLE, "numeric"));
        return l;
    }
}
