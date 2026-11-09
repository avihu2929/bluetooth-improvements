package com.example.bluetoothtracker;

final class TrackingSession {
    final long id;
    final String address;
    final String name;
    final long startedAt;
    final long lastSeen;
    final boolean manual;

    TrackingSession(long id, String address, String name, long startedAt, long lastSeen, boolean manual) {
        this.id = id;
        this.address = address;
        this.name = name;
        this.startedAt = startedAt;
        this.lastSeen = lastSeen;
        this.manual = manual;
    }
}
