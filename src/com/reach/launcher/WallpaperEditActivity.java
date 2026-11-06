package com.reach.launcher;

import android.app.Activity;
import android.app.WallpaperManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Full-screen wallpaper editor: pinch to zoom, drag to reposition, filters with intensity and
 * brightness, then set it on the home screen, lock screen or both. The original photo is kept
 * in app storage so it can be edited again later.
 */
public class WallpaperEditActivity extends Activity {
    static final String[] FILTERS = {"Original", "Vivid", "Warm", "Cool", "Mono", "Noir", "Fade", "Sepia", "Dramatic"};

    private ImageView image;
    private Bitmap src;
    private final Matrix m = new Matrix();
    private int filter;
    private int intensity = 100, brightness = 0;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ScaleGestureDetector scaler;
    private GestureDetector taps;
    private float lastX, lastY;
    private boolean dragging;
    private LinearLayout strip;

    static File saved(android.content.Context c) { return new File(c.getFilesDir(), "wallpaper_source.jpg"); }

    private int dp(float v) { return Ui.dp(this, v); }

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.setStatusBarColor(0xFF000000);
        w.setNavigationBarColor(0xFF000000);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.MATRIX);
        root.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // top: Cancel / Set
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(16), dp(36), dp(16), dp(10));
        top.setBackgroundColor(0x66000000);
        TextView cancel = button("Cancel", false);
        cancel.setOnClickListener(v -> finish());
        top.addView(cancel);
        TextView title = new TextView(this);
        title.setText("Pinch to zoom · drag to move");
        title.setTextColor(0xCCFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setGravity(Gravity.CENTER);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView set = button("Set", true);
        set.setOnClickListener(v -> chooseTarget());
        top.addView(set);
        root.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // bottom: filters + sliders
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(0xB3000000);
        bottom.setPadding(dp(12), dp(10), dp(12), dp(28));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        strip = new LinearLayout(this);
        hs.addView(strip);
        bottom.addView(hs);
        bottom.addView(slider("Intensity", 0, 100, 100, v -> { intensity = v; applyFilter(); }));
        bottom.addView(slider("Brightness", -50, 50, 0, v -> { brightness = v; applyFilter(); }));
        root.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        setContentView(root);

        scaler = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                m.postScale(d.getScaleFactor(), d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                clamp();
                return true;
            }
        });
        taps = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent e) { fit(); return true; }
        });
        image.setOnTouchListener((v, e) -> {
            scaler.onTouchEvent(e);
            taps.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: lastX = e.getX(); lastY = e.getY(); dragging = true; break;
                case MotionEvent.ACTION_POINTER_DOWN: dragging = false; break;
                case MotionEvent.ACTION_MOVE:
                    if (dragging && e.getPointerCount() == 1 && !scaler.isInProgress()) {
                        m.postTranslate(e.getX() - lastX, e.getY() - lastY);
                        clamp();
                    }
                    lastX = e.getX();
                    lastY = e.getY();
                    break;
                case MotionEvent.ACTION_UP: dragging = false; break;
            }
            return true;
        });

        Uri u = getIntent().getData();
        new Thread(() -> {
            Bitmap bm = load(u);
            main.post(() -> {
                if (bm == null) { Ui.toast(this, "Couldn't open that photo"); finish(); return; }
                src = bm;
                image.setImageBitmap(src);
                image.post(this::fit);
                buildFilterStrip();
            });
        }).start();
    }

    private TextView button(String text, boolean primary) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTextColor(primary ? 0xFF000000 : 0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(18), dp(8), dp(18), dp(8));
        t.setBackground(Ui.round(primary ? 0xFFFFFFFF : 0x33FFFFFF, dp(18)));
        return t;
    }

    private View slider(String name, int min, int max, int val, java.util.function.IntConsumer on) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(6), dp(4), 0);
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        row.addView(t, new LinearLayout.LayoutParams(dp(86), ViewGroup.LayoutParams.WRAP_CONTENT));
        SeekBar sb = new SeekBar(this);
        sb.setMin(min);
        sb.setMax(max);
        sb.setProgress(val);
        sb.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        sb.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean u) { if (u) on.accept(v); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        row.addView(sb, new LinearLayout.LayoutParams(0, dp(40), 1f));
        return row;
    }

    /** Decodes (downsampled to about twice the screen) and keeps a copy of the original for later edits. */
    private Bitmap load(Uri u) {
        try {
            File keep = saved(this);
            if (u != null) {
                try (InputStream in = getContentResolver().openInputStream(u); FileOutputStream out = new FileOutputStream(keep)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
            }
            if (!keep.exists()) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(keep.getAbsolutePath(), o);
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int target = Math.max(dm.widthPixels, dm.heightPixels) * 2;
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= target) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap bm = BitmapFactory.decodeFile(keep.getAbsolutePath(), o);
            int rot = exifRotation(keep);
            if (bm != null && rot != 0) {
                Matrix r = new Matrix();
                r.postRotate(rot);
                bm = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), r, true);
            }
            return bm;
        } catch (Exception e) {
            return null;
        }
    }

    private static int exifRotation(File f) {
        try {
            android.media.ExifInterface ex = new android.media.ExifInterface(f.getAbsolutePath());
            switch (ex.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                case android.media.ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case android.media.ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case android.media.ExifInterface.ORIENTATION_ROTATE_270: return 270;
            }
        } catch (Exception ignored) { }
        return 0;
    }

    /** Scale so the photo covers the whole screen, centered. */
    private void fit() {
        if (src == null || image.getWidth() == 0) return;
        float s = Math.max(image.getWidth() / (float) src.getWidth(), image.getHeight() / (float) src.getHeight());
        m.reset();
        m.postScale(s, s);
        m.postTranslate((image.getWidth() - src.getWidth() * s) / 2f, (image.getHeight() - src.getHeight() * s) / 2f);
        image.setImageMatrix(m);
    }

    /** Never zoom out past "cover" and never show empty edges. */
    private void clamp() {
        if (src == null) return;
        float vw = image.getWidth(), vh = image.getHeight();
        float min = Math.max(vw / src.getWidth(), vh / src.getHeight());
        float[] v = new float[9];
        m.getValues(v);
        float s = v[Matrix.MSCALE_X];
        if (s < min) { m.postScale(min / s, min / s, vw / 2, vh / 2); }
        if (s > min * 6) { m.postScale(min * 6 / s, min * 6 / s, vw / 2, vh / 2); }
        RectF r = new RectF(0, 0, src.getWidth(), src.getHeight());
        m.mapRect(r);
        float dx = 0, dy = 0;
        if (r.left > 0) dx = -r.left; else if (r.right < vw) dx = vw - r.right;
        if (r.top > 0) dy = -r.top; else if (r.bottom < vh) dy = vh - r.bottom;
        m.postTranslate(dx, dy);
        image.setImageMatrix(m);
    }

    // ---- filters ----

    static ColorMatrix matrix(int filter, int intensity, int brightness) {
        ColorMatrix f = new ColorMatrix();
        switch (FILTERS[filter]) {
            case "Vivid": f.setSaturation(1.5f); f.postConcat(contrast(1.1f)); break;
            case "Warm": f.setScale(1.08f, 1.0f, 0.88f, 1f); break;
            case "Cool": f.setScale(0.9f, 1.0f, 1.1f, 1f); break;
            case "Mono": f.setSaturation(0f); break;
            case "Noir": f.setSaturation(0f); f.postConcat(contrast(1.4f)); break;
            case "Fade": f.set(new float[]{0.85f, 0, 0, 0, 28, 0, 0.85f, 0, 0, 28, 0, 0, 0.85f, 0, 28, 0, 0, 0, 1, 0}); break;
            case "Sepia": f.set(new float[]{0.393f, 0.769f, 0.189f, 0, 0, 0.349f, 0.686f, 0.168f, 0, 0,
                    0.272f, 0.534f, 0.131f, 0, 0, 0, 0, 0, 1, 0}); break;
            case "Dramatic": f.setSaturation(0.85f); f.postConcat(contrast(1.3f)); f.postConcat(offset(-12)); break;
        }
        // blend toward the original by intensity
        float t = intensity / 100f;
        float[] a = f.getArray(), id = new ColorMatrix().getArray(), out = new float[20];
        for (int i = 0; i < 20; i++) out[i] = id[i] + (a[i] - id[i]) * t;
        ColorMatrix res = new ColorMatrix(out);
        res.postConcat(offset(brightness * 1.6f));
        return res;
    }

    private static ColorMatrix contrast(float c) {
        float o = 128 * (1 - c);
        return new ColorMatrix(new float[]{c, 0, 0, 0, o, 0, c, 0, 0, o, 0, 0, c, 0, o, 0, 0, 0, 1, 0});
    }

    private static ColorMatrix offset(float o) {
        return new ColorMatrix(new float[]{1, 0, 0, 0, o, 0, 1, 0, 0, o, 0, 0, 1, 0, o, 0, 0, 0, 1, 0});
    }

    private void applyFilter() {
        image.setColorFilter(new ColorMatrixColorFilter(matrix(filter, intensity, brightness)));
    }

    private void buildFilterStrip() {
        strip.removeAllViews();
        int th = dp(64);
        Bitmap thumb = Bitmap.createScaledBitmap(src, Math.max(1, th * src.getWidth() / Math.max(1, src.getHeight())), th, true);
        for (int i = 0; i < FILTERS.length; i++) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(thumb);
            iv.setColorFilter(new ColorMatrixColorFilter(matrix(i, 100, 0)));
            iv.setBackground(Ui.round(i == filter ? 0xFFFFFFFF : 0x00000000, dp(10)));
            iv.setPadding(dp(2), dp(2), dp(2), dp(2));
            iv.setClipToOutline(true);
            cell.addView(iv, new LinearLayout.LayoutParams(dp(54), th));
            TextView t = new TextView(this);
            t.setText(FILTERS[i]);
            t.setTextColor(i == filter ? 0xFFFFFFFF : 0x99FFFFFF);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            t.setPadding(0, dp(4), 0, 0);
            cell.addView(t);
            final int idx = i;
            cell.setOnClickListener(v -> { filter = idx; applyFilter(); buildFilterStrip(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(10));
            strip.addView(cell, lp);
        }
    }

    // ---- set ----

    private void chooseTarget() {
        if (src == null) return;
        Sheet s = new Sheet(this, "Set wallpaper", null);
        s.item("Home screen", () -> render(WallpaperManager.FLAG_SYSTEM));
        s.item("Lock screen", () -> render(WallpaperManager.FLAG_LOCK));
        s.item("Home and lock screens", () -> render(WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK));
        s.show();
    }

    /** Draws exactly what's on screen (zoom, position, filter) at full screen resolution. */
    private void render(int which) {
        Rect screen;
        if (Build.VERSION.SDK_INT >= 30) {
            screen = getWindowManager().getCurrentWindowMetrics().getBounds();
        } else {
            DisplayMetrics dm = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(dm);
            screen = new Rect(0, 0, dm.widthPixels, dm.heightPixels);
        }
        int ow = screen.width(), oh = screen.height();
        float k = ow / (float) image.getWidth();
        Matrix out = new Matrix(m);
        out.postTranslate(0, (oh / k - image.getHeight()) / 2f); // the view may be shorter than the screen
        out.postScale(k, k);
        ColorMatrix cm = matrix(filter, intensity, brightness);
        Ui.toast(this, "Setting wallpaper…");
        new Thread(() -> {
            String msg;
            try {
                Bitmap bmp = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888);
                Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
                p.setColorFilter(new ColorMatrixColorFilter(cm));
                new Canvas(bmp).drawBitmap(src, out, p);
                WallpaperManager.getInstance(this).setBitmap(bmp, null, true, which);
                msg = "Wallpaper set";
            } catch (Exception e) {
                msg = "Couldn't set the wallpaper";
            }
            String fm = msg;
            main.post(() -> { Ui.toast(this, fm); finish(); });
        }).start();
    }
}
