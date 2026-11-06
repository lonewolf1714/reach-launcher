package com.reach.launcher;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mirrors active notifications, grouped per app ("pkg@userSerial"), so home rows can show a
 * count badge and the latest message. Needs the user to grant notification access.
 */
public class NotifService extends NotificationListenerService {
    interface Listener { void onNotificationsChanged(); }

    static volatile NotifService instance;
    static Listener listener;
    private static Map<String, List<StatusBarNotification>> byApp = Collections.emptyMap();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static List<StatusBarNotification> forKey(String key) {
        List<StatusBarNotification> l = byApp.get(key);
        return l == null ? Collections.<StatusBarNotification>emptyList() : l;
    }

    static boolean granted(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(new ComponentName(c, NotifService.class).flattenToString());
    }

    static void dismiss(StatusBarNotification sbn) {
        NotifService s = instance;
        if (s != null && sbn.isClearable()) s.cancelNotification(sbn.getKey());
    }

    @Override public void onListenerConnected() { instance = this; rebuild(); }

    @Override public void onListenerDisconnected() {
        instance = null;
        publish(Collections.<String, List<StatusBarNotification>>emptyMap());
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) { rebuild(); }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) { rebuild(); }

    private void rebuild() {
        StatusBarNotification[] all;
        try { all = getActiveNotifications(); } catch (Exception e) { return; }
        if (all == null) return;
        UserManager um = getSystemService(UserManager.class);

        // Groups that have real children: their summary would double count.
        Set<String> groupsWithChildren = new HashSet<>();
        for (StatusBarNotification s : all) {
            Notification n = s.getNotification();
            if (n.getGroup() != null && (n.flags & Notification.FLAG_GROUP_SUMMARY) == 0)
                groupsWithChildren.add(s.getPackageName() + "|" + n.getGroup());
        }

        Map<String, List<StatusBarNotification>> m = new HashMap<>();
        for (StatusBarNotification s : all) {
            Notification n = s.getNotification();
            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0
                    && groupsWithChildren.contains(s.getPackageName() + "|" + n.getGroup())) continue;
            boolean ongoing = (n.flags & Notification.FLAG_ONGOING_EVENT) != 0;
            if (ongoing && !Notification.CATEGORY_TRANSPORT.equals(n.category)
                    && !Notification.CATEGORY_CALL.equals(n.category)) continue; // skip "running" service noise
            Bundle ex = n.extras;
            if (TextUtils.isEmpty(ex.getCharSequence(Notification.EXTRA_TITLE))
                    && TextUtils.isEmpty(ex.getCharSequence(Notification.EXTRA_TEXT))) continue;
            String key = s.getPackageName() + "@" + um.getSerialNumberForUser(s.getUser());
            List<StatusBarNotification> l = m.get(key);
            if (l == null) m.put(key, l = new ArrayList<>());
            l.add(s);
        }
        for (List<StatusBarNotification> l : m.values())
            Collections.sort(l, (a, b) -> Long.compare(b.getPostTime(), a.getPostTime()));
        publish(m);
    }

    private static void publish(Map<String, List<StatusBarNotification>> m) {
        MAIN.post(() -> {
            byApp = m;
            Listener l = listener;
            if (l != null) l.onNotificationsChanged();
        });
    }
}
