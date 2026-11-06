package com.reach.launcher;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import java.io.InputStream;

/** "Change wallpaper": a photo from the gallery (home, lock or both) or the system wallpaper picker. */
final class WallpaperPicker {
    static final int REQ_PICK = 301;

    private WallpaperPicker() { }

    static void showMenu(Activity a) {
        Sheet s = new Sheet(a, "Wallpaper", null);
        s.item("Photo from gallery", () -> pick(a));
        if (WallpaperEditActivity.saved(a).exists())
            s.item("Edit current photo (zoom, position, filters)", () ->
                    a.startActivity(new Intent(a, WallpaperEditActivity.class)));
        s.item("Wallpapers & style", () -> {
            try { a.startActivity(Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper")); }
            catch (Exception e) { Ui.toast(a, "Not available on this device"); }
        });
        s.show();
    }

    static void pick(Activity a) {
        Intent i = Build.VERSION.SDK_INT >= 33
                ? new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
                : new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        try {
            a.startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            try {
                a.startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQ_PICK);
            } catch (Exception e2) {
                Ui.toast(a, "No gallery app found");
            }
        }
    }

    /** Returns true if this was our request. */
    static boolean onResult(Activity a, int req, int res, Intent data) {
        if (req != REQ_PICK) return false;
        if (res != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        Uri u = data.getData();
        try {
            a.startActivity(new Intent(a, WallpaperEditActivity.class).setData(u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            return true;
        } catch (Exception ignored) { }
        Sheet s = new Sheet(a, "Set this photo as", null);
        s.item("Home screen", () -> set(a, u, WallpaperManager.FLAG_SYSTEM));
        s.item("Lock screen", () -> set(a, u, WallpaperManager.FLAG_LOCK));
        s.item("Home and lock screens", () -> set(a, u, WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK));
        s.item("Crop & preview first…", () -> {
            try {
                Intent crop = WallpaperManager.getInstance(a).getCropAndSetWallpaperIntent(u);
                crop.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                a.startActivity(crop);
            } catch (Exception e) {
                Ui.toast(a, "Cropping isn't available here; choose Home or Lock screen");
            }
        });
        s.show();
        return true;
    }

    private static void set(Activity a, Uri u, int which) {
        Ui.toast(a, "Setting wallpaper…");
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String msg;
            try (InputStream in = a.getContentResolver().openInputStream(u)) {
                WallpaperManager.getInstance(a).setStream(in, null, true, which);
                msg = "Wallpaper set";
            } catch (Exception e) {
                msg = "Couldn't use that photo";
            }
            String m = msg;
            main.post(() -> Ui.toast(a, m));
        }).start();
    }
}
