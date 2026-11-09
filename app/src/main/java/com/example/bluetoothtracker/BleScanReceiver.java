package com.example.bluetoothtracker;

import android.bluetooth.le.BluetoothLeScanner;
import android.app.PendingIntent;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Collections;

public final class BleScanReceiver extends BroadcastReceiver {
    static final String ACTION_SCAN = "com.example.bluetoothtracker.ACTION_TRACKED_SCAN";
    static final String ACTION_LOG_UPDATED = "com.example.bluetoothtracker.ACTION_LOG_UPDATED";
    static final String PREFS = "tracker";
    static final String KEY_ADDRESS = "selected_address";
    static final String KEY_NAME = "selected_name";
    static final String KEY_ACTIVE = "tracking_active";
    static final int TRACK_SCAN_REQUEST = 12;
    static final String KEY_SCAN_INTERVAL_SECONDS = "scan_interval_seconds";
    static final String KEY_SESSION_GAP_SECONDS = "session_gap_seconds";
    private static final String LEGACY_KEY_SESSION_GAP_MINUTES = "session_gap_minutes";
    static final int DEFAULT_SCAN_INTERVAL_SECONDS = 1;
    static final int MAX_SCAN_INTERVAL_SECONDS = 300;
    static final int DEFAULT_SESSION_GAP_SECONDS = 120;
    static final int MAX_SESSION_GAP_SECONDS = 7_200;
    static final int MIN_SESSION_GAP_SECONDS = 30;

    static long scanReportDelayMillis(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int seconds = preferences.getInt(KEY_SCAN_INTERVAL_SECONDS, DEFAULT_SCAN_INTERVAL_SECONDS);
        seconds = Math.max(1, Math.min(MAX_SCAN_INTERVAL_SECONDS, seconds));
        return seconds * 1_000L;
    }

    static long sessionGapMillis(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int seconds;
        if (preferences.contains(KEY_SESSION_GAP_SECONDS)) {
            seconds = preferences.getInt(KEY_SESSION_GAP_SECONDS, DEFAULT_SESSION_GAP_SECONDS);
        } else if (preferences.contains(LEGACY_KEY_SESSION_GAP_MINUTES)) {
            int minutes = preferences.getInt(LEGACY_KEY_SESSION_GAP_MINUTES, 2);
            seconds = Math.max(1, Math.min(120, minutes)) * 60;
            preferences.edit().putInt(KEY_SESSION_GAP_SECONDS, seconds)
                    .remove(LEGACY_KEY_SESSION_GAP_MINUTES).apply();
        } else {
            seconds = DEFAULT_SESSION_GAP_SECONDS;
        }
        seconds = Math.max(minimumSessionGapSeconds(context),
                Math.min(MAX_SESSION_GAP_SECONDS, seconds));
        if (!preferences.contains(KEY_SESSION_GAP_SECONDS)
                || preferences.getInt(KEY_SESSION_GAP_SECONDS, -1) != seconds) {
            preferences.edit().putInt(KEY_SESSION_GAP_SECONDS, seconds).apply();
        }
        return seconds * 1_000L;
    }

    static int minimumSessionGapSeconds(Context context) {
        return minimumSessionGapSeconds((int) (scanReportDelayMillis(context) / 1_000L));
    }

    static int minimumSessionGapSeconds(int reportIntervalSeconds) {
        return ShiftCalculator.minimumSessionGapSeconds(reportIntervalSeconds,
                MIN_SESSION_GAP_SECONDS, MAX_SESSION_GAP_SECONDS);
    }

    static PendingIntent trackingPendingIntent(Context context) {
        Intent intent = new Intent(context, BleScanReceiver.class).setAction(ACTION_SCAN);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
        return PendingIntent.getBroadcast(context, TRACK_SCAN_REQUEST, intent, flags);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_SCAN.equals(intent.getAction())) return;

        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String selectedAddress = preferences.getString(KEY_ADDRESS, "");
        if (selectedAddress.isEmpty() || !preferences.getBoolean(KEY_ACTIVE, false)) return;

        ArrayList<ScanResult> results;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            results = intent.getParcelableArrayListExtra(
                    BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult.class);
        } else {
            //noinspection deprecation
            results = intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT);
        }
        if (results == null || results.isEmpty()) return;

        String selectedName = preferences.getString(KEY_NAME, "Bluetooth device");
        TrackerDatabase database = new TrackerDatabase(context);
        ArrayList<Long> timestamps = new ArrayList<>();
        for (ScanResult result : results) {
            if (!selectedAddress.equalsIgnoreCase(result.getDevice().getAddress())) continue;
            if (result.getScanRecord() != null && result.getScanRecord().getDeviceName() != null) {
                selectedName = result.getScanRecord().getDeviceName();
            }
            timestamps.add(wallClockTimestamp(result));
        }
        Collections.sort(timestamps);
        SessionEvents events = timestamps.isEmpty() ? null
                : database.recordSeenBatch(selectedAddress, selectedName, timestamps,
                sessionGapMillis(context));
        database.close();

        if (events != null) {
            SessionNotifications.handleEvents(context, selectedAddress, events);
            context.sendBroadcast(new Intent(ACTION_LOG_UPDATED)
                    .setPackage(context.getPackageName()));
        }
    }

    static long wallClockTimestamp(ScanResult result) {
        long timestampNanos = result.getTimestampNanos();
        if (timestampNanos <= 0) return System.currentTimeMillis();
        long ageMillis = Math.max(0, (SystemClock.elapsedRealtimeNanos() - timestampNanos) / 1_000_000L);
        return System.currentTimeMillis() - ageMillis;
    }
}
