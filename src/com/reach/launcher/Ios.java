package com.reach.launcher;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The grouped-list look used for settings, the theme editor and edit sheets: light grey
 * background with white rounded groups (dark: black with dark grey groups), inset hairlines,
 * segmented controls, green switches. Follows the phone's light/dark setting.
 */
final class Ios {
    final boolean dark;
    final int bg, group, text, sub, sep, tint, segBg, segSel, fill, green, chevron;

    private Ios(boolean dark) {
        this.dark = dark;
        bg = dark ? 0xFF000000 : 0xFFF2F2F7;
        group = dark ? 0xFF1C1C1E : 0xFFFFFFFF;
        text = dark ? 0xFFFFFFFF : 0xFF000000;
        sub = dark ? 0x99EBEBF5 : 0x993C3C43;
        sep = dark ? 0x5C545458 : 0x4A3C3C43;
        tint = dark ? 0xFF0A84FF : 0xFF007AFF;
        segBg = dark ? 0xFF2C2C2E : 0xFFE9E9EB;
        segSel = dark ? 0xFF636366 : 0xFFFFFFFF;
        fill = dark ? 0x3D787880 : 0x1F787880;
        green = 0xFF34C759;
        chevron = dark ? 0x4DEBEBF5 : 0x4D3C3C43;
    }

    /** Follows Reach's Appearance (Light / Dark), otherwise the active look's text colour or the phone. */
    static Ios of(Context c) {
        Prefs p = new Prefs(c);
        String m = p.mode();
        if ("light".equals(m)) return new Ios(false);
        if ("dark".equals(m)) return new Ios(true);
        boolean sys = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if ("theme".equals(m)) return new Ios(p.solidBg() ? Ui.isLight(p.ink()) : sys);
        return new Ios(sys);
    }

    private static int dp(Context c, float v) { return Ui.dp(c, v); }

    /** Title over a segmented control (up to 4 options) or a row of capsules (more options). */
    View choice(Context c, String title, String[] labels, String[] values, String cur, Consumer<String> pick) {
        LinearLayout block = new LinearLayout(c);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12));
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        block.addView(t);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gl.topMargin = dp(c, 10);
        block.addView(labels.length <= 4 ? segmented(c, labels, values, cur, pick) : capsules(c, labels, values, cur, pick), gl);
        return block;
    }

    View segmented(Context c, String[] labels, String[] values, String cur, Consumer<String> pick) {
        LinearLayout seg = new LinearLayout(c);
        seg.setBackground(Ui.round(segBg, dp(c, 9)));
        seg.setPadding(dp(c, 2), dp(c, 2), dp(c, 2), dp(c, 2));
        List<TextView> items = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            TextView s = new TextView(c);
            s.setText(labels[i]);
            s.setGravity(Gravity.CENTER);
            s.setSingleLine(true);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            s.setTag(values[i]);
            items.add(s);
            final String v = values[i];
            s.setOnClickListener(x -> {
                for (TextView o : items) styleSeg(c, o, o == s);
                pick.accept(v);
            });
            styleSeg(c, s, values[i].equals(cur));
            seg.addView(s, new LinearLayout.LayoutParams(0, dp(c, 32), 1f));
        }
        return seg;
    }

    private void styleSeg(Context c, TextView s, boolean on) {
        s.setTextColor(text);
        s.setTypeface(Typeface.create("sans-serif-medium", on ? Typeface.BOLD : Typeface.NORMAL));
        if (on) {
            GradientDrawable g = Ui.round(segSel, dp(c, 7));
            s.setBackground(g);
            s.setElevation(dp(c, 1.5f));
        } else {
            s.setBackground(null);
            s.setElevation(0);
        }
    }

    View capsules(Context c, String[] labels, String[] values, String cur, Consumer<String> pick) {
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout strip = new LinearLayout(c);
        List<TextView> items = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            TextView s = new TextView(c);
            s.setText(labels[i]);
            s.setGravity(Gravity.CENTER);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            s.setPadding(dp(c, 14), 0, dp(c, 14), 0);
            items.add(s);
            final String v = values[i];
            s.setOnClickListener(x -> {
                for (TextView o : items) styleCap(c, o, o == s);
                pick.accept(v);
            });
            styleCap(c, s, values[i].equals(cur));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(c, 34));
            lp.setMarginEnd(dp(c, 8));
            strip.addView(s, lp);
        }
        hs.addView(strip);
        return hs;
    }

    private void styleCap(Context c, TextView s, boolean on) {
        s.setTextColor(on ? 0xFFFFFFFF : text);
        s.setBackground(Ui.ripple(c, Ui.round(on ? tint : fill, dp(c, 17))));
    }

    /** Title + value + slider. live may be null; commit runs when the finger lifts. */
    View slider(Context c, String title, int min, int max, int val, String unit, IntConsumer live, IntConsumer commit) {
        LinearLayout block = new LinearLayout(c);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 4));
        LinearLayout head = new LinearLayout(c);
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView vt = new TextView(c);
        vt.setText(val + unit);
        vt.setTextColor(sub);
        vt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        head.addView(vt);
        block.addView(head);
        SeekBar sb = new SeekBar(c);
        sb.setMin(min);
        sb.setMax(max);
        sb.setProgress(Math.max(min, Math.min(max, val)));
        sb.setProgressTintList(ColorStateList.valueOf(tint));
        sb.setProgressBackgroundTintList(ColorStateList.valueOf(dark ? 0xFF3A3A3C : 0xFFD1D1D6));
        sb.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean u) {
                vt.setText(v + unit);
                if (u && live != null) live.accept(v);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { commit.accept(s.getProgress()); }
        });
        block.addView(sb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 40)));
        return block;
    }

    ColorStateList switchTrack() {
        return new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{green, dark ? 0xFF39393D : 0xFFE9E9EA});
    }

    ColorStateList switchThumb() {
        return new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{0xFFFFFFFF, 0xFFFFFFFF});
    }
}
