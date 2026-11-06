package com.reach.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.graphics.drawable.Drawable;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads apps (all profiles, including work) and pinned shortcuts through LauncherApps.
 * load() runs on a background thread; the icon cache is only touched from that thread.
 *
 * Keys: "a:pkg/.Cls@serial" for apps, "s:pkg/shortcutId@serial" for shortcuts.
 */
final class AppRepo {
    static final class Snapshot {
        final List<Entry> apps = new ArrayList<>();
        final Map<String, Entry> byKey = new HashMap<>();
        final Map<String, Entry> shortcuts = new LinkedHashMap<>();
        final List<Entry> favorites = new ArrayList<>();
        java.util.Set<String> rare = new java.util.HashSet<>();
    }

    private final Context c;
    private final LauncherApps la;
    private final UserManager um;
    private final Prefs prefs;
    private final Map<String, Drawable> iconCache = new HashMap<>();
    private String loadedPack;  // background thread only
    private IconPack pack;
    private String loadedLook = "";  // icon style + color: background thread only

    AppRepo(Context c, LauncherApps la, Prefs prefs) {
        this.c = c.getApplicationContext();
        this.la = la;
        this.um = c.getSystemService(UserManager.class);
        this.prefs = prefs;
    }

    static String appKey(ComponentName cn, long serial) { return "a:" + cn.flattenToShortString() + "@" + serial; }

    static String scKey(String pkg, String id, long serial) { return "s:" + pkg + "/" + id + "@" + serial; }

    /** Called on the background thread before a reload when a package changed. */
    void invalidate(String pkg) {
        if (pkg.equals(loadedPack)) loadedPack = null; // icon pack updated or removed: re-read it
        String needle = ":" + pkg + "/";
        iconCache.keySet().removeIf(k -> k.contains(needle));
    }

    Snapshot load() {
        Snapshot s = new Snapshot();
        String packPkg = prefs.iconPack();
        if (!packPkg.equals(loadedPack)) {
            iconCache.clear();
            pack = packPkg.isEmpty() ? null : IconPack.load(c, packPkg);
            loadedPack = packPkg;
        }
        String look = prefs.iconStyle() + "|" + prefs.iconColor() + "|" + prefs.accent() + "|" + prefs.font()
                + "|" + prefs.depth() + "|" + prefs.surface() + "|" + prefs.s(Prefs.ICON_WEIGHT)
                + "|" + prefs.s(Prefs.ICON_TILE) + "|" + prefs.ink();
        if (!look.equals(loadedLook)) { iconCache.clear(); loadedLook = look; }
        PackageManager pm = c.getPackageManager();
        Map<String, String> roles = IconStyler.usesKinds(prefs.iconStyle()) ? LineIcons.roles(c) : new HashMap<>();
        String self = c.getPackageName();
        UserHandle me = Process.myUserHandle();
        for (UserHandle u : la.getProfiles()) {
            long serial = um.getSerialNumberForUser(u);
            List<LauncherActivityInfo> list;
            try { list = la.getActivityList(null, u); } catch (Exception e) { continue; }
            for (LauncherActivityInfo i : list) {
                ComponentName cn = i.getComponentName();
                if (cn.getPackageName().equals(self)) continue; // our settings entry stays reachable via long-press menu
                String key = appKey(cn, serial);
                Entry e = new Entry(Entry.APP, key, cn.getPackageName(), cn, null, u, serial);
                e.origLabel = String.valueOf(i.getLabel());
                e.work = !u.equals(me);
                Drawable d = iconCache.get(key);
                if (d == null) {
                    try {
                        d = i.getIcon(0);
                        if (pack != null) d = pack.icon(cn, d);
                        String kind = null;
                        if (!roles.isEmpty()) {
                            kind = LineIcons.kindFor(cn, e.origLabel, i.getApplicationInfo().category, roles);
                            if (kind != null && LineIcons.preferOwnLogo(kind, cn, roles)) kind = "~" + kind;
                        }
                        d = style(d, e.origLabel, kind);
                        if (d != null && e.work) d = pm.getUserBadgedIcon(d, u);
                    } catch (Exception ignored) { }
                    if (d != null) iconCache.put(key, d);
                }
                e.icon = d;
                s.apps.add(e);
                s.byKey.put(key, e);
            }
        }
        if (!prefs.seeded()) seedFavorites(s);
        loadShortcutFavorites(s);
        applyMeta(s);
        prefs.firstRun();
        if (prefs.rareFolder()) {
            try { s.rare = Folders.rarelyUsed(c, prefs, s.apps); } catch (Exception ignored) { }
        }
        return s;
    }

    /** One app's icon exactly as the home screen draws it (icon pack + style). Used by IconProvider. */
    synchronized Drawable themedIcon(ComponentName cn) {
        String packPkg = prefs.iconPack();
        if (!packPkg.equals(loadedPack)) {
            iconCache.clear();
            pack = packPkg.isEmpty() ? null : IconPack.load(c, packPkg);
            loadedPack = packPkg;
        }
        String look = prefs.iconStyle() + "|" + prefs.iconColor() + "|" + prefs.accent() + "|" + prefs.font()
                + "|" + prefs.depth() + "|" + prefs.surface() + "|" + prefs.s(Prefs.ICON_WEIGHT)
                + "|" + prefs.s(Prefs.ICON_TILE) + "|" + prefs.ink();
        if (!look.equals(loadedLook)) { iconCache.clear(); loadedLook = look; }
        UserHandle u = Process.myUserHandle();
        String key = appKey(cn, um.getSerialNumberForUser(u));
        Drawable d = iconCache.get(key);
        if (d != null) return d;
        LauncherActivityInfo i = la.resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn), u);
        if (i == null) return null;
        String label = String.valueOf(i.getLabel());
        d = i.getIcon(0);
        if (pack != null) d = pack.icon(cn, d);
        String kind = null;
        if (IconStyler.usesKinds(prefs.iconStyle())) {
            Map<String, String> roles = LineIcons.roles(c);
            kind = LineIcons.kindFor(cn, label, i.getApplicationInfo().category, roles);
            if (kind != null && LineIcons.preferOwnLogo(kind, cn, roles)) kind = "~" + kind;
        }
        d = style(d, label, kind);
        if (d != null) iconCache.put(key, d);
        return d;
    }

    /** Re-applies renames/hidden flags, re-sorts and re-resolves favorites. Main thread safe. */
    void applyMeta(Snapshot s) {
        for (Entry e : s.apps) apply(e);
        for (Entry e : s.shortcuts.values()) apply(e);
        Collections.sort(s.apps, ORDER);
        s.favorites.clear();
        for (String k : prefs.favorites()) {
            Entry e = k.startsWith("s:") ? s.shortcuts.get(k) : s.byKey.get(k);
            if (e != null) s.favorites.add(e);
        }
    }

    private void apply(Entry e) {
        String r = prefs.rename(e.key);
        e.label = r != null ? r : e.origLabel;
        e.norm = Ui.norm(e.label);
        e.section = Ui.sectionOf(e.norm);
        e.hidden = prefs.isHidden(e.key);
    }

    private static final Comparator<Entry> ORDER = (a, b) -> {
        boolean ah = a.section.equals("#"), bh = b.section.equals("#");
        if (ah != bh) return ah ? -1 : 1;
        int r = a.norm.compareTo(b.norm);
        return r != 0 ? r : Boolean.compare(a.work, b.work);
    };

    /** Applies the chosen built-in icon style (no-op for "original"). */
    Drawable style(Drawable d, String label) { return style(d, label, null); }

    Drawable style(Drawable d, String label, String kind) {
        String st = prefs.iconStyle();
        if (d == null || (IconStyler.ORIGINAL.equals(st) && prefs.depth() == 0)) return d;
        boolean tile = prefs.on(Prefs.ICON_TILE), framed = "frame".equals(prefs.s(Prefs.ICON_TILE));
        int color = IconStyler.VIVID.equals(st) ? prefs.ink() : IconStyler.DUO.equals(st) ? prefs.accent()
                : tile ? IconStyler.color(prefs.iconColor(), prefs.accent(), prefs.surface()) : prefs.ink();
        if (IconStyler.DUO.equals(st)) tile = true;
        android.graphics.Typeface face = Ui.typeface(c, prefs.font(), android.graphics.Typeface.NORMAL);
        if (framed) return IconStyler.applyFramed(c.getResources(), d, label, st, color, face, prefs.depth(), kind, prefs.s(Prefs.ICON_WEIGHT));
        return IconStyler.apply(c.getResources(), d, label, st, color, face, prefs.depth(), kind, prefs.s(Prefs.ICON_WEIGHT), tile);
    }

    Entry shortcutEntry(ShortcutInfo si, UserHandle user, long serial) {
        Entry e = new Entry(Entry.SHORTCUT, scKey(si.getPackage(), si.getId(), serial), si.getPackage(),
                null, si, user, serial);
        CharSequence l = si.getShortLabel();
        e.origLabel = l == null ? si.getId() : l.toString();
        try {
            e.icon = style(la.getShortcutBadgedIconDrawable(si, c.getResources().getDisplayMetrics().densityDpi), e.origLabel);
        } catch (Exception ignored) { }
        apply(e);
        return e;
    }

    List<ShortcutInfo> appShortcuts(Entry app) {
        List<ShortcutInfo> out = new ArrayList<>();
        if (app.kind != Entry.APP || !hasShortcutAccess()) return out;
        LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery();
        q.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST);
        q.setPackage(app.pkg);
        q.setActivity(app.component);
        try {
            List<ShortcutInfo> l = la.getShortcuts(q, app.user);
            if (l != null) for (ShortcutInfo si : l) if (si.isEnabled()) out.add(si);
        } catch (Exception ignored) { }
        // Manifest shortcuts first, then dynamic, each by rank — the order apps intend.
        Collections.sort(out, (a, b) -> a.isDeclaredInManifest() != b.isDeclaredInManifest()
                ? (a.isDeclaredInManifest() ? -1 : 1) : Integer.compare(a.getRank(), b.getRank()));
        return out;
    }

    boolean hasShortcutAccess() {
        try { return la.hasShortcutHostPermission(); } catch (Exception e) { return false; }
    }

    /** Pins exactly the shortcuts of pkg that are in favorites (the launcher owns its pin set). */
    void syncPins(String pkg, UserHandle user, long serial) {
        if (!hasShortcutAccess()) return;
        String prefix = "s:" + pkg + "/", suffix = "@" + serial;
        List<String> ids = new ArrayList<>();
        for (String k : prefs.favorites()) {
            if (k.startsWith(prefix) && k.endsWith(suffix))
                ids.add(k.substring(prefix.length(), k.length() - suffix.length()));
        }
        try { la.pinShortcuts(pkg, ids, user); } catch (Exception ignored) { }
    }

    private void loadShortcutFavorites(Snapshot s) {
        if (!hasShortcutAccess()) return;
        // group "s:" keys by package+serial so each app is queried once
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String k : prefs.favorites()) {
            if (!k.startsWith("s:")) continue;
            int slash = k.indexOf('/'), at = k.lastIndexOf('@');
            if (slash < 0 || at < slash) continue;
            String group = k.substring(2, slash) + k.substring(at);
            List<String> ids = groups.get(group);
            if (ids == null) groups.put(group, ids = new ArrayList<>());
            ids.add(k.substring(slash + 1, at));
        }
        for (Map.Entry<String, List<String>> g : groups.entrySet()) {
            int at = g.getKey().lastIndexOf('@');
            String pkg = g.getKey().substring(0, at);
            long serial;
            try { serial = Long.parseLong(g.getKey().substring(at + 1)); } catch (NumberFormatException e) { continue; }
            UserHandle user = um.getUserForSerialNumber(serial);
            if (user == null) continue;
            LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery();
            q.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
                    | LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST);
            q.setPackage(pkg);
            q.setShortcutIds(g.getValue());
            try {
                List<ShortcutInfo> l = la.getShortcuts(q, user);
                if (l == null) continue;
                for (ShortcutInfo si : l) {
                    Entry e = shortcutEntry(si, user, serial);
                    s.shortcuts.put(e.key, e);
                }
            } catch (Exception ignored) { }
        }
    }

    /** First run: phone, messages, browser, camera — whatever this device has. */
    private void seedFavorites(Snapshot s) {
        Intent[] intents = {
                new Intent(Intent.ACTION_DIAL),
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING),
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER),
                new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY),
        };
        PackageManager pm = c.getPackageManager();
        long mySerial = um.getSerialNumberForUser(Process.myUserHandle());
        List<String> favs = prefs.favorites();
        for (Intent i : intents) {
            ResolveInfo r = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
            if (r == null || r.activityInfo == null) continue;
            String pkg = r.activityInfo.packageName;
            for (Entry e : s.apps) {
                if (e.pkg.equals(pkg) && e.serial == mySerial && !favs.contains(e.key)) { favs.add(e.key); break; }
            }
        }
        prefs.setFavorites(favs);
        prefs.markSeeded();
    }
}
