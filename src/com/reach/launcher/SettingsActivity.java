package com.reach.launcher;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextClock;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Minimal glass settings. The window is translucent and (Android 12+) blurs whatever is behind
 * it. Pages: main → Themes → Theme editor, plus Home screen, Folders, Gestures, Notifications, Apps.
 * Touch targets stay large: rows are at least 60dp, cards are spaced 14dp apart.
 */
public class SettingsActivity extends Activity {
    private static final int WHITE = 0xFFFFFFFF, SUB = 0x99FFFFFF;
    private static final int SCRIM_BLUR = 0x8C0E1013, SCRIM_SOLID = 0xF20E1013;

    private Prefs p;
    private Ios ui;
    private ScrollView sv;
    private LinearLayout list;
    /** Area above the scrolling list; pages put their title and live preview here so it never scrolls away. */
    private LinearLayout pinned;
    /** Where header() adds: the pinned area or the scrolling list. */
    private LinearLayout target;
    /** Styled preview icons per look, so re-renders don't flash blank. */
    private static final Map<String, List<Object[]>> PREVIEW_CACHE = new HashMap<>();
    private static List<Object[]> lastPicks;
    private final ColorDrawable windowBg = new ColorDrawable(SCRIM_BLUR);
    private Consumer<Boolean> blurListener;
    private final ArrayDeque<Runnable> pages = new ArrayDeque<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    // ------------------------------------------------------------------ helpers shared with home

    static boolean isDefaultHome(Context c) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo r = c.getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
        return r != null && r.activityInfo != null && c.getPackageName().equals(r.activityInfo.packageName);
    }

    static void requestDefaultHome(Activity a) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                RoleManager rm = a.getSystemService(RoleManager.class);
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                    a.startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 7);
                    return;
                }
            }
            a.startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
        } catch (Exception e) {
            Ui.toast(a, "Open system Settings → Apps → Default apps → Home app");
        }
    }

    // ------------------------------------------------------------------ window

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = new Prefs(this);
        ui = Ios.of(this);
        Window w = getWindow();
        applyBackdrop(Ui.blurOn(this));
        w.setStatusBarColor(0);
        w.setNavigationBarColor(0);
        w.getDecorView(); // installs the decor so the insets controller exists before setContentView
        if (!ui.dark) {
            if (Build.VERSION.SDK_INT >= 30) w.getInsetsController().setSystemBarsAppearance(
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            else w.getDecorView().setSystemUiVisibility(w.getDecorView().getSystemUiVisibility()
                    | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        if (Build.VERSION.SDK_INT >= 30) w.setDecorFitsSystemWindows(false);
        else w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        sv = new ScrollView(this);
        sv.setClipToPadding(false);
        sv.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        pinned = new LinearLayout(this);
        pinned.setOrientation(LinearLayout.VERTICAL);
        pinned.setPadding(dp(20), 0, dp(20), 0);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(pinned, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        target = list;
        root.setOnApplyWindowInsetsListener((v, in) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets s = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = s.top; bottom = s.bottom;
            } else {
                top = in.getSystemWindowInsetTop(); bottom = in.getSystemWindowInsetBottom();
            }
            v.setPadding(0, top + dp(12), 0, 0);
            list.setPadding(dp(20), dp(4), dp(20), bottom + dp(32));
            return in;
        });
        setContentView(root);
        go(this::mainPage);
        if ("themes".equals(getIntent().getStringExtra("page"))) go(this::themesPage);
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (Build.VERSION.SDK_INT >= 31) {
            // battery saver / "reduce transparency" can turn cross-window blur off: fall back to a solid scrim
            blurListener = this::applyBackdrop;
            getWindowManager().addCrossWindowBlurEnabledListener(getMainExecutor(), blurListener);
        }
    }

    /** Real blur when the device allows it, otherwise a frosted gradient from the wallpaper's colors. */
    private void applyBackdrop(boolean blur) {
        getWindow().setBackgroundDrawable(new ColorDrawable(ui.bg));
    }

    @Override
    public void onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= 31 && blurListener != null)
            getWindowManager().removeCrossWindowBlurEnabledListener(blurListener);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (!WallpaperPicker.onResult(this, req, res, data)) super.onActivityResult(req, res, data);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render(); // refresh permission states after visiting system screens
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (pages.size() > 1) {
            pages.pop();
            draw(pages.peek());
            sv.scrollTo(0, 0);
        } else {
            super.onBackPressed();
        }
    }

    private void go(Runnable page) {
        pages.push(page);
        draw(page);
        sv.scrollTo(0, 0);
    }

    private void draw(Runnable page) {
        pinned.removeAllViews();
        list.removeAllViews();
        target = list;
        page.run();
        target = list;
    }

    /** Re-draws the current page, keeping the scroll position. */
    private void render() {
        if (pages.isEmpty()) return;
        int y = sv.getScrollY();
        draw(pages.peek());
        sv.post(() -> sv.scrollTo(0, y));
    }

    private int dp(float v) { return Ui.dp(this, v); }

    private void start(Intent i) {
        try { startActivity(i); } catch (Exception e) { Ui.toast(this, "Not available on this device"); }
    }

    // ------------------------------------------------------------------ glass building blocks

    private void header(String title, boolean back) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        if (back) {
            TextView b = new TextView(this);
            b.setText("‹ Back");
            b.setTextColor(ui.tint);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            b.setGravity(Gravity.CENTER_VERTICAL);
            b.setPadding(dp(4), 0, dp(12), 0);
            b.setContentDescription("Back");
            b.setOnClickListener(v -> onBackPressed());
            h.addView(b, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        }
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(ui.text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        t.setPadding(dp(4), back ? dp(2) : dp(10), 0, 0);
        h.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(16);
        target.addView(h, lp);
    }

    static GradientDrawable glass(Context c, int fill) {
        GradientDrawable g = Ui.round(fill, Ui.dp(c, 22));
        g.setStroke(Ui.dp(c, 1), 0x22FFFFFF);
        return g;
    }

    /** An inset grouped section; rows added to it get inset hairlines. */
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.round(ui.group, dp(12)));
        c.setClipToOutline(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(22);
        list.addView(c, lp);
        return c;
    }

    private void sep(LinearLayout card) {
        if (card.getChildCount() == 0) return;
        View v = new View(this);
        v.setBackgroundColor(ui.sep);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f)));
        lp.setMargins(dp(16), 0, 0, 0);
        card.addView(v, lp);
    }

    private LinearLayout row(LinearLayout card) {
        sep(card);
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(52));
        r.setPadding(dp(16), dp(8), dp(14), dp(8));
        r.setBackground(Ui.ripple(this, null));
        card.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return r;
    }

    private TextView[] texts(LinearLayout parent, CharSequence title, CharSequence sub) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(ui.text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        col.addView(t);
        TextView s = null;
        if (sub != null) {
            s = new TextView(this);
            s.setText(sub);
            s.setTextColor(ui.sub);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            s.setPadding(0, dp(1), 0, 0);
            col.addView(s);
        }
        parent.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return new TextView[]{t, s};
    }

    private void chevron(LinearLayout r) {
        TextView c = new TextView(this);
        c.setText("›");
        c.setTextColor(ui.chevron);
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        c.setPadding(dp(10), 0, 0, dp(2));
        r.addView(c);
    }

    private void nav(LinearLayout card, String title, CharSequence sub, Runnable page) {
        LinearLayout r = row(card);
        texts(r, title, sub);
        chevron(r);
        r.setOnClickListener(v -> go(page));
    }

    private void action(LinearLayout card, String title, CharSequence sub, Runnable run) {
        LinearLayout r = row(card);
        TextView[] tv = texts(r, title, sub);
        tv[0].setTextColor(ui.tint); // tappable actions read as links
        r.setOnClickListener(v -> run.run());
    }

    private void toggle(LinearLayout card, String title, String sub, boolean value, Consumer<Boolean> set) {
        LinearLayout r = row(card);
        texts(r, title, sub);
        Switch sw = new Switch(this);
        sw.setChecked(value);
        sw.setClickable(false);
        sw.setThumbTintList(ui.switchThumb());
        sw.setTrackTintList(ui.switchTrack());
        r.addView(sw);
        r.setOnClickListener(v -> { sw.setChecked(!sw.isChecked()); set.accept(sw.isChecked()); });
    }

    private void slider(LinearLayout card, String title, int min, int max, int value, String unit, IntConsumer set) {
        sep(card);
        card.addView(ui.slider(this, title, min, max, value, unit, null, set::accept));
    }

    /** Segmented control (up to 4 options) or capsules (more). */
    private void chips(LinearLayout card, String title, String[] labels, String[] values, String cur, Consumer<String> pick) {
        sep(card);
        card.addView(ui.choice(this, title, labels, values, cur, pick));
    }

    private void caption(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(ui.sub);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setLineSpacing(0, 1.2f);
        t.setPadding(dp(16), 0, dp(16), dp(22));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = -dp(14);
        list.addView(t, lp);
    }

    private void label(String text) {
        TextView t = new TextView(this);
        t.setText(text.toUpperCase());
        t.setTextColor(ui.sub);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setPadding(dp(16), dp(2), dp(16), dp(6));
        list.addView(t);
    }

    // ------------------------------------------------------------------ pages

    private void mainPage() {
        header("Settings", false);
        boolean isHome = isDefaultHome(this), notif = NotifService.granted(this);
        if (!isHome || !notif) {
            LinearLayout setup = card();
            setup.setBackground(Ui.round(ui.group, dp(12)));
            if (!isHome) action(setup, "Make Reach your home app", "Needed for app shortcuts and pinning",
                    () -> requestDefaultHome(this));
            if (!notif) action(setup, "Allow notification access", "For badges and message previews",
                    () -> start(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        }

        List<JSONObject> themes = Themes.load(p);
        JSONObject active = Themes.find(themes, p.activeTheme());
        LinearLayout apc = card();
        chips(apc, "Appearance", new String[]{"Theme", "Light", "Dark", "Auto"}, new String[]{"theme", "light", "dark", "auto"}, p.mode(), v -> {
            p.set(Prefs.MODE, v);
            JSONObject act = Themes.find(Themes.load(p), p.activeTheme());
            if (act != null) p.applyLook(act, this);
            recreate(); // settings itself switches light/dark too
        });
        LinearLayout tc = card();
        LinearLayout r = row(tc);
        r.setMinimumHeight(dp(76));
        r.addView(themeSwatch(active != null ? active : Themes.capture(p, "")), new LinearLayout.LayoutParams(dp(44), dp(44)));
        r.addView(new View(this), new LinearLayout.LayoutParams(dp(14), 1));
        TextView[] tt = texts(r, "Theme", active != null ? active.optString("name") : "Custom look");
        if (tt[1] != null) tt[1].setTypeface(Ui.typeface(this, p.font(), Typeface.NORMAL));
        chevron(r);
        r.setOnClickListener(v -> go(this::themesPage));

        LinearLayout c = card();
        nav(c, "Home screen", p.pagedLayout() ? "App pages · " + p.columns() + " columns" : "One-handed list", this::homePage);
        action(c, "Widget Studio", "Built-in widgets: calendar, weather, clocks, iPod, counters…",
                () -> start(new Intent().setClassName("com.widgetstudio", "com.widgetstudio.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        nav(c, "Folders", p.rareFolder() ? "Rarely used folder on" : "Rarely used folder off", this::foldersPage);
        nav(c, "Gestures", swipeLabel(p.swipeDown()), this::gesturesPage);
        nav(c, "Notifications", notif ? (p.showPreviews() ? "Badges and previews" : "Badges only") : "Access off", this::notifPage);
        nav(c, "Apps", p.hidden().size() + " hidden", this::appsPage);
        caption("Reach 1.0");
    }

    private static String swipeLabel(String v) {
        return "search".equals(v) ? "Swipe down opens search" : "none".equals(v) ? "Swipe down does nothing" : "Swipe down opens notifications";
    }

    /** Circle of the theme's accent ringed with its text color. */
    private View themeSwatch(JSONObject t) {
        View v = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(t.optInt(Prefs.ACCENT, p.accent()));
        g.setStroke(dp(5), "black".equals(t.optString(Prefs.TEXT_COLOR)) ? 0xFF111111 : 0xFFF5F5F5);
        v.setBackground(g);
        v.setElevation(dp(3));
        return v;
    }

    private static String styleLabel(String s) {
        switch (s) {
            case IconStyler.SQUIRCLE: return "Squircle";
            case IconStyler.MONO: return "Monochrome";
            case IconStyler.TILE: return "Glyph tiles";
            case IconStyler.LETTER: return "Letter tiles";
            case IconStyler.DUO: return "Duo tiles";
            case IconStyler.VIVID: return "Vivid";
            default: return "Original icons";
        }
    }

    private static String fontLabel(String v) {
        for (String[] f : FontList.ALL) if (f[0].equals(v)) return f[1];
        return "System";
    }

    private void themesPage() {
        header("Themes", true);
        List<JSONObject> themes = Themes.load(p);
        String activeId = p.activeTheme();
        LinearLayout c = card();
        for (JSONObject t : themes) {
            LinearLayout r = row(c);
            r.setMinimumHeight(dp(72));
            r.addView(themeSwatch(t), new LinearLayout.LayoutParams(dp(40), dp(40)));
            r.addView(new View(this), new LinearLayout.LayoutParams(dp(14), 1));
            TextView[] tt = texts(r, t.optString("name"),
                    fontLabel(t.optString(Prefs.FONT)) + " · " + styleLabel(t.optString(Prefs.ICON_STYLE)));
            tt[0].setTypeface(Ui.typeface(this, t.optString(Prefs.FONT, "sans-serif"), Typeface.NORMAL));
            tt[0].setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            if (t.optString("id").equals(activeId)) {
                TextView ck = new TextView(this);
                ck.setText("✓");
                ck.setTextColor(ui.text);
                ck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                ck.setPadding(dp(8), 0, dp(4), 0);
                r.addView(ck);
            }
            TextView more = new TextView(this);
            more.setText("⋯");
            more.setTextColor(ui.text);
            more.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            more.setGravity(Gravity.CENTER);
            more.setBackground(Ui.ripple(this, null));
            more.setContentDescription("Theme options");
            more.setOnClickListener(v -> themeMenu(themes, t));
            r.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
            r.setOnClickListener(v -> fullPreview(t.optString("id")));
        }
        LinearLayout add = card();
        action(add, "＋  Create theme", "Starts from your current look", () -> {
            JSONObject n = Themes.capture(p, "My theme " + (themes.size() + 1));
            themes.add(n);
            Themes.save(p, themes);
            p.applyLook(n, this);
            go(() -> editorPage(n.optString("id")));
        });
        caption("Tap a theme to preview it full screen on your home screen, then Apply or Cancel. Use ⋯ to edit, duplicate, rename or delete.");
    }

    /** Full preview of a theme (built-in or yours) before anything is applied. */
    private void previewPage(String id) {
        List<JSONObject> themes = Themes.load(p);
        JSONObject t = Themes.find(themes, id);
        if (t == null) { onBackPressed(); return; }
        boolean active = id.equals(p.activeTheme());
        target = pinned;
        header(t.optString("name"), true);
        pinned.addView(previewCard(t));
        target = list;

        TextView apply = new TextView(this);
        apply.setText(active ? "Applied  ✓" : "Apply theme");
        apply.setGravity(Gravity.CENTER);
        apply.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        apply.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        int accent = t.optInt(Prefs.ACCENT, p.accent());
        apply.setTextColor(0xFFFFFFFF);
        apply.setBackground(Ui.ripple(this, Ui.round(active ? ui.fill : ui.tint, dp(14))));
        apply.setElevation(dp(4));
        apply.setOnClickListener(v -> {
            p.applyLook(t, this);
            Ui.toast(this, t.optString("name") + " applied");
            render();
        });
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        al.bottomMargin = dp(14);
        list.addView(apply, al);
        LinearLayout fs = card();
        action(fs, "Preview full screen", "See it on your real home screen, then Apply or Cancel", () -> fullPreview(id));

        LinearLayout c = card();
        action(c, "Edit", "Applies it, then change colors, font and icons", () -> {
            p.applyLook(t, this);
            go(() -> editorPage(id));
        });
        action(c, "Duplicate & edit", "Keeps this theme as it is", () -> {
            JSONObject copy = Themes.copy(t, t.optString("name") + " copy");
            themes.add(copy);
            Themes.save(p, themes);
            p.applyLook(copy, this);
            go(() -> editorPage(copy.optString("id")));
        });

        LinearLayout info = card();
        String pack = t.optString(Prefs.ICON_PACK, "");
        String[][] rows = {
                {"Font", fontLabel(t.optString(Prefs.FONT))},
                {"Icons", styleLabel(t.optString(Prefs.ICON_STYLE)) + (pack.isEmpty() ? "" : " · icon pack")},
                {"Shadow & depth", new String[]{"Flat", "Soft", "Medium", "Deep"}[Math.max(0, Math.min(3, t.optInt(Prefs.DEPTH, 1)))]},
                {"Text", "black".equals(t.optString(Prefs.TEXT_COLOR)) ? "Black" : "White"},
                {"Wallpaper dim", t.optInt(Prefs.DIM, 20) + "%"},
        };
        for (String[] r : rows) {
            LinearLayout row = row(info);
            row.setBackground(null);
            row.setMinimumHeight(dp(52));
            texts(row, r[0], null);
            TextView v = new TextView(this);
            v.setText(r[1]);
            v.setTextColor(ui.sub);
            v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            row.addView(v);
        }
        caption("Nothing changes on your home screen until you tap Apply.");
    }

    private void themeMenu(List<JSONObject> themes, JSONObject t) {
        Sheet s = new Sheet(this, t.optString("name"), null);
        s.item("Preview full screen", () -> fullPreview(t.optString("id")));
        s.item("Details", () -> go(() -> previewPage(t.optString("id"))));
        s.item("Edit", () -> { p.applyLook(t, this); go(() -> editorPage(t.optString("id"))); });
        s.item("Home layout…", () -> layoutMenu(t));
        s.item("Duplicate", () -> {
            themes.add(Themes.copy(t, t.optString("name") + " copy"));
            Themes.save(p, themes);
            render();
        });
        s.item("Rename", () -> prompt("Rename theme", t.optString("name"), name -> {
            try { t.put("name", name); } catch (Exception ignored) { }
            Themes.save(p, themes);
            render();
        }));
        if (themes.size() > 1) s.item("Delete", () -> {
            themes.remove(t);
            Themes.save(p, themes);
            render();
        });
        s.show();
    }

    /** Each theme has its own home layout; this sheet resets, copies or edits it. */
    private void layoutMenu(JSONObject t) {
        String id = t.optString("id"), name = t.optString("name");
        boolean active = id.equals(p.activeTheme());
        Sheet s = new Sheet(this, name + " · home layout", null);
        s.note("Every theme keeps its own home layout: tiles, widgets and where they sit. Switch theme and its layout comes back.");
        if (active) s.item("Edit this layout on the home screen", () ->
                startActivity(new Intent(this, HomeActivity.class).putExtra("editHome", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        if (Layouts.designed(id) != null) s.item("Reset to " + name + "’s designed layout", () -> {
            Layouts.reset(p, id);
            Ui.toast(this, active ? "Layout reset" : "Reset: you’ll see it when you switch to " + name);
        });
        if (!active) s.item("Use my current home layout for " + name, () -> {
            Layouts.copyCurrentTo(p, id);
            Ui.toast(this, name + " will open with your current layout");
        });
        s.show();
    }

    /** Edits a theme live: each change is saved to the theme and applied to the home screen. */
    private void editorPage(String id) {
        List<JSONObject> themes = Themes.load(p);
        JSONObject t = Themes.find(themes, id);
        if (t == null) { onBackPressed(); return; }
        boolean dark = p.darkNow(this);
        JSONObject darkBlock = t.optJSONObject("dark");
        target = pinned;
        header(t.optString("name") + (darkBlock != null ? (dark ? " · dark" : " · light") : ""), true);
        pinned.addView(previewCard(t));
        target = list;

        // Edits go to the dark block when we're in dark mode and the dark block sets that key.
        Consumer<Object[]> set = kv -> {
            String k = (String) kv[0];
            JSONObject into = dark && darkBlock != null && darkBlock.has(k) ? darkBlock : t;
            try { into.put(k, kv[1]); } catch (Exception ignored) { }
            Themes.save(p, themes);
            p.applyLook(t, this);
            render();
        };
        java.util.function.Function<String, Integer> I = k -> Themes.i(t, k, dark);
        java.util.function.Function<String, String> S = k -> Themes.s(t, k, dark);

        LinearLayout go = card();
        action(go, "Edit on home screen", "Tap or pinch the clock, tiles, widgets, search bar and dock", () -> {
            p.applyLook(t, this);
            startActivity(new Intent(this, HomeActivity.class).putExtra("editHome", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        });
        action(go, "Preview full screen", "See it on your real home screen first", () -> fullPreview(id));

        label("Appearance");
        LinearLayout ap = card();
        chips(ap, "Background", new String[]{"Wallpaper", "Solid color", "Brushed metal"}, new String[]{"wallpaper", "solid", "silver"},
                S.apply(Prefs.BG), v -> set.accept(new Object[]{Prefs.BG, v}));
        if (!"wallpaper".equals(S.apply(Prefs.BG))) {
            colorRow(ap, "Background color", new int[]{0xFFE4E4E4, 0xFFF4F1EA, 0xFFDDE3EA, 0xFFEDE6F2, 0xFF0E0E0F, 0xFF1A1C20, 0xFF14201C, 0xFF201A16},
                    I.apply(Prefs.BG_COLOR), c -> set.accept(new Object[]{Prefs.BG_COLOR, c}));
        }
        colorRow(ap, "Surface (tiles, widget cards, icon tiles)", new int[]{0xFFF0F0F0, 0xFFFFFFFF, 0xFFE7E2D8, 0xFFE3E9EF,
                0xFF1E1E20, 0xFF262A30, 0xFF22302B, 0xFF2E2622}, I.apply(Prefs.SURFACE), c -> set.accept(new Object[]{Prefs.SURFACE, c}));
        chips(ap, "Light & dark versions", new String[]{"One look", "Light + dark"}, new String[]{"one", "two"},
                darkBlock != null ? "two" : "one", v -> {
                    try {
                        if ("two".equals(v) && t.optJSONObject("dark") == null) {
                            JSONObject d = new JSONObject();
                            boolean lightText = "white".equals(Themes.s(t, Prefs.TEXT_COLOR, false));
                            d.put(Prefs.TEXT_COLOR, lightText ? "black" : "white");
                            d.put(Prefs.BG_COLOR, lightText ? 0xFFE4E4E4 : 0xFF0E0E0F);
                            d.put(Prefs.SURFACE, lightText ? 0xFFF0F0F0 : 0xFF1E1E20);
                            t.put("dark", d);
                        } else if ("one".equals(v)) {
                            t.remove("dark");
                        }
                    } catch (Exception ignored) { }
                    Themes.save(p, themes);
                    p.applyLook(t, this);
                    render();
                });
        if (darkBlock != null)
            caption("This theme has light and dark versions. You're editing the " + (dark ? "dark" : "light")
                    + " one; switch with Appearance on the main settings page.");

        label("Color");
        LinearLayout color = card();
        accentRow(color, I.apply(Prefs.ACCENT), a -> set.accept(new Object[]{Prefs.ACCENT, a}));
        float[] hsv = new float[3];
        Color.colorToHSV(I.apply(Prefs.ACCENT), hsv);
        slider(color, "Custom accent hue", 0, 360, (int) hsv[0], "°",
                h -> set.accept(new Object[]{Prefs.ACCENT, Color.HSVToColor(new float[]{h, 0.42f, 1f})}));

        label("Text");
        LinearLayout text = card();
        LinearLayout fr = row(text);
        TextView[] ft = texts(fr, "Font", fontLabel(S.apply(Prefs.FONT)));
        if (ft[1] != null) ft[1].setTypeface(Ui.typeface(this, S.apply(Prefs.FONT), Typeface.NORMAL));
        chevron(fr);
        fr.setOnClickListener(v -> {
            Sheet s = new Sheet(this, "Font", null).cap(0.6f);
            for (String[] f : FontList.ALL)
                s.option(f[1], Ui.typeface(this, f[0], Typeface.NORMAL), f[0].equals(S.apply(Prefs.FONT)),
                        () -> set.accept(new Object[]{Prefs.FONT, f[0]}));
            s.show();
        });
        chips(text, "Text color", new String[]{"White", "Black"}, new String[]{"white", "black"},
                S.apply(Prefs.TEXT_COLOR), v -> set.accept(new Object[]{Prefs.TEXT_COLOR, v}));
        slider(text, "List label size", 12, 30, I.apply(Prefs.LABEL), " sp", v -> set.accept(new Object[]{Prefs.LABEL, v}));

        label("Icons");
        LinearLayout icons = card();
        Map<String, String> packs = IconPack.installed(this);
        LinearLayout pr = row(icons);
        String pack = S.apply(Prefs.ICON_PACK);
        texts(pr, "Icon pack", packs.containsKey(pack) ? packs.get(pack) : "System icons");
        chevron(pr);
        pr.setOnClickListener(v -> {
            Sheet s = new Sheet(this, "Icon pack", null);
            s.option("System icons", null, pack.isEmpty() || !packs.containsKey(pack), () -> set.accept(new Object[]{Prefs.ICON_PACK, ""}));
            for (Map.Entry<String, String> e : packs.entrySet())
                s.option(e.getValue(), null, e.getKey().equals(pack), () -> set.accept(new Object[]{Prefs.ICON_PACK, e.getKey()}));
            s.divider();
            s.item("Get icon packs (incl. iOS-style)…", () -> start(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://search?q=icon%20pack&c=apps"))));
            s.show();
        });
        String style = S.apply(Prefs.ICON_STYLE);
        chips(icons, "Shape & style", new String[]{"Original", "Line icons", "Squircle", "Outline", "Monochrome", "Glyph tile", "Duo tiles", "Vivid", "Letter"},
                new String[]{IconStyler.ORIGINAL, IconStyler.LINE, IconStyler.SQUIRCLE, IconStyler.OUTLINE, IconStyler.MONO, IconStyler.TILE, IconStyler.DUO, IconStyler.VIVID, IconStyler.LETTER},
                style, v -> set.accept(new Object[]{Prefs.ICON_STYLE, v}));
        if (!style.equals(IconStyler.ORIGINAL) && !style.equals(IconStyler.SQUIRCLE) && !style.equals(IconStyler.DUO) && !style.equals(IconStyler.VIVID))
            chips(icons, "Icon background", new String[]{"Tile", "Frame", "None"}, new String[]{"on", "frame", "off"},
                    S.apply(Prefs.ICON_TILE), v -> set.accept(new Object[]{Prefs.ICON_TILE, v}));
        if (style.equals(IconStyler.LINE) || style.equals(IconStyler.OUTLINE) || style.equals(IconStyler.LETTER))
            chips(icons, "Line weight", new String[]{"Thin", "Regular", "Bold"}, new String[]{"thin", "regular", "bold"},
                    S.apply(Prefs.ICON_WEIGHT), v -> set.accept(new Object[]{Prefs.ICON_WEIGHT, v}));
        if (style.equals(IconStyler.MONO) || style.equals(IconStyler.TILE) || style.equals(IconStyler.LETTER) || style.equals(IconStyler.OUTLINE)
                || style.equals(IconStyler.LINE))
            chips(icons, style.equals(IconStyler.MONO) ? "Icon color" : "Tile color", new String[]{"Surface", "White", "Black", "Accent"},
                    new String[]{"surface", "white", "black", "accent"},
                    S.apply(Prefs.ICON_COLOR), v -> set.accept(new Object[]{Prefs.ICON_COLOR, v}));
        chips(icons, "Shadow & depth", new String[]{"Flat", "Soft", "Medium", "Deep"}, new String[]{"0", "1", "2", "3"},
                String.valueOf(I.apply(Prefs.DEPTH)), v -> set.accept(new Object[]{Prefs.DEPTH, Integer.parseInt(v)}));
        slider(icons, "Icon size", 28, 76, I.apply(Prefs.ICON), " dp", v -> set.accept(new Object[]{Prefs.ICON, v}));
        chips(icons, "App labels", new String[]{"Show", "Hide"}, new String[]{"on", "off"},
                S.apply(Prefs.GRID_LABELS), v -> set.accept(new Object[]{Prefs.GRID_LABELS, v}));

        label("Clock");
        LinearLayout ck = card();
        chips(ck, "Clock & date", new String[]{"Show", "Hide"}, new String[]{"on", "off"},
                S.apply(Prefs.CLOCK_SHOW), v -> set.accept(new Object[]{Prefs.CLOCK_SHOW, v}));
        slider(ck, "Clock size", 24, 180, I.apply(Prefs.CLOCK_SIZE), " sp", v -> set.accept(new Object[]{Prefs.CLOCK_SIZE, v}));
        chips(ck, "Clock font", new String[]{"Theme", "Thin", "Light", "Regular", "Bold", "Condensed", "Serif", "Mono"},
                new String[]{"theme", "sans-serif-thin", "sans-serif-light", "sans-serif", "bold", "sans-serif-condensed", "serif", "monospace"},
                S.apply(Prefs.CLOCK_FONT), v -> set.accept(new Object[]{Prefs.CLOCK_FONT, v}));
        chips(ck, "Alignment", new String[]{"Left", "Center", "Right"}, new String[]{"start", "center", "end"},
                S.apply(Prefs.CLOCK_ALIGN), v -> set.accept(new Object[]{Prefs.CLOCK_ALIGN, v}));
        chips(ck, "Date", new String[]{"Long", "Short", "Numbers", "Hidden"}, new String[]{"long", "short", "numeric", "none"},
                S.apply(Prefs.DATE_STYLE), v -> set.accept(new Object[]{Prefs.DATE_STYLE, v}));

        label("Widgets & tiles");
        LinearLayout wd = card();
        chips(wd, "Tile style", new String[]{"Filled", "Outlined"}, new String[]{"filled", "outlined"},
                S.apply(Prefs.TILE_STYLE), v -> set.accept(new Object[]{Prefs.TILE_STYLE, v}));
        chips(wd, "Card behind widgets", new String[]{"None", "Glass", "Dark", "Surface"}, new String[]{"none", "glass", "dark", "surface"},
                S.apply(Prefs.WIDGET_BG), v -> set.accept(new Object[]{Prefs.WIDGET_BG, v}));
        slider(wd, "Corner roundness", 0, 40, I.apply(Prefs.WIDGET_RADIUS), " dp", v -> set.accept(new Object[]{Prefs.WIDGET_RADIUS, v}));
        action(wd, "Add or arrange widgets and tiles", "Opens edit mode on your home screen", () -> {
            p.applyLook(t, this);
            startActivity(new Intent(this, HomeActivity.class).putExtra("editHome", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        });

        label("Search bar & dock");
        LinearLayout sb = card();
        chips(sb, "Search bar", new String[]{"Show", "Remove"}, new String[]{"on", "off"},
                S.apply(Prefs.SEARCH_SHOW), v -> set.accept(new Object[]{Prefs.SEARCH_SHOW, v}));
        chips(sb, "Search shape", new String[]{"Pill", "Rounded", "Square", "Text"}, new String[]{"pill", "rounded", "square", "text"},
                S.apply(Prefs.SEARCH_SHAPE), v -> set.accept(new Object[]{Prefs.SEARCH_SHAPE, v}));
        chips(sb, "Search position", new String[]{"Above dock", "Top"}, new String[]{"bottom", "top"},
                S.apply(Prefs.SEARCH_POS), v -> set.accept(new Object[]{Prefs.SEARCH_POS, v}));
        chips(sb, "Dock background", new String[]{"Glass", "None"}, new String[]{"on", "off"},
                S.apply(Prefs.DOCK_BG), v -> set.accept(new Object[]{Prefs.DOCK_BG, v}));
        slider(sb, "Dock icon size", 32, 88, I.apply(Prefs.DOCK_ICON), " dp", v -> set.accept(new Object[]{Prefs.DOCK_ICON, v}));

        label("Wallpaper");
        LinearLayout wp = card();
        if ("wallpaper".equals(S.apply(Prefs.BG)))
            slider(wp, "Dim", 0, 80, I.apply(Prefs.DIM), "%", v -> set.accept(new Object[]{Prefs.DIM, v}));
        action(wp, "Change wallpaper", "Photo from gallery or system wallpapers", () -> WallpaperPicker.showMenu(this));
        caption("Changes save to this theme and show on your home screen right away.");
    }

    private void fullPreview(String id) {
        startActivity(new Intent(this, HomeActivity.class).putExtra("previewTheme", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void colorRow(LinearLayout card, String title, int[] colors, int current, IntConsumer pick) {
        sep(card);
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(14), dp(12), dp(14), dp(14));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(ui.text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        block.addView(t);
        LinearLayout line = null;
        int size = dp(38), gap = dp(14);
        for (int i = 0; i < colors.length; i++) {
            if (i % 4 == 0) {
                line = new LinearLayout(this);
                LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                ll.topMargin = gap;
                block.addView(line, ll);
            }
            int c = colors[i];
            View sw = new View(this);
            GradientDrawable g = Ui.round(c, dp(12));
            g.setStroke(dp(c == current ? 3 : 1), c == current ? ui.tint : ui.sep);
            sw.setBackground(g);
            sw.setContentDescription(title + " " + (i + 1) + (c == current ? ", selected" : ""));
            sw.setOnClickListener(v -> pick.accept(c));
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(size, size);
            if (i % 4 != 0) sl.setMarginStart(gap);
            line.addView(sw, sl);
        }
        card.addView(block);
    }

    private void accentRow(LinearLayout card, int current, IntConsumer pick) {
        sep(card);
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(14), dp(12), dp(14), dp(14));
        TextView t = new TextView(this);
        t.setText("Accent");
        t.setTextColor(ui.text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        block.addView(t);
        LinearLayout line = null;
        int size = dp(40), gap = dp(14);
        for (int i = 0; i < Prefs.ACCENTS.length; i++) {
            if (i % 4 == 0) {
                line = new LinearLayout(this);
                LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                ll.topMargin = gap;
                block.addView(line, ll);
            }
            int c = Prefs.ACCENTS[i];
            View sw = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(c);
            if (c == current) g.setStroke(dp(3), ui.tint);
            sw.setBackground(g);
            sw.setElevation(dp(2));
            sw.setContentDescription("Accent " + (i + 1) + (c == current ? ", selected" : ""));
            sw.setOnClickListener(v -> pick.accept(c));
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(size, size);
            if (i % 4 != 0) sl.setMarginStart(gap);
            line.addView(sw, sl);
        }
        card.addView(block);
    }

    /** Live mini home screen: background, clock, tiles, icons, search bar and dock, all from the theme. */
    private View previewCard(JSONObject t) {
        boolean dark = p.darkNow(this);
        java.util.function.Function<String, Integer> I = k -> Themes.i(t, k, dark);
        java.util.function.Function<String, String> S = k -> Themes.s(t, k, dark);
        int ink = "black".equals(S.apply(Prefs.TEXT_COLOR)) ? 0xFF111111 : WHITE;
        int surface = I.apply(Prefs.SURFACE);
        boolean solid = !"wallpaper".equals(S.apply(Prefs.BG));
        Typeface face = Ui.typeface(this, S.apply(Prefs.FONT), Typeface.NORMAL);
        int glassTint = Ui.isLight(ink) ? 0xFFFFFF : 0x000000;

        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        GradientDrawable bg;
        if (solid) {
            bg = Ui.round(I.apply(Prefs.BG_COLOR) | 0xFF000000, dp(24));
            if ("silver".equals(S.apply(Prefs.BG))) bg = Ui.metal(I.apply(Prefs.BG_COLOR), dp(24));
        } else {
            bg = (GradientDrawable) Ui.frosted(this);
            bg.setCornerRadius(dp(24));
        }
        // dim is part of the background: a full-size child view would stretch the card to the whole screen
        if (solid) frame.setBackground(bg);
        else frame.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                bg, Ui.round((I.apply(Prefs.DIM) * 255 / 100) << 24, dp(24))}));
        frame.setClipToOutline(true);
        frame.setElevation(dp(8));
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(14), dp(18), dp(14));
        frame.addView(c);

        String al = S.apply(Prefs.CLOCK_ALIGN);
        int grav = "center".equals(al) ? Gravity.CENTER_HORIZONTAL : "end".equals(al) ? Gravity.END : Gravity.START;
        if (!"off".equals(S.apply(Prefs.CLOCK_SHOW))) {
            LinearLayout cb = new LinearLayout(this);
            cb.setOrientation(LinearLayout.VERTICAL);
            cb.setGravity(grav);
            TextClock clock = new TextClock(this);
            String sec = "on".equals(S.apply(Prefs.CLOCK_SECONDS)) ? ":ss" : "";
            clock.setFormat12Hour("h:mm" + sec);
            clock.setFormat24Hour(("24".equals(S.apply(Prefs.CLOCK_FORMAT)) ? "HH:mm" : "H:mm") + sec);
            clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(18, I.apply(Prefs.CLOCK_SIZE) * 0.55f));
            String cf = S.apply(Prefs.CLOCK_FONT);
            clock.setTypeface("theme".equals(cf) ? (S.apply(Prefs.FONT).equals("sans-serif") ? Typeface.create("sans-serif-light", Typeface.NORMAL) : face)
                    : "bold".equals(cf) ? Typeface.create(face, Typeface.BOLD) : Ui.typeface(this, cf, Typeface.NORMAL));
            String cc = S.apply(Prefs.CLOCK_COLOR);
            clock.setTextColor("accent".equals(cc) ? I.apply(Prefs.ACCENT) : "white".equals(cc) ? WHITE : "black".equals(cc) ? 0xFF111111 : ink);
            clock.setIncludeFontPadding(false);
            cb.addView(clock);
            if (!"none".equals(S.apply(Prefs.DATE_STYLE))) {
                TextClock date = new TextClock(this);
                String ds = S.apply(Prefs.DATE_STYLE);
                String df = "short".equals(ds) ? "EEE d MMM" : "numeric".equals(ds) ? "dd/MM/yyyy" : "EEEE, d MMMM";
                date.setFormat12Hour(df);
                date.setFormat24Hour(df);
                date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                date.setTypeface(face);
                date.setTextColor(Ui.alpha(ink, 0xE6));
                cb.addView(date);
            }
            c.addView(cb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // two mini tiles in the theme's surface + card style
        int radius = dp(Math.max(4, I.apply(Prefs.WIDGET_RADIUS) / 2));
        int tileBg = solid ? surface : Ui.isLight(ink) ? 0x2BFFFFFF : 0x99FFFFFF;
        LinearLayout tiles = new LinearLayout(this);
        tiles.setPadding(0, dp(10), 0, dp(6));
        LinearLayout dateTile = new LinearLayout(this);
        dateTile.setOrientation(LinearLayout.VERTICAL);
        dateTile.setBackground(Ui.round(tileBg, radius));
        dateTile.setPadding(dp(10), dp(8), dp(10), dp(8));
        TextClock wd = new TextClock(this);
        wd.setFormat12Hour("EEE MMM");
        wd.setFormat24Hour("EEE MMM");
        wd.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        wd.setTypeface(Typeface.create(face, Typeface.BOLD));
        wd.setTextColor(ink);
        dateTile.addView(wd);
        TextClock dn = new TextClock(this);
        dn.setFormat12Hour("d");
        dn.setFormat24Hour("d");
        dn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        dn.setTypeface(Typeface.create(face, Typeface.BOLD));
        dn.setTextColor(ink);
        dn.setIncludeFontPadding(false);
        dateTile.addView(dn);
        tiles.addView(dateTile, new LinearLayout.LayoutParams(0, dp(66), 1f));
        LinearLayout dayTile = new LinearLayout(this);
        dayTile.setOrientation(LinearLayout.VERTICAL);
        dayTile.setBackground(Ui.round(tileBg, radius));
        dayTile.setPadding(dp(10), dp(8), dp(10), dp(8));
        TextView pct = new TextView(this);
        pct.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        pct.setTypeface(Typeface.create(face, Typeface.BOLD));
        pct.setTextColor(ink);
        dayTile.addView(pct);
        dayTile.addView(new Tiles.Dots(this, ink, false, pct), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(0, dp(66), 1f);
        dl.setMarginStart(dp(10));
        tiles.addView(dayTile, dl);
        c.addView(tiles);

        // app icons row (+ labels), then search bar and dock
        boolean labels = !"off".equals(S.apply(Prefs.GRID_LABELS));
        int iconPx = dp(Math.max(24, (I.apply(Prefs.ICON) + 12) * 0.8f));
        LinearLayout icons = new LinearLayout(this);
        icons.setPadding(0, dp(8), 0, dp(8));
        ImageView[] iv = new ImageView[8];
        TextView[] tv = new TextView[4];
        for (int i = 0; i < 4; i++) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            iv[i] = new ImageView(this);
            cell.addView(iv[i], new LinearLayout.LayoutParams(iconPx, iconPx));
            tv[i] = new TextView(this);
            tv[i].setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            tv[i].setTypeface(face);
            tv[i].setTextColor(ink);
            tv[i].setSingleLine(true);
            tv[i].setEllipsize(TextUtils.TruncateAt.END);
            tv[i].setGravity(Gravity.CENTER);
            tv[i].setPadding(0, dp(3), 0, 0);
            tv[i].setVisibility(labels ? View.VISIBLE : View.GONE);
            cell.addView(tv[i], new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            icons.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        c.addView(icons);

        if (!"off".equals(S.apply(Prefs.SEARCH_SHOW))) {
            TextView pill = new TextView(this);
            pill.setText("Search");
            pill.setTypeface(face);
            pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            pill.setTextColor(Ui.alpha(ink, 0xB3));
            pill.setGravity(Gravity.CENTER_VERTICAL);
            pill.setPadding(dp(14), 0, dp(14), 0);
            int h = dp(Math.max(26, I.apply(Prefs.SEARCH_H) * 0.75f));
            String shape = S.apply(Prefs.SEARCH_SHAPE);
            float r = "square".equals(shape) ? dp(4) : "rounded".equals(shape) ? dp(10) : h / 2f;
            GradientDrawable pg = Ui.round(solid ? surface : Ui.alpha(glassTint, 0x2E), r);
            if (!solid) pg.setStroke(dp(1), Ui.alpha(I.apply(Prefs.ACCENT), 0x99));
            pill.setBackground(pg);
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h);
            pl.bottomMargin = dp(8);
            c.addView(pill, pl);
        }
        LinearLayout dock = new LinearLayout(this);
        dock.setPadding(dp(6), dp(6), dp(6), dp(6));
        if (!"off".equals(S.apply(Prefs.DOCK_BG)))
            dock.setBackground(Ui.round(solid ? surface : Ui.alpha(glassTint, Ui.isLight(ink) ? 0x33 : 0x1F), dp(18)));
        int dockPx = dp(Math.max(24, I.apply(Prefs.DOCK_ICON) * 0.7f));
        for (int i = 4; i < 8; i++) {
            iv[i] = new ImageView(this);
            LinearLayout cell = new LinearLayout(this);
            cell.setGravity(Gravity.CENTER);
            cell.addView(iv[i], new LinearLayout.LayoutParams(dockPx, dockPx));
            dock.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        c.addView(dock);

        String key = S.apply(Prefs.ICON_STYLE) + "|" + S.apply(Prefs.ICON_COLOR) + "|" + I.apply(Prefs.ACCENT)
                + "|" + I.apply(Prefs.DEPTH) + "|" + S.apply(Prefs.ICON_PACK) + "|" + S.apply(Prefs.FONT) + "|" + surface
                + "|" + S.apply(Prefs.ICON_WEIGHT) + "|" + S.apply(Prefs.ICON_TILE) + "|" + ink;
        Consumer<List<Object[]>> show = picks -> {
            for (int i = 0; i < picks.size() && i < 8; i++) {
                iv[i].setImageDrawable(((Drawable) picks.get(i)[0]).getConstantState() != null
                        ? ((Drawable) picks.get(i)[0]).getConstantState().newDrawable(getResources()) : (Drawable) picks.get(i)[0]);
                if (i < 4) tv[i].setText((String) picks.get(i)[1]);
            }
        };
        List<Object[]> cached = PREVIEW_CACHE.get(key);
        if (cached != null) {
            show.accept(cached);
        } else {
            if (lastPicks != null) show.accept(lastPicks); // keep the old icons up while the new look renders
            new Thread(() -> {
                List<Object[]> picks = previewApps(t, face, dark);
                main.post(() -> {
                    PREVIEW_CACHE.put(key, picks);
                    lastPicks = picks;
                    show.accept(picks);
                });
            }).start();
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        frame.setLayoutParams(lp);
        return frame;
    }

    /** Eight of the user's favorites (or first apps), styled with the theme's settings. */
    private List<Object[]> previewApps(JSONObject t, Typeface face, boolean dark) {
        List<Object[]> out = new ArrayList<>();
        try {
            LauncherApps la = getSystemService(LauncherApps.class);
            UserManager um = getSystemService(UserManager.class);
            UserHandle me = Process.myUserHandle();
            long serial = um.getSerialNumberForUser(me);
            Map<String, LauncherActivityInfo> byKey = new HashMap<>();
            List<LauncherActivityInfo> all = la.getActivityList(null, me);
            for (LauncherActivityInfo i : all) byKey.put(AppRepo.appKey(i.getComponentName(), serial), i);
            List<LauncherActivityInfo> chosen = new ArrayList<>();
            List<String> favs = p.favorites();
            for (String k : favs) if (byKey.containsKey(k) && chosen.size() < 4) chosen.add(byKey.get(k));
            for (LauncherActivityInfo i : all) if (chosen.size() < 4 && !chosen.contains(i)) chosen.add(i);
            List<LauncherActivityInfo> dockApps = new ArrayList<>(chosen);
            chosen.clear();
            for (LauncherActivityInfo i : all) if (chosen.size() < 4 && !dockApps.contains(i)) chosen.add(i);
            chosen.addAll(dockApps); // first four: a grid row, last four: the dock
            String packPkg = Themes.s(t, Prefs.ICON_PACK, dark);
            IconPack pack = packPkg.isEmpty() ? null : IconPack.load(this, packPkg);
            int color = IconStyler.color(Themes.s(t, Prefs.ICON_COLOR, dark), Themes.i(t, Prefs.ACCENT, dark), Themes.i(t, Prefs.SURFACE, dark));
            String iconStyle = Themes.s(t, Prefs.ICON_STYLE, dark), weight = Themes.s(t, Prefs.ICON_WEIGHT, dark);
            Map<String, String> roles = IconStyler.usesKinds(iconStyle) ? LineIcons.roles(this) : new HashMap<>();
            for (LauncherActivityInfo i : chosen) {
                Drawable d = i.getIcon(0);
                if (pack != null) d = pack.icon(i.getComponentName(), d);
                String label = String.valueOf(i.getLabel());
                String kind = null;
                if (!roles.isEmpty()) {
                    kind = LineIcons.kindFor(i.getComponentName(), label, i.getApplicationInfo().category, roles);
                    if (kind != null && LineIcons.preferOwnLogo(kind, i.getComponentName(), roles)) kind = "~" + kind;
                }
                synchronized (IconStyler.class) {
                    boolean tile = !"off".equals(Themes.s(t, Prefs.ICON_TILE, dark));
                    int c2 = tile ? color : ("black".equals(Themes.s(t, Prefs.TEXT_COLOR, dark)) ? 0xFF111111 : 0xFFFFFFFF);
                    d = IconStyler.apply(getResources(), d, label, iconStyle, c2, face, Themes.i(t, Prefs.DEPTH, dark), kind, weight, tile);
                }
                out.add(new Object[]{d, label});
            }
        } catch (Exception ignored) { }
        return out;
    }

    private void homePage() {
        header("Home screen", true);
        LinearLayout c = card();
        chips(c, "Layout", new String[]{"App pages", "One-handed list"}, new String[]{"pages", "list"},
                p.pagedLayout() ? "pages" : "list", v -> { p.set(Prefs.LAYOUT, v); render(); });
        if (p.pagedLayout())
            chips(c, "Columns", new String[]{"4", "5"}, new String[]{"4", "5"}, String.valueOf(p.columns()),
                    v -> { p.set(Prefs.COLUMNS, Integer.parseInt(v)); render(); });
        LinearLayout c2 = card();
        toggle(c2, "Clock and date", null, p.showClock(), v -> p.set(Prefs.CLOCK, v));
        toggle(c2, "Search bar", null, p.showSearchBar(), v -> p.set(Prefs.SEARCHBAR, v));
        toggle(c2, "Left-handed", "Letter strip on the left edge", p.leftHanded(), v -> p.set(Prefs.LEFT, v));
        caption("App pages: swipe left and right through your apps. The page to the left holds the clock and widgets.");
    }

    private void foldersPage() {
        header("Folders", true);
        LinearLayout c = card();
        toggle(c, "Rarely used folder", "Groups apps you haven't opened in 3 weeks", p.rareFolder(),
                v -> { p.set("rarefolder", v); render(); });
        if (p.rareFolder()) {
            action(c, "Folder name", p.rareName(), () -> prompt("Folder name", p.rareName(), n -> { p.set("rarename", n); render(); }));
            boolean usage = Folders.usageAccess(this);
            action(c, "Usage access", usage ? "On: uses Android's app usage history"
                            : "Off: tap to allow, or Reach counts launches from your home screen",
                    () -> start(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        }
        LinearLayout auto = card();
        action(auto, "Organise apps into folders", "Social, Movies & TV, Music, Shopping, Finance, Games…", () -> {
            startActivity(new Intent(this, HomeActivity.class).putExtra("organise", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        });
        action(auto, "Undo automatic folders", "Your own folders stay", () -> {
            startActivity(new Intent(this, HomeActivity.class).putExtra("unorganise", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        });
        caption("To make a folder yourself, long-press an app → Add to folder. Long-press a folder to rename or remove it.");
    }

    private void gesturesPage() {
        header("Gestures", true);
        LinearLayout c = card();
        chips(c, "Swipe down", new String[]{"Notifications", "Search", "Nothing"}, new String[]{"shade", "search", "none"},
                p.swipeDown(), v -> { p.set(Prefs.SWIPE_DOWN, v); render(); });
        LinearLayout h = card();
        chips(h, "Hold to edit apps & widgets", new String[]{"Quick", "Normal", "Long", "Extra long"}, new String[]{"400", "600", "900", "1300"},
                String.valueOf(Ui.holdMs(p)), v -> { p.set("holdMs", v); render(); });
        caption("Swipe up: all apps A–Z. Drag the letter strip to jump. Long-press empty space: widgets, wallpaper, settings. "
                + "Hold time is how long you keep a finger on an app, widget or empty space before it starts editing.");
    }

    private void notifPage() {
        header("Notifications", true);
        LinearLayout c = card();
        boolean on = NotifService.granted(this);
        action(c, "Notification access", on ? "On" : "Off: tap to allow",
                () -> start(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        toggle(c, "Message previews", "Latest message under favorites (list layout)", p.showPreviews(), v -> p.set(Prefs.PREVIEW, v));
    }

    private void appsPage() {
        header("Apps", true);
        LinearLayout c = card();
        nav(c, "Hidden apps", p.hidden().size() + " hidden", this::hiddenPage);
        boolean home = isDefaultHome(this);
        action(c, home ? "Default home app" : "Make Reach your home app",
                home ? "Reach" : "Needed for shortcuts and pinning", () -> requestDefaultHome(this));
    }

    private void hiddenPage() {
        header("Hidden apps", true);
        LinearLayout c = card();
        TextView loading = new TextView(this);
        loading.setText("Loading…");
        loading.setTextColor(ui.sub);
        loading.setPadding(dp(14), dp(16), dp(14), dp(16));
        c.addView(loading);
        new Thread(() -> {
            LauncherApps la = getSystemService(LauncherApps.class);
            UserManager um = getSystemService(UserManager.class);
            List<String[]> apps = new ArrayList<>();
            for (UserHandle u : la.getProfiles()) {
                long serial = um.getSerialNumberForUser(u);
                for (LauncherActivityInfo i : la.getActivityList(null, u)) {
                    ComponentName cn = i.getComponentName();
                    if (cn.getPackageName().equals(getPackageName())) continue;
                    String key = AppRepo.appKey(cn, serial);
                    apps.add(new String[]{key, p.rename(key) != null ? p.rename(key) : String.valueOf(i.getLabel())});
                }
            }
            Collections.sort(apps, (a, b) -> Ui.norm(a[1]).compareTo(Ui.norm(b[1])));
            main.post(() -> {
                if (isFinishing()) return;
                c.removeAllViews();
                Set<String> hidden = p.hidden();
                for (String[] a : apps) toggle(c, a[1], null, hidden.contains(a[0]), v -> p.setHidden(a[0], v));
            });
        }).start();
    }

    // ------------------------------------------------------------------ text prompt

    private void prompt(String title, String value, Consumer<String> done) {
        Sheet s = new Sheet(this, title, null);
        EditText et = new EditText(this);
        et.setText(value);
        et.setSelectAllOnFocus(true);
        et.setSingleLine(true);
        et.setTextColor(ui.text);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        et.setBackground(Ui.round(ui.fill, dp(10)));
        et.setPadding(dp(16), dp(12), dp(16), dp(12));
        s.view(et);
        s.item("Save", () -> {
            String n = et.getText().toString().trim();
            if (!n.isEmpty()) done.accept(n);
        });
        s.show();
        s.showKeyboard(et);
    }
}
