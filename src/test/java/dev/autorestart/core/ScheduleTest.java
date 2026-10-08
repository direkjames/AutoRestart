package dev.autorestart.core;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleTest {

    private static final ZoneId MANILA = ZoneId.of("Asia/Manila");

    /** Thursday 8 Oct 2026, 09:30 Manila time */
    private static final ZonedDateTime THU_0930 = ZonedDateTime.of(2026, 10, 8, 9, 30, 0, 0, MANILA);

    @Test
    void parsesEntries() {
        assertEquals(new ScheduleEntry(DayOfWeek.MONDAY, LocalTime.of(23, 0)), ScheduleEntry.parse("Monday;23;00"));
        assertEquals(new ScheduleEntry(null, LocalTime.of(12, 0)), ScheduleEntry.parse("Daily;12;00"));
        assertEquals(new ScheduleEntry(DayOfWeek.FRIDAY, LocalTime.of(2, 5)), ScheduleEntry.parse(" friday ; 2 ; 5 "));
        assertEquals("Friday;02;05", ScheduleEntry.parse("friday;2;5").toString());
    }

    @Test
    void rejectsBadEntries() {
        assertThrows(IllegalArgumentException.class, () -> ScheduleEntry.parse("Daily;12:00"));
        assertThrows(IllegalArgumentException.class, () -> ScheduleEntry.parse("Someday;12;00"));
        assertThrows(IllegalArgumentException.class, () -> ScheduleEntry.parse("Daily;24;00"));
        assertThrows(IllegalArgumentException.class, () -> ScheduleEntry.parse("Daily;12;60"));
        assertThrows(IllegalArgumentException.class, () -> ScheduleEntry.parse("Daily;ab;00"));
    }

    @Test
    void dailyLaterToday() {
        ZonedDateTime next = ScheduleEntry.parse("Daily;12;00").nextAfter(THU_0930);
        assertEquals(THU_0930.withHour(12).withMinute(0), next);
    }

    @Test
    void dailyAlreadyPassedGoesToTomorrow() {
        ZonedDateTime next = ScheduleEntry.parse("Daily;06;00").nextAfter(THU_0930);
        assertEquals(ZonedDateTime.of(2026, 10, 9, 6, 0, 0, 0, MANILA), next);
    }

    @Test
    void weeklyEntries() {
        assertEquals(ZonedDateTime.of(2026, 10, 12, 23, 0, 0, 0, MANILA),
                ScheduleEntry.parse("Monday;23;00").nextAfter(THU_0930));
        // Same weekday, later time -> today
        assertEquals(ZonedDateTime.of(2026, 10, 8, 23, 0, 0, 0, MANILA),
                ScheduleEntry.parse("Thursday;23;00").nextAfter(THU_0930));
        // Same weekday, earlier time -> next week
        assertEquals(ZonedDateTime.of(2026, 10, 15, 2, 0, 0, 0, MANILA),
                ScheduleEntry.parse("Thursday;02;00").nextAfter(THU_0930));
    }

    @Test
    void exactMatchIsNotRepeated() {
        ZonedDateTime noon = THU_0930.withHour(12).withMinute(0);
        assertEquals(noon.plusDays(1), ScheduleEntry.parse("Daily;12;00").nextAfter(noon));
    }

    @Test
    void handlesDaylightSavingGap() {
        // 29 Mar 2026: London clocks jump 01:00 -> 02:00, so 01:30 doesn't exist
        ZoneId london = ZoneId.of("Europe/London");
        ZonedDateTime before = ZonedDateTime.of(2026, 3, 28, 12, 0, 0, 0, london);
        ZonedDateTime next = ScheduleEntry.parse("Daily;01;30").nextAfter(before);
        assertEquals(29, next.getDayOfMonth());
        assertEquals(2, next.getHour()); // shifted forward into valid time
    }

    @Test
    void calculatorPicksEarliest() {
        ScheduleCalculator calc = new ScheduleCalculator(MANILA, List.of(
                ScheduleEntry.parse("Daily;18;00"),
                ScheduleEntry.parse("Daily;12;00"),
                ScheduleEntry.parse("Friday;02;00")), null, Duration.ZERO);
        Instant now = THU_0930.toInstant();
        assertEquals(THU_0930.withHour(12).withMinute(0).toInstant(),
                calc.next(now, now.minusSeconds(3600), null).orElseThrow());
    }

    @Test
    void calculatorRespectsMinUptime() {
        ScheduleCalculator calc = new ScheduleCalculator(MANILA,
                List.of(ScheduleEntry.parse("Daily;09;35")), null, Duration.ofMinutes(10));
        Instant now = THU_0930.toInstant();
        // Server started just now; 09:35 is within the 10 minute min-uptime, so skip to tomorrow
        assertEquals(ZonedDateTime.of(2026, 10, 9, 9, 35, 0, 0, MANILA).toInstant(),
                calc.next(now, now, null).orElseThrow());
    }

    @Test
    void calculatorAllowsScheduleExactlyAtMinUptime() {
        ScheduleCalculator calc = new ScheduleCalculator(MANILA,
                List.of(ScheduleEntry.parse("Daily;09;40")), null, Duration.ofMinutes(10));
        Instant now = THU_0930.toInstant();
        assertEquals(THU_0930.withMinute(40).toInstant(), calc.next(now, now, null).orElseThrow());
    }

    @Test
    void calculatorSkipsCancelled() {
        ScheduleCalculator calc = new ScheduleCalculator(MANILA,
                List.of(ScheduleEntry.parse("Daily;12;00"), ScheduleEntry.parse("Daily;18;00")), null, Duration.ZERO);
        Instant now = THU_0930.toInstant();
        Instant noon = THU_0930.withHour(12).withMinute(0).toInstant();
        assertEquals(THU_0930.withHour(18).withMinute(0).toInstant(),
                calc.next(now, now.minusSeconds(7200), noon).orElseThrow());
    }

    @Test
    void calculatorInterval() {
        ScheduleCalculator calc = new ScheduleCalculator(MANILA, List.of(), Duration.ofHours(6), Duration.ZERO);
        Instant started = THU_0930.toInstant();
        assertEquals(started.plus(Duration.ofHours(6)), calc.next(started.plusSeconds(60), started, null).orElseThrow());
        assertEquals(started.plus(Duration.ofHours(12)),
                calc.next(started.plus(Duration.ofHours(7)), started, null).orElseThrow());
        // Cancelling the 6h restart moves to 12h
        assertEquals(started.plus(Duration.ofHours(12)),
                calc.next(started.plusSeconds(60), started, started.plus(Duration.ofHours(6))).orElseThrow());
    }

    @Test
    void calculatorIntervalAndScheduleCombined() {
        ScheduleCalculator calc = new ScheduleCalculator(MANILA,
                List.of(ScheduleEntry.parse("Daily;12;00")), Duration.ofHours(1), Duration.ZERO);
        Instant now = THU_0930.toInstant();
        Instant next = calc.next(now, now, null).orElseThrow();
        assertEquals(now.plus(Duration.ofHours(1)), next);
    }

    @Test
    void calculatorWithNothingConfigured() {
        ScheduleCalculator calc = new ScheduleCalculator(MANILA, List.of(), null, Duration.ZERO);
        assertTrue(calc.next(Instant.now(), Instant.now(), null).isEmpty());
    }

    @Test
    void warningTrackerFiresEachMarkOnce() {
        WarningTracker tracker = new WarningTracker(List.of(60L, 30L, 10L, 5L));
        tracker.reset(45);
        assertTrue(tracker.poll(45).isEmpty());      // 60 was skipped, too late for it
        assertEquals(30, tracker.poll(30).getAsLong());
        assertTrue(tracker.poll(30).isEmpty());      // not twice
        assertTrue(tracker.poll(20).isEmpty());
        assertEquals(10, tracker.poll(10).getAsLong());
    }

    @Test
    void warningTrackerOnlyClosestAfterLagSpike() {
        WarningTracker tracker = new WarningTracker(List.of(10L, 5L, 4L, 3L));
        tracker.reset(12);
        assertEquals(4, tracker.poll(4).getAsLong()); // jumped from 12 to 4: only "4" fires
        assertEquals(3, tracker.poll(3).getAsLong());
        assertTrue(tracker.poll(2).isEmpty());
    }

    @Test
    void warningTrackerIncludesMarkEqualToStart() {
        WarningTracker tracker = new WarningTracker(List.of(30L));
        tracker.reset(30);
        assertEquals(30, tracker.poll(30).getAsLong());
    }

    @Test
    void warningTrackerWithNoTarget() {
        WarningTracker tracker = new WarningTracker(List.of(30L));
        tracker.reset(Long.MAX_VALUE);
        assertTrue(tracker.poll(Long.MAX_VALUE).isEmpty());
    }
}
