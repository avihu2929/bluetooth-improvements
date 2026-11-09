package com.example.bluetoothtracker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class SessionEndReceiver extends BroadcastReceiver {
    static final String ACTION_SESSION_TIMEOUT = "com.example.bluetoothtracker.ACTION_SESSION_TIMEOUT";
    private static final String EXTRA_ADDRESS = "tracked_address";
    private static final String EXTRA_SESSION_ID = "tracking_session_id";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_SESSION_TIMEOUT.equals(intent.getAction())) return;
        String address = intent.getStringExtra(EXTRA_ADDRESS);
        long sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1);
        if (address == null || address.isEmpty() || sessionId < 0) return;
        SessionNotifications.checkEndAlarm(context, address, sessionId);
    }
}
