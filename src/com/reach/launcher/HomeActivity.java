package com.reach.launcher;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen. Layout, top to bottom: clock/date, widget column, favorites, search pill.
 * A letter strip on the thumb-side edge jumps into the full app list; swipe up opens the list,
 * swipe down pulls the notification shade (or opens search), long-press empty space for the menu.
 */
public class HomeActivity extends Activity implements NotifService.Listener {
    private int listScrim = 0xE8101214; // A–Z list backdrop: light or dark with the look

    Prefs prefs;
    LauncherApps la;
    AppRepo repo;
    Widgets widgets;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private AppRepo.Snapshot snap;
    private long appliedRev;
    private int loadSeq;
    private AppRow.Style style, listStyle;

    private FrameLayout root;
    private ColorDrawable scrim;
    private android.graphics.drawable.Drawable metalSheen;
    private ValueAnimator scrimAnim;
    private LinearLayout home, favColumn, widgetColumn, todayPage, dots, dock;
    private Pager pager;
    private final List<AppCell> cells = new ArrayList<>();
    private Boolean arrangedPaged;
    private boolean pagesShownOnce;
    private LinearLayout clockBox, tilesBox;
    private String arrangedKey;
    /** Extra tile pages (pager pages 1..n before the app pages). */
    private final List<View> tilePages = new ArrayList<>();
    /** Tile views per tile page (page 0 = the clock page). */
    private final List<List<View>> tileViews = new ArrayList<>();
    private boolean lastDark;
    // edit mode
    private boolean editMode, scaling;
    private LinearLayout editBar, pageStrip;
    private android.widget.HorizontalScrollView stripScroll;
    private FrameLayout editOverlay;
    private TextView sizeBadge;
    private GestureDetector editGestures;
    private android.view.ScaleGestureDetector scaler;
    private String scaleKey;
    private float scaleStart, scaleF;
    private int rzStartH;
    private int scaleVal;
    private final List<View> outlined = new ArrayList<>();
    // full-screen theme preview
    private LinearLayout previewBar;
    private TextView previewName;
    private List<org.json.JSONObject> previewThemes;
    private int previewIdx;
    private String previewBackup, previewBackupActive;
    private ScrollView widgetScroll;
    private TextClock clock, date;
    private TextView searchPill, bubble;
    private FrameLayout drawer;
    private ListView drawerList;
    private EditText search;
    private DrawerAdapter adapter;
    private AlphabetBar bar;
    private boolean drawerOpen, downOnBar, downOnInteractive;
    private float downRawY;
    private GestureDetector gestures;

    private final Runnable reloadSoon = this::reload;

    private final LauncherApps.Callback laCallback = new LauncherApps.Callback() {
        @Override public void onPackageRemoved(String p, UserHandle u) { changed(p); }
        @Override public void onPackageAdded(String p, UserHandle u) { changed(p); }
        @Override public void onPackageChanged(String p, UserHandle u) { changed(p); }
        @Override public void onPackagesAvailable(String[] ps, UserHandle u, boolean r) { for (String p : ps) changed(p); }
        @Override public void onPackagesUnavailable(String[] ps, UserHandle u, boolean r) { for (String p : ps) changed(p); }
        @Override public void onShortcutsChanged(String p, List<ShortcutInfo> s, UserHandle u) { scheduleReload(); }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        la = getSystemService(LauncherApps.class);
        repo = new AppRepo(this, la, prefs);
        setupWindow();
        buildUi();
        widgets = new Widgets(this, prefs, la, widgetColumn);
        la.registerCallback(laCallback, main);
        if (Build.VERSION.SDK_INT >= 27) {
            try {
                android.app.WallpaperManager.getInstance(this).addOnColorsChangedListener((colors, which) -> {
                    if ((which & android.app.WallpaperManager.FLAG_SYSTEM) == 0 || !prefs.solidBg()) return;
                    prefs.set(Prefs.BG, "wallpaper");
                    prefs.syncActiveTheme(this);
                    markSynced();
                    applyStyle();
                    Ui.toast(this, "New wallpaper set. Theme background switched to Wallpaper so you can see it.");
                }, main);
            } catch (Exception ignored) { }
        }
        applyStyle();
        widgets.restore();
        appliedRev = prefs.rev();
        lastDark = Prefs.systemDark(this);
        reload();
        handleIntent(getIntent());
    }

    @Override
    protected void onStart() {
        super.onStart();
        try { widgets.host.startListening(); } catch (Exception ignored) { }
        NotifService.listener = this;
        onNotificationsChanged();
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncDarkMode();
        if (prefs.rev() != appliedRev) { // settings, pin requests, etc. changed something
            applyStyle();
            widgets.restore();
            appliedRev = prefs.rev();
            reload();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        try { widgets.host.stopListening(); } catch (Exception ignored) { }
        if (NotifService.listener == this) NotifService.listener = null;
        closeDrawer(false);
        if (previewThemes != null && !isChangingConfigurations()) cancelPreview();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        la.unregisterCallback(laCallback);
        bg.shutdownNow();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (handleIntent(intent)) return;
        if (editMode) { exitEdit(); return; }
        if (drawerOpen) closeDrawer(true); // home button while the list is open
        else if (paged()) { pager.setPage(homePage(), true); widgetScroll.smoothScrollTo(0, 0); }
        else widgetScroll.smoothScrollTo(0, 0);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (editMode) { exitEdit(); return; }
        if (previewThemes != null) { cancelPreview(); return; }
        if (drawerOpen) closeDrawer(true); // a launcher never finishes on back
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        if (WallpaperPicker.onResult(this, req, result, data)) return;
        if (!widgets.onActivityResult(req, result)) super.onActivityResult(req, result, data);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == 55) rebuildFavorites(); // calendar access changed: refresh "Up Next"
    }

    /** Local edits already reflected in the UI: don't treat them as an external change in onResume. */
    void markSynced() { appliedRev = prefs.rev(); }

    // ------------------------------------------------------------------ data

    private void changed(String pkg) {
        bg.execute(() -> repo.invalidate(pkg));
        scheduleReload();
    }

    private void scheduleReload() {
        main.removeCallbacks(reloadSoon);
        main.postDelayed(reloadSoon, 300);
    }

    void reload() {
        final int seq = ++loadSeq;
        bg.execute(() -> {
            AppRepo.Snapshot s = repo.load();
            main.post(() -> {
                if (seq != loadSeq || isDestroyed()) return;
                snap = s;
                appliedRev = prefs.rev();
                rebuildFavorites();
                adapter.rebuild();
            });
        });
    }

    /** Renames, hidden flags and favorite order changed: refresh without reloading. */
    private void refreshMeta() {
        if (snap == null) return;
        repo.applyMeta(snap);
        markSynced();
        rebuildFavorites();
        adapter.rebuild();
    }

    private List<StatusBarNotification> notifsFor(Entry e) {
        return e.kind == Entry.APP ? NotifService.forKey(e.notifKey()) : Collections.<StatusBarNotification>emptyList();
    }

    @Override
    public void onNotificationsChanged() {
        if (style != null) for (AppCell c : cells) {
            if (c.entry != null) c.bind(c.entry, c.iconPx, style.face, style.accent, style.ink, notifCount(c.entry),
                    c.label.getVisibility() == View.VISIBLE);
        }
        for (int i = 0; i < favColumn.getChildCount(); i++) {
            View v = favColumn.getChildAt(i);
            if (v instanceof AppRow && ((AppRow) v).entry != null) bindFavorite((AppRow) v, ((AppRow) v).entry);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    // ------------------------------------------------------------------ window & views

    @SuppressWarnings("deprecation")
    private void setupWindow() {
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(0);
        w.setNavigationBarColor(0);
        if (Build.VERSION.SDK_INT >= 29) w.setNavigationBarContrastEnforced(false);
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        } else {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    private int dp(float v) { return Ui.dp(this, v); }

    @SuppressWarnings("deprecation")
    private void buildUi() {
        root = new FrameLayout(this);
        scrim = new ColorDrawable(0);
        metalSheen = new ColorDrawable(0);
        root.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{scrim, metalSheen}));
        root.setOnApplyWindowInsetsListener((v, in) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets s = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets ime = in.getInsets(WindowInsets.Type.ime());
                l = s.left; t = s.top; r = s.right; b = Math.max(s.bottom, ime.bottom);
            } else {
                l = in.getSystemWindowInsetLeft(); t = in.getSystemWindowInsetTop();
                r = in.getSystemWindowInsetRight(); b = in.getSystemWindowInsetBottom();
            }
            v.setPadding(l, t, r, b);
            return in;
        });

        // --- home page
        home = new LinearLayout(this);
        home.setOrientation(LinearLayout.VERTICAL);

        clock = new TextClock(this);
        clock.setIncludeFontPadding(false);
        clock.setOnClickListener(v -> safeStart(new Intent(AlarmClock.ACTION_SHOW_ALARMS)));
        clock.setOnLongClickListener(v -> hold(() -> { enterEdit(); clockSheet(); }));
        date = new TextClock(this);
        date.setOnClickListener(v -> openCalendar());
        date.setOnLongClickListener(v -> hold(() -> { enterEdit(); clockSheet(); }));
        clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        clockBox.setPadding(0, 0, 0, dp(16));
        clockBox.addView(clock, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        clockBox.addView(date, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        todayPage = new LinearLayout(this);
        todayPage.setOrientation(LinearLayout.VERTICAL);
        todayPage.addView(clockBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        widgetScroll = new ScrollView(this);
        widgetScroll.setVerticalScrollBarEnabled(false);
        widgetScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout todayContent = new LinearLayout(this);
        todayContent.setOrientation(LinearLayout.VERTICAL);
        tilesBox = new LinearLayout(this);
        tilesBox.setOrientation(LinearLayout.VERTICAL);
        todayContent.addView(tilesBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        widgetColumn = new LinearLayout(this);
        widgetColumn.setOrientation(LinearLayout.VERTICAL);
        todayContent.addView(widgetColumn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        widgetScroll.addView(todayContent, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        todayPage.addView(widgetScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        favColumn = new LinearLayout(this);
        favColumn.setOrientation(LinearLayout.VERTICAL);

        // paged layout pieces: pager (page 0 = clock + widgets, then app grid pages), dots, dock
        pager = new Pager(this);
        pager.setListener(new Pager.Listener() {
            @Override public void onPageChanged(int page) {
                updateDots();
                if (iconDragging) resetPageCells();
                if (!rzTouch && rzSlot >= 0 && !(rzTile && page == rzPage)) hideResizer();
                if (editMode && !rzTouch) refreshEdit();
            }
            @Override public void onPagerSized(int w, int h) { if (paged()) rebuildPages(); }
        });
        dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER);
        dock = new LinearLayout(this);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setPadding(dp(6), dp(8), dp(6), dp(8));
        dock.setBackground(Ui.round(0x38FFFFFF, dp(30)));

        searchPill = new TextView(this);
        searchPill.setText("Search apps and web");
        searchPill.setTextColor(0xCCFFFFFF);
        searchPill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        searchPill.setGravity(Gravity.CENTER_VERTICAL);
        searchPill.setPadding(dp(20), 0, dp(20), 0);
        searchPill.setBackground(Ui.ripple(this, Ui.round(0x30FFFFFF, dp(24))));
        searchPill.setOnClickListener(v -> openDrawer(true));
        root.addView(home, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // --- full app list ("drawer")
        drawer = new FrameLayout(this);
        drawer.setVisibility(View.GONE);
        drawerList = new ListView(this);
        drawerList.setDivider(null);
        drawerList.setSelector(new ColorDrawable(0));
        drawerList.setVerticalScrollBarEnabled(false);
        drawerList.setClipToPadding(false);
        drawerList.setOverScrollMode(View.OVER_SCROLL_NEVER);
        adapter = new DrawerAdapter();
        drawerList.setAdapter(adapter);
        drawerList.setOnItemClickListener((p, v, pos, id) -> adapter.click(pos, v));
        drawerList.setOnItemLongClickListener((p, v, pos, id) -> adapter.longClick(pos));
        drawer.addView(drawerList, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        search = new EditText(this);
        search.setHint("Search apps and web");
        search.setHintTextColor(0x99FFFFFF);
        search.setTextColor(0xFFFFFFFF);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_GO);
        search.setPadding(dp(20), 0, dp(20), 0);
        search.setBackground(Ui.round(0xFF2A2D32, dp(24))); // opaque: list rows must not show through
        search.setElevation(dp(4));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { adapter.setQuery(s.toString()); }
        });
        search.setOnEditorActionListener((v, action, ev) -> {
            Object first = adapter.firstResult();
            if (first instanceof Entry) launch((Entry) first, v);
            else if (first instanceof Runnable) ((Runnable) first).run();
            return true;
        });
        drawer.addView(search, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), Gravity.BOTTOM));
        root.addView(drawer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // --- letter strip + bubble
        bar = new AlphabetBar(this);
        bar.setPadding(0, 0, 0, dp(24));
        bar.setListener(new AlphabetBar.Listener() {
            @Override public void onLetter(String letter, float y) {
                if (!drawerOpen) openDrawer(false);
                Integer pos = adapter.sections.get(letter);
                if (pos != null) drawerList.setSelectionFromTop(pos, 0);
                bubble.setText(letter);
                bubble.setTranslationY(y - dp(36));
                bubble.setVisibility(View.VISIBLE);
            }

            @Override public void onRelease() { bubble.setVisibility(View.GONE); }
        });
        root.addView(bar, new FrameLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));

        bubble = new TextView(this);
        bubble.setGravity(Gravity.CENTER);
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        bubble.setTypeface(Typeface.DEFAULT_BOLD);
        bubble.setTextColor(0xFF111315);
        bubble.setVisibility(View.GONE);
        bubble.setElevation(dp(6));
        root.addView(bubble, new FrameLayout.LayoutParams(dp(72), dp(72), Gravity.TOP | Gravity.END));

        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null) return false;
                float dy = e2.getRawY() - e1.getRawY(), dx = e2.getRawX() - e1.getRawX();
                if (Math.abs(dy) < dp(56) || Math.abs(dy) < Math.abs(dx) * 1.5f || Math.abs(vy) < 500) return false;
                if (!drawerOpen) {
                    // let a scrollable widget column scroll instead
                    if (hit(widgetScroll, downRawX(e1), downRawY) && widgetScroll.canScrollVertically(dy < 0 ? 1 : -1)) return false;
                    if (dy < 0) openDrawer(false); else onSwipeDown();
                    return true;
                }
                if (dy > 0 && !drawerList.canScrollVertically(-1) && search.getText().length() == 0) {
                    closeDrawer(true);
                    return true;
                }
                return false;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (drawerOpen || downOnInteractive) return;
                hold(() -> { if (!drawerOpen) enterEdit(); });
            }
        });

        buildOverlays();
        buildResizer();
        setContentView(root);
    }

    private static float downRawX(MotionEvent e) { return e.getRawX(); }

    // ---- hold-to-edit timer: Android fires long-press at ~0.4 s; Reach waits for the user's chosen hold time ----
    private long holdDownAt;
    private boolean holdFinger;
    private float holdX, holdY;
    private Runnable pendingHold;

    private void trackHold(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                holdDownAt = android.os.SystemClock.uptimeMillis();
                holdFinger = true;
                holdX = ev.getRawX();
                holdY = ev.getRawY();
                cancelHold();
                break;
            case MotionEvent.ACTION_MOVE:
                if (pendingHold != null && Math.hypot(ev.getRawX() - holdX, ev.getRawY() - holdY) > dp(10)) cancelHold();
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                holdFinger = false;
                cancelHold();
                break;
        }
    }

    private void cancelHold() {
        if (pendingHold != null) main.removeCallbacks(pendingHold);
        pendingHold = null;
    }

    /** Runs r once the finger has rested for the hold time (right away if it already has, or without a finger). */
    private boolean hold(Runnable r) {
        Ui.holdMs = Ui.holdMs(prefs);
        long left = Ui.holdMs - (android.os.SystemClock.uptimeMillis() - holdDownAt);
        if (!holdFinger || left <= 20) { r.run(); return true; }
        cancelHold();
        pendingHold = () -> { pendingHold = null; if (holdFinger) r.run(); };
        main.postDelayed(pendingHold, left);
        return true;
    }

    private static boolean hit(View v, float rawX, float rawY) {
        if (v == null || v.getVisibility() != View.VISIBLE || !v.isShown()) return false;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return rawX >= loc[0] && rawX < loc[0] + v.getWidth() && rawY >= loc[1] && rawY < loc[1] + v.getHeight();
    }

    private boolean paged() { return prefs.pagedLayout(); }

    private static void detach(View v) {
        if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
    }

    /** Puts the home pieces together for the chosen layout and search position. */
    private void arrangeHome() {
        boolean p = paged(), top = "top".equals(prefs.s(Prefs.SEARCH_POS));
        String key = p + "|" + top;
        if (key.equals(arrangedKey)) return;
        arrangedKey = key;
        home.removeAllViews();
        pager.removeAllViews();
        detach(todayPage);
        detach(searchPill);
        LinearLayout.LayoutParams pill = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(prefs.i(Prefs.SEARCH_H)));
        pill.topMargin = dp(top ? 0 : 8);
        pill.bottomMargin = dp(top ? 14 : 10);
        if (top) home.addView(searchPill, pill);
        if (p) {
            pager.addPage(todayPage);
            home.addView(pager, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            home.addView(dots, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)));
            if (!top) home.addView(searchPill, pill);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dl.bottomMargin = dp(8);
            dl.topMargin = dp(top ? 6 : 0);
            home.addView(dock, dl);
            pagesShownOnce = false;
        } else {
            home.addView(todayPage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            home.addView(favColumn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (!top) home.addView(searchPill, pill);
        }
    }

    private int gridIconPx() { return dp(prefs.iconDp() + 12); }

    private int dockIconPx() { return dp(prefs.i(Prefs.DOCK_ICON)); }

    /** Number of dock slots (2..7); follows the grid columns until the user picks a size. */
    private int dockCount() {
        try { return Math.max(2, Math.min(7, Integer.parseInt(prefs.str("dockCount", "")))); }
        catch (Exception e) { return prefs.columns(); }
    }

    /** The user's icon size, shrunk if needed so it fits a cell of this width (5 and 6 columns). */
    private int fitIcon(int wanted, int cellWidth) {
        if (cellWidth <= 0) return wanted;
        return Math.max(dp(24), Math.min(wanted, cellWidth - dp(16)));
    }

    private int availableWidth() {
        int w = pager.getWidth();
        if (w <= 0) w = getResources().getDisplayMetrics().widthPixels - home.getPaddingLeft() - home.getPaddingRight();
        return w;
    }

    private boolean gridLabels() { return prefs.on(Prefs.GRID_LABELS); }

    private int firstAppPage() { return Math.min(1 + tilePages.size(), Math.max(0, pager.getChildCount() - 1)); }

    /** The page Reach opens on and the home button returns to: "apps" (first app page) or a tile page index. */
    private int homePage() {
        String h = prefs.str("homePage", "apps");
        if (!"apps".equals(h)) {
            try {
                int i = Integer.parseInt(h);
                if (i >= 0 && i < 1 + tilePages.size() && i < pager.getChildCount()) return i;
            } catch (Exception ignored) { }
        }
        return firstAppPage();
    }

    /** Which pages are on the home screen: the Today page and the app pages can be removed (and put back). */
    private void applyPageRange() {
        int tilesN = 1 + tilePages.size();
        boolean noToday = prefs.bool("hideToday", false), noApps = prefs.bool("hideAppPages", false);
        int lo = noToday ? 1 : 0, hi = noApps ? tilesN - 1 : Integer.MAX_VALUE;
        if (hi < lo) { // never leave the home screen without a page
            if (noApps) { prefs.set("hideAppPages", false); hi = Integer.MAX_VALUE; }
            else { prefs.set("hideToday", false); lo = 0; }
        }
        pager.setRange(lo, hi);
        for (int i = 0; i < pager.getChildCount(); i++) pager.getChildAt(i).setVisibility(pager.shown(i) ? View.VISIBLE : View.INVISIBLE);
    }

    /** Removes a page from the home screen: tile pages are deleted, Today and the app pages are hidden. */
    private void deletePage(int idx) {
        int tilesN = 1 + tilePages.size();
        int shownCount = pager.hi() - pager.lo() + 1;
        boolean app = idx >= tilesN;
        int removing = app ? pager.getChildCount() - tilesN : 1;
        if (shownCount - removing < 1) {
            Ui.toast(this, "Add another page first: the home screen needs at least one page");
            return;
        }
        if (idx >= 1 && idx < tilesN) { removeTilePage(idx); return; }
        Sheet s = editSheet(app ? "Remove the app pages?" : "Remove the Today page?");
        s.note(app ? "Your apps stay in the app list: swipe up to open it. Put the pages back any time from Pages."
                : "The clock and widgets on it are kept. Put it back any time from Pages.");
        s.item(app ? "Remove app pages" : "Remove page", () -> {
            prefs.set(app ? "hideAppPages" : "hideToday", true);
            String h = prefs.str("homePage", "apps");
            if (app && "apps".equals(h) || !app && "0".equals(h)) prefs.set("homePage", app ? (tilesN > 1 ? "1" : "0") : "apps");
            applyPageRange();
            pager.setPage(homePage(), true);
            updateDots();
            rebuildStrip();
        });
        s.item("Keep it", () -> { });
        s.show();
    }

    private void restorePage(int idx) {
        prefs.set(idx >= 1 + tilePages.size() ? "hideAppPages" : "hideToday", false);
        applyPageRange();
        pager.setPage(idx, true);
        updateDots();
        rebuildStrip();
    }

    private void setHomePage(int i) {
        prefs.set("homePage", i >= firstAppPage() ? "apps" : String.valueOf(i));
        rebuildStrip();
        Ui.toast(this, "Home screen set");
    }

    // ------------------------------------------------------------------ clock, search, tiles

    private void applyClock() {
        if (style == null) return;
        int ink = prefs.ink();
        String col = prefs.s(Prefs.CLOCK_COLOR);
        int cc = "accent".equals(col) ? prefs.accent() : "white".equals(col) ? 0xFFFFFFFF
                : "black".equals(col) ? 0xFF111111 : ink;
        Ui.ink(clock, cc);
        Ui.ink(date, Ui.alpha(cc, 0xE6));
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.i(Prefs.CLOCK_SIZE));
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.i(Prefs.DATE_SIZE));
        String f = prefs.s(Prefs.CLOCK_FONT);
        Typeface tf;
        if ("theme".equals(f)) tf = prefs.font().equals("sans-serif") ? Typeface.create("sans-serif-light", Typeface.NORMAL) : style.face;
        else if ("bold".equals(f)) tf = Typeface.create(style.face, Typeface.BOLD);
        else tf = Ui.typeface(this, f, Typeface.NORMAL);
        clock.setTypeface(tf);
        date.setTypeface(style.face);
        String sec = "on".equals(prefs.s(Prefs.CLOCK_SECONDS)) ? ":ss" : "";
        switch (prefs.s(Prefs.CLOCK_FORMAT)) {
            case "12": clock.setFormat12Hour("h:mm" + sec); clock.setFormat24Hour("h:mm" + sec); break;
            case "24": clock.setFormat12Hour("HH:mm" + sec); clock.setFormat24Hour("HH:mm" + sec); break;
            default: clock.setFormat12Hour("h:mm" + sec); clock.setFormat24Hour("H:mm" + sec); break;
        }
        String ds = prefs.s(Prefs.DATE_STYLE);
        String df = "short".equals(ds) ? "EEE d MMM" : "numeric".equals(ds) ? "dd/MM/yyyy" : "EEEE, d MMMM";
        date.setFormat12Hour(df);
        date.setFormat24Hour(df);
        date.setVisibility("none".equals(ds) ? View.GONE : View.VISIBLE);
        String al = prefs.s(Prefs.CLOCK_ALIGN);
        clockBox.setGravity("center".equals(al) ? Gravity.CENTER_HORIZONTAL : "end".equals(al) ? Gravity.END : Gravity.START);
        clockBox.setVisibility(prefs.showClock() ? View.VISIBLE : View.GONE);
    }

    private void applySearch() {
        if (style == null) return;
        int ink = prefs.ink(), glassTint = Ui.isLight(ink) ? 0xFFFFFF : 0x000000;
        int h = dp(prefs.i(Prefs.SEARCH_H));
        ViewGroup.LayoutParams lp = searchPill.getLayoutParams();
        if (lp != null && lp.height != h) { lp.height = h; searchPill.setLayoutParams(lp); }
        String shape = prefs.s(Prefs.SEARCH_SHAPE);
        if ("text".equals(shape)) { // just a small "Search" label
            searchPill.setBackground(Ui.ripple(this, null));
            searchPill.setGravity(Gravity.CENTER);
            searchPill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            Ui.ink(searchPill, Ui.alpha(ink, 0x99));
            searchPill.setText("⌕  " + prefs.str(Prefs.SEARCH_TEXT, "Search"));
            searchPill.setTypeface(style.face);
            searchPill.setElevation(0);
            searchPill.setVisibility(prefs.showSearchBar() ? View.VISIBLE : View.GONE);
            return;
        }
        searchPill.setGravity(Gravity.CENTER_VERTICAL);
        searchPill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        float r = "square".equals(shape) ? dp(6) : "rounded".equals(shape) ? dp(14) : h / 2f;
        int a = Math.max(0, Math.min(255, prefs.i(Prefs.SEARCH_OPACITY) * 255 / 100));
        android.graphics.drawable.GradientDrawable bg = Ui.round(prefs.solidBg() ? prefs.surface() : Ui.alpha(glassTint, a), r);
        if (!prefs.solidBg()) bg.setStroke(dp(1), Ui.alpha(glassTint, Math.min(255, a + 0x12)));
        searchPill.setBackground(Ui.ripple(this, bg));
        Ui.ink(searchPill, Ui.alpha(ink, 0xCC));
        searchPill.setText(prefs.str(Prefs.SEARCH_TEXT, "Search apps and web"));
        searchPill.setTypeface(style.face);
        searchPill.setElevation(prefs.depth() > 0 ? dp(prefs.depth() * 2) : 0);
        searchPill.setVisibility(prefs.showSearchBar() ? View.VISIBLE : View.GONE);
    }

    private int tileSurface() {
        if (prefs.solidBg()) return prefs.surface();
        return Ui.isLight(prefs.ink()) ? 0x2BFFFFFF : 0x99FFFFFF;
    }

    private final Tiles.Host tileHost = new Tiles.Host() {
        @Override public org.json.JSONObject tileStyle(String key) { return tileStyles().optJSONObject(key); }
        @Override public View appIcon(Entry e) {
            if (style == null) return null;
            AppCell c = cellFor(e, gridLabels(), style.ink, fitIcon(gridIconPx(), availableWidth() / Math.max(1, prefs.columns())));
            tileAppCells.add(c);
            // the tile around it handles hold-to-edit, so the icon passes its long-press up
            c.setOnLongClickListener(v -> v.getParent() instanceof View && ((View) v.getParent()).performLongClick());
            return c;
        }
        @Override public Entry entry(String key) {
            if (snap == null) return null;
            if (key.startsWith("f:")) { // a folder dropped onto a tile page
                for (Folders.Folder f : Folders.load(prefs)) {
                    if (!key.equals("f:" + f.id)) continue;
                    List<Entry> members = new ArrayList<>();
                    for (String k : f.keys) { Entry m = snap.byKey.get(k); if (m != null && !m.hidden) members.add(m); }
                    return members.isEmpty() ? null : folderEntry(f, members);
                }
                return null;
            }
            return snap.byKey.get(key);
        }
        @Override public View widget(int firstId) { return widgets == null ? null : widgets.viewForId(firstId); }
        @Override public List<Entry> apps(boolean favorites) {
            List<Entry> out = new ArrayList<>();
            if (snap == null) return out;
            if (favorites) { for (Entry e : snap.favorites) out.add(e); }
            else for (Entry e : snap.apps) if (!e.hidden) out.add(e);
            return out;
        }
        @Override public void appMenu(Entry e) { showAppMenu(e); }
        @Override public List<Entry> picked(String listId) {
            List<Entry> out = new ArrayList<>();
            if (snap == null) return out;
            org.json.JSONArray a = listConfig(listId).optJSONArray("a");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                Entry e = snap.byKey.get(a.optString(i));
                if (e != null) out.add(e);
            }
            return out;
        }
        @Override public org.json.JSONObject listConfig(String listId) { return HomeActivity.this.listConfig(listId); }
        @Override public void requestCalendar() {
            requestPermissions(new String[]{android.Manifest.permission.READ_CALENDAR}, 55);
        }
        @Override public void launch(Entry e, View v) { HomeActivity.this.launch(e, v); }
        @Override public void openCalendar() { HomeActivity.this.openCalendar(); }
        @Override public void safeStart(Intent i) { HomeActivity.this.safeStart(i); }
    };

    private static final List<String> MINIMAL_TILES = java.util.Arrays.asList("date", "day", "week");

    /** Rebuilds tiles on the clock page and the extra tile pages. */
    private void rebuildTiles() {
        if (style == null) return;
        syncWidgetTiles();
        List<List<String>> pages = Tiles.load(prefs);
        if (pages.get(0).isEmpty() && "minimal".equals(prefs.activeTheme()) && !prefs.bool("tilesSeeded", false))
            pages.set(0, new ArrayList<>(MINIMAL_TILES));
        if (previewThemes != null) { // full-screen preview: show the theme's signature tiles without saving them
            String tid = prefs.activeTheme();
            String[] want = "inkline".equals(tid) ? new String[]{"date|2|4|0|0", "day|2|4|2|0", "week|4|4|0|4", "events|4|6|0|8"}
                    : "minimal".equals(tid) ? new String[]{"date|2|4|0|0", "day|2|4|2|0", "week|4|4|0|4"} : new String[0];
            List<String> first = new ArrayList<>(pages.get(0));
            for (int i = want.length - 1; i >= 0; i--) {
                String b = Tiles.base(want[i]);
                boolean has = false;
                for (String x : first) if (Tiles.base(x).equals(b)) has = true;
                if (!has) first.add(0, want[i]);
            }
            pages.set(0, first);
        }
        boolean outlined = "outlined".equals(prefs.s(Prefs.TILE_STYLE));
        Tiles.Style ts = new Tiles.Style(prefs.ink(), outlined ? prefs.surface() : tileSurface(), dp(prefs.i(Prefs.WIDGET_RADIUS)),
                style.face, prefs.depth() > 0, outlined);
        int w = widgetScroll.getWidth() > 0 ? widgetScroll.getWidth()
                : getResources().getDisplayMetrics().widthPixels - home.getPaddingLeft() - home.getPaddingRight();
        tileViews.clear();
        tileAppCells.clear();
        tilePages.clear();
        tileBoxes.clear();
        lastTileStyle = ts;
        lastTileWidth = w;
        for (int p = 0; p < pages.size(); p++) {
            LinearLayout box;
            if (p == 0) {
                box = tilesBox;
            } else {
                if (!paged()) break;
                ScrollView sv = new ScrollView(this);
                sv.setVerticalScrollBarEnabled(false);
                sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
                box = new LinearLayout(this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setPadding(0, dp(8), 0, 0);
                sv.addView(box);
                tilePages.add(sv);
            }
            tileBoxes.add(box);
            List<View> views = Tiles.grid(this, box, pages.get(p), tileHost, ts, w);
            for (int i = 0; i < views.size(); i++)
                if (Tiles.isWidget(pages.get(p).get(i))) widgets.sizeChanged(views.get(i));
            final int page = p;
            for (int i = 0; i < views.size(); i++) {
                final int idx = i;
                if (!Tiles.isWidget(pages.get(p).get(i)))
                    views.get(i).setOnLongClickListener(v -> hold(() -> editTile(page, idx)));
            }
            tileViews.add(views);
        }
    }


    // ------------------------------------------------------------------ widget resize frame (Samsung-style)

    /** Border with four edge handles drawn around the selected widget. */
    static final class ResizeFrame extends View {
        private final android.graphics.Paint border = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint ring = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        final int pad;

        ResizeFrame(Context c, int accent) {
            super(c);
            pad = Ui.dp(c, 12);
            border.setStyle(android.graphics.Paint.Style.STROKE);
            border.setStrokeWidth(Ui.dp(c, 2.5f));
            fill.setColor(0xFFFFFFFF);
            ring.setStyle(android.graphics.Paint.Style.STROKE);
            ring.setStrokeWidth(Ui.dp(c, 2.5f));
            setColor(accent);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setColor(int accent) {
            int c = Ui.isLight(accent) && (accent & 0xFFFFFF) > 0xE0E0E0 ? 0xFF3D7BFF : accent;
            border.setColor(c);
            ring.setColor(c);
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas cv) {
            float w = getWidth(), h = getHeight(), r = Ui.dp(getContext(), 9);
            cv.drawRoundRect(pad, pad, w - pad, h - pad, Ui.dp(getContext(), 18), Ui.dp(getContext(), 18), border);
            float[][] pts = {{pad, h / 2}, {w - pad, h / 2}, {w / 2, pad}, {w / 2, h - pad}};
            for (float[] pt : pts) {
                cv.drawCircle(pt[0], pt[1], r, fill);
                cv.drawCircle(pt[0], pt[1], r, ring);
            }
            // corner grips: short rounded L-shapes
            float len = Ui.dp(getContext(), 16), o = pad;
            android.graphics.Paint g = new android.graphics.Paint(ring);
            g.setStrokeWidth(Ui.dp(getContext(), 5));
            g.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            float[][] cs = {{o, o, 1, 1}, {w - o, o, -1, 1}, {o, h - o, 1, -1}, {w - o, h - o, -1, -1}};
            for (float[] c : cs) {
                cv.drawLine(c[0], c[1], c[0] + len * c[2], c[1], g);
                cv.drawLine(c[0], c[1], c[0], c[1] + len * c[3], g);
            }
        }
    }

    private ResizeFrame resizeFrame;
    private LinearLayout resizeBar;
    private int rzSlot = -1, rzMode, rzSpan, rzH;
    /** The resize frame targets a widget slot, or (rzTile) a tile at rzPage/rzSlot. */
    private boolean rzTile;
    private int rzPage;

    private View rzView() {
        if (rzSlot < 0) return null;
        if (!rzTile) return widgets.slotView(rzSlot);
        if (rzPage >= tileViews.size() || rzSlot >= tileViews.get(rzPage).size()) return null;
        return tileViews.get(rzPage).get(rzSlot);
    }

    private List<List<String>> tilePagesForEdit() {
        List<List<String>> pages = Tiles.load(prefs);
        if (pages.get(0).isEmpty() && "minimal".equals(prefs.activeTheme()) && !prefs.bool("tilesSeeded", false))
            pages.set(0, new ArrayList<>(MINIMAL_TILES));
        return pages;
    }

    void editTile(int page, int idx) {
        enterEdit();
        showTileResizer(page, idx);
        armTileDrag();
    }

    private void showTileResizer(int page, int idx) {
        if (!editMode) return;
        rzTile = true;
        rzPage = page;
        rzSlot = idx;
        if (rzView() == null) { hideResizer(); return; }
        rzView().setRotation(0);
        resizeFrame.setColor(prefs.accent());
        resizeFrame.setVisibility(View.VISIBLE);
        resizeBar.setVisibility(View.VISIBLE);
        root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        root.post(this::positionResizer);
    }

    /** Saves a tile spec change and redraws tiles, keeping the frame on the same tile. */
    private void saveTiles(List<List<String>> pages) {
        prefs.set("tilesSeeded", true);
        Tiles.save(prefs, pages);
        markSynced();
        rebuildFavorites();
        root.post(this::positionResizer);
    }
    private float rzDownX, rzDownY;
    private boolean rzTouch;
    private static final int RZ_LEFT = 1, RZ_RIGHT = 2, RZ_TOP = 3, RZ_BOTTOM = 4, RZ_MOVE = 5,
            RZ_TL = 6, RZ_TR = 7, RZ_BL = 8, RZ_BR = 9;

    private static boolean horiz(int m) { return m == RZ_LEFT || m == RZ_RIGHT || m >= RZ_TL; }
    private static boolean vert(int m) { return m == RZ_TOP || m == RZ_BOTTOM || m >= RZ_TL; }
    private static boolean rightSide(int m) { return m == RZ_RIGHT || m == RZ_TR || m == RZ_BR; }
    private static boolean bottomSide(int m) { return m == RZ_BOTTOM || m == RZ_BL || m == RZ_BR; }

    private void buildResizer() {
        resizeFrame = new ResizeFrame(this, prefs.accent());
        resizeFrame.setVisibility(View.GONE);
        root.addView(resizeFrame, new FrameLayout.LayoutParams(1, 1, Gravity.TOP | Gravity.START));
        resizeBar = new LinearLayout(this);
        resizeBar.setBackground(Ui.round(0xF21C1E22, dp(24)));
        resizeBar.setPadding(dp(6), dp(6), dp(6), dp(6));
        resizeBar.setElevation(dp(12));
        resizeBar.setVisibility(View.GONE);
        String[] names = {"Done", "Add", "Options", "Remove"};
        Runnable[] acts = {this::hideResizer,
                () -> { int s = rzSlot; boolean t = rzTile; hideResizer(); if (t) addTileSheet(); else widgets.showPicker(s); },
                () -> { int s = rzSlot, pg = rzPage; if (rzTile) tileSheet(pg, s); else widgetSheet(s); },
                () -> { int s = rzSlot, pg = rzPage; boolean t = rzTile; hideResizer(); if (t) removeTile(pg, s); else confirmRemoveWidget(s); }};
        for (int i = 0; i < names.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
            if (i > 0) lp.setMarginStart(dp(6));
            resizeBar.addView(pill(names[i], i == 0, acts[i]), lp);
        }
        root.addView(resizeBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START));
    }

    // ---- per-tile style (like Widget Studio's style editor) ----

    private org.json.JSONObject tileStyles() {
        try { return new org.json.JSONObject(prefs.str("tilestyles", "{}")); } catch (Exception e) { return new org.json.JSONObject(); }
    }

    private void saveTileStyle(String key, org.json.JSONObject o) {
        org.json.JSONObject all = tileStyles();
        try { if (o == null || o.length() == 0) all.remove(key); else all.put(key, o); } catch (Exception ignored) { }
        prefs.set("tilestyles", all.toString());
        afterEdit(true);
    }

    private void tileStyleSheet(String key, String title) {
        org.json.JSONObject cur = tileStyles().optJSONObject(key);
        final org.json.JSONObject o = cur != null ? cur : new org.json.JSONObject();
        java.util.function.BiConsumer<String, Object> put = (k, v) -> {
            try { o.put(k, v); } catch (Exception ignored) { }
            saveTileStyle(key, o);
        };
        Sheet s = editSheet("Style · " + title);
        s.view(chipsView("Card", new String[]{"Theme", "Glass", "Solid", "None"}, new String[]{"theme", "glass", "solid", "none"},
                o.optString("bg", "theme"), v -> put.accept("bg", v)));
        s.view(swatchView("Card colour", new int[]{0, 0xFFFFFFFF, 0xFFF2F2F7, 0xFFD1D5DB, 0xFF8E8E93, 0xFF2C2C2E, 0xFF000000,
                        0xFF1E3A5F, 0xFF1F4D3A, 0xFF5B2333, 0xFFFFE8CC, 0xFFFFD6E0, 0xFFDCEBFF, prefs.accent()},
                o.optInt("bgc", 0), v -> put.accept("bgc", v)));
        s.view(sliderView("Card opacity", 0, 100, o.optInt("opacity", 100), "%", null, v -> put.accept("opacity", v)));
        s.view(sliderView("Corner radius", 0, 48, o.has("radius") ? o.optInt("radius") : prefs.i(Prefs.WIDGET_RADIUS), " dp", null,
                v -> put.accept("radius", v)));
        s.view(chipsView("Finish", new String[]{"Matte", "Gloss"}, new String[]{"matte", "gloss"},
                o.optBoolean("gloss", false) ? "gloss" : "matte", v -> put.accept("gloss", "gloss".equals(v))));
        s.view(chipsView("Depth shadow", new String[]{"Theme", "On", "Off"}, new String[]{"theme", "on", "off"},
                o.optString("depth", "theme"), v -> put.accept("depth", v)));
        s.view(chipsView("Border", new String[]{"Off", "On"}, new String[]{"off", "on"},
                o.optBoolean("border", false) ? "on" : "off", v -> put.accept("border", "on".equals(v))));
        s.view(swatchView("Text colour", new int[]{0, 0xFFFFFFFF, 0xFF111111, 0xFF8E8E93, prefs.accent(), 0xFFFF453A, 0xFFFF9F0A,
                        0xFFFFD60A, 0xFF30D158, 0xFF64D2FF, 0xFF0A84FF, 0xFFBF5AF2, 0xFFFF375F},
                o.optInt("ink", 0), v -> put.accept("ink", v)));
        String font = o.optString("font", "");
        String fontName = "Theme font";
        for (String[] ft : FontList.ALL) if (ft[0].equals(font)) fontName = ft[1];
        s.item("Font: " + fontName + "…", () -> {
            Sheet f = editSheet("Font");
            f.option("Theme font", null, font.isEmpty(), () -> { o.remove("font"); saveTileStyle(key, o); });
            for (String[] ft : FontList.ALL)
                f.option(ft[1], Ui.typeface(this, ft[0], Typeface.NORMAL), ft[0].equals(font), () -> put.accept("font", ft[0]));
            f.show();
        });
        s.view(sliderView("Text size", 70, 160, o.optInt("scale", 100), "%", null, v -> put.accept("scale", v)));
        s.item("Use this style on every tile", () -> {
            org.json.JSONObject all = tileStyles();
            for (List<String> l : Tiles.load(prefs))
                for (String sp : l) {
                    String b = Tiles.base(sp);
                    if (Tiles.isWidget(sp) || b.startsWith("app:")) continue;
                    try { all.put(b, new org.json.JSONObject(o.toString())); } catch (Exception ignored) { }
                }
            prefs.set("tilestyles", all.toString());
            afterEdit(true);
            Ui.toast(this, "Style copied to all tiles");
        });
        s.item("Reset to the theme's style", () -> saveTileStyle(key, null));
        s.show();
    }

    /** A titled row of colour dots; 0 = "Auto" (follow the theme). */
    private View swatchView(String title, int[] colors, int cur, java.util.function.IntConsumer pick) {
        Ios ios = Ios.of(this);
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(16), dp(12), dp(16), dp(12));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(ios.text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        block.addView(t);
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(10), 0, dp(2));
        List<View> dots = new ArrayList<>();
        for (int col : colors) {
            TextView d = new TextView(this);
            d.setGravity(Gravity.CENTER);
            d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            if (col == 0) { g.setColor(ios.segBg); d.setText("Auto"); d.setTextColor(ios.text); }
            else g.setColor(col);
            boolean sel = col == cur;
            g.setStroke(dp(sel ? 3 : 1), sel ? ios.tint : 0x33808080);
            d.setBackground(g);
            d.setOnClickListener(v -> {
                for (int k = 0; k < dots.size(); k++)
                    ((android.graphics.drawable.GradientDrawable) dots.get(k).getBackground()).setStroke(dp(colors[k] == col ? 3 : 1),
                            colors[k] == col ? ios.tint : 0x33808080);
                pick.accept(col);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(38), dp(38));
            lp.setMarginEnd(dp(10));
            row.addView(d, lp);
            dots.add(d);
        }
        hs.addView(row);
        block.addView(hs);
        return block;
    }

    private void confirmRemoveWidget(int slot) {
        Sheet s = editSheet("Remove this widget?");
        s.item("Remove", () -> { widgets.removeSlot(slot); refreshEdit(); });
        s.item("Keep it", () -> { });
        s.show();
    }

    /** Long-press on a widget (or tap in edit mode): select it with resize handles. */
    void editWidget(int slot) {
        int[] at = widgetItem(widgets.firstIdOf(slot));
        if (at != null) editTile(at[0], at[1]);
    }

    /** Page and index of the grid item holding this widget, or null. */
    private int[] widgetItem(int firstId) {
        List<List<String>> pages = Tiles.load(prefs);
        for (int p = 0; p < pages.size(); p++)
            for (int i = 0; i < pages.get(p).size(); i++)
                if (Tiles.isWidget(pages.get(p).get(i)) && Tiles.widgetId(pages.get(p).get(i)) == firstId) return new int[]{p, i};
        return null;
    }

    /** Widgets.restore() finished: make sure every widget has a place in the grid, then redraw. */
    void widgetsChanged() {
        if (style == null) return;
        if (syncWidgetTiles()) markSynced();
        rebuildFavorites();
    }

    /** Adds grid items for new widgets (first page), drops items for widgets that are gone. */
    private boolean syncWidgetTiles() {
        if (widgets == null) return false;
        List<List<String>> pages = tilePagesForEdit();
        java.util.Set<Integer> present = new java.util.HashSet<>(), referenced = new java.util.HashSet<>();
        for (int i = 0; i < widgets.slotCount(); i++) present.add(widgets.firstIdOf(i));
        boolean changed = false;
        for (List<String> l : pages) {
            for (java.util.Iterator<String> it = l.iterator(); it.hasNext(); ) {
                String spec = it.next();
                if (!Tiles.isWidget(spec)) continue;
                int id = Tiles.widgetId(spec);
                if (!present.contains(id) || referenced.contains(id)) { it.remove(); changed = true; }
                else referenced.add(id);
            }
        }
        int unit = Tiles.unit(this, availableWidth());
        for (int i = 0; i < widgets.slotCount(); i++) {
            int id = widgets.firstIdOf(i);
            if (referenced.contains(id)) continue;
            int hPx = widgets.heightOf(i);
            int h = Math.max(1, Math.min(Tiles.MAX_H, Math.round(hPx / (float) Math.max(1, Tiles.pitch(this, availableWidth())))));
            int w = Math.max(1, Math.min(Tiles.G, widgets.spanOfSlot(i)));
            pages.get(0).add("widget:" + id + "|" + w + "|" + h);
            changed = true;
        }
        if (changed) Tiles.save(prefs, pages);
        return changed;
    }

    private void showResizer(int slot) {
        if (!editMode || widgets.slotView(slot) == null) return;
        rzTile = false;
        View held = widgets.slotView(slot);
        held.setRotation(0);
        if (paged() && pager.current() != 0) pager.setPage(0, false);
        rzSlot = slot;
        resizeFrame.setColor(prefs.accent());
        resizeFrame.setVisibility(View.VISIBLE);
        resizeBar.setVisibility(View.VISIBLE);
        root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        root.post(this::positionResizer);
    }

    private void hideResizer() {
        rzSlot = -1;
        rzTouch = false;
        if (resizeFrame != null) resizeFrame.setVisibility(View.GONE);
        if (resizeBar != null) resizeBar.setVisibility(View.GONE);
    }

    private android.graphics.RectF screenRect(View v) {
        int[] l = new int[2];
        v.getLocationOnScreen(l);
        float sx = home.getScaleX();
        return new android.graphics.RectF(l[0], l[1], l[0] + v.getWidth() * sx, l[1] + v.getHeight() * sx);
    }

    private void positionResizer() {
        if (rzSlot < 0) return;
        View v = rzView();
        if (v == null || !v.isShown()) { hideResizer(); return; }
        android.graphics.RectF r = screenRect(v);
        int[] rl = new int[2];
        root.getLocationOnScreen(rl);
        float ox = rl[0] + root.getPaddingLeft(), oy = rl[1] + root.getPaddingTop();
        int pad = resizeFrame.pad;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) resizeFrame.getLayoutParams();
        lp.width = Math.round(r.width()) + 2 * pad;
        lp.height = Math.round(r.height()) + 2 * pad;
        resizeFrame.setLayoutParams(lp);
        resizeFrame.setTranslationX(r.left - pad - ox);
        resizeFrame.setTranslationY(r.top - pad - oy);
        resizeBar.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int bw = resizeBar.getMeasuredWidth(), bh = resizeBar.getMeasuredHeight();
        float bx = Math.max(dp(8), Math.min(root.getWidth() - root.getPaddingLeft() - root.getPaddingRight() - bw - dp(8),
                r.centerX() - ox - bw / 2f));
        float by = r.top - oy - pad - bh - dp(6);
        if (by < dp(150)) by = r.bottom - oy + pad + dp(6); // no room above (edit bar): put it below
        resizeBar.setTranslationX(bx);
        resizeBar.setTranslationY(by);
    }

    private int resizeModeAt(float x, float y) {
        View v = rzView();
        if (v == null) return 0;
        android.graphics.RectF r = screenRect(v);
        float t = dp(28);
        boolean inY = y > r.top - t && y < r.bottom + t, inX = x > r.left - t && x < r.right + t;
        float ct = t * 1.3f; // corners first: they resize both ways
        if (Math.hypot(x - r.left, y - r.top) < ct) return RZ_TL;
        if (Math.hypot(x - r.right, y - r.top) < ct) return RZ_TR;
        if (Math.hypot(x - r.left, y - r.bottom) < ct) return RZ_BL;
        if (Math.hypot(x - r.right, y - r.bottom) < ct) return RZ_BR;
        if (inY && Math.abs(x - r.left) < t) return RZ_LEFT;
        if (inY && Math.abs(x - r.right) < t) return RZ_RIGHT;
        if (inX && Math.abs(y - r.top) < t) return RZ_TOP;
        if (inX && Math.abs(y - r.bottom) < t) return RZ_BOTTOM;
        if (r.contains(x, y)) return RZ_MOVE;
        return 0;
    }

    private void resizeTouch(MotionEvent ev) {
        float dx = ev.getRawX() - rzDownX, dy = ev.getRawY() - rzDownY, sc = home.getScaleX();
        View v = rzView();
        if (v == null) { hideResizer(); return; }
        if (rzTile) { tileTouch(ev, v, dx, dy); return; }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                if (rzMode == RZ_LEFT || rzMode == RZ_RIGHT) {
                    float cell = widgetColumn.getWidth() * sc / Widgets.COLS;
                    float w = widgets.spanOfSlot(rzSlot) * cell + (rzMode == RZ_RIGHT ? dx : -dx);
                    int span = Math.max(1, Math.min(Widgets.COLS, Math.round(w / cell)));
                    if (span != rzSpan) {
                        rzSpan = span;
                        widgets.setSpan(rzSlot, span, false);
                        v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                    sizeBadge.setText(spanLabel(rzSpan) + " · " + Math.round(rzH / getResources().getDisplayMetrics().density) + " dp tall");
                } else if (rzMode == RZ_TOP || rzMode == RZ_BOTTOM) {
                    int h = widgets.heightOf(rzSlot);
                    int target = Math.round(rzStartH + (rzMode == RZ_BOTTOM ? dy : -dy) / sc);
                    target = Math.max(dp(48), Math.min(getResources().getDisplayMetrics().heightPixels, Math.round(target / (float) dp(8)) * dp(8)));
                    if (target != h) widgets.setHeight(rzSlot, target, false);
                    rzH = target;
                    sizeBadge.setText(spanLabel(rzSpan) + " · " + Math.round(rzH / getResources().getDisplayMetrics().density) + " dp tall");
                } else if (rzMode == RZ_MOVE) {
                    v.setTranslationX(dx / sc);
                    v.setTranslationY(dy / sc);
                    v.setElevation(dp(16));
                    sizeBadge.setText("Drop to move");
                }
                sizeBadge.setVisibility(View.VISIBLE);
                root.post(this::positionResizer);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                sizeBadge.setVisibility(View.GONE);
                if (rzMode == RZ_LEFT || rzMode == RZ_RIGHT) {
                    widgets.setSpan(rzSlot, rzSpan, true);
                } else if (rzMode == RZ_TOP || rzMode == RZ_BOTTOM) {
                    widgets.setHeight(rzSlot, rzH, true);
                } else if (rzMode == RZ_MOVE) {
                    float fx = ev.getRawX(), fy = ev.getRawY();
                    v.setTranslationX(0);
                    v.setTranslationY(0);
                    widgets.style(v);
                    if (Math.abs(dx) > dp(12) || Math.abs(dy) > dp(12)) {
                        // dropped on another widget: swap places; on empty space: stay where dropped
                        int onto = -1;
                        for (int i = 0; i < widgets.slotCount(); i++) {
                            if (i == rzSlot) continue;
                            if (screenRect(widgets.slotView(i)).contains(fx, fy)) { onto = i; break; }
                        }
                        if (onto >= 0) {
                            widgets.moveTo(rzSlot, onto);
                            rzSlot = onto;
                        } else {
                            float cell = widgetColumn.getWidth() * sc / Widgets.COLS;
                            int span = widgets.spanOfSlot(rzSlot);
                            int x = Math.max(0, Math.min(Widgets.COLS - span, Math.round(widgets.xOf(rzSlot) + dx / cell)));
                            int gap = Math.max(0, Math.round((widgets.gapOf(rzSlot) + dy / sc) / dp(8)) * dp(8));
                            widgets.setPlacement(rzSlot, gap, x);
                        }
                    }
                }
                rzTouch = false;
                root.postDelayed(() -> { refreshEdit(); positionResizer(); }, 60);
                break;
        }
    }

    private List<List<String>> rzPages;   // tile pages while dragging/resizing: no storage reads per move
    private boolean draggingVisual;
    private float grabX, grabY;           // finger offset from the item's centre
    private final List<LinearLayout> tileBoxes = new ArrayList<>();
    private Tiles.Style lastTileStyle;
    private int lastTileWidth;

    private android.graphics.RectF baseRect(View v) {
        android.graphics.RectF r = screenRect(v);
        r.offset(-v.getTranslationX(), -v.getTranslationY());
        return r;
    }

    private void follow(View v, float x, float y) {
        android.graphics.RectF r = baseRect(v);
        v.setTranslationX(x - grabX - r.centerX());
        v.setTranslationY(y - grabY - r.centerY());
    }

    /** The slot under the finger: an item it is over, or the end when it's below everything. */
    private int liveIndex(List<View> views, int self, float x, float y) {
        float lowest = -1;
        for (int i = 0; i < views.size(); i++) {
            if (i == self) continue;
            android.graphics.RectF r = baseRect(views.get(i));
            if (r.contains(x, y)) return i;
            lowest = Math.max(lowest, r.bottom);
        }
        if (lowest >= 0 && y > lowest) return views.size() - 1;
        return self;
    }

    private int[][] livePos;   // grid positions while dragging/resizing (others pushed out of the way)
    private int tgtC = -1, tgtR = -1;

    private View autoScrollView;
    private final Runnable autoScrollTick = new Runnable() {
        @Override public void run() {
            View v = autoScrollView;
            if (v == null || !draggingVisual) return;
            ScrollView sv = scrollerOf(v);
            if (sv != null) {
                android.graphics.RectF sr = screenRect(sv);
                float zone = dp(90), speed = 0;
                if (lastRawY > sr.bottom - zone) speed = (lastRawY - (sr.bottom - zone)) / zone;
                else if (lastRawY < sr.top + dp(40)) speed = -(sr.top + dp(40) - lastRawY) / dp(40);
                if (speed != 0) {
                    sv.scrollBy(0, Math.round(Math.max(-1f, Math.min(1f, speed)) * dp(12)));
                    follow(v, lastRawX, lastRawY);
                }
            }
            root.postOnAnimation(this);
        }
    };

    /** Scrolls the page smoothly (every frame) while a dragged item is held near the top or bottom. */
    private void startAutoScroll(View v) {
        if (autoScrollView == v) return;
        autoScrollView = v;
        root.removeCallbacks(autoScrollTick);
        root.postOnAnimation(autoScrollTick);
    }

    private void stopAutoScroll() {
        autoScrollView = null;
        root.removeCallbacks(autoScrollTick);
    }

    private static ScrollView scrollerOf(View v) {
        for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent())
            if (p instanceof ScrollView) return (ScrollView) p;
        return null;
    }

    private void tileTouch(MotionEvent ev, View v, float dx, float dy) {
        if (rzPages == null) rzPages = tilePagesForEdit();
        List<List<String>> pages = rzPages;
        if (rzPage >= pages.size() || rzSlot >= pages.get(rzPage).size() || rzPage >= tileBoxes.size()) {
            rzPages = null;
            hideResizer();
            return;
        }
        List<String> l = pages.get(rzPage);
        String spec = l.get(rzSlot);
        LinearLayout box = tileBoxes.get(rzPage);
        int width = box.getWidth() > 0 ? box.getWidth() : lastTileWidth;
        int unit = Tiles.unit(this, width), pitch = Tiles.pitch(this, width), colW = Tiles.colWidth(this, width), gap = Tiles.gap(this);
        List<View> views = tileViews.get(rzPage);
        if (livePos == null) livePos = Tiles.arrange(l, -1, 0, 0);
        float x = ev.getRawX(), y = ev.getRawY();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                if (rzMode != RZ_MOVE) { // edges and corners: new size, others pushed out of the way
                    int w = rzSpan, h = rzH;
                    if (horiz(rzMode)) {
                        float step = colW + gap;
                        float wpx = Tiles.itemWidth(this, width, Tiles.w(spec)) + (rightSide(rzMode) ? dx : -dx);
                        w = Math.max(1, Math.min(Tiles.G, Math.round((wpx + gap) / step)));
                    }
                    if (vert(rzMode))
                        h = Math.max(1, Math.min(Tiles.MAX_H, Math.round((Tiles.h(spec) * pitch + (bottomSide(rzMode) ? dy : -dy)) / pitch)));
                    if (w != rzSpan || h != rzH) {
                        rzSpan = w;
                        rzH = h;
                        List<String> preview = new ArrayList<>(l);
                        int[] at = livePos[rzSlot];
                        int col = at[0], row = at[1];
                        if (horiz(rzMode) && !rightSide(rzMode)) col = at[0] + Tiles.w(spec) - w; // left edge: the right edge stays put
                        if (vert(rzMode) && !bottomSide(rzMode)) row = at[1] + Tiles.h(spec) - h; // top edge: the bottom edge stays put
                        col = Math.max(0, Math.min(Tiles.G - w, col));
                        row = Math.max(0, row);
                        preview.set(rzSlot, Tiles.placed(Tiles.sized(spec, w, h), col, row));
                        livePos = Tiles.arrange(preview, rzSlot, col, row);
                        FrameLayout canvas = Tiles.canvas(this, box);
                        android.transition.ChangeBounds cb = new android.transition.ChangeBounds();
                        cb.setDuration(160);
                        android.transition.TransitionManager.beginDelayedTransition(canvas, cb);
                        Tiles.layout(this, box, preview, views, width, livePos);
                        v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                    sizeBadge.setText(rzSpan + " × " + rzH);
                    sizeBadge.setVisibility(View.VISIBLE);
                    root.post(this::positionResizer);
                } else {
                    if (!draggingVisual) { // lift: on top of everything, smooth layer, no jiggle or handles
                        draggingVisual = true;
                        stopJiggle();
                        resizeFrame.setVisibility(View.INVISIBLE);
                        resizeBar.setVisibility(View.INVISIBLE);
                        android.graphics.RectF r = baseRect(v);
                        grabX = x - r.centerX();
                        grabY = y - r.centerY();
                        v.bringToFront();
                        v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                        v.setTranslationZ(dp(24));
                        v.animate().scaleX(1.04f).scaleY(1.04f).setDuration(120).start();
                        tgtC = livePos[rzSlot][0];
                        tgtR = livePos[rzSlot][1];
                    }
                    edgeHold(x);
                    startAutoScroll(v);
                    if (pager.current() == rzPage) {
                        FrameLayout canvas = Tiles.canvas(this, box);
                        int[] cl = new int[2];
                        canvas.getLocationOnScreen(cl);
                        float left = x - grabX - v.getWidth() / 2f - cl[0];
                        float top = y - grabY - v.getHeight() / 2f - cl[1];
                        int w = Tiles.w(spec);
                        int c = Math.max(0, Math.min(Tiles.G - w, Math.round(left / (float) (colW + gap))));
                        int r = Math.max(0, Math.round(top / (float) pitch));
                        // an app heading for another app's spot: that app stays put and lights up (drop = folder)
                        AppCell over = tileApp(spec) != null ? appInSlot(l, views, rzSlot, c, r) : null;
                        setMergeTarget(over);
                        if (over == null && (c != tgtC || r != tgtR)) { // others glide aside, or back to their own spots
                            tgtC = c;
                            tgtR = r;
                            livePos = Tiles.arrange(l, rzSlot, c, r);
                            android.transition.ChangeBounds cb = new android.transition.ChangeBounds();
                            cb.setDuration(180);
                            cb.excludeTarget(v, true);
                            android.transition.TransitionManager.beginDelayedTransition(canvas, cb);
                            Tiles.layout(this, box, l, views, width, livePos, v); // the held item stays put under the finger
                            v.bringToFront();
                        }
                    }
                    follow(v, x, y);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                sizeBadge.setVisibility(View.GONE);
                rzTouch = false;
                AppCell into = mergeTarget;
                setMergeTarget(null);
                Entry movingApp = tileApp(spec);
                if (rzMode == RZ_MOVE && into != null && movingApp != null && ev.getActionMasked() == MotionEvent.ACTION_UP) {
                    main.removeCallbacks(edgeFlip);
                    edgeDir = 0;
                    stopAutoScroll();
                    l.remove(rzSlot); // the dragged app tile goes into the folder
                    prefs.set("tilesSeeded", true);
                    Tiles.save(prefs, pages);
                    draggingVisual = false;
                    rzPages = null;
                    livePos = null;
                    tgtC = tgtR = -1;
                    v.setVisibility(View.INVISIBLE);
                    hideResizer();
                    mergeInto(movingApp, into.entry, false);
                    markSynced();
                    refreshMeta();
                    afterEdit(true);
                    break;
                }
                main.removeCallbacks(edgeFlip);
                edgeDir = 0;
                int cur = pager.current();
                stopAutoScroll();
                boolean crossPage = rzMode == RZ_MOVE && paged() && cur != rzPage && cur < 1 + tilePages.size();
                if (rzMode == RZ_MOVE && !crossPage && v.getParent() instanceof FrameLayout) {
                    // move the layout to the final slot but keep the item visually where the finger left it, then glide
                    android.graphics.RectF before = screenRect(v);
                    int[] xy = Tiles.slotXY(this, width, Tiles.w(spec), livePos[rzSlot]);
                    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
                    float dxLayout = xy[0] - lp.leftMargin, dyLayout = xy[1] - lp.topMargin;
                    lp.leftMargin = xy[0];
                    lp.topMargin = xy[1];
                    v.setLayoutParams(lp);
                    v.setTranslationX(v.getTranslationX() - dxLayout);
                    v.setTranslationY(v.getTranslationY() - dyLayout);
                    v.animate().translationX(0).translationY(0).translationZ(0).scaleX(1f).scaleY(1f)
                            .setDuration(220).setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                            .withEndAction(() -> v.setLayerType(View.LAYER_TYPE_NONE, null)).start();
                } else if (rzMode == RZ_MOVE) {
                    v.setTranslationX(0);
                    v.setTranslationY(0);
                    v.setTranslationZ(0);
                    v.setScaleX(1f);
                    v.setScaleY(1f);
                    v.setLayerType(View.LAYER_TYPE_NONE, null);
                }
                if (crossPage) {
                    String moving = Tiles.base(spec) + "|" + Tiles.w(spec) + "|" + Tiles.h(spec); // free spot on the new page
                    l.remove(rzSlot);
                    while (pages.size() <= cur) pages.add(new ArrayList<>());
                    pages.get(cur).add(moving);
                    rzPage = cur;
                    rzSlot = pages.get(cur).size() - 1;
                } else {
                    if (rzMode != RZ_MOVE) l.set(rzSlot, Tiles.sized(l.get(rzSlot), rzSpan, rzH));
                    // everything keeps the spot it ended up in
                    for (int i = 0; i < l.size() && i < livePos.length; i++) l.set(i, Tiles.placed(l.get(i), livePos[i][0], livePos[i][1]));
                }
                draggingVisual = false;
                rzPages = null;
                livePos = null;
                tgtC = tgtR = -1;
                resizeFrame.setVisibility(View.VISIBLE);
                resizeBar.setVisibility(View.VISIBLE);
                if (crossPage) saveTiles(pages);
                else { // views are already in their final places: just remember them (no rebuild, no flash)
                    prefs.set("tilesSeeded", true);
                    Tiles.save(prefs, pages);
                    markSynced();
                }
                root.postDelayed(() -> { refreshEdit(); positionResizer(); }, 240);
                break;
        }
    }

    private int edgeDir;
    private final Runnable edgeFlip = () -> {
        int target = pager.current() + edgeDir;
        if (edgeDir != 0 && target >= 0 && target < 1 + tilePages.size()) {
            pager.setPage(target, true);
            root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
    };

    /** Holding a dragged item at a screen edge flips to the neighbouring tiles page. */
    private void edgeHold(float x) {
        int dir = x < dp(28) ? -1 : x > root.getWidth() - dp(28) ? 1 : 0;
        if (dir == edgeDir) return;
        edgeDir = dir;
        main.removeCallbacks(edgeFlip);
        if (dir != 0) main.postDelayed(edgeFlip, 650);
    }

    private static String spanLabel(int span) {
        switch (span) {
            case 1: return "¼ width";
            case 2: return "½ width";
            case 3: return "¾ width";
            default: return "Full width";
        }
    }


    // ------------------------------------------------------------------ press, hold and drag (iOS-style)

    private float lastRawX, lastRawY;
    private boolean needCancel, iconDragging, dragMoved, dragFromDock;
    private AppCell dragCell;
    private ImageView dragShadow;
    private float dragStartX, dragStartY;
    /** Keys of the app-page items in display order (apps and folders, not the dock). */
    private final List<String> gridKeys = new ArrayList<>();

    /** Sends one CANCEL to the views under the finger, so the item they were pressing lets go. */
    private void cancelChildrenOnce(MotionEvent ev) {
        if (!needCancel) return;
        needCancel = false;
        MotionEvent c = MotionEvent.obtain(ev);
        c.setAction(MotionEvent.ACTION_CANCEL);
        super.dispatchTouchEvent(c);
        c.recycle();
    }

    /** A long-pressed tile or widget follows the same finger right away. */
    private void armTileDrag() {
        rzPages = null;
        livePos = null;
        draggingVisual = false;
        List<List<String>> tp = tilePagesForEdit();
        if (rzPage >= tp.size() || rzSlot < 0 || rzSlot >= tp.get(rzPage).size()) return;
        String sp = tp.get(rzPage).get(rzSlot);
        rzMode = RZ_MOVE;
        rzTouch = true;
        needCancel = true;
        rzDownX = lastRawX;
        rzDownY = lastRawY;
        rzSpan = Tiles.w(sp);
        rzStartH = rzH = Tiles.h(sp);
    }

    /** Lifts an app icon (or folder) from the grid or dock; it follows the finger until released. */
    private void startIconDrag(AppCell c) {
        if (c == null || c.entry == null) return;
        enterEdit();
        hideResizer();
        dragCell = c;
        dragFromDock = c.getParent() == dock;
        dragMoved = false;
        dragStartX = lastRawX;
        dragStartY = lastRawY;
        int w = Math.max(1, c.getWidth()), h = Math.max(1, c.getHeight());
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        c.setRotation(0);
        c.draw(new android.graphics.Canvas(b));
        dragShadow = new ImageView(this);
        dragShadow.setImageBitmap(b);
        dragShadow.animate().scaleX(1.12f).scaleY(1.12f).setDuration(140).start();
        dragShadow.setElevation(dp(20));
        root.addView(dragShadow, new FrameLayout.LayoutParams(w, h, Gravity.TOP | Gravity.START));
        moveShadow(lastRawX, lastRawY);
        c.setAlpha(0.25f);
        iconDragging = true;
        needCancel = true;
        root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }

    private void moveShadow(float rx, float ry) {
        if (dragShadow == null) return;
        int[] rl = new int[2];
        root.getLocationOnScreen(rl);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) dragShadow.getLayoutParams();
        dragShadow.setTranslationX(rx - rl[0] - root.getPaddingLeft() - lp.width / 2f);
        dragShadow.setTranslationY(ry - rl[1] - root.getPaddingTop() - lp.height / 2f);
    }

    private List<AppCell> pageCells;  // icons on the page the finger is over (layout order, includes the dragged one)
    private int iconTarget = -1, pageCellsPage = -1;

    private void collectPageCells() {
        int cur = pager.current();
        if (cur == pageCellsPage && pageCells != null) return;
        resetPageCells();
        pageCellsPage = cur;
        pageCells = new ArrayList<>();
        View page = pager.getChildAt(cur);
        if (page == null || cur < firstAppPage()) return;
        for (AppCell c : cells) if (c.getParent() != dock && c.entry != null && isInside(c, page)) pageCells.add(c);
    }

    private void resetPageCells() {
        if (pageCells != null) for (AppCell c : pageCells) if (c != dragCell) c.animate().translationX(0).translationY(0).setDuration(150).start();
        pageCells = null;
        pageCellsPage = -1;
        iconTarget = -1;
    }

    /** Slides the page's icons so there's a gap at the finger, like iOS. */
    private void reflowIcons(float x, float y) {
        collectPageCells();
        if (pageCells == null || pageCells.isEmpty()) return;
        View page = pager.getChildAt(pager.current());
        int cols = prefs.columns();
        int rows = page instanceof ViewGroup ? Math.max(1, ((ViewGroup) page).getChildCount()) : 1;
        android.graphics.RectF pr = screenRect(page);
        float cw = pr.width() / cols, rh = (pr.height() - page.getPaddingTop() - page.getPaddingBottom()) / rows;
        int col = Math.max(0, Math.min(cols - 1, (int) ((x - pr.left) / cw)));
        int row = Math.max(0, Math.min(rows - 1, (int) ((y - pr.top - page.getPaddingTop()) / rh)));
        List<AppCell> others = new ArrayList<>(pageCells);
        others.remove(dragCell);
        int t = Math.max(0, Math.min(others.size(), row * cols + col));
        if (t == iconTarget) return;
        iconTarget = t;
        for (int i = 0; i < others.size(); i++) {
            AppCell c = others.get(i);
            int from = pageCells.indexOf(c), to = i < t ? i : i + 1;
            c.animate().translationX((to % cols - from % cols) * cw).translationY((to / cols - from / cols) * rh)
                    .setDuration(180).start();
        }
    }

    private void iconDragTouch(MotionEvent ev) {
        float x = ev.getRawX(), y = ev.getRawY();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                moveShadow(x, y);
                if (Math.hypot(x - dragStartX, y - dragStartY) > dp(10)) {
                    if (!dragMoved) { dragMoved = true; stopJiggle(); dragCell.setAlpha(0f); }
                }
                if (paged()) appEdgeHold(x);
                if (dragMoved && paged()) {
                    AppCell t = dragCell.entry.kind == Entry.APP && !hit(dock, x, y) ? cellUnder(x, y) : null;
                    setMergeTarget(t);
                    if (t != null) { /* hovering an icon: hold still so a folder can be made */ }
                    else if (hit(dock, x, y)) resetPageCells();
                    else if (pager.current() >= firstAppPage()) reflowIcons(x, y);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                finishIconDrag(x, y, ev.getActionMasked() == MotionEvent.ACTION_UP);
                break;
        }
    }

    private int appEdgeDir;
    private final Runnable appEdgeFlip = () -> {
        int target = pager.current() + appEdgeDir;
        if (appEdgeDir != 0 && target >= firstAppPage() && target < pager.getChildCount()) {
            pager.setPage(target, true);
            root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            main.postDelayed(this.appEdgeFlipRepeat, 900);
        }
    };
    private final Runnable appEdgeFlipRepeat = () -> { if (iconDragging && appEdgeDir != 0) appEdgeFlip.run(); };

    private void appEdgeHold(float x) {
        int dir = x < dp(28) ? -1 : x > root.getWidth() - dp(28) ? 1 : 0;
        if (dir == appEdgeDir) return;
        appEdgeDir = dir;
        main.removeCallbacks(appEdgeFlip);
        main.removeCallbacks(appEdgeFlipRepeat);
        if (dir != 0) main.postDelayed(appEdgeFlip, 650);
    }

    private void removeShadow() {
        if (dragShadow != null) root.removeView(dragShadow);
        dragShadow = null;
    }

    /** Glides the dragged icon's shadow to a screen point, then runs end. */
    private void settleShadow(float cx, float cy, Runnable end) {
        if (dragShadow == null) { end.run(); return; }
        int[] rl = new int[2];
        root.getLocationOnScreen(rl);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) dragShadow.getLayoutParams();
        dragShadow.animate()
                .translationX(cx - rl[0] - root.getPaddingLeft() - lp.width / 2f)
                .translationY(cy - rl[1] - root.getPaddingTop() - lp.height / 2f)
                .scaleX(1f).scaleY(1f).setDuration(190)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                .withEndAction(end).start();
    }

    private void finishIconDrag(float x, float y, boolean released) {
        iconDragging = false;
        appEdgeDir = 0;
        main.removeCallbacks(appEdgeFlip);
        main.removeCallbacks(appEdgeFlipRepeat);
        AppCell c = dragCell;
        if (c == null) { removeShadow(); return; }
        Entry e = c.entry;
        if (!dragMoved) { // a long press without moving: the item's menu
            removeShadow();
            c.setAlpha(1f);
            dragCell = null;
            dropTempCell(false);
            if (released) { if (e.kind == Entry.FOLDER) folderMenu(e); else showAppMenu(e); }
            return;
        }
        boolean folder = e.kind == Entry.FOLDER;
        List<String> favs = prefs.favorites();
        Runnable commit;
        float destX, destY;
        AppCell merge = mergeTarget;
        setMergeTarget(null);
        if (merge != null && released && !folder && paged()) { // dropped on another icon: they become a folder
            android.graphics.RectF tr = screenRect(merge.icon);
            destX = tr.centerX();
            destY = tr.centerY();
            Entry target = merge.entry;
            boolean fromDock = dragFromDock;
            commit = () -> mergeInto(e, target, fromDock);
        } else if (paged() && hit(dock, x, y) && !folder) {
            int cols = dockCount();
            int[] dl = new int[2];
            dock.getLocationOnScreen(dl);
            float cellW = dock.getWidth() / (float) cols;
            int pos = Math.max(0, Math.min(cols - 1, (int) ((x - dl[0]) / Math.max(1f, cellW))));
            destX = dl[0] + cellW * (pos + 0.5f);
            destY = dl[1] + dock.getHeight() / 2f;
            commit = () -> {
                favs.remove(e.key);
                favs.add(Math.min(pos, favs.size()), e.key);
                prefs.setFavorites(favs);
            };
        } else if (paged() && pager.current() >= firstAppPage()) {
            collectPageCells();
            List<AppCell> others = new ArrayList<>(pageCells == null ? new ArrayList<>() : pageCells);
            others.remove(c);
            int t = iconTarget < 0 ? others.size() : iconTarget;
            // where slot t is on screen (same geometry as the live reflow)
            View page = pager.getChildAt(pager.current());
            int cols = prefs.columns();
            int rows = page instanceof ViewGroup ? Math.max(1, ((ViewGroup) page).getChildCount()) : 1;
            android.graphics.RectF pr = screenRect(page);
            float cw = pr.width() / cols, rh = (pr.height() - page.getPaddingTop() - page.getPaddingBottom()) / rows;
            int slot = Math.min(t, rows * cols - 1);
            destX = pr.left + cw * (slot % cols + 0.5f);
            destY = pr.top + page.getPaddingTop() + rh * (slot / cols) + Math.min(rh, c.getHeight()) / 2f;
            commit = () -> {
                List<String> order = new ArrayList<>(gridKeys);
                if (dragFromDock) {
                    favs.remove(e.key);
                    prefs.setFavorites(favs);
                }
                order.remove(e.key);
                int at;
                if (others.isEmpty()) at = order.size();
                else if (t < others.size()) at = order.indexOf(others.get(t).entry.key);
                else at = order.indexOf(others.get(others.size() - 1).entry.key) + 1;
                order.add(Math.max(0, Math.min(order.size(), at < 0 ? order.size() : at)), e.key);
                prefs.set("apporder", android.text.TextUtils.join("\n", order));
            };
        } else if (paged() && !folder && e.kind == Entry.APP && pager.current() < firstAppPage()
                && pager.current() < tileBoxes.size() && hit(pager.getChildAt(pager.current()), x, y)) {
            // dropped on a tile page: it becomes an app tile right where the finger let go
            int page = pager.current();
            LinearLayout box = tileBoxes.get(page);
            android.graphics.RectF cr = screenRect(Tiles.canvas(this, box));
            int width = box.getWidth() > 0 ? box.getWidth() : lastTileWidth;
            int colW = Tiles.colWidth(this, width), gap = Tiles.gap(this), pitch = Tiles.pitch(this, width);
            int col = Math.max(0, Math.min(Tiles.G - 1, (int) ((x - cr.left) / Math.max(1, colW + gap))));
            int row = Math.max(0, Math.round((y - cr.top) / (float) Math.max(1, pitch)) - 1);
            destX = cr.left + col * (colW + gap) + colW / 2f;
            destY = cr.top + row * pitch + pitch;
            commit = () -> {
                tempCell = null; // a tile is a copy: the app stays in its folder
                if (dragFromDock) { favs.remove(e.key); prefs.setFavorites(favs); }
                List<List<String>> pages = Tiles.load(prefs);
                while (pages.size() <= page) pages.add(new ArrayList<>());
                pages.get(page).add("app:" + e.key + "|1|2|" + col + "|" + row);
                Tiles.save(prefs, pages);
                afterEdit(true);
            };
        } else { // dropped on the clock / tiles page: it flies back where it was
            android.graphics.RectF home0 = screenRect(c);
            settleShadow(home0.centerX(), home0.centerY(), () -> {
                c.setAlpha(1f);
                resetPageCells();
                removeShadow();
                dragCell = null;
                dropTempCell(false);
                startJiggle();
            });
            return;
        }
        settleShadow(destX, destY, () -> {
            commit.run();
            dropTempCell(true);
            markSynced();
            refreshMeta(); // the page is rebuilt underneath the shadow, so nothing flashes
            dragCell = null;
            root.post(() -> {
                removeShadow();
                refreshEdit();
            });
        });
    }

    private static boolean isInside(View v, View ancestor) {
        for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) if (p == ancestor) return true;
        return false;
    }

    /** Long-press in edit mode: lift whatever is under the finger. */
    private void editLongPress(float x, float y) {
        for (AppCell c : cells) {
            if (c.isShown() && hit(c, x, y)) { startIconDrag(c); return; }
        }
        for (int p = 0; p < tileViews.size(); p++)
            for (int i = 0; i < tileViews.get(p).size(); i++)
                if (hit(tileViews.get(p).get(i), x, y)) {
                    showTileResizer(p, i);
                    armTileDrag();
                    return;
                }
    }

    // ------------------------------------------------------------------ edit mode

    private android.graphics.drawable.GradientDrawable dashed() {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(Ui.alpha(prefs.ink(), 0x10));
        g.setCornerRadius(dp(16));
        g.setStroke(dp(2), Ui.alpha(prefs.ink(), 0xB3), dp(8), dp(5));
        return g;
    }

    private TextView pill(String label, boolean filled, Runnable r) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(filled ? 0xFF111315 : 0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), 0, dp(16), 0);
        t.setBackground(Ui.ripple(this, Ui.round(filled ? 0xFFFFFFFF : 0x26FFFFFF, dp(20))));
        t.setOnClickListener(v -> r.run());
        return t;
    }

    private void buildOverlays() {
        // --- edit mode: slim top row (+ and Done), pages overview, size badge
        editOverlay = new FrameLayout(this);
        editOverlay.setVisibility(View.GONE);
        editBar = new LinearLayout(this);
        editBar.setGravity(Gravity.CENTER_VERTICAL);
        TextView add = pill("＋", false, this::addMenu);
        add.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        add.setBackground(Ui.ripple(this, Ui.round(0xD92A2C31, dp(22))));
        add.setContentDescription("Add or change");
        editBar.addView(add, new LinearLayout.LayoutParams(dp(48), dp(44)));
        editBar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView pagesBtn = pill("Pages", false, this::togglePages);
        pagesBtn.setContentDescription("Add, remove or choose home page");
        LinearLayout.LayoutParams pbl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        pbl.setMarginEnd(dp(10));
        editBar.addView(pagesBtn, pbl);
        TextView done = pill("Done", true, this::exitEdit);
        done.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        editBar.addView(done, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        tl.setMargins(dp(16), dp(2), dp(16), 0);
        editOverlay.addView(editBar, tl);

        stripScroll = new android.widget.HorizontalScrollView(this);
        stripScroll.setHorizontalScrollBarEnabled(false);
        stripScroll.setBackground(Ui.round(0xF21C1E22, dp(28)));
        stripScroll.setElevation(dp(12));
        stripScroll.setVisibility(View.GONE);
        pageStrip = new LinearLayout(this);
        pageStrip.setPadding(dp(14), dp(14), dp(14), dp(12));
        stripScroll.addView(pageStrip);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        sl.setMargins(dp(16), 0, dp(16), 0);
        editOverlay.addView(stripScroll, sl);

        sizeBadge = new TextView(this);
        sizeBadge.setTextColor(0xFFFFFFFF);
        sizeBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        sizeBadge.setPadding(dp(18), dp(10), dp(18), dp(10));
        sizeBadge.setBackground(Ui.round(0xE61C1E22, dp(22)));
        sizeBadge.setVisibility(View.GONE);
        editOverlay.addView(sizeBadge, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.addView(editOverlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // --- full-screen theme preview bar
        previewBar = new LinearLayout(this);
        previewBar.setOrientation(LinearLayout.VERTICAL);
        previewBar.setBackground(Ui.round(0xEB1C1E22, dp(26)));
        previewBar.setPadding(dp(10), dp(10), dp(10), dp(12));
        previewBar.setElevation(dp(10));
        previewBar.setVisibility(View.GONE);
        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView prev = pill("‹", false, () -> stepPreview(-1));
        prev.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        nameRow.addView(prev, new LinearLayout.LayoutParams(dp(52), dp(44)));
        previewName = new TextView(this);
        previewName.setTextColor(0xFFFFFFFF);
        previewName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        previewName.setGravity(Gravity.CENTER);
        nameRow.addView(previewName, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView next = pill("›", false, () -> stepPreview(1));
        next.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        nameRow.addView(next, new LinearLayout.LayoutParams(dp(52), dp(44)));
        previewBar.addView(nameRow);
        LinearLayout btns = new LinearLayout(this);
        btns.setPadding(0, dp(10), 0, 0);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, dp(46), 1f);
        bl.setMargins(dp(4), 0, dp(4), 0);
        btns.addView(pill("Cancel", false, this::cancelPreview), bl);
        LinearLayout.LayoutParams bl2 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        bl2.setMargins(dp(4), 0, dp(4), 0);
        btns.addView(pill("Apply", true, this::applyPreview), bl2);
        previewBar.addView(btns);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        pl.setMargins(dp(12), 0, dp(12), dp(12));
        root.addView(previewBar, pl);

        editGestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override
            public void onLongPress(MotionEvent e) {
                if (!scaling) editLongPress(e.getRawX(), e.getRawY());
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (scaling) return false;
                String k = elementAt(e.getRawX(), e.getRawY());
                if (k != null) editElement(k);
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || scaling || !paged()) return false;
                float dx = e2.getRawX() - e1.getRawX(), dy = e2.getRawY() - e1.getRawY();
                if (Math.abs(dx) > dp(50) && Math.abs(dx) > Math.abs(dy)) {
                    pager.setPage(pager.current() + (dx < 0 ? 1 : -1), true);
                    return true;
                }
                return false;
            }
        });
        scaler = new android.view.ScaleGestureDetector(this, new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(android.view.ScaleGestureDetector d) {
                scaleKey = elementAt(d.getFocusX(), d.getFocusY());
                if (scaleKey == null || scaleKey.equals("dots") || scaleKey.startsWith("tile:")) return false;
                scaling = true;
                scaleStart = sizeOf(scaleKey);
                scaleF = 1f;
                scaleVal = (int) scaleStart;
                return true;
            }

            @Override
            public boolean onScale(android.view.ScaleGestureDetector d) {
                scaleF *= d.getScaleFactor();
                scaleVal = clampSize(scaleKey, Math.round(scaleStart * scaleF));
                liveSize(scaleKey, scaleVal);
                sizeBadge.setText(sizeLabel(scaleKey, scaleVal));
                sizeBadge.setVisibility(View.VISIBLE);
                return true;
            }

            @Override
            public void onScaleEnd(android.view.ScaleGestureDetector d) {
                commitSize(scaleKey, scaleVal);
                sizeBadge.setVisibility(View.GONE);
                main.postDelayed(() -> scaling = false, 150);
            }
        });
    }

    // jiggle: one animator rocks every editable view a little, each with its own phase
    private android.animation.ValueAnimator jiggle;
    private final List<View> jigglers = new ArrayList<>();
    private boolean hintShown;

    void enterEdit() {
        if (editMode) return;
        if (previewThemes != null) return;
        if (drawerOpen) closeDrawer(false);
        editMode = true;
        if (paged() && snap != null) rebuildDock(); // empty dock slots show "+"
        pager.setPage(pager.current(), false); // edit mode handles touches itself: settle the pager on a page first
        editOverlay.setVisibility(View.VISIBLE);
        stripScroll.setVisibility(View.GONE);
        root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        // make room for the + / Done row without shrinking anything
        dots.setVisibility(View.VISIBLE);
        dots.setBackground(Ui.round(0x66000000, dp(9)));
        refreshEdit();
        if (!hintShown) {
            hintShown = true;
            sizeBadge.setText("Tap an item to edit · drag edges to resize · Pages to add, remove or pick home");
            sizeBadge.setVisibility(View.VISIBLE);
            main.postDelayed(() -> { if (!scaling) sizeBadge.setVisibility(View.GONE); }, 2600);
        }
    }

    private void exitEdit() {
        if (!editMode) return;
        hideResizer();
        editMode = false;
        if (paged() && snap != null) rebuildDock();
        editOverlay.setVisibility(View.GONE);
        stopJiggle();
        clearOutlines();
        dots.setBackground(null);
        applyStyle(); // restores padding and dot visibility
    }

    private void addMenu() {
        Sheet s = editSheet("Add or change");
        s.item("Add widget", () -> widgets.showPicker());
        s.item("Add tile", this::addTileSheet);
        s.item("Organise apps into folders (Social, Movies…)", this::organiseIntoFolders);
        s.item(Ui.isLight(prefs.ink()) ? "Switch theme to light" : "Switch theme to dark", () -> setAppearance(Ui.isLight(prefs.ink()) ? "light" : "dark"));
        s.item("Widget Studio (customise its widgets)", () -> safeStart(new Intent().setClassName("com.widgetstudio", "com.widgetstudio.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        s.item("Show or hide elements", this::elementsSheet);
        s.item("Pages: add, delete or choose home", this::togglePages);
        if (paged()) s.item("Delete this page", () -> deletePage(pager.current()));
        s.item("Wallpaper", () -> WallpaperPicker.showMenu(this));
        s.item("Theme", () -> startActivity(new Intent(this, SettingsActivity.class).putExtra("page", "themes")));
        s.item("Launcher settings", () -> startActivity(new Intent(this, SettingsActivity.class)));
        s.show();
    }

    private void togglePages() {
        boolean show = stripScroll.getVisibility() != View.VISIBLE;
        stripScroll.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) rebuildStrip();
    }

    private void stopJiggle() {
        if (jiggle != null) jiggle.cancel();
        jiggle = null;
        for (View v : jigglers) v.setRotation(0);
        jigglers.clear();
    }

    private void startJiggle() {
        stopJiggle();
        for (List<View> l : tileViews) jigglers.addAll(l);

        jigglers.addAll(cells);
        if (jigglers.isEmpty()) return;
        jiggle = android.animation.ValueAnimator.ofFloat(0f, 1f);
        jiggle.setDuration(300);
        jiggle.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        jiggle.setInterpolator(new android.view.animation.LinearInterpolator());
        View held = rzView();
        jiggle.addUpdateListener(a -> {
            float t = (Float) a.getAnimatedValue();
            for (int i = 0; i < jigglers.size(); i++) {
                View v = jigglers.get(i);
                if (v == held || !v.isShown()) continue;
                boolean big = v.getWidth() > dp(140);
                float amp = big ? 0.35f : 1.4f;
                v.setRotation((float) (amp * Math.sin(2 * Math.PI * (t + (i % 5) * 0.21))));
            }
        });
        jiggle.start();
    }

    private void clearOutlines() {
        for (View v : outlined) v.setForeground(null);
        outlined.clear();
    }

    /** Faint frame, plus a remove badge in the corner for things that can be removed. */
    private void outline(View v) { outline(v, false); }

    private void outline(View v, boolean removable) {
        if (v == null) return;
        v.setForeground(new EditMark(prefs.ink(), removable, dp(1), dp(11)));
        outlined.add(v);
    }

    static final class EditMark extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint frame = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint badge = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint minus = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final boolean removable;
        private final int r;

        EditMark(int ink, boolean removable, int stroke, int r) {
            this.removable = removable;
            this.r = r;
            frame.setStyle(android.graphics.Paint.Style.STROKE);
            frame.setStrokeWidth(stroke);
            frame.setColor(Ui.alpha(ink, 0x40));
            badge.setColor(0xF0505357);
            minus.setColor(0xFFFFFFFF);
            minus.setStrokeWidth(stroke * 2.2f);
            minus.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        }

        @Override
        public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            c.drawRoundRect(b.left + 1, b.top + 1, b.right - 1, b.bottom - 1, r * 1.6f, r * 1.6f, frame);
            if (removable) {
                float cx = b.left + r + 2, cy = b.top + r + 2;
                c.drawCircle(cx, cy, r, badge);
                c.drawLine(cx - r * 0.45f, cy, cx + r * 0.45f, cy, minus);
            }
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** Marks every editable element, restarts the jiggle, and refreshes the pages overview. */
    private void refreshEdit() {
        if (!editMode) return;
        clearOutlines();
        if (clockBox.getVisibility() == View.VISIBLE) outline(clockBox);
        for (List<View> l : tileViews) for (View v : l) outline(v, true);

        if (searchPill.getVisibility() == View.VISIBLE) outline(searchPill);
        if (paged()) {
            outline(dock);
        } else {
            outline(favColumn);
        }
        startJiggle();
        if (stripScroll.getVisibility() == View.VISIBLE) pager.post(this::rebuildStrip);
    }

    /** True if (x, y) is on the remove badge of view v. */
    private boolean onBadge(View v, float x, float y) {
        if (v == null || !v.isShown()) return false;
        int[] l = new int[2];
        v.getLocationOnScreen(l);
        float cx = l[0] + dp(13), cy = l[1] + dp(13);
        return Math.hypot(x - cx, y - cy) < dp(22);
    }

    private void rebuildStrip() {
        if (!editMode) return;
        pageStrip.removeAllViews();
        int n = paged() ? pager.getChildCount() : 1, tilesN = 1 + tilePages.size();
        int tw = dp(50), th = dp(88);
        for (int i = 0; i < n; i++) {
            View page = paged() ? pager.getChildAt(i) : home;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            FrameLayout frame = new FrameLayout(this);
            boolean cur = !paged() || i == pager.current();
            android.graphics.drawable.GradientDrawable fb = Ui.round(prefs.solidBg() ? prefs.i(Prefs.BG_COLOR) | 0xFF000000 : 0xFF2A2C31, dp(10));
            fb.setStroke(dp(cur ? 2 : 1), cur ? 0xFFFFFFFF : 0x55FFFFFF);
            frame.setBackground(fb);
            frame.setPadding(dp(3), dp(3), dp(3), dp(3));
            if (page != null && page.getWidth() > 0 && page.getHeight() > 0) {
                try {
                    android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(tw, th, android.graphics.Bitmap.Config.ARGB_8888);
                    android.graphics.Canvas cv = new android.graphics.Canvas(b);
                    cv.scale(tw / (float) page.getWidth(), th / (float) page.getHeight());
                    page.draw(cv);
                    ImageView iv = new ImageView(this);
                    iv.setImageBitmap(b);
                    frame.addView(iv, new FrameLayout.LayoutParams(tw, th));
                } catch (Throwable ignored) { }
            }
            final int idx = i;
            frame.setOnClickListener(v -> { if (paged()) pager.setPage(idx, true); });
            boolean isHome = paged() && (i == homePage() || (i >= tilesN && homePage() >= tilesN && i == firstAppPage()));
            if (paged() && i <= firstAppPage() && pager.shown(i)) {
                TextView hm = new TextView(this);
                hm.setText("⌂");
                hm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                hm.setGravity(Gravity.CENTER);
                hm.setTextColor(isHome ? 0xFF1C1E22 : 0xFFFFFFFF);
                hm.setBackground(Ui.round(isHome ? 0xFFFFFFFF : 0x66000000, dp(11)));
                hm.setContentDescription("Make this the home screen");
                hm.setOnClickListener(v -> setHomePage(idx));
                frame.addView(hm, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP | Gravity.START));
            }
            boolean hidden = paged() && !pager.shown(i);
            if (hidden) {
                frame.setAlpha(0.4f);
                frame.setOnClickListener(v -> restorePage(idx));
            }
            if (paged() && (hidden || i <= firstAppPage())) {
                TextView x = new TextView(this);
                x.setText("✕");
                x.setTextColor(0xFFFFFFFF);
                x.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                x.setGravity(Gravity.CENTER);
                x.setBackground(Ui.round(0xFFE5484D, dp(11)));
                if (hidden) {
                    x.setText("↺");
                    x.setBackground(Ui.round(0xFF34C759, dp(11)));
                    x.setContentDescription("Put this page back");
                    x.setOnClickListener(v -> restorePage(idx));
                } else {
                    x.setContentDescription("Delete this page");
                    x.setOnClickListener(v -> deletePage(idx));
                }
                frame.addView(x, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP | Gravity.END));
            }
            item.addView(frame, new LinearLayout.LayoutParams(tw + dp(6), th + dp(6)));
            TextView lab = new TextView(this);
            lab.setText(!paged() ? "Home" : hidden ? "Removed · tap ↺" : isHome ? "Home ⌂" : i == 0 ? "Today" : i < tilesN ? "Page " + (i + 1) : "Apps");
            if (paged() && i <= firstAppPage() && !hidden) lab.setOnClickListener(v -> setHomePage(idx));
            if (paged() && i > firstAppPage()) { item.setVisibility(View.GONE); } // the app pages are shown as one "Apps" item
            lab.setTextColor(0xCCFFFFFF);
            lab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            lab.setPadding(0, dp(4), 0, 0);
            item.addView(lab);
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            il.setMarginEnd(dp(10));
            pageStrip.addView(item, il);
        }
        if (paged()) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView plus = new TextView(this);
            plus.setText("＋");
            plus.setTextColor(0xFFFFFFFF);
            plus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
            plus.setGravity(Gravity.CENTER);
            android.graphics.drawable.GradientDrawable pb = Ui.round(0x1AFFFFFF, dp(10));
            pb.setStroke(dp(1), 0x55FFFFFF, dp(6), dp(4));
            plus.setBackground(pb);
            plus.setContentDescription("Add page");
            plus.setOnClickListener(v -> addTilePage());
            item.addView(plus, new LinearLayout.LayoutParams(tw + dp(6), th + dp(6)));
            TextView lab = new TextView(this);
            lab.setText("Add page");
            lab.setTextColor(0xCCFFFFFF);
            lab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            lab.setPadding(0, dp(4), 0, 0);
            item.addView(lab);
            pageStrip.addView(item);
        }
    }

    private void addTilePage() {
        List<List<String>> pages = Tiles.load(prefs);
        pages.add(new ArrayList<>());
        Tiles.save(prefs, pages);
        afterEdit(true);
        pager.setPage(pages.size() - 1, true);
        Ui.toast(this, "Page added. Use Add tile to fill it.");
    }

    private void removeTilePage(int page) {
        List<List<String>> pages = Tiles.load(prefs);
        if (page < 1 || page >= pages.size()) return;
        int n = pages.get(page).size();
        Sheet s = editSheet(n == 0 ? "Remove this empty page?" : "Remove this page and its " + n + " tile" + (n == 1 ? "" : "s") + "?");
        s.item("Remove page", () -> {
            String h = prefs.str("homePage", "apps");
            if (h.equals(String.valueOf(page))) prefs.set("homePage", "apps");
            else try { if (Integer.parseInt(h) > page) prefs.set("homePage", String.valueOf(Integer.parseInt(h) - 1)); } catch (Exception ignored) { }
            pages.remove(page);
            Tiles.save(prefs, pages);
            afterEdit(true);
            pager.setPage(page - 1, true);
        });
        s.item("Keep it", () -> { });
        s.show();
    }

    /** Which editable element is under a screen point. */
    private String elementAt(float x, float y) {
        for (int p = 0; p < tileViews.size(); p++)
            for (int i = 0; i < tileViews.get(p).size(); i++)
                if (onBadge(tileViews.get(p).get(i), x, y)) return "remove-tile:" + p + ":" + i;
        for (int i = 0; i < widgets.slotCount(); i++)
            if (onBadge(widgets.slotView(i), x, y)) return "remove-widget:" + i;
        if (hit(searchPill, x, y)) return "search";
        if (paged() && hit(dock, x, y)) return "dock";
        if (paged() && hit(dots, x, y)) return "dots";
        for (int p = 0; p < tileViews.size(); p++)
            for (int i = 0; i < tileViews.get(p).size(); i++)
                if (hit(tileViews.get(p).get(i), x, y)) return "tile:" + p + ":" + i;
        for (int i = 0; i < widgets.slotCount(); i++)
            if (hit(widgets.slotView(i), x, y)) return "widget:" + i;
        if (clockBox.getVisibility() == View.VISIBLE && hit(clockBox, x, y)) return "clock";
        if (paged() && pager.current() >= 1 + tilePages.size() && hit(pager, x, y)) return "grid";
        if (!paged() && hit(favColumn, x, y)) return "grid";
        return null;
    }

    private void editElement(String k) {
        if (k.equals("clock")) clockSheet();
        else if (k.equals("search")) searchSheet();
        else if (k.equals("dock")) dockSheet();
        else if (k.equals("grid")) gridSheet();
        else if (k.equals("dots")) togglePages();
        else if (k.startsWith("remove-widget:")) {
            int[] at = widgetItem(widgets.firstIdOf(Integer.parseInt(k.substring(14))));
            if (at != null) removeTile(at[0], at[1]);
        }
        else if (k.startsWith("remove-tile:")) {
            String[] p = k.split(":");
            removeTile(Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        }
        else if (k.startsWith("widget:")) editWidget(Integer.parseInt(k.substring(7)));
        else if (k.startsWith("tile:")) {
            String[] p = k.split(":");
            showTileResizer(Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        }
    }

    // ---- pinch sizes ----

    private float sizeOf(String k) {
        if (k.equals("clock")) return prefs.i(Prefs.CLOCK_SIZE);
        if (k.equals("search")) return prefs.i(Prefs.SEARCH_H);
        if (k.equals("dock")) return prefs.i(Prefs.DOCK_ICON);
        if (k.equals("grid")) return prefs.iconDp();
        if (k.startsWith("widget:")) return widgets.heightOf(Integer.parseInt(k.substring(7)));
        return 0;
    }

    private int clampSize(String k, int v) {
        if (k.equals("clock")) return Math.max(24, Math.min(180, v));
        if (k.equals("search")) return Math.max(32, Math.min(84, v));
        if (k.equals("dock")) return Math.max(32, Math.min(88, v));
        if (k.equals("grid")) return Math.max(28, Math.min(76, v));
        if (k.startsWith("widget:")) return Math.max(dp(48), Math.min(getResources().getDisplayMetrics().heightPixels, v));
        return v;
    }

    private String sizeLabel(String k, int v) {
        if (k.equals("clock")) return "Clock · " + v + " sp";
        if (k.equals("search")) return "Search bar · " + v + " dp";
        if (k.equals("dock")) return "Dock icons · " + v + " dp";
        if (k.equals("grid")) return "App icons · " + v + " dp";
        return "Widget · " + Math.round(v / getResources().getDisplayMetrics().density) + " dp";
    }

    private void liveSize(String k, int v) {
        if (k.equals("clock")) clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, v);
        else if (k.equals("search")) { searchPill.getLayoutParams().height = dp(v); searchPill.requestLayout(); }
        else if (k.equals("dock")) { float f = v / scaleStart; dock.setScaleX(f); dock.setScaleY(f); }
        else if (k.equals("grid")) {
            View pg = paged() ? pager.getChildAt(pager.current()) : favColumn;
            float f = (v + 12f) / (scaleStart + 12f);
            if (pg != null) { pg.setScaleX(f); pg.setScaleY(f); }
        } else if (k.startsWith("widget:")) widgets.setHeight(Integer.parseInt(k.substring(7)), v, false);
    }

    private void commitSize(String k, int v) {
        if (k == null) return;
        dock.setScaleX(1f);
        dock.setScaleY(1f);
        View pg = paged() ? pager.getChildAt(pager.current()) : favColumn;
        if (pg != null) { pg.setScaleX(1f); pg.setScaleY(1f); }
        if (k.equals("clock")) { prefs.set(Prefs.CLOCK_SIZE, v); afterEdit(false); }
        else if (k.equals("search")) { prefs.set(Prefs.SEARCH_H, v); afterEdit(false); }
        else if (k.equals("dock")) { prefs.set(Prefs.DOCK_ICON, v); afterEdit(true); }
        else if (k.equals("grid")) { prefs.set(Prefs.ICON, v); afterEdit(true); }
        else if (k.startsWith("widget:")) { widgets.setHeight(Integer.parseInt(k.substring(7)), v, true); refreshEdit(); }
    }

    /** Saves the change into the active theme and redraws (full = rebuild grid, dock, tiles). */
    private void afterEdit(boolean full) {
        prefs.syncActiveTheme(this);
        markSynced();
        if (full) applyStyle();
        else { applyClock(); applySearch(); refreshEdit(); }
    }

    // ---- edit sheets ----

    private Sheet editSheet(String title) { return new Sheet(this, title, null).dim(0.05f).cap(0.45f); }

    private View chipsView(String title, String[] labels, String[] values, String cur, java.util.function.Consumer<String> pick) {
        return Ios.of(this).choice(this, title, labels, values, cur, pick);
    }

    private View sliderView(String title, int min, int max, int val, String unit,
                            java.util.function.IntConsumer live, java.util.function.IntConsumer commit) {
        return Ios.of(this).slider(this, title, min, max, val, unit, live, commit);
    }

    private void setS(String k, String v, boolean full) { prefs.set(k, v); afterEdit(full); }

    private void setI(String k, int v, boolean full) { prefs.set(k, v); afterEdit(full); }

    private void clockSheet() {
        Sheet s = editSheet("Clock & date");
        s.view(sliderView("Clock size", 24, 180, prefs.i(Prefs.CLOCK_SIZE), " sp",
                v -> clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, v), v -> setI(Prefs.CLOCK_SIZE, v, false)));
        s.view(chipsView("Clock font", new String[]{"Theme", "Thin", "Light", "Regular", "Medium", "Bold", "Condensed", "Serif", "Mono"},
                new String[]{"theme", "sans-serif-thin", "sans-serif-light", "sans-serif", "sans-serif-medium", "bold",
                        "sans-serif-condensed", "serif", "monospace"}, prefs.s(Prefs.CLOCK_FONT), v -> setS(Prefs.CLOCK_FONT, v, false)));
        s.view(chipsView("Alignment", new String[]{"Left", "Center", "Right"}, new String[]{"start", "center", "end"},
                prefs.s(Prefs.CLOCK_ALIGN), v -> setS(Prefs.CLOCK_ALIGN, v, false)));
        s.view(chipsView("Format", new String[]{"Phone setting", "12-hour", "24-hour"}, new String[]{"system", "12", "24"},
                prefs.s(Prefs.CLOCK_FORMAT), v -> setS(Prefs.CLOCK_FORMAT, v, false)));
        s.view(chipsView("Seconds", new String[]{"Off", "On"}, new String[]{"off", "on"},
                prefs.s(Prefs.CLOCK_SECONDS), v -> setS(Prefs.CLOCK_SECONDS, v, false)));
        s.view(chipsView("Color", new String[]{"Text color", "Accent", "White", "Black"}, new String[]{"text", "accent", "white", "black"},
                prefs.s(Prefs.CLOCK_COLOR), v -> setS(Prefs.CLOCK_COLOR, v, false)));
        s.view(chipsView("Date", new String[]{"Long", "Short", "Numbers", "Hidden"}, new String[]{"long", "short", "numeric", "none"},
                prefs.s(Prefs.DATE_STYLE), v -> setS(Prefs.DATE_STYLE, v, false)));
        s.view(sliderView("Date size", 10, 36, prefs.i(Prefs.DATE_SIZE), " sp",
                v -> date.setTextSize(TypedValue.COMPLEX_UNIT_SP, v), v -> setI(Prefs.DATE_SIZE, v, false)));
        s.item("More clock fonts…", () -> {
            Sheet f = editSheet("Clock font");
            for (String[] ft : FontList.ALL)
                f.option(ft[1], Ui.typeface(this, ft[0], Typeface.NORMAL), ft[0].equals(prefs.s(Prefs.CLOCK_FONT)),
                        () -> setS(Prefs.CLOCK_FONT, ft[0], false));
            f.show();
        });
        s.item("Hide clock & date", () -> setS(Prefs.CLOCK_SHOW, "off", true));
        s.show();
    }

    private void searchSheet() {
        Sheet s = editSheet("Search bar");
        s.view(chipsView("Search bar", new String[]{"Show", "Remove"}, new String[]{"on", "off"},
                prefs.showSearchBar() ? "on" : "off", v -> { prefs.set(Prefs.SEARCHBAR, true); setS(Prefs.SEARCH_SHOW, v, true); }));
        s.view(chipsView("Position", new String[]{"Above dock", "Top of screen"}, new String[]{"bottom", "top"},
                prefs.s(Prefs.SEARCH_POS), v -> setS(Prefs.SEARCH_POS, v, true)));
        s.view(sliderView("Height", 32, 84, prefs.i(Prefs.SEARCH_H), " dp",
                v -> { searchPill.getLayoutParams().height = dp(v); searchPill.requestLayout(); },
                v -> setI(Prefs.SEARCH_H, v, false)));
        s.view(chipsView("Shape", new String[]{"Pill", "Rounded", "Square", "Text"}, new String[]{"pill", "rounded", "square", "text"},
                prefs.s(Prefs.SEARCH_SHAPE), v -> setS(Prefs.SEARCH_SHAPE, v, false)));
        s.view(sliderView("Transparency", 0, 70, prefs.i(Prefs.SEARCH_OPACITY), "%", null, v -> setI(Prefs.SEARCH_OPACITY, v, false)));
        EditText et = new EditText(this);
        et.setText(prefs.str(Prefs.SEARCH_TEXT, "Search apps and web"));
        et.setSingleLine(true);
        Ios io = Ios.of(this);
        et.setTextColor(io.text);
        et.setHint("Placeholder text");
        et.setHintTextColor(io.sub);
        et.setBackground(Ui.round(io.fill, dp(10)));
        et.setPadding(dp(14), dp(10), dp(14), dp(10));
        s.view(et);
        s.item("Save placeholder text", () -> {
            prefs.set(Prefs.SEARCH_TEXT, et.getText().toString().trim().isEmpty() ? "Search apps and web" : et.getText().toString().trim());
            afterEdit(false);
        });
        s.show();
    }

    private void dockSheet() {
        Sheet s = editSheet("Dock");
        s.view(sliderView("Icon size", 32, 88, prefs.i(Prefs.DOCK_ICON), " dp", null, v -> setI(Prefs.DOCK_ICON, v, true)));
        s.view(chipsView("Background", new String[]{"Glass", "None"}, new String[]{"on", "off"},
                prefs.s(Prefs.DOCK_BG), v -> setS(Prefs.DOCK_BG, v, true)));
        s.view(chipsView("Dock size", new String[]{"3", "4", "5", "6", "7"}, new String[]{"3", "4", "5", "6", "7"},
                String.valueOf(dockCount()), v -> { prefs.set("dockCount", v); afterEdit(true); rebuildDock(); }));
        int n = snap == null ? 0 : Math.min(dockCount(), snap.favorites.size());
        for (int i = 0; i < n; i++) {
            Entry e = snap.favorites.get(i);
            final int at = i;
            s.item(e.icon, e.label + "  ·  tap to change", () -> dockAppSheet(e, at), null);
        }
        if (n < 7) s.item("＋  Add app to the dock", this::addToDock);
        s.note("Drag an app onto the dock to put it there, or drag a dock app back to the pages to take it out.");
        s.show();
    }

    private void dockAppSheet(Entry e, int at) {
        Sheet s = editSheet(e.label + " in the dock");
        s.item("Change to another app…", () -> pickApp("Replace " + e.label, key -> {
            List<String> favs = prefs.favorites();
            favs.remove(key);
            int i = favs.indexOf(e.key);
            if (i < 0) favs.add(Math.min(at, favs.size()), key); else favs.set(i, key);
            prefs.setFavorites(favs);
            dockChanged();
        }));
        if (at > 0) s.item("Move left", () -> { prefs.moveFavorite(e.key, -1); dockChanged(); });
        if (at < Math.min(dockCount(), prefs.favorites().size()) - 1) s.item("Move right", () -> { prefs.moveFavorite(e.key, 1); dockChanged(); });
        s.item("Remove from dock", () -> { prefs.removeFavorite(e.key); dockChanged(); });
        s.show();
    }

    /** Picks an app and puts it in the next dock slot; a full dock grows by one (up to 7). */
    private void addToDock() {
        pickApp("Add to the dock", key -> {
            List<String> favs = prefs.favorites();
            favs.remove(key);
            int n = snap == null ? 0 : Math.min(dockCount(), snap.favorites.size());
            if (n >= dockCount() && dockCount() < 7) prefs.set("dockCount", String.valueOf(dockCount() + 1));
            favs.add(Math.min(n, favs.size()), key);
            prefs.setFavorites(favs);
            dockChanged();
        });
    }

    private void dockChanged() { refreshMeta(); }

    private void gridSheet() {
        Sheet s = editSheet("App icons");
        s.view(sliderView("Icon size", 28, 76, prefs.iconDp(), " dp", null, v -> setI(Prefs.ICON, v, true)));
        s.view(chipsView("Labels", new String[]{"Show", "Hide"}, new String[]{"on", "off"},
                prefs.s(Prefs.GRID_LABELS), v -> setS(Prefs.GRID_LABELS, v, true)));
        s.view(chipsView("Icon style", new String[]{"Original", "Line icons", "Squircle", "Outline", "Glyph tile", "Duo tiles", "Vivid"},
                new String[]{IconStyler.ORIGINAL, IconStyler.LINE, IconStyler.SQUIRCLE, IconStyler.OUTLINE, IconStyler.TILE, IconStyler.DUO, IconStyler.VIVID},
                prefs.iconStyle(), v -> { setS(Prefs.ICON_STYLE, v, true); reload(); }));
        s.view(chipsView("Line weight", new String[]{"Thin", "Regular", "Bold"}, new String[]{"thin", "regular", "bold"},
                prefs.s(Prefs.ICON_WEIGHT), v -> { setS(Prefs.ICON_WEIGHT, v, true); reload(); }));
        s.view(chipsView("Icon background", new String[]{"Tile", "Frame", "None"}, new String[]{"on", "frame", "off"},
                prefs.s(Prefs.ICON_TILE), v -> { setS(Prefs.ICON_TILE, v, true); reload(); }));
        s.view(sliderView("Label size", 9, 18, prefs.i(Prefs.GRID_LABEL_SIZE), " sp", null, v -> setI(Prefs.GRID_LABEL_SIZE, v, true)));
        if (paged()) s.view(chipsView("Columns", new String[]{"3", "4", "5", "6"}, new String[]{"3", "4", "5", "6"},
                String.valueOf(prefs.columns()), v -> { prefs.set(Prefs.COLUMNS, Integer.parseInt(v)); afterEdit(true); }));
        s.item("Icon shape, style & pack…", () -> startActivity(new Intent(this, SettingsActivity.class).putExtra("page", "themes")));
        s.show();
    }

    private void toggleSheet(String title, String key) {
        Sheet s = editSheet(title);
        s.view(chipsView(title, new String[]{"Show", "Hide"}, new String[]{"on", "off"}, prefs.s(key), v -> setS(key, v, true)));
        s.show();
    }

    private void elementsSheet() {
        Sheet s = editSheet("Elements");
        s.view(chipsView("Clock & date", new String[]{"Show", "Hide"}, new String[]{"on", "off"},
                prefs.showClock() ? "on" : "off", v -> { prefs.set(Prefs.CLOCK, true); setS(Prefs.CLOCK_SHOW, v, true); }));
        s.view(chipsView("Search bar", new String[]{"Show", "Remove"}, new String[]{"on", "off"},
                prefs.showSearchBar() ? "on" : "off", v -> { prefs.set(Prefs.SEARCHBAR, true); setS(Prefs.SEARCH_SHOW, v, true); }));
        s.view(chipsView("Page dots", new String[]{"Show", "Hide"}, new String[]{"on", "off"}, prefs.s(Prefs.DOTS), v -> setS(Prefs.DOTS, v, true)));
        s.view(chipsView("Dock background", new String[]{"Glass", "None"}, new String[]{"on", "off"}, prefs.s(Prefs.DOCK_BG), v -> setS(Prefs.DOCK_BG, v, true)));
        s.view(chipsView("App labels", new String[]{"Show", "Hide"}, new String[]{"on", "off"}, prefs.s(Prefs.GRID_LABELS), v -> setS(Prefs.GRID_LABELS, v, true)));
        s.view(chipsView("Widget cards", new String[]{"None", "Glass", "Dark", "Surface"}, new String[]{"none", "glass", "dark", "surface"},
                prefs.s(Prefs.WIDGET_BG), v -> setS(Prefs.WIDGET_BG, v, true)));
        s.show();
    }

    private void widgetSheet(int slot) {
        Sheet s = editSheet("Widget");
        s.view(sliderView("Height", 48, 640, Math.round(widgets.heightOf(slot) / getResources().getDisplayMetrics().density), " dp",
                v -> widgets.setHeight(slot, dp(v), false), v -> { widgets.setHeight(slot, dp(v), true); refreshEdit(); }));
        s.view(chipsView("Card (all widgets)", new String[]{"None", "Glass", "Dark", "Surface"}, new String[]{"none", "glass", "dark", "surface"},
                prefs.s(Prefs.WIDGET_BG), v -> setS(Prefs.WIDGET_BG, v, true)));
        s.view(sliderView("Corner roundness", 0, 40, prefs.i(Prefs.WIDGET_RADIUS), " dp", null, v -> setI(Prefs.WIDGET_RADIUS, v, true)));
        s.item("More: move, stack, settings, remove…", () -> widgets.menu(slot));
        s.show();
    }

    private void addTileSheet() {
        int cur = paged() ? pager.current() : 0;
        int page = cur < 1 + tilePages.size() ? cur : 0;
        Sheet s = editSheet(page == 0 ? "Add tile" : "Add tile to this page");
        for (String[] t : Tiles.TYPES) s.item(t[1], () -> addTile(page, t[0]));
        s.item("App tile…", () -> pickApp("Choose an app", key -> addTile(page, "app:" + key)));
        s.show();
    }

    private void pickApp(String title, java.util.function.Consumer<String> done) {
        if (snap == null) return;
        Sheet s = new Sheet(this, title, null).cap(0.6f);
        for (Entry e : snap.apps) {
            if (e.hidden) continue;
            android.graphics.drawable.Drawable ic = e.icon != null && e.icon.getConstantState() != null
                    ? e.icon.getConstantState().newDrawable(getResources()) : null;
            s.item(ic, e.label, () -> done.accept(e.key), null);
        }
        s.show();
    }

    org.json.JSONObject listConfig(String id) {
        try { return new org.json.JSONObject(prefs.str("al:" + id, "{}")); } catch (Exception e) { return new org.json.JSONObject(); }
    }

    void saveListConfig(String id, org.json.JSONObject cfg) { prefs.set("al:" + id, cfg.toString()); }

    /** Checklist of every app: tap to add/remove; picks keep the order they were ticked in. */
    private void pickListApps(String id) {
        if (snap == null) return;
        org.json.JSONObject cfg = listConfig(id);
        List<String> chosen = new ArrayList<>();
        org.json.JSONArray a = cfg.optJSONArray("a");
        if (a != null) for (int i = 0; i < a.length(); i++) chosen.add(a.optString(i));
        Ios io = Ios.of(this);
        Sheet s = new Sheet(this, "Choose apps", null).cap(0.72f);
        TextView count = new TextView(this);
        count.setTextColor(io.sub);
        count.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        count.setPadding(dp(16), 0, dp(16), dp(6));
        count.setText(chosen.size() + " chosen · tap apps to add or remove");
        s.view(count);
        Runnable save = () -> {
            try { cfg.put("a", new org.json.JSONArray(chosen)); } catch (Exception ignored) { }
            saveListConfig(id, cfg);
            count.setText(chosen.size() + " chosen · tap apps to add or remove");
        };
        for (Entry e : snap.apps) {
            if (e.hidden) continue;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(8), dp(16), dp(8));
            row.setBackground(Ui.ripple(this, null));
            ImageView iv = new ImageView(this);
            if (e.icon != null && e.icon.getConstantState() != null) iv.setImageDrawable(e.icon.getConstantState().newDrawable(getResources()));
            row.addView(iv, new LinearLayout.LayoutParams(dp(32), dp(32)));
            TextView t = new TextView(this);
            t.setText(e.label);
            t.setTextColor(io.text);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            t.setPadding(dp(14), 0, 0, 0);
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView mark = new TextView(this);
            mark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            mark.setTextColor(io.tint);
            mark.setText(chosen.contains(e.key) ? "✓" : "");
            row.addView(mark);
            row.setOnClickListener(v -> {
                if (chosen.remove(e.key)) mark.setText("");
                else { chosen.add(e.key); mark.setText("✓"); }
                save.run();
            });
            s.view(row);
        }
        s.item("Sort chosen apps A–Z", () -> {
            chosen.sort((x, y) -> {
                Entry ex = snap.byKey.get(x), ey = snap.byKey.get(y);
                return Ui.norm(ex == null ? x : ex.label).compareTo(Ui.norm(ey == null ? y : ey.label));
            });
            save.run();
            afterEdit(true);
        });
        s.item("Done", () -> afterEdit(true));
        s.show();
    }

    /** Light / dark / auto / theme default, applied to the active theme right away. */
    void setAppearance(String mode) {
        prefs.set(Prefs.MODE, mode);
        org.json.JSONObject t = Themes.find(Themes.load(prefs), prefs.activeTheme());
        if (t != null) prefs.applyLook(t, this);
        markSynced();
        applyStyle();
        widgets.applyStyle();
        reload();
    }

    /** Sorts loose apps into category folders (Social, Movies & TV, …). Dock apps and the user's own folders stay. */
    void organiseIntoFolders() {
        if (snap == null) return;
        List<Folders.Folder> all = Folders.load(prefs);
        all.removeIf(f -> f.id.startsWith(Folders.AUTO)); // re-running replaces the previous automatic folders
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (Folders.Folder f : all) taken.addAll(f.keys);
        for (int i = 0; i < Math.min(dockCount(), snap.favorites.size()); i++) taken.add(snap.favorites.get(i).key);
        java.util.Map<String, String> roles = LineIcons.roles(this);
        java.util.Map<String, List<String>> groups = new java.util.LinkedHashMap<>();
        for (String n : new String[]{"Social", "Movies & TV", "Music", "Shopping", "Finance", "Photos", "Games", "Travel",
                "Health", "Reading", "Productivity", "Utilities"}) groups.put(n, new ArrayList<>());
        for (Entry e : snap.apps) {
            if (e.hidden || taken.contains(e.key) || e.component == null) continue;
            int cat = -1;
            try { cat = getPackageManager().getApplicationInfo(e.pkg, 0).category; } catch (Exception ignored) { }
            String kind = LineIcons.kindFor(e.component, e.origLabel, cat, roles);
            String folder = Folders.category(kind);
            if (folder == null) folder = "Utilities";
            groups.get(folder).add(e.key);
        }
        List<String> front = new ArrayList<>();
        int made = 0;
        for (java.util.Map.Entry<String, List<String>> g : groups.entrySet()) {
            if (g.getValue().size() < 2) continue; // a single app stays on its own
            Folders.Folder f = new Folders.Folder();
            f.id = Folders.AUTO + g.getKey().toLowerCase().replaceAll("[^a-z]", "");
            f.name = g.getKey();
            f.keys.addAll(g.getValue());
            all.add(f);
            front.add("f:" + f.id);
            made++;
        }
        Folders.save(prefs, all);
        List<String> order = new ArrayList<>(front); // the new folders lead the app pages
        for (String k : prefs.str("apporder", "").split("\n")) if (!k.isEmpty() && !order.contains(k)) order.add(k);
        prefs.set("apporder", android.text.TextUtils.join("\n", order));
        markSynced();
        rebuildFavorites();
        Ui.toast(this, made == 0 ? "Nothing to organise" : "Made " + made + " folders. Undo: Settings → Folders.");
    }

    void undoAutoFolders() {
        List<Folders.Folder> all = Folders.load(prefs);
        all.removeIf(f -> f.id.startsWith(Folders.AUTO));
        Folders.save(prefs, all);
        markSynced();
        rebuildFavorites();
        Ui.toast(this, "Automatic folders removed");
    }

    private void addTile(int page, String spec) {
        if (spec.equals("applist:pick")) {
            String id = java.util.UUID.randomUUID().toString().substring(0, 8);
            saveListConfig(id, new org.json.JSONObject());
            spec = "applist:pick:" + id;
            final String sp = spec;
            main.postDelayed(() -> pickListApps(id), 300);
        }
        List<List<String>> pages = Tiles.load(prefs);
        if (pages.get(0).isEmpty() && "minimal".equals(prefs.activeTheme()) && !prefs.bool("tilesSeeded", false))
            pages.set(0, new ArrayList<>(MINIMAL_TILES));
        while (pages.size() <= page) pages.add(new ArrayList<>());
        pages.get(page).add(spec);
        Tiles.save(prefs, pages);
        afterEdit(true);
    }

    private void removeTile(int page, int idx) {
        List<List<String>> pages = tilePagesForEdit();
        if (page >= pages.size() || idx >= pages.get(page).size()) return;
        String gone = pages.get(page).remove(idx);
        if (Tiles.isWidget(gone)) {
            Tiles.save(prefs, pages);
            hideResizer();
            widgets.removeSlot(widgets.slotOfId(Tiles.widgetId(gone)));
            return;
        }
        prefs.set("tilesSeeded", true);
        Tiles.save(prefs, pages);
        afterEdit(true);
    }

    private void tileSheet(int page, int idx) {
        List<List<String>> pages = Tiles.load(prefs);
        if (pages.get(0).isEmpty() && "minimal".equals(prefs.activeTheme()) && !prefs.bool("tilesSeeded", false))
            pages.set(0, new ArrayList<>(MINIMAL_TILES));
        if (page >= pages.size() || idx >= pages.get(page).size()) return;
        List<String> l = pages.get(page);
        String spec = l.get(idx);
        String bs = Tiles.base(spec);
        Entry app = bs.startsWith("app:") && snap != null ? snap.byKey.get(bs.substring(4)) : null;
        Sheet s = editSheet(Tiles.label(spec, app != null ? app.label : null));
        Runnable save = () -> { Tiles.save(prefs, pages); afterEdit(true); };
        if (idx > 0) s.item("Move earlier", () -> { java.util.Collections.swap(l, idx, idx - 1); save.run(); });
        if (idx < l.size() - 1) s.item("Move later", () -> { java.util.Collections.swap(l, idx, idx + 1); save.run(); });
        if (Tiles.w(spec) < Tiles.G) s.item("Wider", () -> { l.set(idx, Tiles.sized(spec, Tiles.w(spec) + 1, Tiles.h(spec))); save.run(); });
        if (Tiles.w(spec) > 1) s.item("Narrower", () -> { l.set(idx, Tiles.sized(spec, Tiles.w(spec) - 1, Tiles.h(spec))); save.run(); });
        if (Tiles.h(spec) < Tiles.MAX_H) s.item("Taller", () -> { l.set(idx, Tiles.sized(spec, Tiles.w(spec), Tiles.h(spec) + 1)); save.run(); });
        if (Tiles.h(spec) > 1) s.item("Shorter", () -> { l.set(idx, Tiles.sized(spec, Tiles.w(spec), Tiles.h(spec) - 1)); save.run(); });
        if (Tiles.base(spec).startsWith("applist")) {
            String bs2 = Tiles.base(spec);
            boolean picks = bs2.startsWith("applist:pick");
            s.item(picks ? "Choose apps…" : "Choose my own apps for this list…", () -> {
                String id = picks ? Tiles.listId(spec) : java.util.UUID.randomUUID().toString().substring(0, 8);
                if (!picks) { // start from what the list shows now
                    org.json.JSONArray a = new org.json.JSONArray();
                    for (Entry e : tileHost.apps(bs2.endsWith("fav"))) a.put(e.key);
                    org.json.JSONObject cfg = listConfig(Tiles.listId(spec));
                    try { cfg.put("a", a); } catch (Exception ignored) { }
                    saveListConfig(id, cfg);
                    l.set(idx, "applist:pick:" + id + "|" + Tiles.w(spec) + "|" + Tiles.h(spec)
                            + (Tiles.place(spec) == null ? "" : "|" + Tiles.place(spec)[0] + "|" + Tiles.place(spec)[1]));
                    save.run();
                }
                pickListApps(id);
            });
            String lid = Tiles.listId(spec);
            org.json.JSONObject cfg = listConfig(lid);
            s.item("List title…", () -> prompt("List title", cfg.optString("t", ""), t -> {
                try { cfg.put("t", t); } catch (Exception ignored) { }
                saveListConfig(lid, cfg);
                afterEdit(true);
            }));
            s.view(chipsView("Text size", new String[]{"Small", "Medium", "Large"}, new String[]{"14", "16", "19"},
                    String.valueOf(cfg.optInt("sz", 16)), v -> { try { cfg.put("sz", Integer.parseInt(v)); } catch (Exception ignored) { }
                        saveListConfig(lid, cfg); afterEdit(true); }));
            s.view(chipsView("App icons", new String[]{"Show", "Hide"}, new String[]{"on", "off"},
                    cfg.optBoolean("ic", true) ? "on" : "off", v -> { try { cfg.put("ic", "on".equals(v)); } catch (Exception ignored) { }
                        saveListConfig(lid, cfg); afterEdit(true); }));
            s.view(chipsView("Letter strip", new String[]{"Show", "Hide"}, new String[]{"on", "off"},
                    cfg.optBoolean("st", !lid.equals("fav") && !picks) ? "on" : "off",
                    v -> { try { cfg.put("st", "on".equals(v)); } catch (Exception ignored) { }
                        saveListConfig(lid, cfg); afterEdit(true); }));
        }
        if (Tiles.isWidget(spec)) {
            int ws = widgets.slotOfId(Tiles.widgetId(spec));
            s.item("Widget options: stack, settings…", () -> widgets.menu(ws));
            s.view(chipsView("Card behind widgets", new String[]{"None", "Glass", "Dark", "Surface"},
                    new String[]{"none", "glass", "dark", "surface"}, prefs.s(Prefs.WIDGET_BG), v -> setS(Prefs.WIDGET_BG, v, true)));
        }
        if (!Tiles.isWidget(spec) && !Tiles.base(spec).startsWith("app:"))
            s.item("Style: card, colours, font, size…", () -> tileStyleSheet(Tiles.base(spec), Tiles.label(spec, null)));
        if (Tiles.base(spec).startsWith("app:")) s.item("Change app…", () -> pickApp("Choose an app",
                key -> { l.set(idx, "app:" + key + "|" + Tiles.w(spec) + "|" + Tiles.h(spec)); save.run(); }));
        s.view(chipsView("Tile corners (all tiles & widgets)", new String[]{"Square", "Soft", "Round", "Extra round"},
                new String[]{"8", "18", "26", "36"}, String.valueOf(prefs.i(Prefs.WIDGET_RADIUS)),
                v -> setI(Prefs.WIDGET_RADIUS, Integer.parseInt(v), true)));
        s.item("Remove tile", () -> { l.remove(idx); save.run(); });
        s.show();
    }

    // ------------------------------------------------------------------ intents, preview, dark mode

    /** Extras: "previewTheme" (theme id) opens the full-screen preview; "editHome" opens edit mode. */
    private boolean handleIntent(Intent i) {
        if (i == null) return false;
        if (i.getBooleanExtra("organise", false)) { i.removeExtra("organise"); main.postDelayed(this::organiseIntoFolders, 600); return true; }
        if (i.getBooleanExtra("unorganise", false)) { i.removeExtra("unorganise"); main.postDelayed(this::undoAutoFolders, 300); return true; }
        String pv = i.getStringExtra("previewTheme");
        boolean edit = i.getBooleanExtra("editHome", false);
        i.removeExtra("previewTheme");
        i.removeExtra("editHome");
        if (pv != null) { main.post(() -> startPreview(pv)); return true; }
        if (edit) { main.postDelayed(this::enterEdit, 250); return true; }
        return false;
    }

    private void startPreview(String id) {
        if (editMode) exitEdit();
        if (drawerOpen) closeDrawer(false);
        if (previewThemes == null) {
            previewBackup = Themes.capture(prefs, "backup").toString();
            previewBackupActive = prefs.activeTheme();
        }
        previewThemes = Themes.load(prefs);
        previewIdx = 0;
        for (int i = 0; i < previewThemes.size(); i++) if (previewThemes.get(i).optString("id").equals(id)) previewIdx = i;
        showPreview();
    }

    private void stepPreview(int d) {
        if (previewThemes == null || previewThemes.isEmpty()) return;
        previewIdx = (previewIdx + d + previewThemes.size()) % previewThemes.size();
        showPreview();
    }

    private void showPreview() {
        org.json.JSONObject t = previewThemes.get(previewIdx);
        prefs.applyLook(t, this);
        markSynced();
        applyStyle();
        widgets.applyStyle();
        reload();
        previewName.setText(t.optString("name") + "  (" + (previewIdx + 1) + "/" + previewThemes.size() + ")");
        previewName.setTypeface(Ui.typeface(this, Themes.s(t, Prefs.FONT, prefs.darkNow(this)), Typeface.NORMAL));
        previewBar.setVisibility(View.VISIBLE);
        if (paged()) pager.setPage(0, true); // start on the clock & tiles page, swipe to see apps
        widgetScroll.scrollTo(0, 0);
        main.postDelayed(() -> widgetScroll.scrollTo(0, 0), 600);
    }

    private void cancelPreview() {
        if (previewThemes == null) return;
        try { prefs.applyLook(new org.json.JSONObject(previewBackup), false); } catch (Exception ignored) { }
        prefs.set(Prefs.THEME, previewBackupActive);
        endPreview();
        Ui.toast(this, "Kept your previous look");
    }

    private void applyPreview() {
        if (previewThemes == null) return;
        String name = previewThemes.get(previewIdx).optString("name");
        endPreview();
        Ui.toast(this, name + " applied");
    }

    private void endPreview() {
        previewThemes = null;
        previewBar.setVisibility(View.GONE);
        markSynced();
        applyStyle();
        widgets.applyStyle();
        reload();
    }

    /** Re-applies the active theme when light/dark changes (only matters for themes with a dark variant). */
    private void syncDarkMode() {
        boolean dark = Prefs.systemDark(this);
        if (dark == lastDark) return;
        lastDark = dark;
        if (previewThemes != null) return;
        if ("light".equals(prefs.mode()) || "dark".equals(prefs.mode())) return; // fixed choice: the phone doesn't matter
        org.json.JSONObject t = Themes.find(Themes.load(prefs), prefs.activeTheme());
        if (t == null) return;
        prefs.applyLook(t, this);
        markSynced();
        applyStyle();
        widgets.applyStyle();
        reload();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        syncDarkMode();
    }

    private void updateDots() {
        dots.removeAllViews();
        int n = pager.getChildCount();
        if (pager.hi() - pager.lo() < 1) return;
        for (int i = pager.lo(); i <= pager.hi() && i < n; i++) {
            View d = new View(this);
            d.setBackground(Ui.round(Ui.alpha(prefs.ink(), i == pager.current() ? 0xFF : 0x66), dp(4)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(7), dp(7));
            lp.setMargins(dp(4), 0, dp(4), 0);
            dots.addView(d, lp);
        }
    }

    private void updateBar() {
        boolean listShowing = adapter == null || adapter.query.isEmpty();
        bar.setVisibility(listShowing && (drawerOpen || !paged()) ? View.VISIBLE : View.GONE);
    }

    private AppCell makeCell(Entry e, boolean showLabel, int iconPx) {
        AppCell c = cellFor(e, showLabel, style.ink, iconPx);
        cells.add(c);
        return c;
    }

    private AppCell cellFor(Entry e, boolean showLabel, int ink, int iconPx) {
        AppCell c = new AppCell(this);
        c.bind(e, iconPx, style.face, style.accent, ink, notifCount(e), showLabel);
        c.label.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.i(Prefs.GRID_LABEL_SIZE));
        if (e.kind == Entry.FOLDER) c.setOnClickListener(v -> openFolder(e));
        else c.setOnClickListener(v -> launch(e, v));
        c.setOnLongClickListener(v -> hold(() -> startIconDrag(c)));
        return c;
    }

    private int notifCount(Entry e) {
        if (e.kind != Entry.FOLDER) return notifsFor(e).size();
        int n = 0;
        List<Entry> m = folderMembers.get(e.key);
        if (m != null) for (Entry x : m) n += notifsFor(x).size();
        return n;
    }

    // ------------------------------------------------------------------ folders

    private final java.util.Map<String, List<Entry>> folderMembers = new java.util.HashMap<>();

    private Entry folderEntry(Folders.Folder f, List<Entry> members) {
        Entry e = new Entry(Entry.FOLDER, "f:" + f.id, "", null, null, null, 0);
        e.label = e.origLabel = f.name;
        e.folder = f;
        Drawable[] icons = new Drawable[Math.min(4, members.size())];
        for (int i = 0; i < icons.length; i++) {
            Drawable d = members.get(i).icon;
            icons[i] = d != null && d.getConstantState() != null ? d.getConstantState().newDrawable(getResources()) : d;
        }
        e.icon = IconStyler.folderIcon(getResources(), icons, prefs.depth(), Ui.isLight(prefs.ink()));
        folderMembers.put(e.key, members);
        return e;
    }

    /** Glass popup over a blurred home screen with the folder's apps; tap the name to rename. */
    private void openFolder(Entry fe) {
        List<Entry> members = folderMembers.get(fe.key);
        if (members == null) return;
        android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable bg = Ui.round(Ui.blurOn(this) ? 0x8C1E2024 : 0xF51E2024, dp(34));
        bg.setStroke(dp(1), 0x2EFFFFFF);
        box.setBackground(bg);
        box.setPadding(dp(16), dp(20), dp(16), dp(20));

        TextView title = new TextView(this);
        title.setText(fe.label);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(style.face);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(4), 0, dp(16));
        title.setOnClickListener(v -> { d.dismiss(); renameFolder(fe); });
        box.addView(title);

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        int cols = 4;
        for (int i = 0; i < members.size(); i += cols) {
            LinearLayout row = new LinearLayout(this);
            for (int c = 0; c < cols; c++) {
                View v;
                if (i + c < members.size()) {
                    Entry m = members.get(i + c);
                    AppCell cell = cellFor(m, true, 0xFFFFFFFF, gridIconPx());
                    cell.setOnClickListener(x -> { d.dismiss(); launch(m, x); });
                    cell.setOnLongClickListener(x -> { d.dismiss(); showAppMenu(m); return true; }); // inside a folder: menu
                    v = cell;
                } else {
                    v = new View(this);
                }
                row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rl.bottomMargin = dp(10);
            grid.addView(row, rl);
        }
        ScrollView sc = new ScrollView(this);
        sc.setVerticalScrollBarEnabled(false);
        sc.addView(grid);
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.55f);
        box.addView(sc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                members.size() > 16 ? maxH : ViewGroup.LayoutParams.WRAP_CONTENT));
        d.setContentView(box);
        d.show();
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setLayout(getResources().getDisplayMetrics().widthPixels - dp(40), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.25f);
            w.setWindowAnimations(android.R.style.Animation_Dialog);
            if (Build.VERSION.SDK_INT >= 31) {
                w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                WindowManager.LayoutParams a = w.getAttributes();
                a.setBlurBehindRadius(dp(36));
                w.setAttributes(a);
            }
        }
    }

    private void folderMenu(Entry fe) {
        boolean rare = Folders.RARE_ID.equals(fe.folder.id);
        Sheet s = new Sheet(this, fe.label, null);
        s.item("Open", () -> openFolder(fe));
        s.item("Rename", () -> renameFolder(fe));
        if (rare) {
            s.item("Turn off this folder", () -> { prefs.set("rarefolder", false); markSynced(); rebuildFavorites(); });
        } else {
            s.item("Remove folder (keeps the apps)", () -> {
                List<Folders.Folder> all = Folders.load(prefs);
                all.removeIf(f -> f.id.equals(fe.folder.id));
                Folders.save(prefs, all);
                markSynced();
                rebuildFavorites();
            });
        }
        s.show();
    }

    private void renameFolder(Entry fe) {
        prompt("Folder name", fe.label, name -> {
            if (Folders.RARE_ID.equals(fe.folder.id)) {
                prefs.set("rarename", name);
            } else {
                List<Folders.Folder> all = Folders.load(prefs);
                for (Folders.Folder f : all) if (f.id.equals(fe.folder.id)) f.name = name;
                Folders.save(prefs, all);
            }
            markSynced();
            rebuildFavorites();
        });
    }

    private void addToFolder(Entry app) {
        List<Folders.Folder> all = Folders.load(prefs);
        Sheet s = new Sheet(this, "Add to folder", null);
        for (Folders.Folder f : all) {
            s.item(f.name, () -> {
                Folders.put(all, f, app.key);
                Folders.save(prefs, all);
                markSynced();
                rebuildFavorites();
                Ui.toast(this, "Added to " + f.name);
            });
        }
        s.item("＋  New folder…", () -> prompt("New folder", "Folder", name -> {
            Folders.Folder f = Folders.create(all, name);
            Folders.put(all, f, app.key);
            Folders.save(prefs, all);
            markSynced();
            rebuildFavorites();
        }));
        s.show();
    }

    private void prompt(String title, String value, java.util.function.Consumer<String> done) {
        Sheet s = new Sheet(this, title, null);
        EditText et = new EditText(this);
        et.setText(value);
        et.setSelectAllOnFocus(true);
        et.setSingleLine(true);
        et.setTextColor(Ios.of(this).text);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        et.setBackground(Ui.round(Ios.of(this).fill, dp(10)));
        et.setPadding(dp(16), dp(12), dp(16), dp(12));
        s.view(et);
        s.item("Save", () -> {
            String n = et.getText().toString().trim();
            if (!n.isEmpty()) done.accept(n);
        });
        s.show();
        s.showKeyboard(et);
    }

    private void rebuildDock() {
        cells.removeIf(c -> c.getParent() == dock);
        dock.removeAllViews();
        if (snap == null) return;
        int cols = dockCount();
        int n = Math.min(cols, snap.favorites.size());
        if (n == 0 && !editMode) {
            TextView hint = new TextView(this);
            hint.setText("Long-press any app → Add to favorites to put it in the dock");
            Ui.ink(hint, Ui.alpha(prefs.ink(), 0xCC));
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            hint.setGravity(Gravity.CENTER);
            hint.setPadding(dp(12), dp(14), dp(12), dp(14));
            dock.addView(hint, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            return;
        }
        for (int i = 0; i < cols; i++) {
            int dockIcon = fitIcon(dockIconPx(), (availableWidth() - dock.getPaddingLeft() - dock.getPaddingRight()) / Math.max(1, cols));
            View v;
            if (i < n) v = makeCell(snap.favorites.get(i), false, dockIcon);
            else if (editMode) { // empty slot while editing: a dashed "+" that adds an app here
                TextView plus = new TextView(this);
                plus.setText("+");
                plus.setGravity(Gravity.CENTER);
                plus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
                Ui.ink(plus, Ui.alpha(prefs.ink(), 0xCC));
                android.graphics.drawable.GradientDrawable pd = Ui.round(0x00000000, dockIcon / 3);
                pd.setStroke(dp(1.5f), Ui.alpha(prefs.ink(), 0x88), dp(5), dp(4));
                plus.setBackground(pd);
                plus.setContentDescription("Add an app to the dock");
                plus.setOnClickListener(x -> addToDock());
                FrameLayout slot = new FrameLayout(this);
                slot.addView(plus, new FrameLayout.LayoutParams(dockIcon, dockIcon, Gravity.CENTER));
                slot.setMinimumHeight(dockIcon + dp(8));
                v = slot;
            } else v = new View(this);
            dock.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
    }

    /** App grid pages: every visible app not already in the dock, alphabetical, left to right. */
    private void rebuildPages() {
        int w = pager.getWidth(), h = pager.getHeight();
        if (snap == null || w == 0 || h == 0 || style == null) return;
        int keepPage = pager.current();
        pager.removeAllViews();
        pager.addPage(todayPage);
        rebuildTiles();
        for (View tp : tilePages) pager.addPage(tp);
        cells.removeIf(c -> c.getParent() != dock);

        java.util.Set<String> inDock = new java.util.HashSet<>();
        for (int i = 0; i < Math.min(dockCount(), snap.favorites.size()); i++) inDock.add(snap.favorites.get(i).key);
        // folders first (user folders, then the automatic rarely-used one), then loose apps A-Z
        folderMembers.clear();
        List<Entry> apps = new ArrayList<>();
        java.util.Set<String> placed = new java.util.HashSet<>(inDock);
        for (Folders.Folder f : Folders.load(prefs)) {
            List<Entry> members = new ArrayList<>();
            for (String k : f.keys) {
                Entry m = snap.byKey.get(k);
                if (m != null && !m.hidden) members.add(m);
            }
            placed.addAll(f.keys);
            if (!members.isEmpty()) apps.add(folderEntry(f, members));
        }
        if (prefs.rareFolder() && !snap.rare.isEmpty()) {
            List<Entry> members = new ArrayList<>();
            for (Entry e : snap.apps) if (!e.hidden && snap.rare.contains(e.key) && !placed.contains(e.key)) members.add(e);
            if (members.size() >= 2) {
                Folders.Folder rf = new Folders.Folder();
                rf.id = Folders.RARE_ID;
                rf.name = prefs.rareName();
                for (Entry m : members) { rf.keys.add(m.key); placed.add(m.key); }
                apps.add(folderEntry(rf, members));
            }
        }
        for (Entry e : snap.apps) if (!e.hidden && !placed.contains(e.key)) apps.add(e);
        // the user's own arrangement first (saved when icons are dragged), new items after it
        java.util.Map<String, Entry> byKey = new java.util.LinkedHashMap<>();
        for (Entry e : apps) byKey.put(e.key, e);
        List<Entry> ordered = new ArrayList<>();
        for (String k : prefs.str("apporder", "").split("\n")) {
            Entry e = byKey.remove(k);
            if (e != null) ordered.add(e);
        }
        ordered.addAll(byKey.values());
        apps = ordered;
        gridKeys.clear();
        for (Entry e : apps) gridKeys.add(e.key);

        int cols = prefs.columns();
        int gridIcon = fitIcon(gridIconPx(), w / Math.max(1, cols));
        int cellH = gridIcon + dp(8) + (gridLabels() ? dp(12) + Math.round(prefs.i(Prefs.GRID_LABEL_SIZE) * 1.6f * getResources().getDisplayMetrics().scaledDensity) : dp(4));
        int rows = Math.max(1, (h - dp(8)) / cellH);
        int per = rows * cols;
        for (int start = 0; start < apps.size(); start += per) {
            LinearLayout page = new LinearLayout(this);
            page.setOrientation(LinearLayout.VERTICAL);
            page.setPadding(0, dp(4), 0, dp(4));
            for (int r = 0; r < rows; r++) {
                LinearLayout row = new LinearLayout(this);
                for (int c = 0; c < cols; c++) {
                    int idx = start + r * cols + c;
                    View v = idx < apps.size() && idx < start + per ? makeCell(apps.get(idx), gridLabels(), gridIcon) : new View(this);
                    row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
                }
                page.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            }
            pager.addPage(page);
        }
        int target = previewThemes != null ? 0 : pagesShownOnce ? Math.min(keepPage, pager.getChildCount() - 1) : homePage();
        pagesShownOnce = true;
        applyPageRange();
        pager.setPage(target, false);
        updateDots();
        if (editMode) refreshEdit();
    }

    private boolean overInteractive(float x, float y) {
        for (AppCell c : cells) if (hit(c, x, y)) return true;
        for (int i = 0; i < favColumn.getChildCount(); i++) if (hit(favColumn.getChildAt(i), x, y)) return true;
        for (int i = 0; i < widgets.slotCount(); i++) if (hit(widgets.slotView(i), x, y)) return true;
        for (List<View> l : tileViews) for (View v : l) if (hit(v, x, y)) return true;
        return hit(searchPill, x, y) || hit(clock, x, y) || hit(date, x, y);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        lastRawX = ev.getRawX();
        lastRawY = ev.getRawY();
        int am0 = ev.getActionMasked();
        trackHold(ev);
        if (am0 == MotionEvent.ACTION_UP || am0 == MotionEvent.ACTION_CANCEL) main.post(pager::fingerUp);
        if (iconDragging) {
            cancelChildrenOnce(ev);
            iconDragTouch(ev);
            return true;
        }
        if (rzTouch && needCancel) cancelChildrenOnce(ev);
        if (editMode && rzSlot >= 0) {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                rzTouch = false;
                if (!hit(resizeBar, ev.getRawX(), ev.getRawY())) {
                    rzMode = resizeModeAt(ev.getRawX(), ev.getRawY());
                    if (rzMode != 0) {
                        rzTouch = true;
                        rzDownX = ev.getRawX();
                        rzDownY = ev.getRawY();
                        if (rzTile) {
                            livePos = null;
                            rzPages = null;
                            List<List<String>> tp = tilePagesForEdit();
                            String sp = rzPage < tp.size() && rzSlot < tp.get(rzPage).size() ? tp.get(rzPage).get(rzSlot) : "day";
                            rzSpan = Tiles.w(sp);
                            rzStartH = rzH = Tiles.h(sp);
                        } else {
                            rzSpan = widgets.spanOfSlot(rzSlot);
                            rzStartH = rzH = widgets.heightOf(rzSlot);
                        }
                    } else if (!hit(editBar, ev.getRawX(), ev.getRawY()) && !hit(stripScroll, ev.getRawX(), ev.getRawY())) {
                        hideResizer();
                    }
                }
            }
            if (rzTouch) { resizeTouch(ev); return true; }
            if (rzSlot >= 0 && ev.getActionMasked() != MotionEvent.ACTION_DOWN || hit(resizeBar, ev.getRawX(), ev.getRawY()))
                return super.dispatchTouchEvent(ev);
        }
        if (editMode) {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downOnBar = hit(editBar, ev.getRawX(), ev.getRawY()) || hit(stripScroll, ev.getRawX(), ev.getRawY());
                if (!downOnBar && stripScroll.getVisibility() == View.VISIBLE && !hit(dots, ev.getRawX(), ev.getRawY())) {
                    stripScroll.setVisibility(View.GONE); // tap outside closes the pages overview
                    return true;
                }
            }
            if (downOnBar) return super.dispatchTouchEvent(ev);
            scaler.onTouchEvent(ev);
            editGestures.onTouchEvent(ev);
            return true;
        }
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && previewBar.getVisibility() == View.VISIBLE
                && hit(previewBar, ev.getRawX(), ev.getRawY())) {
            downOnBar = true;
            return super.dispatchTouchEvent(ev);
        }
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downRawY = ev.getRawY();
            int[] loc = new int[2];
            bar.getLocationOnScreen(loc);
            downOnBar = hit(bar, ev.getRawX(), ev.getRawY()) && bar.inZone(ev.getRawY() - loc[1]);
            if (!editMode) for (List<View> l : tileViews) for (View tv : l) {
                if (!hit(tv, ev.getRawX(), ev.getRawY())) continue;
                if ("applist".equals(tv.getTag())) downOnBar = true; // the list scrolls; no home swipe gestures
                else {
                    int[] tl = new int[2];
                    tv.getLocationOnScreen(tl);
                    if (Widgets.scrollableAt(tv, ev.getRawX() - tl[0], ev.getRawY() - tl[1])) downOnBar = true;
                }
            }
            downOnInteractive = overInteractive(ev.getRawX(), ev.getRawY());
        }
        if (!downOnBar) gestures.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    /** Pushes preferences into the views. */
    void applyStyle() {
        arrangeHome();
        boolean left = prefs.leftHanded();
        int side = dp(20), barSide = dp(44);
        int pl = left ? barSide : side, pr = left ? side : barSide;
        if (paged()) home.setPadding(dp(12), dp(28), dp(12), 0);
        else home.setPadding(pl, dp(28), pr, 0);
        drawerList.setPadding(pl, dp(16), pr, dp(80));
        FrameLayout.LayoutParams sl = (FrameLayout.LayoutParams) search.getLayoutParams();
        sl.setMargins(pl, 0, pr, dp(12));
        search.setLayoutParams(sl);

        FrameLayout.LayoutParams bl = (FrameLayout.LayoutParams) bar.getLayoutParams();
        bl.gravity = left ? Gravity.START : Gravity.END;
        bar.setLayoutParams(bl);
        FrameLayout.LayoutParams ul = (FrameLayout.LayoutParams) bubble.getLayoutParams();
        ul.gravity = Gravity.TOP | (left ? Gravity.START : Gravity.END);
        ul.setMarginStart(left ? dp(56) : 0);
        ul.setMarginEnd(left ? 0 : dp(56));
        bubble.setLayoutParams(ul);

        int accent = prefs.accent();
        Typeface face = Ui.typeface(this, prefs.font(), Typeface.NORMAL);
        int ink = prefs.ink();
        Ui.depth = prefs.depth();
        Ui.holdMs = Ui.holdMs(prefs);
        setLightBars(!Ui.isLight(ink));
        style = new AppRow.Style(dp(prefs.iconDp()), prefs.labelSp(), face, accent, prefs.showPreviews(), ink);
        boolean lightLook = !Ui.isLight(ink);
        listStyle = new AppRow.Style(style.iconPx, style.labelSp, face, accent, false, lightLook ? 0xFF111111 : 0xFFFFFFFF);
        listScrim = lightLook ? 0xF2F2F2F7 : 0xE8101214;
        search.setTextColor(lightLook ? 0xFF111111 : 0xFFFFFFFF);
        search.setHintTextColor(lightLook ? 0x99000000 : 0x99FFFFFF);
        search.setBackground(Ui.round(lightLook ? 0xFFE3E3E8 : 0xFF2A2D32, dp(24)));
        bar.setColors(Ui.alpha(ink, 0xCC), accent);
        int glassTint = Ui.isLight(ink) ? 0xFFFFFF : 0x000000;
        boolean solid = prefs.solidBg();
        android.graphics.drawable.GradientDrawable dockBg = Ui.round(solid ? prefs.surface()
                : Ui.alpha(glassTint, Ui.isLight(ink) ? 0x33 : 0x1F), dp(32));
        if (!solid) dockBg.setStroke(dp(1), Ui.alpha(glassTint, 0x38));
        boolean dockBgOn = prefs.on(Prefs.DOCK_BG);
        dock.setBackground(dockBgOn ? dockBg : null);
        dock.setElevation(dockBgOn ? dp(prefs.depth() * 4) : 0);
        bubble.setBackground(Ui.round(accent, dp(36)));
        dots.setVisibility(prefs.on(Prefs.DOTS) || editMode ? View.VISIBLE : View.INVISIBLE);
        applyClock();
        arrangeHome();
        applySearch();
        search.setTypeface(face);
        if (widgets != null) widgets.applyStyle();
        if (previewThemes == null && "inkline".equals(prefs.activeTheme()) && !prefs.bool("inklineSeeded", false)) {
            // Inkline's look: date + day on top, the week below, then Up Next; other items move down
            List<List<String>> pg = Tiles.load(prefs);
            List<String> first = pg.get(0);
            String[] want = {"date|2|4|0|0", "day|2|4|2|0", "week|4|4|0|4", "events|4|6|0|8"};
            for (int i = want.length - 1; i >= 0; i--) {
                String b = Tiles.base(want[i]);
                boolean has = false;
                for (String x : first) if (Tiles.base(x).equals(b)) has = true;
                if (!has) first.add(0, want[i]);
            }
            Tiles.save(prefs, pg);
            prefs.set("inklineSeeded", true);
            markSynced();
        }
        if (previewThemes == null && "minimal".equals(prefs.activeTheme()) && !prefs.bool("tilesSeeded", false)) {
            List<List<String>> pg = Tiles.load(prefs);
            if (pg.get(0).isEmpty()) { pg.set(0, new ArrayList<>(MINIMAL_TILES)); Tiles.save(prefs, pg); }
            prefs.set("tilesSeeded", true);
            markSynced();
        }
        setScrim(false);
        updateBar();
        rebuildFavorites();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    @SuppressWarnings("deprecation")
    private void setLightBars(boolean light) {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            int f = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            w.getDecorView();
            w.getInsetsController().setSystemBarsAppearance(light ? f : 0, f);
        } else {
            int v = w.getDecorView().getSystemUiVisibility();
            int f = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            w.getDecorView().setSystemUiVisibility(light ? v | f : v & ~f);
        }
    }

    /** The brushed-metal sheen over a "silver" background (hidden while the app list is open). */
    private void applySheen() {
        if (!(root.getBackground() instanceof android.graphics.drawable.LayerDrawable)) return;
        android.graphics.drawable.LayerDrawable ld = (android.graphics.drawable.LayerDrawable) root.getBackground();
        android.graphics.drawable.Drawable d = prefs.metalBg() ? Ui.metal(prefs.i(Prefs.BG_COLOR), 0) : new ColorDrawable(0);
        d.setAlpha(drawerOpen ? 0 : 255);
        ld.setDrawable(1, d);
        metalSheen = d;
    }

    private int homeScrim() {
        return prefs.solidBg() ? prefs.i(Prefs.BG_COLOR) | 0xFF000000 : (prefs.dimPercent() * 255 / 100) << 24;
    }

    private void setScrim(boolean animate) {
        int to = drawerOpen ? listScrim : homeScrim();
        applySheen();
        if (scrimAnim != null) scrimAnim.cancel();
        if (!animate) { scrim.setColor(to); return; }
        scrimAnim = ValueAnimator.ofArgb(scrim.getColor(), to);
        scrimAnim.setDuration(180);
        scrimAnim.addUpdateListener(a -> scrim.setColor((Integer) a.getAnimatedValue()));
        scrimAnim.start();
    }

    // ------------------------------------------------------------------ favorites

    private void rebuildFavorites() {
        if (favColumn == null || style == null) return;
        if (paged()) {
            cells.clear();
            rebuildDock();
            rebuildPages();
            return;
        }
        rebuildTiles();
        if (editMode) main.post(this::refreshEdit);
        favColumn.removeAllViews();
        if (snap == null) return;
        if (snap.favorites.isEmpty()) {
            TextView hint = new TextView(this);
            hint.setText("Long-press any app in the list to add it here.\nSwipe up for all apps.");
            hint.setTextColor(Ui.alpha(prefs.ink(), 0xB3));
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            hint.setPadding(dp(4), dp(8), dp(4), dp(8));
            Ui.shadow(hint);
            favColumn.addView(hint);
            return;
        }
        for (Entry e : snap.favorites) {
            AppRow r = new AppRow(this);
            bindFavorite(r, e);
            r.setOnClickListener(v -> launch(e, v));
            r.setOnLongClickListener(v -> { showAppMenu(e); return true; });
            favColumn.addView(r);
        }
    }

    private void bindFavorite(AppRow r, Entry e) {
        List<StatusBarNotification> n = notifsFor(e);
        r.bind(e, style, n, true);
        if (r.preview.getVisibility() == View.VISIBLE) {
            StatusBarNotification latest = n.get(0);
            r.preview.setOnClickListener(v -> openNotification(latest, e, v));
            r.preview.setOnLongClickListener(v -> {
                if (latest.isClearable()) { NotifService.dismiss(latest); Ui.toast(this, "Dismissed"); }
                return true;
            });
        }
    }

    // ------------------------------------------------------------------ list / search

    private void openDrawer(boolean focusSearch) {
        if (!drawerOpen) {
            drawerOpen = true;
            drawer.setVisibility(View.VISIBLE);
            drawer.setAlpha(0f);
            drawer.setTranslationY(dp(48));
            drawer.animate().alpha(1f).translationY(0f).setDuration(180).start();
            home.animate().alpha(0f).setDuration(120).withEndAction(() -> { if (drawerOpen) home.setVisibility(View.INVISIBLE); }).start();
            drawerList.setSelection(0);
            setScrim(true);
            updateBar();
        }
        if (focusSearch) {
            search.requestFocus();
            search.post(() -> getSystemService(InputMethodManager.class).showSoftInput(search, InputMethodManager.SHOW_IMPLICIT));
        }
    }

    private void closeDrawer(boolean animate) {
        if (!drawerOpen) return;
        drawerOpen = false;
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.setText("");
        search.clearFocus();
        bubble.setVisibility(View.GONE);
        home.setVisibility(View.VISIBLE);
        if (animate) {
            drawer.animate().alpha(0f).translationY(dp(48)).setDuration(160)
                    .withEndAction(() -> { if (!drawerOpen) drawer.setVisibility(View.GONE); }).start();
            home.animate().alpha(1f).setDuration(160).start();
        } else {
            drawer.animate().cancel();
            home.animate().cancel();
            drawer.setVisibility(View.GONE);
            home.setAlpha(1f);
        }
        setScrim(animate);
        updateBar();
    }

    private void onSwipeDown() {
        String a = prefs.swipeDown();
        if ("search".equals(a)) openDrawer(true);
        else if ("shade".equals(a) && !expandShade()) openDrawer(true);
    }

    /** Pulls down the notification shade. Uses a non-SDK call; falls back to search if blocked. */
    private boolean expandShade() {
        try {
            Object sb = getSystemService("statusbar");
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static final class Item {
        static final int HEADER = 0, APP = 1, ACTION = 2;
        final int type;
        final String text;
        final Entry entry;
        final Runnable action;
        final int iconRes;

        Item(int type, String text, Entry entry, Runnable action, int iconRes) {
            this.type = type;
            this.text = text;
            this.entry = entry;
            this.action = action;
            this.iconRes = iconRes;
        }
    }

    final class DrawerAdapter extends BaseAdapter {
        final List<Item> items = new ArrayList<>();
        final LinkedHashMap<String, Integer> sections = new LinkedHashMap<>();
        String query = "";

        void setQuery(String q) {
            query = q.trim();
            rebuild();
            drawerList.setSelection(0);
        }

        void rebuild() {
            items.clear();
            sections.clear();
            if (snap != null) {
                if (query.isEmpty()) {
                    String cur = null;
                    for (Entry e : snap.apps) {
                        if (e.hidden) continue;
                        if (!e.section.equals(cur)) {
                            cur = e.section;
                            sections.put(cur, items.size());
                            items.add(new Item(Item.HEADER, cur, null, null, 0));
                        }
                        items.add(new Item(Item.APP, null, e, null, 0));
                    }
                } else {
                    search();
                }
            }
            notifyDataSetChanged();
            bar.setLetters(new ArrayList<>(sections.keySet()));
            updateBar();
        }

        private void search() {
            String q = Ui.norm(query);
            List<Entry> pool = new ArrayList<>();
            for (Entry e : snap.apps) if (!e.hidden) pool.add(e);
            pool.addAll(snap.shortcuts.values());
            List<Object[]> scored = new ArrayList<>();
            for (Entry e : pool) {
                int s = Ui.score(e.norm, q);
                if (s >= 0) scored.add(new Object[]{s, e});
            }
            Collections.sort(scored, (x, y) -> {
                int r = Integer.compare((Integer) y[0], (Integer) x[0]);
                return r != 0 ? r : ((Entry) x[1]).norm.compareTo(((Entry) y[1]).norm);
            });
            for (int i = 0; i < scored.size() && i < 30; i++) items.add(new Item(Item.APP, null, (Entry) scored.get(i)[1], null, 0));
            final String raw = query;
            items.add(new Item(Item.ACTION, "Search the web for “" + raw + "”", null,
                    () -> webSearch(raw), android.R.drawable.ic_menu_search));
            items.add(new Item(Item.ACTION, "Find “" + raw + "” in app store", null,
                    () -> safeStart(new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(raw)))),
                    android.R.drawable.ic_menu_add));
        }

        Object firstResult() {
            for (Item it : items) {
                if (it.type == Item.APP) return it.entry;
                if (it.type == Item.ACTION) return it.action;
            }
            return null;
        }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int p) { return items.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public int getViewTypeCount() { return 3; }
        @Override public int getItemViewType(int p) { return items.get(p).type; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int p) { return items.get(p).type != Item.HEADER; }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            Item it = items.get(pos);
            if (it.type == Item.HEADER) {
                TextView tv = cv instanceof TextView ? (TextView) cv : new TextView(HomeActivity.this);
                tv.setText(it.text);
                tv.setTextColor(style.accent);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                tv.setTypeface(Ui.typeface(HomeActivity.this, prefs.font(), Typeface.BOLD));
                tv.setPadding(dp(6), dp(14), dp(6), dp(2));
                return tv;
            }
            AppRow r = cv instanceof AppRow ? (AppRow) cv : new AppRow(HomeActivity.this);
            r.setClickable(false);
            r.setLongClickable(false);
            if (it.type == Item.APP) r.bind(it.entry, listStyle, notifsFor(it.entry), false);
            else r.bindAction(getDrawable(it.iconRes), it.text, listStyle);
            return r;
        }

        void click(int pos, View v) {
            Item it = items.get(pos);
            if (it.type == Item.APP) launch(it.entry, v);
            else if (it.type == Item.ACTION) it.action.run();
        }

        boolean longClick(int pos) {
            Item it = items.get(pos);
            if (it.type != Item.APP) return false;
            return hold(() -> dragFromList(it.entry));
        }
    }

    private final List<AppCell> tileAppCells = new ArrayList<>(); // app icons placed on tile pages
    private AppCell mergeTarget; // the icon the dragged app is resting on

    /** The app or folder icon of the tile (other than skip) whose current spot covers column c, row r. */
    private AppCell appInSlot(List<String> l, List<View> views, int skip, int c, int r) {
        if (livePos == null) return null;
        for (int k = 0; k < l.size() && k < livePos.length && k < views.size(); k++) {
            if (k == skip || !Tiles.base(l.get(k)).startsWith("app:")) continue;
            int kc = livePos[k][0], kr = livePos[k][1];
            if (c >= kc && c < kc + Tiles.w(l.get(k)) && r >= kr && r < kr + Tiles.h(l.get(k))) {
                View tv = views.get(k);
                if (tv instanceof AppCell) return (AppCell) tv;
                if (tv instanceof ViewGroup && ((ViewGroup) tv).getChildCount() > 0 && ((ViewGroup) tv).getChildAt(0) instanceof AppCell)
                    return (AppCell) ((ViewGroup) tv).getChildAt(0);
                return null;
            }
        }
        return null;
    }

    /** The app an app tile opens (not a folder tile), or null. */
    private Entry tileApp(String spec) {
        String b = Tiles.base(spec);
        if (!b.startsWith("app:") || b.startsWith("app:f:")) return null;
        Entry e = tileHost.entry(b.substring(4));
        return e != null && e.kind == Entry.APP ? e : null;
    }

    private AppCell cellUnder(float x, float y) { return cellUnder(x, y, null); }

    /** An app icon (or folder) under the finger, on the current page, other than the one being dragged. */
    private AppCell cellUnder(float x, float y, View except) {
        List<AppCell> all = new ArrayList<>(cells);
        all.addAll(tileAppCells);
        for (AppCell c : all) {
            if (c == dragCell || c.entry == null || c.getParent() == dock || !c.isShown()) continue;
            if (except != null && (c == except || c.getParent() == except)) continue;
            if (c.entry.kind != Entry.APP && c.entry.kind != Entry.FOLDER) continue;
            if (c.entry.kind == Entry.FOLDER && c.entry.folder != null && Folders.RARE_ID.equals(c.entry.folder.id)) continue;
            if (dragCell != null && dragCell.entry != null && c.entry.key.equals(dragCell.entry.key)) continue;
            android.graphics.RectF r = screenRect(c.icon);
            if (r.width() <= 0) continue;
            float rad = r.width() * 0.42f;
            if (Math.hypot(x - r.centerX(), y - r.centerY()) < rad) return c;
        }
        return null;
    }

    private void setMergeTarget(AppCell t) {
        if (t == mergeTarget) return;
        if (mergeTarget != null) mergeTarget.icon.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
        mergeTarget = t;
        if (t != null) {
            t.icon.animate().scaleX(1.18f).scaleY(1.18f).setDuration(140).start();
            root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
    }

    /** Puts app d into folder target, or makes a new folder of target + d where target was. */
    private void mergeInto(Entry d, Entry target, boolean fromDock) {
        tempCell = null;
        List<Folders.Folder> fl = Folders.load(prefs);
        for (Folders.Folder f : fl) f.keys.remove(d.key);
        if (fromDock) {
            List<String> favs = prefs.favorites();
            favs.remove(d.key);
            prefs.setFavorites(favs);
        }
        if (target.kind == Entry.FOLDER && target.folder != null) {
            for (Folders.Folder f : fl) if (f.id.equals(target.folder.id) && !f.keys.contains(d.key)) f.keys.add(d.key);
            Folders.save(prefs, fl);
            Ui.toast(this, "Added to " + target.label);
            return;
        }
        Folders.Folder f = Folders.create(fl, folderNameFor(target));
        for (Folders.Folder o : fl) if (o != f) o.keys.remove(target.key);
        f.keys.add(target.key);
        f.keys.add(d.key);
        Folders.save(prefs, fl);
        // on the app pages the folder takes the target's spot
        List<String> order = new ArrayList<>();
        for (String k : prefs.str("apporder", "").split("\n")) if (!k.isEmpty()) order.add(k);
        if (order.isEmpty()) order.addAll(gridKeys);
        int at = order.indexOf(target.key);
        if (at >= 0) order.set(at, "f:" + f.id);
        order.remove(d.key);
        prefs.set("apporder", android.text.TextUtils.join("\n", order));
        // on a tile page the target's app tile becomes the folder
        List<List<String>> pages = Tiles.load(prefs);
        boolean tiles = false;
        outer:
        for (List<String> l : pages) {
            for (int i = 0; i < l.size(); i++) {
                String sp = l.get(i);
                if (Tiles.base(sp).equals("app:" + target.key)) {
                    l.set(i, "app:f:" + f.id + sp.substring(sp.indexOf('|') < 0 ? sp.length() : sp.indexOf('|')));
                    tiles = true;
                    break outer;
                }
            }
        }
        if (tiles) { Tiles.save(prefs, pages); afterEdit(true); }
        Ui.toast(this, "Folder “" + f.name + "” made. Tap it to open or rename.");
    }

    private String folderNameFor(Entry e) {
        try {
            int cat = -1;
            try { cat = getPackageManager().getApplicationInfo(e.pkg, 0).category; } catch (Exception ignored) { }
            String n = e.component == null ? null : Folders.category(LineIcons.kindFor(e.component, e.origLabel, cat, LineIcons.roles(this)));
            return n != null ? n : "Folder";
        } catch (Exception ex) {
            return "Folder";
        }
    }

    private AppCell tempCell; // stand-in for an app dragged out of a folder from the list

    /** Takes the stand-in away; when dropped on a page or the dock, the app also leaves its folder. */
    private void dropTempCell(boolean placed) {
        AppCell c = tempCell;
        tempCell = null;
        if (c == null) return;
        if (placed && c.entry != null) {
            List<Folders.Folder> fl = Folders.load(prefs);
            Folders.Folder in = Folders.containing(fl, c.entry.key);
            if (in != null) { in.keys.remove(c.entry.key); Folders.save(prefs, fl); }
        }
    }

    /** Hold an app in the list: the list closes and its home icon follows the finger, to drop on any page or the dock. */
    private void dragFromList(Entry e) {
        AppCell found = null;
        if (!paged()) { showAppMenu(e); return; }
        if (!prefs.bool("hideAppPages", false)) {
            for (AppCell x : cells) {
                if (x.entry == null || !x.entry.key.equals(e.key)) continue;
                if (found == null || found.getParent() == dock) found = x;
            }
        }
        AppCell c = found;
        if (c == null) { // in a folder or no app pages: a stand-in icon (dropped on the app pages it leaves its folder)
            c = cellFor(e, gridLabels(), style.ink, gridIconPx());
            int w = dp(84), h = gridIconPx() + dp(36);
            c.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
            c.layout(0, 0, w, h);
            tempCell = c;
        }
        closeDrawer(false);
        startIconDrag(c);
        Ui.toast(this, "Drag onto a page or the dock, then let go");
    }

    // ------------------------------------------------------------------ actions

    void launch(Entry e, View v) {
        if (e.kind == Entry.APP) Folders.recordLaunch(prefs, e.key);
        Rect r = new Rect();
        v.getGlobalVisibleRect(r);
        Bundle opts = ActivityOptions.makeClipRevealAnimation(v, 0, 0, v.getWidth(), v.getHeight()).toBundle();
        try {
            if (e.kind == Entry.APP) la.startMainActivity(e.component, e.user, r, opts);
            else la.startShortcut(e.shortcut, r, opts);
        } catch (Exception ex) {
            Ui.toast(this, "Can't open " + e.label);
        }
    }

    private void openNotification(StatusBarNotification sbn, Entry e, View v) {
        PendingIntent pi = sbn.getNotification().contentIntent;
        if (pi == null) { launch(e, v); return; }
        try {
            Bundle opts = null;
            if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions o = ActivityOptions.makeBasic();
                o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                opts = o.toBundle();
            }
            pi.send(this, 0, null, null, null, null, opts);
            if ((sbn.getNotification().flags & android.app.Notification.FLAG_AUTO_CANCEL) != 0) NotifService.dismiss(sbn);
        } catch (Exception ex) {
            launch(e, v);
        }
    }

    private void webSearch(String q) {
        try {
            startActivity(new Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q));
        } catch (ActivityNotFoundException ex) {
            safeStart(new Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=" + Uri.encode(q))));
        }
    }

    private void openCalendar() {
        Uri.Builder b = CalendarContract.CONTENT_URI.buildUpon().appendPath("time");
        ContentUris.appendId(b, System.currentTimeMillis());
        safeStart(new Intent(Intent.ACTION_VIEW).setData(b.build()));
    }

    void safeStart(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Ui.toast(this, "No app can handle that");
        }
    }

    private void showHomeMenu() {
        Sheet s = new Sheet(this, null, null);
        s.item("Add widget", widgets::showPicker);
        s.item("Change wallpaper", () -> safeStart(Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper")));
        s.item("Launcher settings", () -> startActivity(new Intent(this, SettingsActivity.class)));
        if (!NotifService.granted(this))
            s.item("Turn on notification previews", () -> safeStart(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        if (!SettingsActivity.isDefaultHome(this))
            s.item("Make Reach the default home app", () -> SettingsActivity.requestDefaultHome(this));
        s.show();
    }

    private void showAppMenu(Entry e) {
        Sheet s = new Sheet(this, e.label + (e.work ? " (work)" : ""), e.icon);
        if (e.kind == Entry.APP) {
            if (repo.hasShortcutAccess()) {
                List<ShortcutInfo> shortcuts = repo.appShortcuts(e);
                int density = getResources().getDisplayMetrics().densityDpi;
                for (ShortcutInfo si : shortcuts) {
                    Drawable icon = null;
                    try { icon = la.getShortcutIconDrawable(si, density); } catch (Exception ignored) { }
                    CharSequence label = si.getShortLabel() != null ? si.getShortLabel() : si.getId();
                    s.item(icon, label, () -> startShortcut(si, e), () -> pinShortcut(si, e));
                }
                if (!shortcuts.isEmpty()) s.note("Long-press a shortcut to pin it to favorites").divider();
            } else {
                s.note("Make Reach your default home app to see app shortcuts").divider();
            }
        }
        boolean fav = prefs.isFavorite(e.key);
        if (fav) {
            List<String> favs = prefs.favorites();
            int i = favs.indexOf(e.key);
            if (i > 0) s.item("Move up", () -> { prefs.moveFavorite(e.key, -1); refreshMeta(); });
            if (i < favs.size() - 1) s.item("Move down", () -> { prefs.moveFavorite(e.key, 1); refreshMeta(); });
            s.item("Remove from favorites", () -> {
                prefs.removeFavorite(e.key);
                if (e.kind == Entry.SHORTCUT) {
                    repo.syncPins(e.pkg, e.user, e.serial);
                    snap.shortcuts.remove(e.key);
                }
                refreshMeta();
            });
        } else {
            s.item("Add to favorites", () -> { prefs.addFavorite(e.key); refreshMeta(); });
        }
        s.item("Rename", () -> rename(e));
        if (e.kind == Entry.APP) {
            List<Folders.Folder> fl = Folders.load(prefs);
            Folders.Folder in = Folders.containing(fl, e.key);
            s.item(in == null ? "Add to folder…" : "Move to another folder…", () -> addToFolder(e));
            if (in != null) s.item("Remove from “" + in.name + "”", () -> {
                in.keys.remove(e.key);
                Folders.save(prefs, fl);
                markSynced();
                rebuildFavorites();
            });
            s.item(e.hidden ? "Show in app list" : "Hide from app list", () -> {
                prefs.setHidden(e.key, !e.hidden);
                refreshMeta();
                if (!e.hidden) Ui.toast(this, "Hidden. Unhide it in Launcher settings → Hidden apps.");
            });
            s.item("App info", () -> {
                try { la.startAppDetailsActivity(e.component, e.user, null, null); } catch (Exception ignored) { }
            });
            s.item("Uninstall", () -> safeStart(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + e.pkg))
                    .putExtra(Intent.EXTRA_USER, e.user)));
        }
        s.show();
    }

    private void startShortcut(ShortcutInfo si, Entry app) {
        try { la.startShortcut(si, null, null); }
        catch (Exception ex) { Ui.toast(this, "Can't open shortcut"); }
    }

    private void pinShortcut(ShortcutInfo si, Entry app) {
        if (snap == null) return;
        Entry e = repo.shortcutEntry(si, app.user, app.serial);
        snap.shortcuts.put(e.key, e);
        prefs.addFavorite(e.key);
        repo.syncPins(si.getPackage(), app.user, app.serial);
        refreshMeta();
        Ui.toast(this, "Pinned " + e.label);
    }

    private void rename(Entry e) {
        EditText et = new EditText(this);
        et.setText(e.label);
        et.setSingleLine(true);
        et.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(et);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Rename")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String n = et.getText().toString().trim();
                    prefs.setRename(e.key, n.equals(e.origLabel) ? null : n);
                    refreshMeta();
                })
                .setNeutralButton("Original", (d, w) -> { prefs.setRename(e.key, null); refreshMeta(); })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
