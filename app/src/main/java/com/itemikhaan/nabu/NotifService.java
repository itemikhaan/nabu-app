package com.itemikhaan.nabu;

import android.app.Notification;
import android.content.pm.PackageManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public class NotifService extends NotificationListenerService {
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || getPackageName().equals(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (n == null) return;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0 || (n.flags & Notification.FLAG_ONGOING_EVENT) != 0) return;
        CharSequence t = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence x = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        String title = t != null ? t.toString() : null;
        if (title == null || title.isEmpty()) {
            try {
                PackageManager pm = getPackageManager();
                title = pm.getApplicationLabel(pm.getApplicationInfo(sbn.getPackageName(), 0)).toString();
            } catch (Exception e) { title = "Notification"; }
        }
        NabuClient.get(this).sendNotification(title, x != null ? x.toString() : "");
    }
}
