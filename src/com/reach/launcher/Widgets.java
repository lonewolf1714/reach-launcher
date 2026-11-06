package com.reach.launcher;

import android.app.Activity;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.UserHandle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hosts standard Android app widgets in a vertical column. Each slot holds one widget or a stack
 * of several (swipe up/down on a stack to flip through it). Slots get the theme's widget style.
 */
final class Widgets {
    static final int HOST_ID = 0x52454143;
    static final int REQ_BIND = 101, REQ_CONFIG = 102;

    /** Widget view that lets a long press through even when the widget's own views handle touches. */
    static final class HostView extends AppWidgetHostView {
        private final int slop;
        private float downX, downY;
        private boolean fired;
        private final Runnable longPress = () -> { fired = true; performLongClick(); };

        HostView(Context c) {
            super(c);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // a scrolling list inside the widget (e.g. Widget Studio's App List) gets vertical swipes
                    if (scrollableAt(this, e.getX(), e.getY())) getParent().requestDisallowInterceptTouchEvent(true);
                    fired = false;
                    downX = e.getX();
                    downY = e.getY();
                    postDelayed(longPress, Math.max(ViewConfiguration.getLongPressTimeout(), Ui.holdMs));
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(e.getX() - downX) > slop || Math.abs(e.getY() - downY) > slop)
                        removeCallbacks(longPress);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(longPress);
                    break;
            }
            return fired;
        }
    }

    /** True if a view under (x, y) in root's coordinates can scroll vertically. */
    static boolean scrollableAt(View root, float x, float y) {
        if (!(root instanceof ViewGroup)) return false;
        ViewGroup g = (ViewGroup) root;
        for (int i = g.getChildCount() - 1; i >= 0; i--) {
            View ch = g.getChildAt(i);
            if (ch.getVisibility() != View.VISIBLE) continue;
            float cx = x - ch.getLeft() + g.getScrollX(), cy = y - ch.getTop() + g.getScrollY();
            if (cx < 0 || cy < 0 || cx >= ch.getWidth() || cy >= ch.getHeight()) continue;
            if (ch instanceof android.widget.AbsListView || ch instanceof android.widget.ScrollView
                    || ch.canScrollVertically(1) || ch.canScrollVertically(-1)) return true;
            if (scrollableAt(ch, cx, cy)) return true;
        }
        return false;
    }

    static final class Host extends AppWidgetHost {
        Host(Context c, int id) { super(c, id); }

        @Override
        protected AppWidgetHostView onCreateView(Context c, int id, AppWidgetProviderInfo info) {
            return new HostView(c);
        }
    }

    /** Several widgets in one slot; vertical swipe flips to the next/previous one. */
    static final class Stack extends FrameLayout {
        final List<View> items = new ArrayList<>();
        int index;
        private final LinearLayout dots;
        private final int slop;
        private float downX, downY;
        private boolean dragging, decided;
        private Prefs prefs;
        private String key;

        Stack(Context c) {
            super(c);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            dots = new LinearLayout(c);
            dots.setOrientation(LinearLayout.VERTICAL);
            setClipChildren(true);
        }

        /** Remembers which card is showing across home-screen rebuilds. */
        void remember(Prefs p, int firstId) {
            prefs = p;
            key = "wsi:" + firstId;
            int saved = p.integer(key, 0);
            if (saved > 0 && saved < items.size()) {
                items.get(index).setVisibility(GONE);
                index = saved;
                items.get(index).setVisibility(VISIBLE);
            }
        }

        void add(View v) {
            items.add(v);
            addView(v, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            v.setVisibility(items.size() - 1 == index ? VISIBLE : GONE);
        }

        void finish() {
            removeView(dots);
            LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
            lp.setMarginEnd(Ui.dp(getContext(), 4));
            addView(dots, lp);
            updateDots();
        }

        private void updateDots() {
            dots.removeAllViews();
            for (int i = 0; i < items.size(); i++) {
                View d = new View(getContext());
                d.setBackground(Ui.round(i == index ? 0xE6FFFFFF : 0x66FFFFFF, Ui.dp(getContext(), 3)));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(getContext(), 5), Ui.dp(getContext(), 5));
                lp.setMargins(0, Ui.dp(getContext(), 3), 0, Ui.dp(getContext(), 3));
                dots.addView(d, lp);
            }
        }

        void show(int i, int dir) {
            if (items.isEmpty()) return;
            i = (i + items.size()) % items.size();
            if (i == index) return;
            View out = items.get(index), in = items.get(i);
            int h = getHeight();
            in.setVisibility(VISIBLE);
            in.setTranslationY(dir * h * 0.35f);
            in.setAlpha(0f);
            in.animate().translationY(0).alpha(1f).setDuration(220).start();
            out.animate().translationY(-dir * h * 0.35f).alpha(0f).setDuration(200)
                    .withEndAction(() -> { out.setVisibility(GONE); out.setTranslationY(0); out.setAlpha(1f); }).start();
            index = i;
            updateDots();
            if (prefs != null) prefs.setQuietInt(key, index);
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        }

        /** True when a touch at this screen point is on a stack that can flip. */
        boolean flippableAt(float rawX, float rawY) {
            if (items.size() < 2 || !isShown()) return false;
            int[] p = new int[2];
            getLocationOnScreen(p);
            return rawX >= p[0] && rawX < p[0] + getWidth() && rawY >= p[1] && rawY < p[1] + getHeight();
        }

        View current() { return items.isEmpty() ? null : items.get(index); }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            if (items.size() < 2) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    dragging = false;
                    decided = false;
                    // claim the gesture now, before the scrolling page or the pager take it
                    getParent().requestDisallowInterceptTouchEvent(true);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (decided) break;
                    float dy = e.getY() - downY, dx = e.getX() - downX;
                    if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) { // sideways: hand it to the pager
                        decided = true;
                        getParent().requestDisallowInterceptTouchEvent(false);
                    } else if (Math.abs(dy) > slop) {
                        decided = true;
                        dragging = true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    getParent().requestDisallowInterceptTouchEvent(false);
                    break;
            }
            return dragging;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            View cur = current();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    if (items.size() > 1) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging && items.size() > 1 && Math.abs(e.getY() - downY) > slop) dragging = true;
                    if (dragging && cur != null) { // the card follows the finger
                        float dy = e.getY() - downY;
                        cur.setTranslationY(dy * 0.6f);
                        cur.setAlpha(1f - Math.min(.6f, Math.abs(dy) / Math.max(1, getHeight())));
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    getParent().requestDisallowInterceptTouchEvent(false);
                    if (dragging) {
                        float dy = e.getY() - downY;
                        if (e.getActionMasked() == MotionEvent.ACTION_UP && Math.abs(dy) > Math.min(getHeight() * 0.12f, slop * 4)) {
                            show(index + (dy < 0 ? 1 : -1), dy < 0 ? 1 : -1);
                        } else if (cur != null) {
                            cur.animate().translationY(0).alpha(1f).setDuration(160).start();
                        }
                    }
                    dragging = false;
                    return true;
            }
            return true;
        }
    }

    private final HomeActivity a;
    private final Prefs prefs;
    private final LauncherApps la;
    private final LinearLayout column;
    final AppWidgetManager awm;
    final Host host;
    private int pendingId = -1;
    /** Slot views in order; the column holds them in rows (spans out of COLS per row). */
    private final List<View> slotViews = new ArrayList<>();
    static final int COLS = 4;
    /** Slot index the widget being added should stack onto; -1 = new slot. */
    private int pendingSlot = -1;

    Widgets(HomeActivity a, Prefs prefs, LauncherApps la, LinearLayout column) {
        this.a = a;
        this.prefs = prefs;
        this.la = la;
        this.column = column;
        awm = AppWidgetManager.getInstance(a);
        host = new Host(a, HOST_ID);
    }

    /** Rebuilds the column from prefs, dropping widgets whose provider is gone. */
    /** Ids whose provider isn't connected yet, and how many refreshes we've waited for them. */
    private final Map<Integer, Integer> waiting = new HashMap<>();
    private boolean retryPosted;

    void restore() {
        column.removeAllViews();
        for (View v : slotViews) detach(v);
        slotViews.clear();
        List<String> slots = prefs.widgetSlots(), keepSlots = new ArrayList<>();
        List<Integer> keepIds = new ArrayList<>();
        boolean needRetry = false;
        for (String slot : slots) {
            List<Integer> ok = new ArrayList<>(), stillWaiting = new ArrayList<>();
            Map<Integer, AppWidgetProviderInfo> infos = new HashMap<>();
            for (int id : Prefs.idsOf(slot)) {
                AppWidgetProviderInfo info = awm.getAppWidgetInfo(id);
                if (info == null) {
                    // a widget that was just added (e.g. from Widget Studio's "Add" button) is bound a moment
                    // later: keep it and look again, and only give up after several tries
                    int n = waiting.containsKey(id) ? waiting.get(id) + 1 : 1;
                    if (n <= 6) { waiting.put(id, n); stillWaiting.add(id); needRetry = true; }
                    else { waiting.remove(id); host.deleteAppWidgetId(id); }
                    continue;
                }
                waiting.remove(id);
                ok.add(id);
                infos.put(id, info);
            }
            keepIds.addAll(ok);
            keepIds.addAll(stillWaiting);
            List<Integer> all = new ArrayList<>(ok);
            all.addAll(stillWaiting);
            if (all.isEmpty()) continue;
            keepSlots.add(Prefs.slotOf(all));
            if (!ok.isEmpty()) addSlot(keepSlots.size() - 1, ok, infos);
        }
        if (!keepSlots.equals(slots)) prefs.setWidgetSlots(keepSlots);
        a.widgetsChanged(); // the home grid places widget views alongside tiles
        int[] allocated = host.getAppWidgetIds();
        if (allocated != null) for (int id : allocated) if (!keepIds.contains(id) && id != pendingId) host.deleteAppWidgetId(id);
        if (needRetry && !retryPosted) {
            retryPosted = true;
            column.postDelayed(() -> { retryPosted = false; restore(); a.markSynced(); }, 1200);
        }
    }

    private int defaultHeight(AppWidgetProviderInfo info) {
        return Math.max(Ui.dp(a, 72), info.minHeight + Ui.dp(a, 8));
    }

    private void addSlot(int slot, List<Integer> ids, Map<Integer, AppWidgetProviderInfo> infos) {
        int first = ids.get(0);
        int h = prefs.widgetHeight(first);
        if (h <= 0) {
            for (int id : ids) h = Math.max(h, defaultHeight(infos.get(id)));
        }
        View v;
        if (ids.size() == 1) {
            v = host.createView(a, first, infos.get(first));
            v.setOnLongClickListener(x -> { a.editWidget(slotIndexOf(x)); return true; });
        } else {
            Stack st = new Stack(a);
            for (int id : ids) {
                AppWidgetHostView hv = host.createView(a, id, infos.get(id));
                hv.setOnLongClickListener(x -> { a.editWidget(slotIndexOf(st)); return true; });
                st.add(hv);
            }
            st.finish();
            st.remember(prefs, first);
            v = st;
        }
        v.setTag(Prefs.slotOf(ids));
        v.setLayoutParams(new LinearLayout.LayoutParams(0, h, spanOf(ids.get(0))));
        style(v);
        slotViews.add(v);
    }

    private static void detach(View v) {
        if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
    }

    int spanOf(int firstId) { return Math.max(1, Math.min(COLS, prefs.integer("ws:" + firstId, COLS))); }

    /** Free placement: empty space above the slot (px) and a column offset within its row. */
    int gapOf(int slot) {
        View v = slotView(slot);
        return v == null ? 0 : prefs.integer("wg:" + Prefs.idsOf((String) v.getTag()).get(0), 0);
    }

    int xOf(int slot) {
        View v = slotView(slot);
        return v == null ? 0 : prefs.integer("wx:" + Prefs.idsOf((String) v.getTag()).get(0), 0);
    }

    void setPlacement(int slot, int gapPx, int xCells) {
        View v = slotView(slot);
        if (v == null) return;
        int first = Prefs.idsOf((String) v.getTag()).get(0);
        prefs.set("wg:" + first, Math.max(0, gapPx));
        prefs.set("wx:" + first, Math.max(0, Math.min(COLS - 1, xCells)));
        a.markSynced();
        layoutRows();
    }

    int spanOfSlot(int slot) {
        View v = slotView(slot);
        return v == null ? COLS : spanOf(Prefs.idsOf((String) v.getTag()).get(0));
    }

    /** Puts slot views into rows: left to right, wrapping when a row's spans would exceed COLS. */
    void layoutRows() {
        for (View v : slotViews) detach(v);
        column.removeAllViews();
        java.util.Set<Integer> parked = Layouts.parked(prefs); // used by another theme's layout, not this one
        int gap = Ui.dp(a, 12);
        LinearLayout row = null;
        int used = 0;
        for (View v : slotViews) {
            if (parked.contains(Prefs.idsOf((String) v.getTag()).get(0))) continue;
            int span = Math.max(1, Math.min(COLS, Math.round(((LinearLayout.LayoutParams) v.getLayoutParams()).weight)));
            int first = Prefs.idsOf((String) v.getTag()).get(0);
            int gapAbove = prefs.integer("wg:" + first, 0);
            int x = Math.max(0, Math.min(COLS - span, prefs.integer("wx:" + first, 0)));
            if (gapAbove > 0 || x > 0) span += 0; // placed widgets start their own row when they need space
            if (row != null && (gapAbove > 0 || used + x + span > COLS)) used = COLS; // force a new row
            if (row == null || used + span > COLS) {
                if (row != null && used < COLS) row.addView(new View(a), new LinearLayout.LayoutParams(0, 1, COLS - used));
                row = new LinearLayout(a);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rl.bottomMargin = Ui.dp(a, 14);
                column.addView(row, rl);
                used = 0;
            }
            if (used == 0 && x > 0) { // column offset: empty cells before the widget
                row.addView(new View(a), new LinearLayout.LayoutParams(0, 1, x));
                used += x;
            }
            if (gapAbove > 0) ((LinearLayout.LayoutParams) row.getLayoutParams()).topMargin = gapAbove;
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
            lp.width = 0;
            lp.weight = span;
            lp.setMarginStart(used == 0 ? 0 : gap / 2);
            lp.setMarginEnd(used + span >= COLS ? 0 : gap / 2);
            row.addView(v, lp);
            used += span;
        }
        if (row != null && used < COLS) row.addView(new View(a), new LinearLayout.LayoutParams(0, 1, COLS - used));
        for (View v : slotViews) v.post(() -> updateSize(v, v.getLayoutParams().height));
    }

    /** Width in quarter columns (1..4). */
    void setSpan(int slot, int span, boolean save) {
        View v = slotView(slot);
        if (v == null) return;
        span = Math.max(1, Math.min(COLS, span));
        ((LinearLayout.LayoutParams) v.getLayoutParams()).weight = span;
        layoutRows();
        if (save) {
            for (int id : Prefs.idsOf((String) v.getTag())) prefs.set("ws:" + id, span);
            a.markSynced();
        }
    }

    /** Moves a slot to another position in the flow. */
    void moveTo(int from, int to) {
        List<String> slots = prefs.widgetSlots();
        if (from < 0 || from >= slots.size() || from >= slotViews.size()) return;
        to = Math.max(0, Math.min(slots.size() - 1, to));
        if (to == from) { layoutRows(); return; }
        slots.add(to, slots.remove(from));
        slotViews.add(to, slotViews.remove(from));
        prefs.setWidgetSlots(slots);
        a.markSynced();
        layoutRows();
    }

    int slotIndexOf(View v) { return slotViews.indexOf(v); }

    int firstIdOf(int slot) {
        View v = slotView(slot);
        return v == null ? -1 : Prefs.idsOf((String) v.getTag()).get(0);
    }

    int slotOfId(int firstId) {
        for (int i = 0; i < slotViews.size(); i++) if (firstIdOf(i) == firstId) return i;
        return -1;
    }

    /** The slot's view, detached so the grid can place it. */
    /** True when a touch at this screen point lands on a stack of two or more widgets. */
    boolean stackAt(float rawX, float rawY) {
        for (View v : slotViews) if (v instanceof Stack && ((Stack) v).flippableAt(rawX, rawY)) return true;
        return false;
    }

    View viewForId(int firstId) {
        int s = slotOfId(firstId);
        if (s < 0) return null;
        View v = slotViews.get(s);
        detach(v);
        return v;
    }

    void sizeChanged(View v) { v.post(() -> updateSize(v, v.getHeight() > 0 ? v.getHeight() : v.getLayoutParams().height)); }

    void removeSlot(int slot) {
        View v = slotView(slot);
        if (v == null) return;
        for (int id : Prefs.idsOf((String) v.getTag())) {
            host.deleteAppWidgetId(id);
            prefs.removeWidget(id);
        }
        a.markSynced();
        restore();
    }

    /** Theme widget style: background (none / glass / dark / surface) and corner radius. */
    void style(View v) {
        String bg = prefs.s(Prefs.WIDGET_BG);
        int r = Ui.dp(a, prefs.i(Prefs.WIDGET_RADIUS));
        GradientDrawable d = null;
        switch (bg) {
            case "glass": d = Ui.round(0x24FFFFFF, r); d.setStroke(Ui.dp(a, 1), 0x2EFFFFFF); break;
            case "dark": d = Ui.round(0x8C000000, r); break;
            case "surface": d = Ui.round(prefs.surface(), r); break;
        }
        if ("outlined".equals(prefs.s(Prefs.TILE_STYLE))) {
            if (d == null) d = Ui.round(0x00000000, r);
            d.setStroke(Ui.dp(a, 2.5f), prefs.ink());
        }
        v.setBackground(d);
        int pad = d == null ? 0 : Ui.dp(a, 8);
        v.setPadding(pad, pad, pad, pad);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) { o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r); }
        });
        v.setClipToOutline(r > 0);
        v.setElevation(d != null && prefs.depth() > 0 ? Ui.dp(a, prefs.depth() * 2) : 0);
    }

    void applyStyle() {
        for (View v : slotViews) style(v);
    }

    @SuppressWarnings("deprecation")
    void updateSize(View v, int hPx) {
        float d = a.getResources().getDisplayMetrics().density;
        int w = (int) (v.getWidth() / d), h = (int) (hPx / d);
        if (w <= 0) return;
        if (v instanceof AppWidgetHostView) ((AppWidgetHostView) v).updateAppWidgetSize(new Bundle(), w, h, w, h);
        else if (v instanceof Stack) for (View x : ((Stack) v).items)
            if (x instanceof AppWidgetHostView) ((AppWidgetHostView) x).updateAppWidgetSize(new Bundle(), w, h, w, h);
    }

    int slotCount() { return slotViews.size(); }

    View slotView(int slot) { return slot >= 0 && slot < slotViews.size() ? slotViews.get(slot) : null; }

    int heightOf(int slot) {
        View v = slotView(slot);
        return v == null ? 0 : v.getLayoutParams().height;
    }

    /** Live resize; save=true stores it. */
    void setHeight(int slot, int px, boolean save) {
        View v = slotView(slot);
        if (v == null) return;
        int h = Math.max(Ui.dp(a, 48), px);
        v.getLayoutParams().height = h;
        v.requestLayout();
        if (save) {
            for (int id : Prefs.idsOf((String) v.getTag())) prefs.setWidgetHeight(id, h);
            a.markSynced();
            updateSize(v, h);
        }
    }

    private String nameOf(int id) {
        AppWidgetProviderInfo info = awm.getAppWidgetInfo(id);
        return info == null ? "Widget" : info.loadLabel(a.getPackageManager());
    }

    /** The menu for a widget slot (also opened from edit mode). */
    void menu(int slot) {
        View v = slotView(slot);
        if (v == null) return;
        List<Integer> ids = Prefs.idsOf((String) v.getTag());
        boolean stack = v instanceof Stack;
        int currentId = stack ? ids.get(((Stack) v).index) : ids.get(0);
        AppWidgetProviderInfo info = awm.getAppWidgetInfo(currentId);
        Sheet s = new Sheet(a, stack ? "Stack · " + nameOf(currentId) : nameOf(currentId),
                info == null ? null : info.loadIcon(a, a.getResources().getDisplayMetrics().densityDpi));
        s.item("Resize or move (handles)", () -> a.editWidget(slot));
        s.item("Add widget to this stack", () -> showPicker(slot));
        if (stack) {
            s.item("Show next in stack", () -> ((Stack) v).show(((Stack) v).index + 1, 1));
            s.item("Unstack (separate them)", () -> unstack(slot));
        }
        if (info != null && info.configure != null && Build.VERSION.SDK_INT >= 28) {
            s.item("Widget settings", () -> {
                try { host.startAppWidgetConfigureActivityForResult(a, currentId, 0, 0, null); }
                catch (Exception e) { Ui.toast(a, "This widget can't be reconfigured"); }
            });
        }
        s.divider();
        if (stack) s.item("Remove “" + nameOf(currentId) + "” from stack", () -> removeId(currentId));
        s.item(stack ? "Remove whole stack" : "Remove widget", () -> { for (int id : ids) removeId(id); });
        s.show();
    }

    void move(int slot, int delta) { moveTo(slot, slot + delta); }

    private void unstack(int slot) {
        List<String> slots = prefs.widgetSlots();
        if (slot < 0 || slot >= slots.size()) return;
        List<Integer> ids = Prefs.idsOf(slots.remove(slot));
        for (int i = 0; i < ids.size(); i++) slots.add(slot + i, String.valueOf(ids.get(i)));
        prefs.setWidgetSlots(slots);
        a.markSynced();
        restore();
    }

    private void removeId(int id) {
        host.deleteAppWidgetId(id);
        prefs.removeWidget(id);
        a.markSynced();
        restore();
    }

    // ---- adding ----

    /** stackOnto: slot index to stack the new widget onto, or -1 to ask (when widgets exist). */
    void showPicker(int stackOnto) {
        PackageManager pm = a.getPackageManager();
        List<AppWidgetProviderInfo> all = new ArrayList<>();
        for (UserHandle u : la.getProfiles()) {
            try { all.addAll(awm.getInstalledProvidersForProfile(u)); } catch (Exception ignored) { }
        }
        Map<String, String> appNames = new HashMap<>();
        for (AppWidgetProviderInfo i : all) {
            String pkg = i.provider.getPackageName();
            if (appNames.containsKey(pkg)) continue;
            String n = pkg;
            try { n = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); } catch (Exception ignored) { }
            if (pkg.equals(a.getPackageName())) n = "Widget Studio";
            appNames.put(pkg, n);
        }
        String self = a.getPackageName();
        Collections.sort(all, (x, y) -> {
            boolean xs = x.provider.getPackageName().equals(self), ys = y.provider.getPackageName().equals(self);
            if (xs != ys) return xs ? -1 : 1; // built-in Widget Studio widgets first
            int r = Ui.norm(appNames.get(x.provider.getPackageName()))
                    .compareTo(Ui.norm(appNames.get(y.provider.getPackageName())));
            return r != 0 ? r : Ui.norm(x.loadLabel(pm)).compareTo(Ui.norm(y.loadLabel(pm)));
        });
        if (all.isEmpty()) { Ui.toast(a, "No widgets available"); return; }
        Sheet s = new Sheet(a, stackOnto >= 0 ? "Add to stack" : "Add a widget", null).cap(0.82f);
        int density = a.getResources().getDisplayMetrics().densityDpi;
        Ios ui = Ios.of(a);
        List<Object[]> pending = new ArrayList<>(); // {info, ImageView} to fill with previews off the main thread
        String lastApp = null;
        for (AppWidgetProviderInfo info : all) {
            String app = appNames.get(info.provider.getPackageName());
            String w = info.loadLabel(pm);
            if (!app.equals(lastApp)) { // app header, iOS widget-gallery style
                lastApp = app;
                android.widget.TextView h = new android.widget.TextView(a);
                h.setText(app.toUpperCase());
                h.setTextColor(ui.sub);
                h.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
                h.setPadding(Ui.dp(a, 16), Ui.dp(a, 14), Ui.dp(a, 16), Ui.dp(a, 6));
                s.view(h);
            }
            LinearLayout card = new LinearLayout(a);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(Ui.ripple(a, Ui.round(ui.bg, Ui.dp(a, 16))));
            card.setPadding(Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 10));
            android.widget.ImageView preview = new android.widget.ImageView(a);
            preview.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            preview.setAdjustViewBounds(true);
            preview.setMaxHeight(Ui.dp(a, 190));
            preview.setImageDrawable(info.loadIcon(a, density)); // placeholder until the preview loads
            card.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 150)));
            LinearLayout foot = new LinearLayout(a);
            foot.setGravity(android.view.Gravity.CENTER_VERTICAL);
            foot.setPadding(0, Ui.dp(a, 10), 0, 0);
            LinearLayout texts = new LinearLayout(a);
            texts.setOrientation(LinearLayout.VERTICAL);
            android.widget.TextView name = new android.widget.TextView(a);
            name.setText(w);
            name.setTextColor(ui.text);
            name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
            name.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            texts.addView(name);
            android.widget.TextView size = new android.widget.TextView(a);
            int cw = Math.max(1, Math.round(info.minWidth / a.getResources().getDisplayMetrics().density / 70f));
            int ch = Math.max(1, Math.round(info.minHeight / a.getResources().getDisplayMetrics().density / 70f));
            size.setText(Math.min(4, cw) + " × " + Math.min(4, ch));
            size.setTextColor(ui.sub);
            size.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            texts.addView(size);
            foot.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            android.widget.TextView add = new android.widget.TextView(a);
            add.setText(stackOnto >= 0 ? "Add to stack" : "Add");
            add.setTextColor(0xFFFFFFFF);
            add.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
            add.setGravity(android.view.Gravity.CENTER);
            add.setPadding(Ui.dp(a, 18), 0, Ui.dp(a, 18), 0);
            add.setBackground(Ui.ripple(a, Ui.round(ui.tint, Ui.dp(a, 17))));
            foot.addView(add, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(a, 34)));
            card.addView(foot);
            View.OnClickListener go = v -> {
                s.dismiss();
                if (stackOnto >= 0 || slotCount() == 0) pick(info, stackOnto);
                else askPlacement(info);
            };
            card.setOnClickListener(go);
            add.setOnClickListener(go);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cl.setMargins(Ui.dp(a, 12), Ui.dp(a, 4), Ui.dp(a, 12), Ui.dp(a, 8));
            card.setLayoutParams(cl);
            s.view(card);
            pending.add(new Object[]{info, preview});
        }
        s.show();
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            for (Object[] o : pending) {
                AppWidgetProviderInfo info = (AppWidgetProviderInfo) o[0];
                Drawable d = null;
                try { d = info.loadPreviewImage(a, density); } catch (Exception ignored) { }
                if (d == null) continue;
                Drawable fd = d;
                android.widget.ImageView iv = (android.widget.ImageView) o[1];
                main.post(() -> iv.setImageDrawable(fd));
            }
        }).start();
    }

    void showPicker() { showPicker(-1); }

    /** New widget or stack it with an existing one. */
    private void askPlacement(AppWidgetProviderInfo info) {
        Sheet s = new Sheet(a, "Place " + info.loadLabel(a.getPackageManager()), null);
        s.item("As a new widget", () -> pick(info, -1));
        for (int i = 0; i < slotCount(); i++) {
            View v = slotView(i);
            List<Integer> ids = Prefs.idsOf((String) v.getTag());
            String name = nameOf(ids.get(0)) + (ids.size() > 1 ? " + " + (ids.size() - 1) + " more" : "");
            final int slot = i;
            s.item("Stack with " + name, () -> pick(info, slot));
        }
        s.show();
    }

    private void pick(AppWidgetProviderInfo info, int slot) {
        int id = host.allocateAppWidgetId();
        pendingId = id;
        pendingSlot = slot;
        boolean ok;
        try { ok = awm.bindAppWidgetIdIfAllowed(id, info.getProfile(), info.provider, null); }
        catch (Exception e) { ok = false; }
        if (ok) {
            configure(id, info);
        } else {
            Intent i = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.getProfile());
            a.startActivityForResult(i, REQ_BIND);
        }
    }

    private void configure(int id, AppWidgetProviderInfo info) {
        boolean optional = Build.VERSION.SDK_INT >= 28
                && (info.widgetFeatures & AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) != 0;
        if (info.configure != null && !optional) {
            try {
                host.startAppWidgetConfigureActivityForResult(a, id, 0, REQ_CONFIG, null);
                return;
            } catch (Exception ignored) { }
        }
        commit(id);
    }

    private void commit(int id) {
        pendingId = -1;
        AppWidgetProviderInfo info = awm.getAppWidgetInfo(id);
        if (info == null) { host.deleteAppWidgetId(id); return; }
        Layouts.unpark(prefs, id);
        if (pendingSlot >= 0) prefs.stackWidget(pendingSlot, id); else prefs.addWidget(id);
        pendingSlot = -1;
        a.markSynced();
        restore();
    }

    private void cancel() {
        if (pendingId != -1) host.deleteAppWidgetId(pendingId);
        pendingId = -1;
        pendingSlot = -1;
    }

    boolean onActivityResult(int req, int result) {
        if (req == REQ_BIND) {
            AppWidgetProviderInfo info = pendingId == -1 ? null : awm.getAppWidgetInfo(pendingId);
            if (result == Activity.RESULT_OK && info != null) configure(pendingId, info); else cancel();
            return true;
        }
        if (req == REQ_CONFIG) {
            if (result == Activity.RESULT_OK) commit(pendingId); else cancel();
            return true;
        }
        return false;
    }
}
