package com.reach.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.Collections;
import java.util.List;

/**
 * Edge strip of section letters. Letters only occupy the lower part of the screen so the whole
 * strip sits inside the thumb's natural reach; touches above it fall through to the home screen.
 */
final class AlphabetBar extends View {
    interface Listener {
        void onLetter(String letter, float y);
        void onRelease();
    }

    private static final float TOP_FRACTION = 0.30f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float maxText;
    private List<String> letters = Collections.emptyList();
    private int active = -1;
    private int color = 0xCCFFFFFF, accent = 0xFF8FD3C3;
    private Listener listener;

    AlphabetBar(Context c) {
        super(c);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setShadowLayer(4f, 0f, 1f, 0x66000000);
        maxText = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13, c.getResources().getDisplayMetrics());
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setLetters(List<String> l) {
        letters = l;
        active = -1;
        invalidate();
    }

    void setColors(int color, int accent) {
        this.color = color;
        this.accent = accent;
        invalidate();
    }

    void setListener(Listener l) { listener = l; }

    private float zoneTop() { return getHeight() * TOP_FRACTION; }

    private float slot() {
        return (getHeight() - zoneTop() - getPaddingBottom()) / Math.max(1, letters.size());
    }

    /** y is relative to this view. */
    boolean inZone(float y) { return !letters.isEmpty() && y >= zoneTop() && y <= getHeight() - getPaddingBottom(); }

    @Override
    protected void onDraw(Canvas canvas) {
        if (letters.isEmpty()) return;
        float slot = slot(), size = Math.min(slot * 0.72f, maxText), top = zoneTop(), cx = getWidth() / 2f;
        for (int i = 0; i < letters.size(); i++) {
            boolean on = i == active;
            paint.setColor(on ? accent : color);
            paint.setTextSize(on ? size * 1.4f : size);
            float cy = top + slot * i + slot / 2f;
            canvas.drawText(letters.get(i), cx, cy - (paint.descent() + paint.ascent()) / 2f, paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!inZone(e.getY())) return false;
                getParent().requestDisallowInterceptTouchEvent(true);
                // fall through
            case MotionEvent.ACTION_MOVE: {
                int i = (int) ((e.getY() - zoneTop()) / slot());
                i = Math.max(0, Math.min(letters.size() - 1, i));
                if (i != active && !letters.isEmpty()) {
                    active = i;
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    invalidate();
                    if (listener != null) listener.onLetter(letters.get(i), zoneTop() + slot() * i + slot() / 2f);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                active = -1;
                invalidate();
                if (listener != null) listener.onRelease();
                return true;
        }
        return true;
    }
}
