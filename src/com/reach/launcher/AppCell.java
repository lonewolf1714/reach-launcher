package com.reach.launcher;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Grid cell for the paged layout: icon with a count badge, short label underneath. */
final class AppCell extends LinearLayout {
    final ImageView icon;
    final TextView label, badge;
    private final FrameLayout iconBox;
    Entry entry;
    int iconPx;

    AppCell(Context c) {
        super(c);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
        setBackground(Ui.ripple(c, null));
        setPadding(Ui.dp(c, 2), Ui.dp(c, 4), Ui.dp(c, 2), Ui.dp(c, 4));

        iconBox = new FrameLayout(c);
        icon = new ImageView(c);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iconBox.addView(icon, new FrameLayout.LayoutParams(Ui.dp(c, 56), Ui.dp(c, 56), Gravity.CENTER));
        badge = new TextView(c);
        badge.setGravity(Gravity.CENTER);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextColor(0xFF111315);
        badge.setMinWidth(Ui.dp(c, 20));
        badge.setPadding(Ui.dp(c, 5), 0, Ui.dp(c, 5), 0);
        badge.setElevation(Ui.dp(c, 2));
        badge.setVisibility(GONE);
        iconBox.addView(badge, new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, Ui.dp(c, 20), Gravity.TOP | Gravity.END));
        addView(iconBox, new LayoutParams(Ui.dp(c, 64), Ui.dp(c, 64)));

        label = new TextView(c);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        Ui.shadow(label);
        LayoutParams ll = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        ll.topMargin = Ui.dp(c, 4);
        addView(label, ll);
    }

    void bind(Entry e, int iconPx, Typeface face, int accent, int ink, int notifCount, boolean showLabel) {
        entry = e;
        this.iconPx = iconPx;
        Ui.ink(label, ink);
        FrameLayout.LayoutParams il = (FrameLayout.LayoutParams) icon.getLayoutParams();
        if (il.width != iconPx) {
            il.width = il.height = iconPx;
            icon.setLayoutParams(il);
            LayoutParams bl = (LayoutParams) iconBox.getLayoutParams();
            bl.width = bl.height = iconPx + Ui.dp(getContext(), 8);
            iconBox.setLayoutParams(bl);
        }
        Drawable d = e.icon;
        if (d != null && d.getConstantState() != null) d = d.getConstantState().newDrawable(getResources());
        icon.setImageDrawable(d);
        label.setText(e.label);
        label.setTypeface(face);
        label.setVisibility(showLabel ? VISIBLE : GONE);
        if (notifCount > 0) {
            badge.setText(String.valueOf(notifCount));
            badge.setBackground(Ui.round(accent, Ui.dp(getContext(), 10)));
            badge.setVisibility(VISIBLE);
        } else {
            badge.setVisibility(GONE);
        }
        setContentDescription(notifCount > 0 ? e.label + ", " + notifCount + " notifications" : e.label);
    }
}
