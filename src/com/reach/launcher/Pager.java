package com.reach.launcher;

import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.OverScroller;

/** Minimal horizontal pager: full-size pages side by side, drag to scroll, snap on release. */
final class Pager extends ViewGroup {
    interface Listener {
        void onPageChanged(int page);
        void onPagerSized(int w, int h);
    }

    private final OverScroller scroller;
    private final int slop, minFling;
    private VelocityTracker vt;
    private float downX, downY, lastX;
    private boolean dragging;
    private int current;
    private Listener listener;

    Pager(Context c) {
        super(c);
        scroller = new OverScroller(c, new DecelerateInterpolator(1.6f));
        ViewConfiguration vc = ViewConfiguration.get(c);
        slop = vc.getScaledTouchSlop();
        minFling = vc.getScaledMinimumFlingVelocity() * 4;
    }

    void setListener(Listener l) { listener = l; }

    int current() { return current; }

    // pages outside [minPage, maxPage] are removed from the home screen (still kept, just not reachable)
    private int minPage = 0, maxPage = Integer.MAX_VALUE;

    void setRange(int lo, int hi) {
        minPage = Math.max(0, lo);
        maxPage = hi;
        setPage(current, false);
    }

    int lo() { return Math.max(0, Math.min(minPage, getChildCount() - 1)); }

    int hi() { return Math.max(lo(), Math.min(maxPage, getChildCount() - 1)); }

    boolean shown(int page) { return page >= lo() && page <= hi(); }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
        int cw = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), ch = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).measure(cw, ch);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).layout(i * w, 0, (i + 1) * w, h);
        if (scroller.isFinished() && (!dragging || !touching)) {
            dragging = false;
            current = Math.max(lo(), Math.min(hi(), current));
            scrollTo(current * w, 0); // never rest between pages
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (listener != null && w > 0 && h > 0) post(() -> listener.onPagerSized(w, h));
    }

    private int maxScroll() { return hi() * getWidth(); }

    private int minScroll() { return lo() * getWidth(); }

    /** True only while a finger is actually down on the pager. */
    private boolean touching;

    void setPage(int page, boolean animate) {
        dragging = false;
        page = Math.max(lo(), Math.min(hi(), page));
        boolean changed = page != current;
        current = page;
        int target = page * getWidth();
        if (animate && getWidth() > 0) {
            scroller.startScroll(getScrollX(), 0, target - getScrollX(), 0, 280);
            postInvalidateOnAnimation();
        } else {
            scroller.forceFinished(true);
            scrollTo(target, 0);
        }
        if (changed && listener != null) listener.onPageChanged(page);
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.getCurrX(), 0);
            postInvalidateOnAnimation();
        } else if (!touching && getWidth() > 0 && getScrollX() != current * getWidth()) {
            // self-healing: with no finger down the pager always rests exactly on a page
            dragging = false;
            setPage(current, true);
        }
    }

    /** Called for every finger-up on the screen, even ones this view never saw (edit mode, drags). */
    void fingerUp() {
        touching = false;
        if (dragging) {
            dragging = false;
            recycle();
            setPage(Math.round(getScrollX() / (float) Math.max(1, getWidth())), true);
        } else if (scroller.isFinished() && getScrollX() != current * getWidth()) {
            setPage(current, true);
        }
    }

    private void track(MotionEvent e) {
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(e);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        track(e);
        int am = e.getActionMasked();
        touching = am != MotionEvent.ACTION_UP && am != MotionEvent.ACTION_CANCEL;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = e.getX();
                downY = e.getY();
                dragging = !scroller.isFinished();
                if (dragging) scroller.forceFinished(true);
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = Math.abs(e.getX() - downX), dy = Math.abs(e.getY() - downY);
                if (dx > slop && dx > dy * 1.2f) {
                    dragging = true;
                    lastX = e.getX();
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) recycle();
                break;
        }
        return dragging;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        track(e);
        int am = e.getActionMasked();
        touching = am != MotionEvent.ACTION_UP && am != MotionEvent.ACTION_CANCEL;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = e.getX();
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (!dragging && Math.abs(e.getX() - downX) > slop) dragging = true;
                if (dragging) {
                    float delta = lastX - e.getX();
                    int x = getScrollX();
                    if (x + delta < minScroll() || x + delta > maxScroll()) delta /= 3f; // resist past the edges
                    scrollTo((int) (x + delta), 0);
                }
                lastX = e.getX();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                int w = Math.max(1, getWidth());
                int target = Math.round(getScrollX() / (float) w);
                if (vt != null) {
                    vt.computeCurrentVelocity(1000);
                    float v = vt.getXVelocity();
                    if (v < -minFling) target = current + 1;
                    else if (v > minFling) target = current - 1;
                }
                dragging = false;
                recycle();
                setPage(target, true);
                return true;
            }
        }
        return true;
    }

    private void recycle() {
        if (vt != null) { vt.recycle(); vt = null; }
    }

    /** Removes every page after the first `keep`. */
    void trimTo(int keep) {
        while (getChildCount() > keep) removeViewAt(getChildCount() - 1);
    }

    void addPage(View v) { addView(v, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)); }
}
