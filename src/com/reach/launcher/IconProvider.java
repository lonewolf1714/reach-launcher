package com.reach.launcher;

import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.LauncherApps;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.FileNotFoundException;
import java.io.OutputStream;

/**
 * Shares Reach's styled app icons (icon pack + icon style + depth) as PNGs with Widget Studio, so its
 * App List widget looks like the home screen. Only Widget Studio may read it.
 *
 * content://com.reach.launcher.icons/icon/{flattened component}?s={size px}
 */
public class IconProvider extends ContentProvider {
    static final String CLIENT = "com.widgetstudio";
    private static AppRepo repo;

    @Override public boolean onCreate() { return true; }

    private synchronized AppRepo repo() {
        if (repo == null) {
            repo = new AppRepo(getContext(), getContext().getSystemService(LauncherApps.class), new Prefs(getContext()));
        }
        return repo;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!CLIENT.equals(getCallingPackage())) throw new SecurityException("not allowed");
        ComponentName cn = ComponentName.unflattenFromString(String.valueOf(uri.getLastPathSegment()));
        if (cn == null) throw new FileNotFoundException("bad component");
        Drawable d;
        try {
            d = repo().themedIcon(cn);
        } catch (Exception e) {
            d = null;
        }
        if (d == null) throw new FileNotFoundException("no icon");
        int s = 192;
        try { s = Math.max(48, Math.min(512, Integer.parseInt(uri.getQueryParameter("s")))); } catch (Exception ignored) { }
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Drawable copy = d.getConstantState() != null ? d.getConstantState().newDrawable(getContext().getResources()).mutate() : d;
        copy.setBounds(0, 0, s, s);
        copy.draw(new Canvas(b));
        try {
            ParcelFileDescriptor[] p = ParcelFileDescriptor.createPipe();
            new Thread(() -> {
                try (OutputStream os = new ParcelFileDescriptor.AutoCloseOutputStream(p[1])) {
                    b.compress(Bitmap.CompressFormat.PNG, 100, os);
                } catch (Exception ignored) {
                } finally {
                    b.recycle();
                }
            }).start();
            return p[0];
        } catch (Exception e) {
            throw new FileNotFoundException(e.toString());
        }
    }

    @Override public String getType(Uri uri) { return "image/png"; }

    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { return null; }

    @Override public Uri insert(Uri uri, ContentValues v) { return null; }

    @Override public int delete(Uri uri, String s, String[] a) { return 0; }

    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
