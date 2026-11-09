package com.example.bluetoothtracker;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class ShiftCalculator {
    private ShiftCalculator() { }

    static long timestampAt(LocalDate date, LocalTime time, ZoneId zone) {
        return date.atTime(time).atZone(zone).toInstant().toEpochMilli();
    }

    static int minimumSessionGapSeconds(int reportIntervalSeconds, int configuredMinimum,
                                        int maximum) {
        int twiceReportInterval = Math.min(maximum, Math.max(1, reportIntervalSeconds) * 2);
        return Math.max(configuredMinimum, twiceReportInterval);
    }

    static long endTimestamp(LocalDate startDate, LocalTime startTime,
                             LocalTime endTime, ZoneId zone) {
        LocalDate endDate = endTime.isAfter(startTime) ? startDate : startDate.plusDays(1);
        return timestampAt(endDate, endTime, zone);
    }

    static List<TrackingSession> sessionsStartingOn(LocalDate date,
                                                     List<TrackingSession> sessions,
                                                     ZoneId zone) {
        long dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli();
        long nextDayStart = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        List<TrackingSession> matches = new ArrayList<>();
        for (TrackingSession session : sessions) {
            if (session.startedAt >= dayStart && session.startedAt < nextDayStart) {
                matches.add(session);
            }
        }
        return matches;
    }

    static long durationOnDay(LocalDate date, List<TrackingSession> sessions, ZoneId zone) {
        long duration = 0;
        for (TrackingSession session : sessionsStartingOn(date, sessions, zone)) {
            duration += Math.max(0, session.lastSeen - session.startedAt);
        }
        return duration;
    }

    static long durationInMonth(YearMonth month, List<TrackingSession> sessions, ZoneId zone) {
        long duration = 0;
        for (TrackingSession session : sessionsStartingInMonth(month, sessions, zone)) {
            duration += Math.max(0, session.lastSeen - session.startedAt);
        }
        return duration;
    }

    static List<TrackingSession> sessionsStartingInMonth(YearMonth month,
                                                          List<TrackingSession> sessions,
                                                          ZoneId zone) {
        long monthStart = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
        long nextMonthStart = month.plusMonths(1).atDay(1)
                .atStartOfDay(zone).toInstant().toEpochMilli();
        List<TrackingSession> matches = new ArrayList<>();
        for (TrackingSession session : sessions) {
            if (session.startedAt >= monthStart && session.startedAt < nextMonthStart) {
                matches.add(session);
            }
        }
        matches.sort(Comparator.comparingLong(session -> session.startedAt));
        return matches;
    }
}
