package com.reach.launcher;

import android.content.ComponentName;
import android.content.pm.ShortcutInfo;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;

/** One launchable thing: an app activity or a pinned app shortcut. */
final class Entry {
    static final int APP = 0, SHORTCUT = 1, FOLDER = 2;

    final int kind;
    final String key;
    final String pkg;
    final ComponentName component; // APP only
    final ShortcutInfo shortcut;   // SHORTCUT only
    final UserHandle user;
    final long serial;

    String origLabel = "";
    String label = "";
    String norm = "";
    String section = "#";
    Drawable icon;
    boolean hidden;
    boolean work;
    Folders.Folder folder; // FOLDER only

    Entry(int kind, String key, String pkg, ComponentName component, ShortcutInfo shortcut, UserHandle user, long serial) {
        this.kind = kind;
        this.key = key;
        this.pkg = pkg;
        this.component = component;
        this.shortcut = shortcut;
        this.user = user;
        this.serial = serial;
    }

    /** Key used by NotifService to group notifications. */
    String notifKey() { return pkg + "@" + serial; }
}
