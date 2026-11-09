package com.example.bluetoothtracker;

import java.util.List;

final class SessionEvents {
    final List<TrackingSession> started;
    final List<TrackingSession> ended;
    final TrackingSession latest;

    SessionEvents(List<TrackingSession> started, List<TrackingSession> ended,
                  TrackingSession latest) {
        this.started = started;
        this.ended = ended;
        this.latest = latest;
    }
}
