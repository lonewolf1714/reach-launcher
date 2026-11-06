package com.reach.launcher;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Bottom sheet menu: every action lands at the bottom of the screen, under the thumb. */
final class Sheet {
    private final Context c;
    private final Dialog d;
    private final LinearLayout list;
    private Ios ui;
    private float dimAmount = 0.35f;
    private int maxH;

    Sheet(Context c, CharSequence title, Drawable icon) {
        this.c = c;
        d = new Dialog(c);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout outer = new LinearLayout(c);
        outer.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        ui = Ios.of(c);
        bg.setColor(ui.dark ? 0xFF1C1C1E : 0xFFF2F2F7);
        float r = Ui.dp(c, 24);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        outer.setBackground(bg);
        outer.setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 12), Ui.dp(c, 28));

        View handle = new View(c);
        handle.setBackground(Ui.round(ui.chevron, Ui.dp(c, 2)));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 4));
        hl.gravity = Gravity.CENTER_HORIZONTAL;
        hl.bottomMargin = Ui.dp(c, 10);
        outer.addView(handle, hl);

        if (title != null) {
            LinearLayout head = new LinearLayout(c);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPadding(Ui.dp(c, 12), Ui.dp(c, 4), Ui.dp(c, 12), Ui.dp(c, 10));
            if (icon != null) {
                ImageView iv = new ImageView(c);
                iv.setImageDrawable(icon.getConstantState() != null
                        ? icon.getConstantState().newDrawable(c.getResources()) : icon);
                LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 36));
                il.setMarginEnd(Ui.dp(c, 14));
                head.addView(iv, il);
            }
            TextView t = new TextView(c);
            t.setText(title);
            t.setTextColor(ui.text);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            head.addView(t);
            outer.addView(head);
        }

        list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(Ui.round(ui.group, Ui.dp(c, 12)));
        list.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 4));
        ScrollView sv = new ScrollView(c) {
            @Override
            protected void onMeasure(int w, int h) {
                if (maxH > 0) h = MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST);
                super.onMeasure(w, h);
            }
        };
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(list);
        outer.addView(sv);
        d.setContentView(outer);
    }

    Sheet item(CharSequence label, Runnable click) { return item(null, label, click, null); }

    Sheet item(Drawable icon, CharSequence label, Runnable click, Runnable longClick) {
        return item(icon, label, null, false, click, longClick);
    }

    /** Row whose label is drawn in its own typeface, with a check when selected. */
    Sheet option(CharSequence label, Typeface face, boolean checked, Runnable click) {
        return item(null, label, face, checked, click, null);
    }

    Sheet item(Drawable icon, CharSequence label, Typeface face, boolean checked, Runnable click, Runnable longClick) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(c, 52));
        row.setPadding(Ui.dp(c, 12), Ui.dp(c, 6), Ui.dp(c, 12), Ui.dp(c, 6));
        row.setBackground(Ui.ripple(c, null));
        if (icon != null) {
            ImageView iv = new ImageView(c);
            iv.setImageDrawable(icon);
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(Ui.dp(c, 28), Ui.dp(c, 28));
            il.setMarginEnd(Ui.dp(c, 16));
            row.addView(iv, il);
        }
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextColor(ui.text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, face != null ? 18 : 16);
        if (face != null) t.setTypeface(face);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (checked) {
            TextView ck = new TextView(c);
            ck.setText("✓");
            ck.setTextColor(ui.tint);
            ck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            row.addView(ck);
        }
        row.setOnClickListener(v -> { d.dismiss(); click.run(); });
        if (longClick != null) row.setOnLongClickListener(v -> { d.dismiss(); longClick.run(); return true; });
        list.addView(row);
        return this;
    }

    /** Lighter dim, so the home screen behind stays visible while editing it. */
    Sheet dim(float amount) { dimAmount = amount; return this; }

    /** Caps the scrollable list to this fraction of the screen height. */
    Sheet cap(float fraction) { maxH = (int) (c.getResources().getDisplayMetrics().heightPixels * fraction); return this; }

    /** Adds any view (e.g. a text field) to the sheet. */
    Sheet view(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(c, 8), Ui.dp(c, 4), Ui.dp(c, 8), Ui.dp(c, 8));
        list.addView(v, lp);
        return this;
    }

    /** Call after show(): opens the keyboard for a text field in the sheet. */
    void showKeyboard(android.widget.EditText et) {
        Window w = d.getWindow();
        if (w != null) w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        et.requestFocus();
    }

    Sheet note(CharSequence text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(ui.sub);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setPadding(Ui.dp(c, 12), Ui.dp(c, 4), Ui.dp(c, 12), Ui.dp(c, 8));
        list.addView(t);
        return this;
    }

    Sheet divider() {
        View v = new View(c);
        v.setBackgroundColor(ui.sep);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(Ui.dp(c, 12), Ui.dp(c, 6), Ui.dp(c, 12), Ui.dp(c, 6));
        list.addView(v, lp);
        return this;
    }

    void dismiss() { d.dismiss(); }

    void show() {
        d.show();
        Window w = d.getWindow();
        if (w == null) return;
        w.setBackgroundDrawable(new ColorDrawable(0));
        w.setGravity(Gravity.BOTTOM);
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        w.setWindowAnimations(android.R.style.Animation_InputMethod);
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setDimAmount(dimAmount);
        if (android.os.Build.VERSION.SDK_INT >= 31 && dimAmount >= 0.2f) {
            w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            WindowManager.LayoutParams a = w.getAttributes();
            a.setBlurBehindRadius(Ui.dp(c, 28));
            w.setAttributes(a);
        }
    }
}
