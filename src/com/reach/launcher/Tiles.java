package com.reach.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import org.json.JSONArray;

import java.text.DateFormatSymbols;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Reach's own home-screen tiles (not Android widgets), drawn with the theme's font, text color and
 * surface color. Specs: "date", "day" (day progress), "battery", "week" (two-week calendar),
 * "app:<key>" (small app tile), "wapp:<key>" (wide app tile). Tiles live on tile pages: page 0 is the
 * clock page; more pages can be added in edit mode.
 */
final class Tiles {
    private Tiles() { }

    static final String[][] TYPES = {{"date", "Date"}, {"day", "Day progress"}, {"battery", "Battery"},
            {"week", "Week calendar"}, {"events", "Up Next (calendar)"}, {"applist:pick", "App list · choose apps"},
            {"applist:fav", "App list · favorites"}, {"applist:all", "App list · all apps"}};

    // ---- storage: prefs "tilepages" = [[spec, ...], [spec, ...]] ----

    static List<List<String>> load(Prefs p) {
        List<List<String>> out = new ArrayList<>();
        try {
            JSONArray pages = new JSONArray(p.str("tilepages", "[[]]"));
            for (int i = 0; i < pages.length(); i++) {
                JSONArray a = pages.getJSONArray(i);
                List<String> l = new ArrayList<>();
                for (int j = 0; j < a.length(); j++) l.add(a.getString(j));
                out.add(l);
            }
        } catch (Exception ignored) { }
        if (out.isEmpty()) out.add(new ArrayList<>());
        if (!"2".equals(p.str("tileGrid", "1"))) { // old 2-column grid -> the finer 4-column, half-row grid
            for (List<String> l : out) for (int i = 0; i < l.size(); i++) l.set(i, doubled(l.get(i)));
            JSONArray a = new JSONArray();
            for (List<String> l : out) a.put(new JSONArray(l));
            p.setQuiet("tilepages", a.toString());
            p.setQuiet("tileGrid", "2");
        }
        return out;
    }

    /** Old-grid spec numbers (w|h|col|row) times two. */
    static String doubled(String spec) {
        String[] p = spec.split("\\|");
        if (p.length < 2) return spec;
        StringBuilder b = new StringBuilder(p[0]);
        for (int i = 1; i < p.length; i++) {
            try { b.append("|").append(Integer.parseInt(p[i]) * 2); } catch (Exception e) { b.append("|").append(p[i]); }
        }
        return b.toString();
    }

    static void save(Prefs p, List<List<String>> pages) {
        JSONArray a = new JSONArray();
        for (List<String> l : pages) a.put(new JSONArray(l));
        p.set("tilesSeeded", true); // from now on an empty page stays empty (no default tiles)
        p.set("tilepages", a.toString());
    }

    static String label(String spec, String appLabel) {
        spec = base(spec);
        if (spec.startsWith("widget:")) return "Widget";
        for (String[] t : TYPES) if (t[0].equals(spec)) return t[1];
        return appLabel != null ? appLabel : "App";
    }

    // ---- views ----

    interface Host {
        Entry entry(String key);
        View widget(int firstId);
        List<Entry> apps(boolean favorites);
        /** Apps picked for a custom list, in the user's order. */
        List<Entry> picked(String listId);
        /** Settings for an app list: title "t", text size "sz", icons "ic", letter strip "st". */
        org.json.JSONObject listConfig(String listId);
        void appMenu(Entry e);
        void requestCalendar();
        void launch(Entry e, View v);
        void openCalendar();
        void safeStart(Intent i);
        /** The app's home-screen icon with its label (same look as the app pages), or null for the card look. */
        default View appIcon(Entry e) { return null; }
        /** This tile's own style overrides (card, colors, font, size...), or null to follow the theme. */
        default org.json.JSONObject tileStyle(String key) { return null; }
    }

    // ---- size: spec "type|w|h"; w = 1 (half) or 2 (full width); h = height in half-cell units (1..4) ----

    static String base(String spec) {
        int bar = spec.indexOf('|');
        String b = bar < 0 ? spec : spec.substring(0, bar);
        return b.startsWith("wapp:") ? b.substring(1) : b; // old wide app tiles
    }

    static int w(String spec) {
        String[] p = spec.split("\\|");
        if (p.length >= 2) try { return Math.max(1, Math.min(G, Integer.parseInt(p[1]))); } catch (Exception ignored) { }
        return spec.equals("week") || spec.startsWith("week|") || spec.startsWith("wapp:") || spec.startsWith("applist") ? G : G / 2;
    }

    static int h(String spec) {
        String[] p = spec.split("\\|");
        if (p.length >= 3) try { return Math.max(1, Math.min(MAX_H, Integer.parseInt(p[2]))); } catch (Exception ignored) { }
        return spec.startsWith("applist") ? 8 : 4;
    }

    /** Keeps the item's saved position, if it has one. */
    static String sized(String spec, int w, int h) {
        int[] at = place(spec);
        return base(spec) + "|" + w + "|" + h + (at == null ? "" : "|" + at[0] + "|" + at[1]);
    }

    /** Saved grid position {column 0..1, row}, or null if the item flows into the first free spot. */
    static int[] place(String spec) {
        String[] p = spec.split("\\|");
        if (p.length >= 5) {
            try { return new int[]{Math.max(0, Math.min(G - 1, Integer.parseInt(p[3]))), Math.max(0, Integer.parseInt(p[4]))}; }
            catch (Exception ignored) { }
        }
        return null;
    }

    static String placed(String spec, int col, int row) { return base(spec) + "|" + w(spec) + "|" + h(spec) + "|" + col + "|" + row; }

    /** Grid columns; rows are half a classic tile tall, so sizes and spots move in small steps. */
    static final int G = 4;
    static final int MAX_H = 14;

    /** "widget:<first app-widget id>" items are Android widgets placed in the same grid as tiles. */
    static boolean isWidget(String spec) { return base(spec).startsWith("widget:"); }

    static int widgetId(String spec) {
        try { return Integer.parseInt(base(spec).substring(7)); } catch (Exception e) { return -1; }
    }

    static boolean wide(String spec) { return w(spec) == G; }

    // ---- position grid: 2 columns, rows of one "unit"; empty cells are allowed ----

    static int gap(Context c) { return Ui.dp(c, 14); }

    static int colWidth(Context c, int width) { return (width - (G - 1) * gap(c)) / G; }

    /** Pixel width of an item w columns wide. */
    static int itemWidth(Context c, int width, int w) {
        return w >= G ? width : w * colWidth(c, width) + (w - 1) * gap(c);
    }

    static int pitch(Context c, int width) { return unit(c, width) + gap(c); }

    private static boolean free(List<boolean[]> occ, int col, int row, int w, int h) {
        for (int r = row; r < row + h; r++) {
            if (r >= occ.size()) continue;
            for (int c = col; c < col + w; c++) if (c >= G || occ.get(r)[c]) return false;
        }
        return true;
    }

    private static void mark(List<boolean[]> occ, int col, int row, int w, int h) {
        while (occ.size() < row + h) occ.add(new boolean[G]);
        for (int r = row; r < row + h; r++) for (int c = col; c < Math.min(G, col + w); c++) occ.get(r)[c] = true;
    }

    /**
     * Positions {col,row} for every item. The fixed item (being dragged, -1 for none) goes exactly at
     * (fc, fr); every other item keeps its saved spot when it's free, otherwise it is pushed down to the
     * next free spot. Items without a saved spot fill the first free spots from the top.
     */
    static int[][] arrange(List<String> specs, int fixed, int fc, int fr) {
        return arrange(specs, fixed, fc, fr, Integer.MAX_VALUE);
    }

    /**
     * As above, but nothing may reach below maxRows. When the others can't keep their spots, they are
     * repacked into the free space (top to bottom, in their current order), and the moved item may settle
     * a row or two away from the finger. Returns null only when nothing fits at all.
     */
    static int[][] arrange(List<String> specs, int fixed, int fc, int fr, int maxRows) {
        int[][] keep = arrangeStrict(specs, fixed, fc, fr, maxRows);
        if (keep != null || maxRows == Integer.MAX_VALUE) return keep;
        if (fixed < 0 || fixed >= specs.size()) return compact(specs, -1, 0, 0, maxRows);
        int h = h(specs.get(fixed)), top = Math.max(0, Math.min(fr, maxRows - h));
        for (int d = 0; d <= maxRows; d++) {
            for (int rr : new int[]{top - d, top + d}) {
                if (rr < 0 || rr + h > maxRows || (d == 0 && rr != top)) continue;
                int[][] out = compact(specs, fixed, fc, rr, maxRows);
                if (out != null) return out;
            }
        }
        return null;
    }

    /** Fixed item at (fc, fr); every other item takes the first free spot from the top, in its current order. */
    static int[][] compact(List<String> specs, int fixed, int fc, int fr, int maxRows) {
        int n = specs.size();
        int[][] out = new int[n][];
        List<boolean[]> occ = new ArrayList<>();
        if (fixed >= 0) {
            String sp = specs.get(fixed);
            int w = w(sp), c = Math.max(0, Math.min(G - w, fc));
            if (fr + h(sp) > maxRows) return null;
            out[fixed] = new int[]{c, fr};
            mark(occ, c, fr, w, h(sp));
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) if (i != fixed) order.add(i);
        order.sort((a, b) -> {
            int[] pa = place(specs.get(a)), pb = place(specs.get(b));
            int ra = pa == null ? 0 : pa[1], rb = pb == null ? 0 : pb[1];
            if (ra != rb) return Integer.compare(ra, rb);
            return Integer.compare(pa == null ? 0 : pa[0], pb == null ? 0 : pb[0]);
        });
        for (int i : order) {
            String sp = specs.get(i);
            int w = w(sp), h = h(sp);
            int[] pref = place(sp);
            int prefCol = pref == null ? 0 : Math.max(0, Math.min(G - w, pref[0]));
            boolean done = false;
            for (int r = 0; r + h <= maxRows && !done; r++) {
                int[] cols = new int[G - w + 1];
                cols[0] = prefCol;
                for (int k = 0, j = 1; k <= G - w; k++) if (k != prefCol) cols[j++] = k;
                for (int c : cols) if (free(occ, c, r, w, h)) { out[i] = new int[]{c, r}; mark(occ, c, r, w, h); done = true; break; }
            }
            if (!done) return null;
        }
        return out;
    }

    private static int[][] arrangeStrict(List<String> specs, int fixed, int fc, int fr, int maxRows) {
        int n = specs.size();
        int[][] out = new int[n][];
        List<boolean[]> occ = new ArrayList<>();
        if (fixed >= 0 && fixed < n) {
            String sp = specs.get(fixed);
            int w = w(sp), c = Math.max(0, Math.min(G - w, fc)), r = Math.max(0, fr);
            if (maxRows != Integer.MAX_VALUE) r = Math.max(0, Math.min(r, maxRows - h(sp)));
            out[fixed] = new int[]{c, r};
            mark(occ, c, r, w, h(sp));
        }
        for (int i = 0; i < n; i++) {
            if (i == fixed) continue;
            String sp = specs.get(i);
            int w = w(sp), h = h(sp);
            int[] pref = place(sp);
            int startRow = pref == null ? 0 : pref[1];
            int prefCol = pref == null ? 0 : Math.max(0, Math.min(G - w, pref[0]));
            if (pref != null && free(occ, prefCol, startRow, w, h)) {
                out[i] = new int[]{prefCol, startRow};
            } else {
                found:
                for (int r = startRow; ; r++) {
                    if (maxRows != Integer.MAX_VALUE && r + h > maxRows) {
                        if (r == startRow && startRow > 0) { r = -1; startRow = 0; continue; } // look higher up too
                        return null; // no room left on the page
                    }
                    int[] cols = new int[G - w + 1];
                    cols[0] = prefCol; // the preferred column first, then left to right
                    for (int k = 0, j = 1; k <= G - w; k++) if (k != prefCol) cols[j++] = k;
                    for (int c : cols) {
                        if (free(occ, c, r, w, h)) { out[i] = new int[]{c, r}; break found; }
                    }
                }
            }
            mark(occ, out[i][0], out[i][1], w, h);
        }
        return out;
    }

    /** Creates the views and lays them out on the position grid. Returns views in spec order. */
    static List<View> grid(Context c, LinearLayout box, List<String> specs, Host host, Style s, int width) {
        List<View> views = new ArrayList<>();
        for (String spec : specs) views.add(create(c, spec, host, s));
        layout(c, box, specs, views, width);
        return views;
    }

    static void layout(Context c, LinearLayout box, List<String> specs, List<View> views, int width) {
        layout(c, box, specs, views, width, arrange(specs, -1, 0, 0));
    }

    /** The grid canvas inside a page box (created once and reused so moves can animate). */
    static FrameLayout canvas(Context c, LinearLayout box) {
        if (box.getChildCount() == 1 && "canvas".equals(box.getChildAt(0).getTag())) return (FrameLayout) box.getChildAt(0);
        box.removeAllViews();
        FrameLayout canvas = new FrameLayout(c);
        canvas.setTag("canvas");
        canvas.setClipChildren(false);
        canvas.setClipToPadding(false);
        box.setClipChildren(false);
        box.addView(canvas, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return canvas;
    }

    /** Places views at grid positions (absolute), and sizes the canvas to fit the lowest item. */
    static void layout(Context c, LinearLayout box, List<String> specs, List<View> views, int width, int[][] pos) {
        layout(c, box, specs, views, width, pos, null);
    }

    /** As above, but the held view keeps its current place (it follows the finger via translation). */
    static void layout(Context c, LinearLayout box, List<String> specs, List<View> views, int width, int[][] pos, View held) {
        FrameLayout canvas = canvas(c, box);
        int gap = gap(c), colW = colWidth(c, width), pitch = pitch(c, width);
        int bottom = 0;
        java.util.Set<View> keep = new java.util.HashSet<>(views);
        for (int i = canvas.getChildCount() - 1; i >= 0; i--) if (!keep.contains(canvas.getChildAt(i))) canvas.removeViewAt(i);
        for (int k = 0; k < specs.size() && k < views.size(); k++) {
            String spec = specs.get(k);
            View v = views.get(k);
            int w = w(spec), h = h(spec);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(itemWidth(c, width, w), h * pitch - gap);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.leftMargin = w >= G ? 0 : pos[k][0] * (colW + gap);
            lp.topMargin = pos[k][1] * pitch;
            bottom = Math.max(bottom, lp.topMargin + lp.height);
            if (v == held && v.getParent() == canvas) continue;
            if (v.getParent() == canvas) v.setLayoutParams(lp);
            else {
                if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
                canvas.addView(v, lp);
            }
        }
        ViewGroup.LayoutParams cl = canvas.getLayoutParams();
        cl.height = bottom + gap;
        canvas.setLayoutParams(cl);
    }

    /** Top-left of grid position {col,row} inside the canvas, for an item of width w. */
    static int[] slotXY(Context c, int width, int w, int[] pos) {
        int gap = gap(c), colW = colWidth(c, width);
        return new int[]{w >= G ? 0 : pos[0] * (colW + gap), pos[1] * pitch(c, width)};
    }

    static int unit(Context c, int width) {
        int gap = Ui.dp(c, 14);
        int classic = Math.round(Math.max(Ui.dp(c, 120), (width - gap) / 2) * 0.47f); // the old full row
        return Math.round((classic + gap) / 2f) - gap; // half row: two of them plus a gap make a classic row
    }

    static final class Style {
        final int ink, surface, radius;
        final Typeface face;
        final boolean depth, outlined;
        // per-tile extras (Widget Studio style editor)
        float scale = 1f;
        String bg = "theme";   // theme, glass, solid, none
        int opacity = 100;
        boolean gloss, border;

        /** A copy with this tile's own overrides applied. */
        Style with(Context c, org.json.JSONObject o) {
            if (o == null || o.length() == 0) return this;
            int ink2 = o.optInt("ink", 0) != 0 ? o.optInt("ink") : ink;
            int surf2 = o.optInt("bgc", 0) != 0 ? o.optInt("bgc") : surface;
            int rad2 = o.has("radius") ? Ui.dp(c, o.optInt("radius")) : radius;
            String f = o.optString("font", "");
            Typeface face2 = f.isEmpty() ? face : Ui.typeface(c, f, Typeface.NORMAL);
            String d = o.optString("depth", "theme");
            boolean depth2 = "on".equals(d) || (!"off".equals(d) && depth);
            Style s = new Style(ink2, surf2, rad2, face2, depth2, outlined);
            s.scale = o.optInt("scale", 100) / 100f;
            s.bg = o.optString("bg", "theme");
            if (o.optInt("bgc", 0) != 0 && "theme".equals(s.bg)) s.bg = "solid";
            s.opacity = o.optInt("opacity", 100);
            s.gloss = o.optBoolean("gloss", false);
            s.border = o.optBoolean("border", false);
            return s;
        }

        Style(int ink, int surface, int radius, Typeface face, boolean depth) { this(ink, surface, radius, face, depth, false); }

        Style(int ink, int surface, int radius, Typeface face, boolean depth, boolean outlined) {
            this.ink = ink;
            this.surface = surface;
            this.radius = radius;
            this.face = face;
            this.depth = depth;
            this.outlined = outlined;
        }
    }

    private static FrameLayout card(Context c, Style s) {
        FrameLayout f = new FrameLayout(c);
        f.setBackground(Ui.ripple(c, cardLook(c, s)));
        int pad = s.outlined ? 0 : Ui.dp(c, 16);
        f.setPadding(pad, pad, pad, pad);
        if (s.depth && !s.outlined && !"none".equals(s.bg)) f.setElevation(Ui.dp(c, 3));
        f.setClipToOutline(true);
        return f;
    }

    /** The card behind a tile: theme surface, glass, a solid colour or nothing, with opacity, border and gloss. */
    private static android.graphics.drawable.Drawable cardLook(Context c, Style s) {
        boolean none = "none".equals(s.bg);
        int fill = "glass".equals(s.bg) ? (Ui.isLight(s.ink) ? 0x30FFFFFF : 0x40FFFFFF) : s.surface;
        fill = Ui.alpha(fill, Math.round((fill >>> 24) * Math.max(0, Math.min(100, s.opacity)) / 100f));
        android.graphics.drawable.GradientDrawable bg = Ui.round(none ? 0 : fill, s.radius);
        if (s.outlined) bg.setStroke(Ui.dp(c, 2.5f), s.ink);
        else if (s.border) bg.setStroke(Ui.dp(c, 1.5f), Ui.alpha(s.ink, 0x66));
        else if ("glass".equals(s.bg)) bg.setStroke(Ui.dp(c, 1), 0x40FFFFFF);
        android.graphics.drawable.Drawable look = bg;
        if (s.gloss && !none) { // a soft light across the top half
            android.graphics.drawable.GradientDrawable shine = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x55FFFFFF, 0x10FFFFFF, 0x00FFFFFF});
            shine.setCornerRadius(s.radius);
            look = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{bg, shine});
        }
        return look;
    }

    /** Outlined style: a header bar, a full-width rule, then the body. Returns the body container. */
    private static LinearLayout framed(Context c, FrameLayout f, Style s, View header) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(c, 16), Ui.dp(c, 10), Ui.dp(c, 16), Ui.dp(c, 10));
        head.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(head);
        View rule = new View(c);
        rule.setBackgroundColor(s.ink);
        col.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 2.5f)));
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ui.dp(c, 14), Ui.dp(c, 10), Ui.dp(c, 14), Ui.dp(c, 12));
        col.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        f.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return body;
    }

    private static TextView text(Context c, Style s, float sp, boolean bold, int alpha) {
        TextView t = new TextView(c);
        t.setTextColor(Ui.alpha(s.ink, alpha));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * s.scale);
        t.setTypeface(Typeface.create(s.face, bold ? Typeface.BOLD : Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    static View create(Context c, String spec, Host host, Style s) {
        spec = base(spec);
        if (!spec.startsWith("widget:") && !spec.startsWith("app:")) s = s.with(c, host.tileStyle(spec));
        if (spec.startsWith("widget:")) {
            View w = host.widget(widgetId(spec));
            if (w != null) return w;
            FrameLayout ph = card(c, s);
            TextView t = text(c, s, 14, false, 0x99);
            t.setText("Widget loading…");
            ph.addView(t);
            return ph;
        }
        if (spec.startsWith("app:")) {
            Entry e = host.entry(spec.substring(4));
            View icon = e == null ? null : host.appIcon(e);
            if (icon != null) { // a plain icon + label, no card
                FrameLayout bare = new FrameLayout(c);
                bare.addView(icon, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
                bare.setContentDescription(e.label);
                return bare;
            }
        }
        FrameLayout f = card(c, s);
        if (s.outlined && (spec.equals("date") || spec.equals("day") || spec.equals("battery") || spec.equals("week") || spec.equals("events"))) {
            outlinedTile(c, f, spec, host, s);
            return f;
        }
        if (spec.equals("events")) {
            TextView t = text(c, s, 16, true, 0xFF);
            t.setText("Up Next");
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(t);
            col.addView(new Events(c, s, host), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            f.addView(col);
            f.setOnClickListener(v -> host.openCalendar());
            return f;
        }
        if (spec.equals("date")) {
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout top = new LinearLayout(c);
            TextClock wd = new TextClock(c);
            styleClock(wd, s, 17, true, 0xFF, "EEE");
            TextClock mo = new TextClock(c);
            styleClock(mo, s, 17, false, 0x88, " MMM");
            top.addView(wd);
            top.addView(mo);
            col.addView(top);
            TextClock day = new TextClock(c);
            styleClock(day, s, 58, true, 0xFF, "d");
            day.setPadding(0, Ui.dp(c, 4), 0, 0);
            col.addView(day);
            f.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER_VERTICAL | Gravity.START));
            f.setOnClickListener(v -> host.openCalendar());
            f.setContentDescription("Date");
        } else if (spec.equals("day") || spec.equals("battery")) {
            boolean battery = spec.equals("battery");
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout head = new LinearLayout(c);
            TextView name = text(c, s, 16, true, 0xFF);
            name.setText(battery ? "Battery" : "Day");
            head.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView pct = text(c, s, 16, true, 0xFF);
            head.addView(pct);
            col.addView(head);
            Dots dots = new Dots(c, s.ink, battery, pct);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            dl.topMargin = Ui.dp(c, 12);
            col.addView(dots, dl);
            f.addView(col);
            if (battery) f.setOnClickListener(v -> host.safeStart(new Intent(Intent.ACTION_POWER_USAGE_SUMMARY)));
            f.setContentDescription(battery ? "Battery" : "Day progress");
        } else if (spec.equals("week")) {
            f.addView(new Week(c, s));
            f.setOnClickListener(v -> host.openCalendar());
            f.setContentDescription("Calendar");
        } else if (spec.startsWith("applist:")) {
            appList(c, f, spec, host, s);
        } else if (spec.startsWith("app:")) {
            Entry e = host.entry(spec.substring(spec.indexOf(':') + 1));
            ImageView iv = new ImageView(c);
            if (e != null && e.icon != null && e.icon.getConstantState() != null)
                iv.setImageDrawable(e.icon.getConstantState().newDrawable(c.getResources()));
            f.addView(iv, new FrameLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40), Gravity.TOP | Gravity.START));
            TextView t = text(c, s, 17, true, 0xFF);
            t.setText(e != null ? e.label : "App removed");
            f.addView(t, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.START));
            if (e != null) f.setOnClickListener(v -> host.launch(e, v));
            f.setContentDescription(e != null ? e.label : "App");
        }
        return f;
    }

    /**
     * A list inside the (also scrolling) home page: it keeps the swipe while it can scroll that way,
     * and hands it back to the page at its top or bottom.
     */
    static final class InnerScroll extends android.widget.ScrollView {
        private float downY;

        InnerScroll(Context c) { super(c); }

        @Override
        public boolean onInterceptTouchEvent(android.view.MotionEvent e) {
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                downY = e.getY();
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            return super.onInterceptTouchEvent(e);
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent e) {
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_MOVE) {
                float dy = e.getY() - downY;
                if ((dy > 0 && !canScrollVertically(-1)) || (dy < 0 && !canScrollVertically(1)))
                    getParent().requestDisallowInterceptTouchEvent(false); // at the end: let the page take over
            }
            return super.onTouchEvent(e);
        }
    }

    /** Id of a list in "applist:all", "applist:fav" or "applist:pick:<id>" (the id keys its settings). */
    static String listId(String spec) {
        String b = base(spec);
        return b.startsWith("applist:pick:") ? b.substring(13) : b.substring(8);
    }

    /** A scrolling list of apps inside a tile: all apps, favorites, or the user's own picks. */
    private static void appList(Context c, FrameLayout f, String spec, Host host, Style s) {
        f.setTag("applist");
        f.setPadding(Ui.dp(c, 6), Ui.dp(c, 8), Ui.dp(c, 6), Ui.dp(c, 8));
        f.setBackground(cardLook(c, s)); // no ripple over the whole list
        if ("none".equals(s.bg)) f.setElevation(0);
        String id = listId(spec);
        boolean picks = spec.startsWith("applist:pick"), fav = id.equals("fav");
        org.json.JSONObject cfg = host.listConfig(id);
        List<Entry> list = picks ? host.picked(id) : host.apps(fav);
        float sz = cfg.optInt("sz", 16);
        boolean icons = cfg.optBoolean("ic", true);
        boolean strip = cfg.optBoolean("st", !fav && !picks);
        String title = cfg.optString("t", "");
        LinearLayout outer = new LinearLayout(c);
        outer.setOrientation(LinearLayout.VERTICAL);
        if (!title.isEmpty()) {
            TextView h = text(c, s, 16, true, 0xFF);
            h.setText(title);
            h.setPadding(Ui.dp(c, 10), Ui.dp(c, 2), Ui.dp(c, 10), Ui.dp(c, 6));
            outer.addView(h);
        }
        android.widget.ScrollView sv = new InnerScroll(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        sv.setFadingEdgeLength(Ui.dp(c, 18));
        sv.setVerticalFadingEdgeEnabled(true);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        java.util.Map<String, View> firstOf = new java.util.LinkedHashMap<>();
        for (Entry e : list) {
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(c, 10), Ui.dp(c, 7), Ui.dp(c, 10), Ui.dp(c, 7));
            row.setBackground(Ui.ripple(c, null));
            if (icons) {
                ImageView iv = new ImageView(c);
                if (e.icon != null && e.icon.getConstantState() != null)
                    iv.setImageDrawable(e.icon.getConstantState().newDrawable(c.getResources()));
                int is = Ui.dp(c, sz * 2.1f);
                row.addView(iv, new LinearLayout.LayoutParams(is, is));
            }
            TextView t = text(c, s, sz, false, 0xFF);
            t.setText(e.label);
            t.setPadding(icons ? Ui.dp(c, 14) : Ui.dp(c, 2), 0, 0, 0);
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.setOnClickListener(v -> host.launch(e, v));
            row.setOnLongClickListener(v -> { host.appMenu(e); return true; });
            col.addView(row);
            if (!firstOf.containsKey(e.section)) firstOf.put(e.section, row);
        }
        if (list.isEmpty()) {
            TextView t = text(c, s, 14, false, 0x99);
            t.setText(picks ? "No apps picked yet. Edit mode → tap this list → Options → Choose apps."
                    : fav ? "No favorites yet. Long-press an app → Add to favorites." : "No apps");
            t.setSingleLine(false);
            t.setPadding(Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12));
            col.addView(t);
        }
        sv.addView(col);
        boolean bar = strip && firstOf.size() > 1;
        FrameLayout listArea = new FrameLayout(c);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        if (bar) sl.rightMargin = Ui.dp(c, 26);
        listArea.addView(sv, sl);
        outer.addView(listArea, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        f.addView(outer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (bar) {
            WaveBar wave = new WaveBar(c, new ArrayList<>(firstOf.keySet()), s.ink, s.ink, s.face, letter -> {
                View target = firstOf.get(letter);
                if (target != null) sv.smoothScrollTo(0, target.getTop());
            });
            listArea.addView(wave, new FrameLayout.LayoutParams(Ui.dp(c, 26), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));
        }
    }

    private static void outlinedTile(Context c, FrameLayout f, String spec, Host host, Style s) {
        if (spec.equals("date")) {
            LinearLayout top = new LinearLayout(c);
            top.setGravity(Gravity.CENTER);
            TextClock wd = new TextClock(c);
            styleClock(wd, s, 18, true, 0xFF, "EEE");
            TextClock mo = new TextClock(c);
            styleClock(mo, s, 18, false, 0x80, " MMM");
            top.addView(wd);
            top.addView(mo);
            LinearLayout body = framed(c, f, s, top);
            body.setGravity(Gravity.CENTER);
            TextClock day = new TextClock(c);
            styleClock(day, s, 64, false, 0xFF, "dd");
            day.setGravity(Gravity.CENTER);
            body.addView(day, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            f.setOnClickListener(v -> host.openCalendar());
            f.setContentDescription("Date");
        } else if (spec.equals("day") || spec.equals("battery")) {
            boolean battery = spec.equals("battery");
            LinearLayout head = new LinearLayout(c);
            TextView name = text(c, s, 18, true, 0xFF);
            name.setText(battery ? "Battery" : "Day");
            head.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView pct = text(c, s, 18, false, 0x80);
            head.addView(pct);
            LinearLayout body = framed(c, f, s, head);
            Dots dots = new Dots(c, s.ink, battery, pct);
            dots.hollow = true;
            body.addView(dots, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            if (battery) f.setOnClickListener(v -> host.safeStart(new Intent(Intent.ACTION_POWER_USAGE_SUMMARY)));
            f.setContentDescription(battery ? "Battery" : "Day progress");
        } else if (spec.equals("week")) {
            TextView month = text(c, s, 18, true, 0xFF);
            month.setText(Calendar.getInstance().getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.getDefault()));
            LinearLayout body = framed(c, f, s, month);
            Week w = new Week(c, s);
            w.noTitle = true;
            body.addView(w, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            f.setOnClickListener(v -> host.openCalendar());
            f.setContentDescription("Calendar");
        } else if (spec.equals("events")) {
            TextView t = text(c, s, 18, true, 0xFF);
            t.setText("Up Next");
            LinearLayout body = framed(c, f, s, t);
            body.addView(new Events(c, s, host), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            f.setOnClickListener(v -> host.openCalendar());
            f.setContentDescription("Up next");
        }
    }

    /** Next calendar events (up to a week ahead), refreshed every few minutes. */
    static final class Events extends LinearLayout {
        private final Style s;
        private final Host host;
        private final Runnable tick = new Runnable() {
            @Override public void run() { load(); postDelayed(this, 5 * 60_000); }
        };

        Events(Context c, Style s, Host host) {
            super(c);
            this.s = s;
            this.host = host;
            setOrientation(VERTICAL);
            setPadding(0, Ui.dp(c, 4), 0, 0);
        }

        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); post(tick); }

        @Override protected void onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow(); }

        private void load() {
            removeAllViews();
            Context c = getContext();
            if (c.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                TextView t = text(c, s, 14, false, 0x99);
                t.setSingleLine(false);
                t.setText("Tap to show your calendar events");
                t.setOnClickListener(v -> host.requestCalendar());
                addView(t);
                return;
            }
            List<String[]> ev = next(c, 3);
            if (ev.isEmpty()) {
                TextView t = text(c, s, 14, false, 0x99);
                t.setText("Nothing coming up");
                addView(t);
                return;
            }
            for (String[] e : ev) {
                TextView title = text(c, s, 16, true, 0xFF);
                title.setText(e[0]);
                addView(title);
                TextView when = text(c, s, 13, false, 0x99);
                when.setText(e[1]);
                when.setPadding(0, Ui.dp(c, 1), 0, Ui.dp(c, 8));
                addView(when);
            }
        }

        static List<String[]> next(Context c, int max) {
            List<String[]> out = new ArrayList<>();
            long now = System.currentTimeMillis();
            android.net.Uri.Builder b = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon();
            android.content.ContentUris.appendId(b, now - 24L * 3600_000);
            android.content.ContentUris.appendId(b, now + 7L * 24 * 3600_000);
            String[] proj = {android.provider.CalendarContract.Instances.TITLE, android.provider.CalendarContract.Instances.BEGIN,
                    android.provider.CalendarContract.Instances.END, android.provider.CalendarContract.Instances.ALL_DAY};
            try (android.database.Cursor cur = c.getContentResolver().query(b.build(), proj,
                    android.provider.CalendarContract.Instances.VISIBLE + "=1 AND " + android.provider.CalendarContract.Instances.END + ">?",
                    new String[]{String.valueOf(now)}, android.provider.CalendarContract.Instances.BEGIN + " ASC")) {
                if (cur == null) return out;
                java.text.DateFormat tf = android.text.format.DateFormat.getTimeFormat(c);
                while (cur.moveToNext() && out.size() < max) {
                    String title = cur.getString(0);
                    long begin = cur.getLong(1), end = cur.getLong(2);
                    boolean allDay = cur.getInt(3) == 1;
                    String day = dayLabel(begin, allDay);
                    String when = allDay ? (day.isEmpty() ? "All day" : day + " · All day")
                            : (day.isEmpty() ? "" : day + " · ") + tf.format(new java.util.Date(begin)) + " – " + tf.format(new java.util.Date(end));
                    out.add(new String[]{title == null || title.isEmpty() ? "(No title)" : title, when});
                }
            } catch (Exception ignored) { }
            return out;
        }

        private static String dayLabel(long t, boolean allDay) {
            Calendar e = Calendar.getInstance(allDay ? java.util.TimeZone.getTimeZone("UTC") : java.util.TimeZone.getDefault());
            e.setTimeInMillis(t);
            Calendar today = Calendar.getInstance();
            int dy = e.get(Calendar.YEAR) * 400 + e.get(Calendar.DAY_OF_YEAR), ty = today.get(Calendar.YEAR) * 400 + today.get(Calendar.DAY_OF_YEAR);
            if (dy == ty) return "";
            if (dy == ty + 1) return "Tomorrow";
            return e.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.getDefault()) + " " + e.get(Calendar.DAY_OF_MONTH);
        }
    }

    private static void styleClock(TextClock t, Style s, float sp, boolean bold, int alpha, String fmt) {
        t.setFormat12Hour(fmt);
        t.setFormat24Hour(fmt);
        t.setTextColor(Ui.alpha(s.ink, alpha));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * s.scale);
        t.setTypeface(Typeface.create(s.face, bold ? Typeface.BOLD : Typeface.NORMAL));
        t.setIncludeFontPadding(false);
    }

    /** 7 x 4 dots filling with the day's progress (or battery level); refreshes every minute. */
    static final class Dots extends View {
        private final Paint on = new Paint(Paint.ANTI_ALIAS_FLAG), off = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean battery;
        boolean hollow;
        private final TextView pct;
        private float fraction;
        private final Runnable tick = new Runnable() {
            @Override public void run() { update(); postDelayed(this, 60_000); }
        };

        Dots(Context c, int ink, boolean battery, TextView pct) {
            super(c);
            this.battery = battery;
            this.pct = pct;
            on.setColor(ink);
            off.setColor(Ui.alpha(ink, 0x33));
            update();
        }

        void update() {
            if (battery) {
                Intent b = getContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                int level = b == null ? -1 : b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = b == null ? 100 : b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                fraction = level < 0 ? 0 : level / (float) scale;
            } else {
                Calendar now = Calendar.getInstance();
                int minutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
                fraction = minutes / 1440f;
            }
            pct.setText(Math.round(fraction * 100) + "%");
            invalidate();
        }

        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); post(tick); }

        @Override protected void onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow(); }

        @Override
        protected void onDraw(Canvas cv) {
            int cols = 7, rows = 4, total = cols * rows, filled = Math.round(fraction * total);
            float cw = getWidth() / (float) cols, rh = getHeight() / (float) rows;
            float r = Math.min(cw, rh) * 0.24f;
            for (int i = 0; i < total; i++) {
                float x = cw * (i % cols) + cw / 2f, y = rh * (i / cols) + rh / 2f;
                if (i < filled) cv.drawCircle(x, y, r, on);
                else if (hollow) {
                    off.setStyle(Paint.Style.STROKE);
                    off.setStrokeWidth(Math.max(2f, r * 0.32f));
                    off.setColor(on.getColor());
                    cv.drawCircle(x, y, r * 0.85f, off);
                } else cv.drawCircle(x, y, r, off);
            }
        }
    }

    /** Month name, weekday initials and two weeks of dates with today ringed. Starts on Monday. */
    static final class Week extends View {
        boolean noTitle;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Style s;
        private final Runnable tick = new Runnable() {
            @Override public void run() { invalidate(); postDelayed(this, 60_000); }
        };

        Week(Context c, Style s) {
            super(c);
            this.s = s;
            p.setTextAlign(Paint.Align.CENTER);
        }

        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); post(tick); }

        @Override protected void onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow(); }

        private float sp(float v) {
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
        }

        @Override
        protected void onDraw(Canvas cv) {
            Calendar today = Calendar.getInstance();
            Calendar start = (Calendar) today.clone();
            int dow = (start.get(Calendar.DAY_OF_WEEK) + 5) % 7; // Monday = 0
            start.add(Calendar.DAY_OF_MONTH, -dow);
            float w = getWidth(), h = getHeight(), colW = w / 7f;

            p.setTextAlign(Paint.Align.LEFT);
            p.setTypeface(Typeface.create(s.face, Typeface.BOLD));
            p.setTextSize(sp(16));
            p.setColor(s.ink);
            String month = today.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.getDefault());
            if (!noTitle) cv.drawText(month == null ? "" : month, 0, sp(16), p);

            p.setTextAlign(Paint.Align.CENTER);
            String[] names = new DateFormatSymbols().getShortWeekdays(); // index 1 = Sunday
            float top = noTitle ? 0 : sp(26);
            float rowH = (h - top) / 3f, y0 = top;
            p.setTextSize(sp(12));
            p.setTypeface(Typeface.create(s.face, Typeface.BOLD));
            for (int i = 0; i < 7; i++) {
                String n = names[(i + 1) % 7 + 1];
                n = n.length() > 2 ? n.substring(0, 2) : n;
                p.setColor(Ui.alpha(s.ink, i >= 5 ? 0x88 : 0xDD));
                cv.drawText(n, colW * i + colW / 2f, y0 + rowH * 0.55f, p);
            }
            p.setTextSize(sp(14));
            p.setTypeface(Typeface.create(s.face, Typeface.NORMAL));
            Calendar d = (Calendar) start.clone();
            for (int row = 1; row <= 2; row++) {
                for (int i = 0; i < 7; i++) {
                    float cx = colW * i + colW / 2f, cy = y0 + rowH * row + rowH * 0.5f;
                    boolean isToday = d.get(Calendar.YEAR) == today.get(Calendar.YEAR)
                            && d.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR);
                    String num = String.valueOf(d.get(Calendar.DAY_OF_MONTH));
                    if (isToday && s.outlined) { // ringed, number stays in ink
                        p.setColor(s.ink);
                        p.setStyle(Paint.Style.STROKE);
                        p.setStrokeWidth(Ui.dp(getContext(), 2));
                        cv.drawCircle(cx, cy, Math.min(colW, rowH) * 0.42f, p);
                        p.setStyle(Paint.Style.FILL);
                        p.setTypeface(Typeface.create(s.face, Typeface.BOLD));
                    } else if (isToday) {
                        p.setColor(s.ink);
                        cv.drawCircle(cx, cy, Math.min(colW, rowH) * 0.42f, p);
                        p.setColor(Ui.isLight(s.ink) ? 0xFF111111 : 0xFFFFFFFF);
                        p.setTypeface(Typeface.create(s.face, Typeface.BOLD));
                    } else {
                        p.setColor(Ui.alpha(s.ink, i >= 5 ? 0x88 : 0xEE));
                        p.setTypeface(Typeface.create(s.face, Typeface.NORMAL));
                    }
                    cv.drawText(num, cx, cy - (p.descent() + p.ascent()) / 2f, p);
                    d.add(Calendar.DAY_OF_MONTH, 1);
                }
            }
        }
    }
}
