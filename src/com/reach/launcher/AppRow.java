package com.reach.launcher;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** Icon + label (+ optional notification preview line and count badge). Used on home and in the list. */
final class AppRow extends LinearLayout {
    static final class Style {
        final int iconPx;
        final float labelSp;
        final Typeface face;
        final int accent;
        final boolean previews;
        final int ink;

        Style(int iconPx, float labelSp, Typeface face, int accent, boolean previews, int ink) {
            this.ink = ink;
            this.iconPx = iconPx;
            this.labelSp = labelSp;
            this.face = face;
            this.accent = accent;
            this.previews = previews;
        }
    }

    final ImageView icon;
    final TextView label;
    final TextView preview;
    final TextView badge;
    Entry entry;

    AppRow(Context c) {
        super(c);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(c, 7);
        setPadding(Ui.dp(c, 4), p, Ui.dp(c, 4), p);
        setBackground(Ui.ripple(c, null));

        icon = new ImageView(c);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        addView(icon, new LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)));

        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(VERTICAL);
        label = new TextView(c);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setTextColor(0xFFFFFFFF);
        Ui.shadow(label);
        preview = new TextView(c);
        preview.setSingleLine(true);
        preview.setEllipsize(TextUtils.TruncateAt.END);
        preview.setTextColor(0xD9FFFFFF);
        preview.setVisibility(GONE);
        Ui.shadow(preview);
        texts.addView(label);
        texts.addView(preview);
        LayoutParams tl = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        tl.setMarginStart(Ui.dp(c, 16));
        addView(texts, tl);

        badge = new TextView(c);
        badge.setGravity(Gravity.CENTER);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextColor(0xFF111315);
        badge.setMinWidth(Ui.dp(c, 20));
        badge.setPadding(Ui.dp(c, 6), 0, Ui.dp(c, 6), 0);
        badge.setVisibility(GONE);
        LayoutParams bl = new LayoutParams(LayoutParams.WRAP_CONTENT, Ui.dp(c, 20));
        bl.setMarginStart(Ui.dp(c, 8));
        addView(badge, bl);
    }

    private void applyStyle(Style s) {
        LayoutParams lp = (LayoutParams) icon.getLayoutParams();
        if (lp.width != s.iconPx) {
            lp.width = lp.height = s.iconPx;
            icon.setLayoutParams(lp);
        }
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, s.labelSp);
        label.setTypeface(s.face);
        Ui.ink(label, s.ink);
        Ui.ink(preview, Ui.alpha(s.ink, 0xD9));
        preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(12f, s.labelSp * 0.62f));
        preview.setTypeface(s.face);
    }

    void bind(Entry e, Style s, List<StatusBarNotification> notifs, boolean withPreview) {
        entry = e;
        applyStyle(s);
        Drawable d = e.icon;
        if (d != null && d.getConstantState() != null) d = d.getConstantState().newDrawable(getResources());
        icon.setImageDrawable(d);
        label.setText(e.label);
        int n = notifs.size();
        if (n > 0) {
            badge.setText(String.valueOf(n));
            badge.setBackground(Ui.round(s.accent, Ui.dp(getContext(), 10)));
            badge.setVisibility(VISIBLE);
        } else {
            badge.setVisibility(GONE);
        }
        if (withPreview && s.previews && n > 0) {
            preview.setText(Ui.preview(notifs.get(0)));
            preview.setVisibility(VISIBLE);
        } else {
            preview.setVisibility(GONE);
            preview.setOnClickListener(null);
            preview.setOnLongClickListener(null);
            preview.setClickable(false);
            preview.setLongClickable(false);
        }
        setContentDescription(n > 0 ? e.label + ", " + n + " notifications" : e.label);
    }

    void bindAction(Drawable d, CharSequence text, Style s) {
        entry = null;
        applyStyle(s);
        if (d != null) { d = d.mutate(); d.setTint(s.ink); }
        icon.setImageDrawable(d);
        label.setText(text);
        badge.setVisibility(GONE);
        preview.setVisibility(GONE);
        setContentDescription(text);
    }
}
