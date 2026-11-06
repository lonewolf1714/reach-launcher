package com.reach.launcher;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;

/**
 * Built-in icon styles, drawn from each app's own icon so every app gets a consistent look:
 *  - "squircle": the full icon in a smooth superellipse (continuous-curve rounded square)
 *  - "mono":     the app's glyph in one color on a transparent background
 *  - "tile":     the glyph on a rounded tile of that color
 *  - "letter":   the app's initial on a rounded tile
 * Any style (including "original") can get depth: a soft drop shadow, and at higher levels a
 * subtle top highlight. Glyph source, in order: the adaptive monochrome layer (Android 13+),
 * the adaptive foreground silhouette, then a letter.
 */
final class IconStyler {
    static final String ORIGINAL = "original", SQUIRCLE = "squircle", MONO = "mono", TILE = "tile", LETTER = "letter",
            OUTLINE = "outline", LINE = "line", DUO = "duo", VIVID = "vivid";

    /** Styles that look up what kind of app it is (phone, mail, music...) to pick a drawn symbol or color. */
    static boolean usesKinds(String style) { return LINE.equals(style) || VIVID.equals(style); }
    private static final int SIZE = 256;

    private IconStyler() { }

    static int color(String key, int accent) { return color(key, accent, 0xFFF0F0F0); }

    /** "surface" = the theme's surface (tile) color. */
    static int color(String key, int accent, int surface) {
        switch (key) {
            case "surface": return surface;
            case "black": return 0xFF111111;
            case "accent": return accent;
            default: return 0xFFFFFFFF;
        }
    }

    /**
     * "vivid": a bright gradient tile colored by the kind of app, with a white symbol (the app's own
     * logo shape, or a drawn symbol for phone, messages, camera...). color = the text ink: on a dark
     * look (white ink) the tile turns near-black and the symbol takes the color instead.
     */
    private static Bitmap vividIcon(Drawable original, String label, int ink, Typeface face) {
        String k = lineKind == null ? null : lineKind.replace("~", "");
        boolean dark = Ui.isLight(ink);
        // the phone's own apps: Reach's drawn icon set; every other app keeps its real icon on a rounded tile
        if (lineKind != null && !lineKind.startsWith("~")) {
            Bitmap own = VividIcons.draw(lineKind, dark);
            if (own != null) return own;
        } else {
            return squircleIcon(original);
        }
        Bitmap glyph = null;
        boolean drawn = false;
        if (lineKind != null && !lineKind.startsWith("~")) {
            glyph = LineIcons.draw(lineKind, SIZE, LineIcons.weight("bold"));
            drawn = glyph != null;
        }
        if (glyph == null) glyph = glyph(original);
        if (glyph == null && k != null) { glyph = LineIcons.draw(k, SIZE, LineIcons.weight("bold")); drawn = glyph != null; }
        int[] g = vividColors(k, label);
        Bitmap out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        Path shape = squircle(SIZE * 0.05f, SIZE * 0.05f, SIZE * 0.90f);
        if (dark) p.setColor(0xFF1C1C1E);
        else p.setShader(new LinearGradient(0, SIZE * 0.05f, 0, SIZE * 0.95f, g[0], g[1], Shader.TileMode.CLAMP));
        cv.drawPath(shape, p);
        p.setShader(null);
        Bitmap fg = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas fc = new Canvas(fg);
        Paint fp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (glyph != null) {
            float scale = drawn ? 0.60f : 0.56f;
            int s = (int) (SIZE * scale), o = (SIZE - s) / 2;
            fc.drawBitmap(glyph, null, new Rect(o, o, o + s, o + s), fp);
        } else {
            String initial = label == null || label.trim().isEmpty() ? "?" : label.trim().substring(0, 1).toUpperCase();
            fp.setColor(0xFFFFFFFF);
            fp.setTypeface(face != null ? Typeface.create(face, Typeface.BOLD) : Typeface.DEFAULT_BOLD);
            fp.setTextAlign(Paint.Align.CENTER);
            fp.setTextSize(SIZE * 0.46f);
            fc.drawText(initial, SIZE / 2f, SIZE / 2f - (fp.descent() + fp.ascent()) / 2f, fp);
        }
        // paint the symbol: white on the colored tile, the tile's colors on the dark tile
        Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        if (dark) tint.setShader(new LinearGradient(0, SIZE * 0.2f, 0, SIZE * 0.8f, g[0], g[1], Shader.TileMode.CLAMP));
        else tint.setColor(0xFFFFFFFF);
        fc.drawRect(0, 0, SIZE, SIZE, tint);
        cv.drawBitmap(fg, 0, 0, null);
        return out;
    }

    /** Top and bottom gradient colors for a kind of app; unknown apps get one from their name. */
    static int[] vividColors(String kind, String label) {
        if (kind != null) switch (kind) {
            case "phone": case "message": case "chat": return new int[]{0xFF6EE36F, 0xFF22B83A};
            case "mail": case "globe": case "cloud": case "download": case "send": return new int[]{0xFF5BC2FF, 0xFF1A73F0};
            case "camera": case "gear": case "lock": case "torch": case "calculator": case "grid": return new int[]{0xFFB9BCC2, 0xFF6E7279};
            case "photos": case "heart": return new int[]{0xFFFF8A7A, 0xFFF0386B};
            case "music": case "play": case "mic": return new int[]{0xFFFF6B7D, 0xFFF0283F};
            case "notes": case "calendar": case "news": case "book": return new int[]{0xFFFFD54A, 0xFFF5A300};
            case "pin": case "car": return new int[]{0xFF4FD8B8, 0xFF0F9F86};
            case "bank": case "wallet": case "bag": return new int[]{0xFF8E8BFF, 0xFF5146E0};
            case "game": return new int[]{0xFFB98BFF, 0xFF7A3FE0};
            case "clock": return new int[]{0xFF3A3A3C, 0xFF111111};
            case "person": case "folder": return new int[]{0xFFC9A27E, 0xFF94704F};
        }
        int[][] pal = {{0xFF5BC2FF, 0xFF1A73F0}, {0xFF6EE36F, 0xFF22B83A}, {0xFFFF8A5C, 0xFFF0522B}, {0xFFB98BFF, 0xFF7A3FE0},
                {0xFF4FD8B8, 0xFF0F9F86}, {0xFFFF6B9A, 0xFFE0306A}, {0xFFFFC64A, 0xFFF08A00}, {0xFF8E8BFF, 0xFF5146E0}};
        int h = Math.abs((label == null ? "" : label.trim().toLowerCase()).hashCode()) % pal.length;
        return pal[h];
    }

    /** "duo": each app gets a black, white or accent tile (picked from its name), its own glyph on top. */
    static int duoTile(String label, int accent) {
        int h = Math.abs((label == null ? "" : label.trim().toLowerCase()).hashCode()) % 7;
        return h < 3 ? 0xFF141414 : h < 6 ? 0xFFFAFAFA : accent;
    }

    private static int contrast(int c) { return Ui.isLight(c) ? 0xFF111111 : 0xFFFFFFFF; }

    /** Superellipse (n = 5) filling the given box: smoother corners than a plain rounded rect. */
    static Path squircle(float left, float top, float size) {
        Path p = new Path();
        float r = size / 2f, cx = left + r, cy = top + r;
        double n = 5.0;
        for (int i = 0; i <= 120; i++) {
            double t = 2 * Math.PI * i / 120;
            double c = Math.cos(t), s = Math.sin(t);
            float x = (float) (cx + r * Math.signum(c) * Math.pow(Math.abs(c), 2 / n));
            float y = (float) (cy + r * Math.signum(s) * Math.pow(Math.abs(s), 2 / n));
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.close();
        return p;
    }

    static Drawable apply(Resources res, Drawable original, String label, String style, int color, Typeface face, int depth) {
        return apply(res, original, label, style, color, face, depth, null, "regular");
    }

    /**
     * kind: a LineIcons kind for the "line" style ("~kind" = trace the app's own icon first, use the
     * drawn kind only if that fails). weight: "thin", "regular" or "bold" for line/outline strokes.
     */
    static synchronized Drawable apply(Resources res, Drawable original, String label, String style, int color, Typeface face, int depth,
                          String kind, String weight) {
        return apply(res, original, label, style, color, face, depth, kind, weight, true);
    }

    /** tile=false draws line/outline/glyph/letter icons bare (no rounded square behind them). */
    static synchronized Drawable apply(Resources res, Drawable original, String label, String style, int color, Typeface face, int depth,
                          String kind, String weight, boolean tile) {
        if (original == null) return null;
        bare = !tile;
        frame = frameNext;
        lineKind = kind;
        lineWeight = weight == null ? "regular" : weight;
        if (ORIGINAL.equals(style) && depth <= 0) return original;
        Bitmap base;
        switch (style) {
            case SQUIRCLE: base = squircleIcon(original); break;
            case MONO: case TILE: case LETTER: case OUTLINE: case LINE: base = glyphIcon(original, label, style, color, face); break;
            case DUO: base = glyphIcon(original, label, TILE, duoTile(label, color), face); break;
            case VIVID: base = vividIcon(original, label, color, face); break;
            default: base = render(original); break;
        }
        return new BitmapDrawable(res, depth > 0 ? withDepth(base, depth) : base);
    }

    // set per call by apply(); styling runs on one thread at a time per caller
    private static String lineKind, lineWeight = "regular";
    private static boolean bare, frame;

    /** "frame": an outlined rounded square around the icon instead of a filled tile. */
    static synchronized Drawable applyFramed(Resources res, Drawable original, String label, String style, int color, Typeface face,
                                             int depth, String kind, String weight) {
        Drawable d;
        synchronized (IconStyler.class) {
            frameNext = true;
            d = apply(res, original, label, style, color, face, depth, kind, weight, true);
            frameNext = false;
        }
        return d;
    }

    private static boolean frameNext;

    private static int traceRadius() {
        switch (lineWeight) {
            case "thin": return Math.round(SIZE * 0.03f);
            case "bold": return Math.round(SIZE * 0.065f);
            default: return Math.round(SIZE * 0.045f);
        }
    }

    private static Bitmap render(Drawable d) {
        Bitmap b = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Rect old = d.copyBounds();
        d.setBounds(0, 0, SIZE, SIZE);
        d.draw(new Canvas(b));
        d.setBounds(old);
        return b;
    }

    private static Bitmap squircleIcon(Drawable d) {
        Bitmap out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        cv.clipPath(squircle(0, 0, SIZE));
        Rect old = d.copyBounds();
        if (d instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) d;
            int ex = SIZE / 4;
            for (Drawable layer : new Drawable[]{a.getBackground(), a.getForeground()}) {
                if (layer == null) continue;
                Rect lo = layer.copyBounds();
                layer.setBounds(-ex, -ex, SIZE + ex, SIZE + ex);
                layer.draw(cv);
                layer.setBounds(lo);
            }
        } else {
            // legacy icon: centre it on a clean white tile
            cv.drawColor(0xFFF7F7F7);
            int pad = (int) (SIZE * 0.14f);
            d.setBounds(pad, pad, SIZE - pad, SIZE - pad);
            d.draw(cv);
        }
        d.setBounds(old);
        return out;
    }

    private static Bitmap glyphIcon(Drawable original, String label, String style, int color, Typeface face) {
        boolean line = LINE.equals(style);
        boolean outline = OUTLINE.equals(style) || line;
        boolean drawn = false;
        Bitmap glyph = null;
        if (line && lineKind != null && !lineKind.startsWith("~")) {
            glyph = LineIcons.draw(lineKind, SIZE, LineIcons.weight(lineWeight));
            drawn = glyph != null;
        }
        if (glyph == null && !LETTER.equals(style)) {
            glyph = glyph(original);
            if (outline && glyph != null) glyph = outline(glyph, traceRadius());
        }
        if (glyph == null && line && lineKind != null) {
            glyph = LineIcons.draw(lineKind.replace("~", ""), SIZE, LineIcons.weight(lineWeight));
            drawn = glyph != null;
        }
        Bitmap out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        boolean tile = !bare && (!MONO.equals(style) || glyph == null);
        int fg = color;
        if (tile) {
            p.setColor(color);
            Path shape = squircle(SIZE * 0.05f, SIZE * 0.05f, SIZE * 0.90f);
            cv.drawPath(shape, p);
            fg = contrast(color);
            if (frame) { // bold outline around the tile
                Paint st = new Paint(Paint.ANTI_ALIAS_FLAG);
                st.setStyle(Paint.Style.STROKE);
                st.setStrokeWidth(SIZE * 0.036f);
                st.setColor(fg);
                cv.drawPath(shape, st);
            }
        }
        if (glyph != null) {
            float scale = drawn ? (tile ? 0.66f : 0.86f) : (tile ? 0.62f : 0.80f);
            int s = (int) (SIZE * scale), o = (SIZE - s) / 2;
            p.setColorFilter(new PorterDuffColorFilter(fg, PorterDuff.Mode.SRC_IN));
            cv.drawBitmap(glyph, null, new Rect(o, o, o + s, o + s), p);
            p.setColorFilter(null);
        } else {
            String initial = label == null || label.trim().isEmpty() ? "?" : label.trim().substring(0, 1).toUpperCase();
            p.setColor(fg);
            p.setTypeface(face != null ? Typeface.create(face, Typeface.BOLD) : Typeface.DEFAULT_BOLD);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(SIZE * 0.46f);
            if (outline) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(SIZE * LineIcons.weight(lineWeight) / 24f * 0.62f * 1.2f);
                p.setTypeface(face != null ? face : Typeface.DEFAULT);
            }
            cv.drawText(initial, SIZE / 2f, SIZE / 2f - (p.descent() + p.ascent()) / 2f, p);
        }
        return out;
    }

    /** Shrinks the icon slightly, adds a soft drop shadow, and (depth >= 2) a gentle top highlight. */
    static Bitmap withDepth(Bitmap icon, int depth) {
        int inner = (int) (SIZE * 0.86f), ox = (SIZE - inner) / 2, oy = ox - (int) (SIZE * 0.012f * depth);
        Bitmap scaled = Bitmap.createScaledBitmap(icon, inner, inner, true).copy(Bitmap.Config.ARGB_8888, true);

        if (depth >= 2) {
            Canvas gc = new Canvas(scaled);
            Paint gp = new Paint(Paint.ANTI_ALIAS_FLAG);
            gp.setShader(new LinearGradient(0, 0, 0, inner * 0.55f, depth >= 3 ? 0x40FFFFFF : 0x24FFFFFF, 0x00FFFFFF,
                    Shader.TileMode.CLAMP));
            gp.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
            gc.drawRect(0, 0, inner, inner, gp);
        }

        Bitmap out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint blur = new Paint();
        blur.setMaskFilter(new BlurMaskFilter(SIZE * (0.018f + 0.014f * depth), BlurMaskFilter.Blur.NORMAL));
        int[] off = new int[2];
        Bitmap shadow = scaled.extractAlpha(blur, off);
        Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        sp.setColor((50 + 30 * depth) << 24);
        cv.drawBitmap(shadow, ox + off[0], oy + off[1] + SIZE * 0.018f * depth, sp);
        cv.drawBitmap(scaled, ox, oy, new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    /** Glass folder tile: up to four app icons on a frosted squircle. */
    static Drawable folderIcon(Resources res, Drawable[] icons, int depth) { return folderIcon(res, icons, depth, true); }

    /** darkLook: white-ish frosted tile (dark themes); otherwise a soft grey tile for light themes. */
    static Drawable folderIcon(Resources res, Drawable[] icons, int depth, boolean darkLook) {
        Bitmap out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Path shape = squircle(0, 0, SIZE);
        p.setColor(darkLook ? 0x52FFFFFF : 0x1A000000);
        cv.drawPath(shape, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(SIZE * 0.012f);
        p.setColor(0x40FFFFFF);
        cv.drawPath(shape, p);
        int cell = (int) (SIZE * 0.34f), gap = (int) (SIZE * 0.07f), start = (SIZE - 2 * cell - gap) / 2;
        for (int i = 0; i < Math.min(4, icons.length); i++) {
            Drawable d = icons[i];
            if (d == null) continue;
            int x = start + (i % 2) * (cell + gap), y = start + (i / 2) * (cell + gap);
            Rect old = d.copyBounds();
            d.setBounds(x, y, x + cell, y + cell);
            d.draw(cv);
            d.setBounds(old);
        }
        return new BitmapDrawable(res, depth > 0 ? withDepth(out, depth) : out);
    }

    /**
     * Turns a filled glyph mask into a line drawing: the glyph minus its erosion by r pixels.
     * Erosion uses separable running-window checks, so it is linear in the pixel count.
     */
    static Bitmap outline(Bitmap glyph, int r) {
        int w = glyph.getWidth(), h = glyph.getHeight();
        int[] px = new int[w * h];
        glyph.getPixels(px, 0, w, 0, 0, w, h);
        boolean[] solid = new boolean[w * h];
        for (int i = 0; i < px.length; i++) solid[i] = (px[i] >>> 24) > 110;
        boolean[] horiz = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            int run = 0; // consecutive solid pixels ending at x
            int[] runAt = new int[w];
            for (int x = 0; x < w; x++) { run = solid[y * w + x] ? run + 1 : 0; runAt[x] = run; }
            for (int x = 0; x < w; x++) {
                int right = Math.min(w - 1, x + r);
                horiz[y * w + x] = x - r >= 0 && right == x + r && runAt[right] >= 2 * r + 1;
            }
        }
        boolean[] eroded = new boolean[w * h];
        for (int x = 0; x < w; x++) {
            int run = 0;
            int[] runAt = new int[h];
            for (int y = 0; y < h; y++) { run = horiz[y * w + x] ? run + 1 : 0; runAt[y] = run; }
            for (int y = 0; y < h; y++) {
                int down = Math.min(h - 1, y + r);
                eroded[y * w + x] = y - r >= 0 && down == y + r && runAt[down] >= 2 * r + 1;
            }
        }
        int[] out = new int[w * h];
        for (int i = 0; i < out.length; i++) {
            if (solid[i] && !eroded[i]) out[i] = (px[i] & 0xFF000000) | 0x00FFFFFF;
        }
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b.setPixels(out, 0, w, 0, 0, w, h);
        return b;
    }

    /**
     * The icon's symbol as an alpha mask: its monochrome/foreground layer when that is a real
     * shape, otherwise the parts of the full-colour icon that stand out from its background.
     * Plain blobs (a filled square or circle) don't count. Null if nothing usable is found.
     */
    private static Bitmap glyph(Drawable d) {
        Bitmap g = layerGlyph(d);
        if (g != null && !isBlob(g)) return g;
        Bitmap det = detailGlyph(d);
        if (det != null && !isBlob(det)) return det;
        return null;
    }

    /** True for shapes with almost no detail: their outline is short for their area (disc, square). */
    private static boolean isBlob(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int solid = 0, edge = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                if ((px[i] >>> 24) <= 128) continue;
                solid++;
                if ((px[i - 1] >>> 24) <= 128 || (px[i + 1] >>> 24) <= 128 || (px[i - w] >>> 24) <= 128 || (px[i + w] >>> 24) <= 128)
                    edge++;
            }
        }
        if (solid == 0) return true;
        return edge * (float) edge / solid < 24f; // disc ~12.6, square ~16; logos and lettering are far higher
    }

    /** Everything in the full-colour icon that differs clearly from its background colour. */
    private static Bitmap detailGlyph(Drawable d) {
        Bitmap full = render(d);
        int[] px = new int[SIZE * SIZE];
        full.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE);
        // background colour: average of a ring inside the icon shape
        long r = 0, g = 0, b = 0, n = 0;
        int in = Math.round(SIZE * 0.2f);
        for (int t = in; t < SIZE - in; t += 3) {
            int[] pts = {in * SIZE + t, (SIZE - in) * SIZE + t, t * SIZE + in, t * SIZE + (SIZE - in)};
            for (int i : pts) {
                if (i < 0 || i >= px.length) continue;
                int c = px[i];
                if ((c >>> 24) < 200) continue;
                r += (c >> 16) & 0xFF; g += (c >> 8) & 0xFF; b += c & 0xFF; n++;
            }
        }
        if (n == 0) return null;
        int br = (int) (r / n), bgc = (int) (g / n), bb = (int) (b / n);
        int[] out = new int[px.length];
        int solid = 0;
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            if ((c >>> 24) < 128) continue;
            int dist = Math.abs(((c >> 16) & 0xFF) - br) + Math.abs(((c >> 8) & 0xFF) - bgc) + Math.abs((c & 0xFF) - bb);
            int a = Math.max(0, Math.min(255, (dist - 70) * 3));
            if (a > 0) out[i] = (a << 24) | 0x00FFFFFF;
            if (a > 128) solid++;
        }
        float cover = solid / (float) px.length;
        if (cover < 0.015f || cover > 0.6f) return null;
        Bitmap res = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        res.setPixels(out, 0, SIZE, 0, 0, SIZE, SIZE);
        return res;
    }

    /** The adaptive monochrome/foreground layer (or a legacy icon) as a mask, or null. */
    private static Bitmap layerGlyph(Drawable d) {
        Drawable src = d;
        boolean layer = false;
        if (d instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) d;
            Drawable mono = Build.VERSION.SDK_INT >= 33 ? a.getMonochrome() : null;
            src = mono != null ? mono : a.getForeground();
            layer = true;
            if (src == null) return null;
        }
        Bitmap b = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Rect old = src.copyBounds();
        if (layer) src.setBounds(-SIZE / 4, -SIZE / 4, SIZE + SIZE / 4, SIZE + SIZE / 4);
        else src.setBounds(0, 0, SIZE, SIZE);
        src.draw(cv);
        src.setBounds(old);

        int[] px = new int[SIZE * SIZE];
        b.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE);
        int solid = 0;
        for (int c : px) if ((c >>> 24) > 128) solid++;
        float cover = solid / (float) px.length;
        if (cover > 0.72f || cover < 0.01f) return null;
        return b;
    }
}
