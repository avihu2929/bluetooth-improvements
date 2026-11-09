package com.example.bluetoothtracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class ShiftCalculatorTest {
    private static final long HOUR = 60 * 60 * 1000L;

    @Test
    public void endBeforeStartMeansOvernight() {
        LocalDate day = LocalDate.of(2026, 11, 2);
        long end = ShiftCalculator.endTimestamp(day, LocalTime.of(22, 0),
                LocalTime.of(2, 0), ZoneOffset.UTC);

        assertEquals(Instant.parse("2026-11-03T02:00:00Z").toEpochMilli(), end);
    }

    @Test
    public void equalStartAndEndMeansTwentyFourHourShift() {
        LocalDate day = LocalDate.of(2026, 11, 2);
        long start = ShiftCalculator.timestampAt(day, LocalTime.NOON, ZoneOffset.UTC);
        long end = ShiftCalculator.endTimestamp(day, LocalTime.NOON,
                LocalTime.NOON, ZoneOffset.UTC);

        assertEquals(start + 24 * HOUR, end);
    }

    @Test
    public void multipleShiftsAreCombinedOnTheirStartDay() {
        LocalDate day = LocalDate.of(2026, 11, 2);
        TrackingSession first = shift(1, day, 9, 0, day, 11, 0);
        TrackingSession second = shift(2, day, 13, 0, day, 16, 30);

        assertEquals(5 * HOUR + 30 * 60 * 1000L,
                ShiftCalculator.durationOnDay(day, Arrays.asList(first, second), ZoneOffset.UTC));
        assertEquals(2, ShiftCalculator.sessionsStartingOn(day,
                Arrays.asList(first, second), ZoneOffset.UTC).size());
    }

    @Test
    public void overnightShiftIsCountedOnlyOnItsStartDay() {
        LocalDate startDay = LocalDate.of(2026, 11, 2);
        LocalDate endDay = startDay.plusDays(1);
        TrackingSession overnight = shift(1, startDay, 22, 0, endDay, 2, 0);
        List<TrackingSession> sessions = Collections.singletonList(overnight);

        assertEquals(4 * HOUR, ShiftCalculator.durationOnDay(startDay, sessions, ZoneOffset.UTC));
        assertEquals(0, ShiftCalculator.durationOnDay(endDay, sessions, ZoneOffset.UTC));
        assertEquals(1, ShiftCalculator.sessionsStartingOn(startDay, sessions, ZoneOffset.UTC).size());
        assertTrue(ShiftCalculator.sessionsStartingOn(endDay, sessions, ZoneOffset.UTC).isEmpty());
    }

    @Test
    public void monthTotalUsesStartMonthForOvernightShifts() {
        LocalDate jan31 = LocalDate.of(2026, 1, 31);
        LocalDate feb1 = jan31.plusDays(1);
        TrackingSession overnight = shift(1, jan31, 22, 0, feb1, 2, 0);
        TrackingSession febShift = shift(2, feb1, 9, 0, feb1, 10, 0);
        List<TrackingSession> sessions = Arrays.asList(overnight, febShift);

        assertEquals(4 * HOUR,
                ShiftCalculator.durationInMonth(YearMonth.of(2026, 1), sessions, ZoneOffset.UTC));
        assertEquals(HOUR,
                ShiftCalculator.durationInMonth(YearMonth.of(2026, 2), sessions, ZoneOffset.UTC));
    }

    @Test
    public void monthExportSessionsAreSortedAndSelectedByStartDate() {
        LocalDate jan31 = LocalDate.of(2026, 1, 31);
        LocalDate feb1 = jan31.plusDays(1);
        TrackingSession febLater = shift(1, feb1, 13, 0, feb1, 14, 0);
        TrackingSession janOvernight = shift(2, jan31, 22, 0, feb1, 2, 0);
        TrackingSession febEarlier = shift(3, feb1, 9, 0, feb1, 10, 0);
        List<TrackingSession> february = ShiftCalculator.sessionsStartingInMonth(
                YearMonth.of(2026, 2), Arrays.asList(febLater, janOvernight, febEarlier), ZoneOffset.UTC);

        assertEquals(2, february.size());
        assertEquals(febEarlier.id, february.get(0).id);
        assertEquals(febLater.id, february.get(1).id);
    }

    @Test
    public void noSignalTimeoutMinimumTracksReportInterval() {
        assertEquals(30, ShiftCalculator.minimumSessionGapSeconds(1, 30, 7_200));
        assertEquals(60, ShiftCalculator.minimumSessionGapSeconds(30, 30, 7_200));
        assertEquals(600, ShiftCalculator.minimumSessionGapSeconds(300, 30, 7_200));
    }

    private TrackingSession shift(long id, LocalDate startDate, int startHour, int startMinute,
                                  LocalDate endDate, int endHour, int endMinute) {
        long start = ShiftCalculator.timestampAt(startDate,
                LocalTime.of(startHour, startMinute), ZoneOffset.UTC);
        long end = ShiftCalculator.timestampAt(endDate,
                LocalTime.of(endHour, endMinute), ZoneOffset.UTC);
        return new TrackingSession(id, "AA:BB:CC:DD:EE:FF", "Test device", start, end, true);
    }
}
