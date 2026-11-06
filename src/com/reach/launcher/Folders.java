package com.reach.launcher;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Process;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * User folders (named groups of app keys, stored as JSON) and the automatic "rarely used" folder.
 * Rarely used comes from Android's usage history when the user grants usage access, otherwise
 * from launches made through Reach (only after Reach has been used for a week, so new installs
 * don't put everything in it).
 */
final class Folders {
    static final String RARE_ID = "rare";
    private static final long DAY = 24L * 60 * 60 * 1000;

    static final class Folder {
        String id, name;
        final List<String> keys = new ArrayList<>();
    }

    private Folders() { }

    static List<Folder> load(Prefs p) {
        List<Folder> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p.str("folders", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Folder f = new Folder();
                f.id = o.getString("id");
                f.name = o.optString("name", "Folder");
                JSONArray k = o.optJSONArray("apps");
                if (k != null) for (int j = 0; j < k.length(); j++) f.keys.add(k.getString(j));
                out.add(f);
            }
        } catch (Exception ignored) { }
        return out;
    }

    static void save(Prefs p, List<Folder> list) {
        JSONArray a = new JSONArray();
        try {
            for (Folder f : list) {
                if (f.keys.isEmpty()) continue; // an emptied folder disappears
                JSONObject o = new JSONObject();
                o.put("id", f.id);
                o.put("name", f.name);
                o.put("apps", new JSONArray(f.keys));
                a.put(o);
            }
        } catch (Exception ignored) { }
        p.set("folders", a.toString());
    }

    static Folder create(List<Folder> list, String name) {
        Folder f = new Folder();
        f.id = UUID.randomUUID().toString().substring(0, 8);
        f.name = name;
        list.add(f);
        return f;
    }

    /** Automatic category folders have ids starting with this; "Undo" removes exactly these. */
    static final String AUTO = "auto-";

    /** App kind (see LineIcons) -> folder name. */
    static String category(String kind) {
        if (kind == null) return null;
        switch (kind.replace("~", "")) {
            case "chat": case "message": case "send": return "Social";
            case "play": return "Movies & TV";
            case "music": return "Music";
            case "bag": return "Shopping";
            case "bank": case "wallet": return "Finance";
            case "notes": case "folder": case "mail": case "calendar": return "Productivity";
            case "pin": case "car": return "Travel";
            case "camera": case "photos": return "Photos";
            case "game": return "Games";
            case "heart": return "Health";
            case "book": case "news": return "Reading";
            default: return "Utilities";
        }
    }

    static Folder containing(List<Folder> list, String key) {
        for (Folder f : list) if (f.keys.contains(key)) return f;
        return null;
    }

    /** Moves key into target (removing it from any other folder). */
    static void put(List<Folder> list, Folder target, String key) {
        for (Folder f : list) f.keys.remove(key);
        if (!target.keys.contains(key)) target.keys.add(key);
    }

    // ---- usage ----

    static void recordLaunch(Prefs p, String key) {
        try {
            JSONObject u = new JSONObject(p.str("uses", "{}"));
            JSONArray v = u.optJSONArray(key);
            int count = v == null ? 0 : v.optInt(0);
            u.put(key, new JSONArray().put(count + 1).put(System.currentTimeMillis()));
            p.setQuiet("uses", u.toString());
        } catch (Exception ignored) { }
    }

    static boolean usageAccess(Context c) {
        try {
            AppOpsManager ops = c.getSystemService(AppOpsManager.class);
            return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName())
                    == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    /** Keys of apps not opened in the last three weeks. Call off the main thread. */
    static Set<String> rarelyUsed(Context c, Prefs p, List<Entry> apps) {
        Set<String> out = new HashSet<>();
        long now = System.currentTimeMillis(), cutoff = now - 21 * DAY;
        if (usageAccess(c)) {
            UsageStatsManager usm = c.getSystemService(UsageStatsManager.class);
            Map<String, UsageStats> stats = usm.queryAndAggregateUsageStats(now - 60 * DAY, now);
            if (stats == null || stats.isEmpty()) return out;
            for (Entry e : apps) {
                UsageStats s = stats.get(e.pkg);
                if (s == null || (s.getLastTimeUsed() < cutoff && s.getTotalTimeInForeground() < 5 * 60 * 1000))
                    out.add(e.key);
            }
            return out;
        }
        long first = p.firstRun();
        if (now - first < 7 * DAY) return out; // not enough history yet
        try {
            JSONObject u = new JSONObject(p.str("uses", "{}"));
            for (Entry e : apps) {
                JSONArray v = u.optJSONArray(e.key);
                if (v == null || v.optLong(1) < cutoff) out.add(e.key);
            }
        } catch (Exception ignored) { }
        return out;
    }
}
