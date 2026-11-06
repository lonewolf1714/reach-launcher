package com.reach.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.os.Bundle;
import android.os.UserManager;

/** Confirms "add to home screen" requests from other apps (pinned shortcuts and widgets). */
public class PinActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LauncherApps la = getSystemService(LauncherApps.class);
        LauncherApps.PinItemRequest req = la.getPinItemRequest(getIntent());
        if (req == null || !req.isValid()) { finish(); return; }
        Prefs prefs = new Prefs(this);

        if (req.getRequestType() == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT) {
            ShortcutInfo si = req.getShortcutInfo();
            CharSequence label = si.getShortLabel() != null ? si.getShortLabel() : si.getId();
            confirm("Add “" + label + "” to your favorites?", () -> {
                if (req.accept()) {
                    long serial = getSystemService(UserManager.class).getSerialNumberForUser(si.getUserHandle());
                    prefs.addFavorite(AppRepo.scKey(si.getPackage(), si.getId(), serial));
                    Ui.toast(this, "Added " + label);
                }
            });
        } else if (req.getRequestType() == LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET) {
            AppWidgetProviderInfo info = req.getAppWidgetProviderInfo(this);
            String label = info == null ? "widget" : info.loadLabel(getPackageManager());
            confirm("Add the “" + label + "” widget to your home screen?", () -> {
                AppWidgetHost host = new AppWidgetHost(this, Widgets.HOST_ID);
                int id = host.allocateAppWidgetId();
                Bundle extras = new Bundle();
                extras.putInt(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
                if (req.accept(extras)) prefs.addWidget(id); else host.deleteAppWidgetId(id);
            });
        } else {
            finish();
        }
    }

    private void confirm(String message, Runnable onAdd) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage(message)
                .setPositiveButton("Add", (d, w) -> onAdd.run())
                .setNegativeButton("Cancel", null)
                .setOnDismissListener(d -> finish())
                .show();
    }
}
