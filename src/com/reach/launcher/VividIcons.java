package com.reach.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.util.Calendar;
import java.util.Locale;

/**
 * Reach's own filled icon set for the phone's built-in apps ("vivid" style): bold white symbols on
 * bright gradient tiles. In dark mode the tile goes near-black and the symbol takes the tile's colors.
 * Every drawing here is original; returns null for kinds without a drawing.
 */
final class VividIcons {
    private static final int S = 256;
    private static final float IN = S * 0.05f, SZ = S * 0.90f;
    private static final int DARK_TILE = 0xFF1C1C1E;

    private VividIcons() { }

    static Bitmap draw(String kind, boolean dark) {
        if (kind == null) return null;
        Bitmap out = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        cv.save();
        cv.clipPath(IconStyler.squircle(IN, IN, SZ));
        boolean ok = true;
        switch (kind) {
            case "phone": phone(cv, dark); break;
            case "message": case "chat": message(cv, dark); break;
            case "mail": mail(cv, dark); break;
            case "camera": camera(cv, dark); break;
            case "photos": photos(cv, dark); break;
            case "music": music(cv, dark); break;
            case "notes": notes(cv, dark); break;
            case "calendar": calendar(cv, dark); break;
            case "clock": clock(cv, dark); break;
            case "gear": gear(cv, dark); break;
            case "globe": globe(cv, dark); break;
            case "cloud": weather(cv, dark); break;
            case "calculator": calculator(cv, dark); break;
            case "folder": folder(cv, dark); break;
            case "person": person(cv, dark); break;
            case "pin": pin(cv, dark); break;
            case "play": play(cv, dark); break;
            case "mic": mic(cv, dark); break;
            case "download": download(cv, dark); break;
            case "wallet": wallet(cv, dark); break;
            case "heart": heart(cv, dark); break;
            case "bag": bag(cv, dark); break;
            case "book": book(cv, dark); break;
            case "torch": torch(cv, dark); break;
            case "lock": lock(cv, dark); break;
            default: ok = false;
        }
        cv.restore();
        if (!ok) return null;
        // a faint top sheen, like light on glass
        Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        sheen.setShader(new LinearGradient(0, IN, 0, S * 0.5f, 0x22FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        cv.drawPath(IconStyler.squircle(IN, IN, SZ), sheen);
        return out;
    }

    // ---- helpers ----

    private static Paint p() { return new Paint(Paint.ANTI_ALIAS_FLAG); }

    /** Gradient tile (or the dark tile) filling the clip. */
    private static void tile(Canvas cv, int top, int bottom, boolean dark) {
        Paint t = p();
        if (dark) t.setColor(DARK_TILE);
        else t.setShader(new LinearGradient(0, 0, 0, S, top, bottom, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, S, S, t);
    }

    /** The symbol paint: white on a color tile, the tile's colors on the dark tile. */
    private static Paint ink(int top, int bottom, boolean dark) {
        Paint i = p();
        if (dark) i.setShader(new LinearGradient(0, S * 0.2f, 0, S * 0.8f, top, bottom, Shader.TileMode.CLAMP));
        else i.setColor(0xFFFFFFFF);
        return i;
    }

    private static Paint stroke(Paint base, float w) {
        Paint s = new Paint(base);
        s.setStyle(Paint.Style.STROKE);
        s.setStrokeWidth(w);
        s.setStrokeCap(Paint.Cap.ROUND);
        s.setStrokeJoin(Paint.Join.ROUND);
        return s;
    }

    // ---- drawings ----

    private static void phone(Canvas cv, boolean dark) {
        int a = 0xFF7CF08A, b = 0xFF1FB841;
        tile(cv, a, b, dark);
        Paint i = ink(a, b, dark);
        cv.save();
        cv.rotate(-38, 128, 128);
        cv.drawRoundRect(new RectF(96, 52, 160, 96), 20, 20, i);   // earpiece
        cv.drawRoundRect(new RectF(96, 160, 160, 204), 20, 20, i); // mouthpiece
        Path spine = new Path();
        spine.moveTo(112, 84);
        spine.quadTo(70, 128, 112, 172);
        cv.drawPath(spine, stroke(i, 36));
        cv.restore();
    }

    private static void message(Canvas cv, boolean dark) {
        int a = 0xFF6FE97E, b = 0xFF22B53E;
        tile(cv, a, b, dark);
        Paint i = ink(a, b, dark);
        cv.drawOval(new RectF(46, 58, 210, 176), i);
        Path tail = new Path();
        tail.moveTo(78, 150);
        tail.quadTo(76, 186, 52, 202);
        tail.quadTo(96, 198, 118, 168);
        tail.close();
        cv.drawPath(tail, i);
    }

    private static void mail(Canvas cv, boolean dark) {
        int a = 0xFF4FC3FF, b = 0xFF1668E8;
        tile(cv, a, b, dark);
        Paint i = ink(a, b, dark);
        RectF env = new RectF(44, 74, 212, 182);
        if (dark) {
            cv.drawRoundRect(env, 14, 14, stroke(i, 12));
            Path flap = new Path();
            flap.moveTo(50, 82); flap.lineTo(128, 140); flap.lineTo(206, 82);
            cv.drawPath(flap, stroke(i, 12));
        } else {
            cv.drawRoundRect(env, 14, 14, i);
            Paint f = stroke(p(), 9);
            f.setColor(0xFF2E86F0);
            Path flap = new Path();
            flap.moveTo(52, 84); flap.lineTo(128, 142); flap.lineTo(204, 84);
            cv.drawPath(flap, f);
            Path low = new Path();
            low.moveTo(52, 174); low.lineTo(104, 126);
            low.moveTo(204, 174); low.lineTo(152, 126);
            f.setStrokeWidth(5);
            f.setColor(0x552E86F0);
            cv.drawPath(low, f);
        }
    }

    private static void camera(Canvas cv, boolean dark) {
        tile(cv, 0xFFE4E6EA, 0xFF9A9EA6, dark);
        Paint body = p();
        body.setColor(dark ? 0xFF3A3A3E : 0xFF2A2B2F);
        cv.drawRoundRect(new RectF(44, 82, 212, 190), 26, 26, body);
        cv.drawRoundRect(new RectF(92, 64, 164, 96), 12, 12, body);
        Paint ring = p();
        ring.setColor(dark ? 0xFF9A9EA6 : 0xFFD9DBE0);
        cv.drawCircle(128, 136, 40, ring);
        Paint lens = p();
        lens.setShader(new RadialGradient(118, 126, 34, 0xFF5C6B8A, 0xFF101218, Shader.TileMode.CLAMP));
        cv.drawCircle(128, 136, 30, lens);
        Paint glint = p();
        glint.setColor(0xCCFFFFFF);
        cv.drawCircle(116, 124, 7, glint);
        Paint dot = p();
        dot.setColor(0xFFFFB020);
        cv.drawCircle(186, 104, 7, dot);
    }

    /** A little picture: sky, sun and two hills. */
    private static void photos(Canvas cv, boolean dark) {
        Paint sky = p();
        sky.setShader(new LinearGradient(0, 0, 0, S, dark ? 0xFF233A66 : 0xFF6FD3FF, dark ? 0xFF1C2333 : 0xFFB8F0FF, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, S, S, sky);
        Paint sun = p();
        sun.setColor(0xFFFFC93C);
        cv.drawCircle(176, 88, 28, sun);
        Paint far = p();
        far.setColor(dark ? 0xFF2F8F6A : 0xFF4CC38A);
        Path h1 = new Path();
        h1.moveTo(0, 196); h1.quadTo(96, 92, 196, 196); h1.lineTo(196, S); h1.lineTo(0, S); h1.close();
        cv.drawPath(h1, far);
        Paint near = p();
        near.setColor(dark ? 0xFF1F6B4E : 0xFF239A63);
        Path h2 = new Path();
        h2.moveTo(64, S); h2.quadTo(170, 120, S, 206); h2.lineTo(S, S); h2.close();
        cv.drawPath(h2, near);
    }

    private static void music(Canvas cv, boolean dark) {
        int a = 0xFFFF6A7E, b = 0xFFF0243F;
        tile(cv, a, b, dark);
        Paint i = ink(a, b, dark);
        cv.drawOval(new RectF(58, 152, 112, 192), i);
        cv.drawOval(new RectF(142, 136, 196, 176), i);
        cv.drawRect(96, 70, 112, 172, i);
        cv.drawRect(180, 54, 196, 156, i);
        Path beam = new Path();
        beam.moveTo(96, 70); beam.lineTo(196, 50); beam.lineTo(196, 80); beam.lineTo(96, 100); beam.close();
        cv.drawPath(beam, i);
    }

    private static void notes(Canvas cv, boolean dark) {
        Paint bg = p();
        bg.setColor(dark ? DARK_TILE : 0xFFFFFFFF);
        cv.drawRect(0, 0, S, S, bg);
        Paint band = p();
        band.setShader(new LinearGradient(0, 0, 0, 84, 0xFFFFE066, 0xFFFFC21A, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, S, 84, band);
        Paint dots = p();
        dots.setColor(dark ? 0x99FFFFFF : 0x66000000);
        for (int x = 30; x < S - 20; x += 14) cv.drawCircle(x, 98, 2.6f, dots);
        Paint line = p();
        line.setColor(dark ? 0x55FFFFFF : 0x22000000);
        for (int y : new int[]{140, 180, 220}) cv.drawRect(20, y, S - 20, y + 3, line);
    }

    private static void calendar(Canvas cv, boolean dark) {
        Paint bg = p();
        bg.setColor(dark ? DARK_TILE : 0xFFFFFFFF);
        cv.drawRect(0, 0, S, S, bg);
        Calendar now = Calendar.getInstance();
        String day = now.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.getDefault());
        Paint t = p();
        t.setTextAlign(Paint.Align.CENTER);
        t.setColor(0xFFF0383C);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextSize(40);
        cv.drawText(day == null ? "" : day.toUpperCase(Locale.getDefault()), 128, 82, t);
        t.setColor(dark ? 0xFFFFFFFF : 0xFF111111);
        t.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        t.setTextSize(122);
        cv.drawText(String.valueOf(now.get(Calendar.DAY_OF_MONTH)), 128, 196, t);
    }

    private static void clock(Canvas cv, boolean dark) {
        Paint bg = p();
        bg.setColor(0xFF111113);
        cv.drawRect(0, 0, S, S, bg);
        Paint face = p();
        face.setColor(dark ? 0xFF2A2A2E : 0xFFFFFFFF);
        cv.drawCircle(128, 128, 100, face);
        Paint tick = stroke(p(), 5);
        tick.setColor(dark ? 0xFFCCCCCC : 0xFF333333);
        for (int k = 0; k < 12; k++) {
            double a = Math.PI * 2 * k / 12;
            float r1 = k % 3 == 0 ? 74 : 82;
            cv.drawLine(128 + (float) Math.sin(a) * r1, 128 - (float) Math.cos(a) * r1,
                    128 + (float) Math.sin(a) * 90, 128 - (float) Math.cos(a) * 90, tick);
        }
        Paint hand = stroke(p(), 9);
        hand.setColor(dark ? 0xFFFFFFFF : 0xFF111111);
        cv.drawLine(128, 128, 128 + 34, 128 - 38, hand);   // hour hand towards ~1:30
        cv.drawLine(128, 128, 128 - 52, 128 - 50, hand);   // minute hand towards ~52 min
        Paint sec = stroke(p(), 4);
        sec.setColor(0xFFFF8A00);
        cv.drawLine(128, 150, 128 + 30, 128 + 66, sec);
        Paint hub = p();
        hub.setColor(0xFFFF8A00);
        cv.drawCircle(128, 128, 8, hub);
    }

    private static void gear(Canvas cv, boolean dark) {
        tile(cv, 0xFFC9CCD2, 0xFF7D828B, dark);
        Paint g = p();
        g.setColor(dark ? 0xFF9A9EA6 : 0xFF3B3D42);
        cv.save();
        for (int k = 0; k < 12; k++) {
            cv.drawRoundRect(new RectF(116, 34, 140, 70), 6, 6, g);
            cv.rotate(30, 128, 128);
        }
        cv.restore();
        cv.drawCircle(128, 128, 72, g);
        Paint hole = p();
        hole.setColor(dark ? DARK_TILE : 0xFFB3B7BE);
        cv.drawCircle(128, 128, 44, hole);
        Paint spoke = stroke(p(), 10);
        spoke.setColor(g.getColor());
        for (int k = 0; k < 3; k++) {
            double a = Math.PI * 2 * k / 3 - Math.PI / 2;
            cv.drawLine(128, 128, 128 + (float) Math.cos(a) * 44, 128 + (float) Math.sin(a) * 44, spoke);
        }
        cv.drawCircle(128, 128, 12, g);
    }

    /** A globe with meridians, for the browser. */
    private static void globe(Canvas cv, boolean dark) {
        Paint bg = p();
        bg.setColor(dark ? DARK_TILE : 0xFFFFFFFF);
        cv.drawRect(0, 0, S, S, bg);
        Paint ball = p();
        ball.setShader(new LinearGradient(0, 34, 0, 222, 0xFF3FB6FF, 0xFF1460E0, Shader.TileMode.CLAMP));
        cv.drawCircle(128, 128, 94, ball);
        Paint l = stroke(p(), 6);
        l.setColor(0xDDFFFFFF);
        cv.drawOval(new RectF(90, 34, 166, 222), l);
        cv.drawLine(128, 34, 128, 222, l);
        cv.drawLine(34, 128, 222, 128, l);
        Path lat = new Path();
        lat.moveTo(50, 84); lat.quadTo(128, 104, 206, 84);
        lat.moveTo(50, 172); lat.quadTo(128, 152, 206, 172);
        cv.drawPath(lat, l);
    }

    private static void weather(Canvas cv, boolean dark) {
        Paint sky = p();
        sky.setShader(new LinearGradient(0, 0, 0, S, dark ? 0xFF1E2C4A : 0xFF3E8EF0, dark ? 0xFF15192A : 0xFF7CC4FF, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, S, S, sky);
        Paint sun = p();
        sun.setShader(new RadialGradient(164, 98, 44, 0xFFFFE680, 0xFFFFB81C, Shader.TileMode.CLAMP));
        cv.drawCircle(164, 98, 40, sun);
        Paint cloud = p();
        cloud.setShader(new LinearGradient(0, 110, 0, 196, 0xFFFFFFFF, dark ? 0xFFB9C2D0 : 0xFFDDE7F5, Shader.TileMode.CLAMP));
        cv.drawCircle(98, 148, 40, cloud);
        cv.drawCircle(142, 136, 46, cloud);
        cv.drawRoundRect(new RectF(54, 146, 204, 192), 23, 23, cloud);
    }

    private static void calculator(Canvas cv, boolean dark) {
        int a = 0xFFFFA43A, b = 0xFFF26A1B;
        tile(cv, a, b, dark);
        Paint i = stroke(ink(a, b, dark), 14);
        cv.drawLine(66, 88, 106, 88, i); cv.drawLine(86, 68, 86, 108, i);       // +
        cv.drawLine(150, 88, 190, 88, i);                                          // −
        cv.drawLine(70, 152, 102, 184, i); cv.drawLine(102, 152, 70, 184, i);   // ×
        cv.drawLine(150, 158, 190, 158, i); cv.drawLine(150, 178, 190, 178, i); // =
    }

    private static void folder(Canvas cv, boolean dark) {
        int a = 0xFF56C1FF, b = 0xFF1A7BEA;
        tile(cv, a, b, dark);
        Paint i = ink(a, b, dark);
        Path f = new Path();
        f.moveTo(50, 84); f.lineTo(104, 84); f.lineTo(120, 100); f.lineTo(206, 100);
        f.lineTo(206, 186); f.lineTo(50, 186); f.close();
        cv.drawPath(f, i);
        if (!dark) {
            Paint fold = p();
            fold.setColor(0x33000000);
            cv.drawRect(50, 112, 206, 116, fold);
        }
    }

    private static void person(Canvas cv, boolean dark) {
        int a = 0xFFB9BEC6, b = 0xFF7E848E;
        tile(cv, a, b, dark);
        Paint i = ink(a, b, dark);
        cv.drawCircle(128, 100, 38, i);
        cv.drawOval(new RectF(58, 150, 198, 250), i);
    }

    private static void pin(Canvas cv, boolean dark) {
        Paint land = p();
        land.setShader(new LinearGradient(0, 0, S, S, dark ? 0xFF22423A : 0xFFB9F0C8, dark ? 0xFF1B2B2C : 0xFFE9F3E2, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, S, S, land);
        Paint road = stroke(p(), 30);
        road.setStrokeCap(Paint.Cap.BUTT);
        road.setColor(dark ? 0xFF5C6370 : 0xFFFFFFFF);
        cv.drawLine(-20, 200, 276, 70, road);
        Paint lane = stroke(p(), 4);
        lane.setColor(0xFFFFC53D);
        cv.drawLine(-20, 200, 276, 70, lane);
        Paint pin = p();
        pin.setColor(0xFFF0383C);
        Path drop = new Path();
        drop.addCircle(128, 104, 40, Path.Direction.CW);
        drop.moveTo(96, 128); drop.lineTo(128, 192); drop.lineTo(160, 128); drop.close();
        cv.drawPath(drop, pin);
        Paint dot = p();
        dot.setColor(0xFFFFFFFF);
        cv.drawCircle(128, 104, 15, dot);
    }

    private static void play(Canvas cv, boolean dark) {
        int a = 0xFF4A5CFF, b = 0xFF2330B8;
        tile(cv, a, b, dark);
        Paint i = ink(a, b, dark);
        Path t = new Path();
        t.moveTo(98, 70); t.lineTo(190, 128); t.lineTo(98, 186); t.close();
        Paint r = new Paint(i);
        r.setPathEffect(new android.graphics.CornerPathEffect(18));
        cv.drawPath(t, r);
    }

    private static void mic(Canvas cv, boolean dark) {
        int a = 0xFFFF5E62, b = 0xFFD81E34;
        tile(cv, a, b, dark);
        Paint i = stroke(ink(a, b, dark), 16);
        int[] h = {36, 70, 110, 70, 46, 90, 36};
        for (int k = 0; k < h.length; k++) {
            float x = 56 + k * 24;
            cv.drawLine(x, 128 - h[k] / 2f, x, 128 + h[k] / 2f, i);
        }
    }

    private static void download(Canvas cv, boolean dark) {
        int a = 0xFF39C5FF, b = 0xFF1659E6;
        tile(cv, a, b, dark);
        Paint i = stroke(ink(a, b, dark), 18);
        cv.drawLine(128, 56, 128, 150, i);
        Path head = new Path();
        head.moveTo(90, 114); head.lineTo(128, 152); head.lineTo(166, 114);
        cv.drawPath(head, i);
        Path tray = new Path();
        tray.moveTo(62, 160); tray.lineTo(62, 194); tray.lineTo(194, 194); tray.lineTo(194, 160);
        cv.drawPath(tray, i);
    }

    private static void wallet(Canvas cv, boolean dark) {
        Paint bg = p();
        bg.setColor(0xFF111113);
        cv.drawRect(0, 0, S, S, bg);
        int[] cards = {0xFF2FBF71, 0xFFFFB020, 0xFF3D8BFF, 0xFFF0456A};
        for (int k = 0; k < cards.length; k++) {
            Paint c = p();
            c.setColor(cards[k]);
            cv.drawRoundRect(new RectF(48, 62 + k * 22, 208, 132 + k * 22), 12, 12, c);
        }
        Paint pocket = p();
        pocket.setColor(dark ? 0xFF2A2A2E : 0xFF3A3A3E);
        cv.drawRoundRect(new RectF(40, 144, 216, 206), 16, 16, pocket);
    }

    private static void heart(Canvas cv, boolean dark) {
        Paint bg = p();
        bg.setColor(dark ? DARK_TILE : 0xFFFFFFFF);
        cv.drawRect(0, 0, S, S, bg);
        Paint h = p();
        h.setShader(new LinearGradient(0, 70, 0, 200, 0xFFFF6B8B, 0xFFF0284A, Shader.TileMode.CLAMP));
        Path p = new Path();
        p.moveTo(128, 200);
        p.cubicTo(30, 140, 50, 56, 104, 64);
        p.cubicTo(118, 66, 126, 76, 128, 86);
        p.cubicTo(130, 76, 138, 66, 152, 64);
        p.cubicTo(206, 56, 226, 140, 128, 200);
        p.close();
        cv.drawPath(p, h);
    }

    private static void bag(Canvas cv, boolean dark) {
        int a = 0xFFFFA14A, b = 0xFFF0581E;
        tile(cv, a, b, dark);
        Paint i = ink(a, b, dark);
        cv.drawRoundRect(new RectF(62, 96, 194, 204), 18, 18, i);
        Paint handle = stroke(i, 12);
        cv.drawArc(new RectF(94, 52, 162, 124), 180, 180, false, handle);
    }

    private static void book(Canvas cv, boolean dark) {
        int a = 0xFFFFA43A, b = 0xFFF06A1B;
        tile(cv, a, b, dark);
        Paint i = ink(a, b, dark);
        Path l = new Path();
        l.moveTo(124, 80); l.quadTo(92, 62, 50, 70); l.lineTo(50, 188); l.quadTo(92, 180, 124, 198); l.close();
        Path r = new Path();
        r.moveTo(132, 80); r.quadTo(164, 62, 206, 70); r.lineTo(206, 188); r.quadTo(164, 180, 132, 198); r.close();
        cv.drawPath(l, i);
        cv.drawPath(r, i);
    }

    private static void torch(Canvas cv, boolean dark) {
        int a = 0xFF5A5F6B, b = 0xFF24262C;
        tile(cv, a, b, dark);
        Paint beam = p();
        beam.setShader(new LinearGradient(0, 40, 0, 120, 0xFFFFE680, 0x00FFE680, Shader.TileMode.CLAMP));
        Path cone = new Path();
        cone.moveTo(84, 40); cone.lineTo(172, 40); cone.lineTo(150, 110); cone.lineTo(106, 110); cone.close();
        cv.drawPath(cone, beam);
        Paint body = p();
        body.setColor(0xFFFFFFFF);
        cv.drawRoundRect(new RectF(100, 106, 156, 132), 8, 8, body);
        cv.drawRoundRect(new RectF(110, 128, 146, 214), 10, 10, body);
        Paint btn = p();
        btn.setColor(0xFFFFB020);
        cv.drawCircle(128, 158, 8, btn);
    }

    private static void lock(Canvas cv, boolean dark) {
        int a = 0xFF5B6270, b = 0xFF2A2E36;
        tile(cv, a, b, dark);
        Paint i = p();
        i.setColor(dark ? 0xFFB9BEC6 : 0xFFFFFFFF);
        Paint shackle = stroke(i, 18);
        cv.drawArc(new RectF(90, 58, 166, 140), 180, 180, false, shackle);
        cv.drawLine(90, 100, 90, 120, shackle);
        cv.drawLine(166, 100, 166, 120, shackle);
        cv.drawRoundRect(new RectF(68, 116, 188, 206), 20, 20, i);
        Paint hole = p();
        hole.setColor(0xFF2A2E36);
        cv.drawCircle(128, 156, 12, hole);
        cv.drawRect(123, 156, 133, 184, hole);
    }
}
