package com.example.bluetoothtracker;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class SessionNotifications {
    private static final String CHANNEL_ID = "device_presence";
    private static final int END_ALARM_REQUEST = 71;
    private static final String EXTRA_ADDRESS = "tracked_address";
    private static final String EXTRA_SESSION_ID = "tracking_session_id";

    private SessionNotifications() { }

    static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Device presence", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Alerts when the tracked Bluetooth device appears or disappears.");
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    static void handleEvents(Context context, String address, SessionEvents events) {
        for (TrackingSession session : events.ended) notifyEnded(context, session);
        for (TrackingSession session : events.started) notifyStarted(context, session);
        if (events.latest != null && !events.latest.manual) {
            cancelNotification(context, notificationId(events.latest.id, true));
            scheduleEnd(context, address, events.latest);
        } else {
            cancelEnd(context);
        }
    }

    static void notifyStarted(Context context, TrackingSession session) {
        post(context, notificationId(session.id, false), "Device in range",
                session.name + " detected at " + time(session.startedAt));
    }

    static void notifyEnded(Context context, TrackingSession session) {
        post(context, notificationId(session.id, true), "Device no longer detected",
                session.name + " last seen at " + time(session.lastSeen));
    }

    static void scheduleEnd(Context context, String address, TrackingSession session) {
        if (session == null || session.manual) return;
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        PendingIntent pendingIntent = endPendingIntent(context, address, session.id);
        long reportDelay = BleScanReceiver.scanReportDelayMillis(context);
        long timeout = BleScanReceiver.sessionGapMillis(context) + reportDelay;
        long now = System.currentTimeMillis();
        long triggerAt = Math.max(session.lastSeen + timeout, now + reportDelay);
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
    }

    static void cancelEnd(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        PendingIntent pendingIntent = endPendingIntent(context, "", 0);
        alarms.cancel(pendingIntent);
        pendingIntent.cancel();
    }

    static void checkEndAlarm(Context context, String address, long sessionId) {
        SharedPreferences preferences = context.getSharedPreferences(
                BleScanReceiver.PREFS, Context.MODE_PRIVATE);
        if (!preferences.getBoolean(BleScanReceiver.KEY_ACTIVE, false)
                || !address.equals(preferences.getString(BleScanReceiver.KEY_ADDRESS, ""))) return;

        TrackerDatabase database = new TrackerDatabase(context);
        TrackingSession latest = database.latestForAddress(address);
        database.close();
        if (latest == null || latest.id != sessionId || latest.manual) return;

        long timeout = BleScanReceiver.sessionGapMillis(context)
                + BleScanReceiver.scanReportDelayMillis(context);
        if (System.currentTimeMillis() - latest.lastSeen >= timeout) {
            notifyEnded(context, latest);
        } else {
            scheduleEnd(context, address, latest);
        }
    }

    private static PendingIntent endPendingIntent(Context context, String address, long sessionId) {
        Intent intent = new Intent(context, SessionEndReceiver.class)
                .setAction(SessionEndReceiver.ACTION_SESSION_TIMEOUT)
                .putExtra(EXTRA_ADDRESS, address)
                .putExtra(EXTRA_SESSION_ID, sessionId);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(context, END_ALARM_REQUEST, intent, flags);
    }

    private static void post(Context context, int id, String title, String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;
        createChannel(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;

        Notification notification;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification = new Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_more)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setCategory(Notification.CATEGORY_STATUS)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .build();
        } else {
            notification = new Notification.Builder(context)
                    .setSmallIcon(android.R.drawable.stat_notify_more)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .build();
        }
        manager.notify(id, notification);
    }

    private static void cancelNotification(Context context, int id) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(id);
    }

    private static int notificationId(long sessionId, boolean ended) {
        return (int) ((sessionId % 1_000_000L) * 2 + (ended ? 1 : 0));
    }

    private static String time(long timestamp) {
        return DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
                .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(timestamp));
    }
}
