package com.example.bluetoothtracker;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Collections;

final class TrackerDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "bluetooth-tracker.db";
    private static final int DB_VERSION = 3;
    private static final String TABLE = "tracking_sessions";
    TrackerDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createSessionsTable(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            createSessionsTable(db);
            db.execSQL("INSERT INTO " + TABLE
                    + " (address, name, started_at, last_seen) "
                    + "SELECT address, name, first_seen, last_seen FROM tracked_devices");
        } else if (oldVersion < 3) {
            db.execSQL("ALTER TABLE " + TABLE
                    + " ADD COLUMN manual INTEGER NOT NULL DEFAULT 0");
        }
    }

    synchronized SessionEvents recordSeen(String address, String name, long timestamp,
                                          long sessionGapMillis) {
        return recordSeenBatch(address, name, Collections.singletonList(timestamp), sessionGapMillis);
    }

    synchronized SessionEvents recordSeenBatch(String address, String name, List<Long> timestamps,
                                               long sessionGapMillis) {
        List<TrackingSession> started = new ArrayList<>();
        List<TrackingSession> ended = new ArrayList<>();
        if (timestamps.isEmpty()) return new SessionEvents(started, ended, latestForAddress(address));
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        TrackingSession latest = null;
        try {
            latest = latestSession(db, address);
            for (long timestamp : timestamps) {
                if (latest != null && timestamp <= latest.lastSeen) continue;

                if (latest != null && !latest.manual
                        && timestamp - latest.lastSeen <= sessionGapMillis) {
                    ContentValues update = new ContentValues();
                    update.put("name", name);
                    update.put("last_seen", timestamp);
                    db.update(TABLE, update, "id = ?", new String[]{Long.toString(latest.id)});
                    latest = new TrackingSession(latest.id, address, name,
                            latest.startedAt, timestamp, false);
                } else {
                    if (latest != null && !latest.manual) ended.add(latest);
                    ContentValues session = new ContentValues();
                    session.put("address", address);
                    session.put("name", name);
                    session.put("started_at", timestamp);
                    session.put("last_seen", timestamp);
                    session.put("manual", 0);
                    long id = db.insert(TABLE, null, session);
                    latest = new TrackingSession(id, address, name, timestamp, timestamp, false);
                    started.add(latest);
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return new SessionEvents(started, ended, latest);
    }

    synchronized long addManualSession(String address, String name, long startedAt, long endedAt) {
        if (endedAt <= startedAt) throw new IllegalArgumentException("End must be after start");
        ContentValues values = new ContentValues();
        values.put("address", address);
        values.put("name", name);
        values.put("started_at", startedAt);
        values.put("last_seen", endedAt);
        values.put("manual", 1);
        return getWritableDatabase().insert(TABLE, null, values);
    }

    synchronized int updateManualSession(long id, String address, String name,
                                         long startedAt, long endedAt) {
        if (endedAt <= startedAt) throw new IllegalArgumentException("End must be after start");
        ContentValues values = new ContentValues();
        values.put("name", name);
        values.put("started_at", startedAt);
        values.put("last_seen", endedAt);
        values.put("manual", 1);
        return getWritableDatabase().update(TABLE, values,
                "id = ? AND address = ?", new String[]{Long.toString(id), address});
    }

    synchronized int deleteSession(long id, String address) {
        return getWritableDatabase().delete(TABLE,
                "id = ? AND address = ?", new String[]{Long.toString(id), address});
    }

    synchronized List<TrackingSession> sessionsFor(String address) {
        List<TrackingSession> sessions = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(TABLE,
                new String[]{"id", "address", "name", "started_at", "last_seen", "manual"},
                "address = ?", new String[]{address}, null, null, "started_at DESC")) {
            while (cursor.moveToNext()) {
                sessions.add(new TrackingSession(cursor.getLong(0), cursor.getString(1),
                        cursor.getString(2), cursor.getLong(3), cursor.getLong(4), cursor.getInt(5) != 0));
            }
        }
        return sessions;
    }

    synchronized TrackingSession latestForAddress(String address) {
        return latestSession(getReadableDatabase(), address);
    }

    private void createSessionsTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "address TEXT NOT NULL, "
                + "name TEXT NOT NULL, "
                + "started_at INTEGER NOT NULL, "
                + "last_seen INTEGER NOT NULL, "
                + "manual INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX tracking_sessions_address_start "
                + "ON " + TABLE + " (address, started_at DESC)");
    }

    private TrackingSession latestSession(SQLiteDatabase db, String address) {
        try (Cursor cursor = db.query(TABLE,
                new String[]{"id", "address", "name", "started_at", "last_seen", "manual"},
                "address = ?", new String[]{address}, null, null, "started_at DESC", "1")) {
            if (!cursor.moveToFirst()) return null;
            return new TrackingSession(cursor.getLong(0), cursor.getString(1),
                    cursor.getString(2), cursor.getLong(3), cursor.getLong(4), cursor.getInt(5) != 0);
        }
    }
}
