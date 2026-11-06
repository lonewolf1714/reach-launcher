package com.reach.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Letter strip for the app-list tile. While a finger slides along it, the letters near the finger
 * swell and lean out in a smooth wave, and the list jumps to the letter under the finger.
 */
final class WaveBar extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<String> letters = new ArrayList<>();
    private final Consumer<String> onLetter;
    private final int color, accent;
    private float touchY = -1, wave;          // finger position and the wave's current strength (animated)
    private int active = -1;
    private final float base;

    WaveBar(Context c, List<String> letters, int color, int accent, Typeface face, Consumer<String> onLetter) {
        super(c);
        this.letters.addAll(letters);
        this.onLetter = onLetter;
        this.color = color;
        this.accent = accent;
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.create(face, Typeface.BOLD));
        base = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10, c.getResources().getDisplayMetrics());
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    private float slot() { return getHeight() / (float) Math.max(1, letters.size()); }

    @Override
    protected void onDraw(Canvas cv) {
        if (letters.isEmpty()) return;
        float slot = slot(), radius = slot * 5.5f;
        for (int i = 0; i < letters.size(); i++) {
            float cy = slot * i + slot / 2f;
            float k = 0;
            if (touchY >= 0) {
                float d = Math.abs(cy - touchY) / radius;
                if (d < 1) k = (float) Math.pow(Math.cos(d * Math.PI / 2), 2) * wave; // smooth bell curve
            }
            float size = Math.min(slot * 0.8f, base) * (1 + 0.9f * k);
            p.setTextSize(size);
            p.setColor(i == active ? accent : Ui.alpha(color, (int) (0x99 + 0x66 * k)));
            float cx = getWidth() / 2f - k * Ui.dp(getContext(), 34); // leans out toward the list
            cv.drawText(letters.get(i), cx, cy - (p.descent() + p.ascent()) / 2f, p);
        }
        if (touchY >= 0 && wave < 1f) { wave = Math.min(1f, wave + 0.18f); postInvalidateOnAnimation(); }
        else if (touchY < 0 && wave > 0f) { wave = Math.max(0f, wave - 0.2f); postInvalidateOnAnimation(); }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                // fall through
            case MotionEvent.ACTION_MOVE: {
                touchY = e.getY();
                int i = Math.max(0, Math.min(letters.size() - 1, (int) (e.getY() / slot())));
                if (i != active && !letters.isEmpty()) {
                    active = i;
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    onLetter.accept(letters.get(i));
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touchY = -1;
                active = -1;
                invalidate();
                return true;
        }
        return true;
    }
}
