package com.example.bluetoothtracker;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.Collections;

public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        SharedPreferences preferences = context.getSharedPreferences(
                BleScanReceiver.PREFS, Context.MODE_PRIVATE);
        String address = preferences.getString(BleScanReceiver.KEY_ADDRESS, "");
        if (address.isEmpty() || !preferences.getBoolean(BleScanReceiver.KEY_ACTIVE, false)) return;
        if (!hasRequiredPermissions(context)) return;

        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        BluetoothLeScanner scanner = adapter == null ? null : adapter.getBluetoothLeScanner();
        if (scanner == null) return;

        ScanFilter filter;
        try {
            filter = new ScanFilter.Builder().setDeviceAddress(address).build();
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                    .setReportDelay(BleScanReceiver.scanReportDelayMillis(context))
                    .build();
            scanner.startScan(Collections.singletonList(filter), settings,
                    BleScanReceiver.trackingPendingIntent(context));
            TrackerDatabase database = new TrackerDatabase(context);
            TrackingSession latest = database.latestForAddress(address);
            database.close();
            if (latest != null) SessionNotifications.scheduleEnd(context, address, latest);
        } catch (SecurityException | IllegalArgumentException ignored) {
            // The user may have revoked Bluetooth permissions or Bluetooth may be unavailable.
        }
    }

    private boolean hasRequiredPermissions(Context context) {
        String scanPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? Manifest.permission.BLUETOOTH_SCAN
                : Manifest.permission.ACCESS_FINE_LOCATION;
        if (context.checkSelfPermission(scanPermission) != PackageManager.PERMISSION_GRANTED) return false;

        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || Build.VERSION.SDK_INT > Build.VERSION_CODES.R
                || context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }
}
