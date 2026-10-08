package dev.autorestart.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TimeParserTest {

    @Test
    void parsesPlainSeconds() {
        assertEquals(Duration.ofSeconds(300), TimeParser.parse("300"));
    }

    @Test
    void parsesUnits() {
        assertEquals(Duration.ofMinutes(5), TimeParser.parse("5m"));
        assertEquals(Duration.ofMinutes(90), TimeParser.parse("1h30m"));
        assertEquals(Duration.ofSeconds(2 * 3600 + 15 * 60 + 10), TimeParser.parse("2h 15m 10s"));
        assertEquals(Duration.ofDays(1), TimeParser.parse("1D"));
    }

    @Test
    void parsesHoursColonMinutes() {
        assertEquals(Duration.ofMinutes(90), TimeParser.parse("1:30"));
        assertEquals(Duration.ofMinutes(5), TimeParser.parse("0:05"));
    }

    @Test
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> TimeParser.parse(""));
        assertThrows(IllegalArgumentException.class, () -> TimeParser.parse("abc"));
        assertThrows(IllegalArgumentException.class, () -> TimeParser.parse("5x"));
        assertThrows(IllegalArgumentException.class, () -> TimeParser.parse("1:75"));
        assertThrows(IllegalArgumentException.class, () -> TimeParser.parse("0"));
        assertThrows(IllegalArgumentException.class, () -> TimeParser.parse("0m"));
    }

    @Test
    void formats() {
        assertEquals("10s", TimeParser.format(Duration.ofSeconds(10)));
        assertEquals("4m 30s", TimeParser.format(Duration.ofSeconds(270)));
        assertEquals("1h 5m", TimeParser.format(Duration.ofSeconds(3903)));
        assertEquals("1d 2h", TimeParser.format(Duration.ofHours(26)));
        assertEquals("0s", TimeParser.format(Duration.ZERO));
        assertEquals("1m", TimeParser.format(Duration.ofSeconds(60)));
    }
}
