package dev.autorestart.core;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

/**
 * One line from the {@code schedules} list, e.g. {@code Monday;23;00} or {@code Daily;12;00}.
 *
 * @param day  the day of the week, or {@code null} for every day
 * @param time the local time of day in the configured timezone
 */
public record ScheduleEntry(DayOfWeek day, LocalTime time) {

    public static ScheduleEntry parse(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Schedule is empty");
        }
        String[] parts = input.trim().split(";");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Schedule '" + input + "' must look like Day;HH;MM (e.g. Daily;06;00)");
        }

        String dayText = parts[0].trim().toUpperCase(Locale.ROOT);
        DayOfWeek day;
        if (dayText.equals("DAILY") || dayText.equals("EVERYDAY")) {
            day = null;
        } else {
            try {
                day = DayOfWeek.valueOf(dayText);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown day '" + parts[0].trim() + "' in schedule '" + input
                        + "'. Use Monday-Sunday or Daily");
            }
        }

        int hour;
        int minute;
        try {
            hour = Integer.parseInt(parts[1].trim());
            minute = Integer.parseInt(parts[2].trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Hour and minute must be numbers in schedule '" + input + "'");
        }
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            throw new IllegalArgumentException("Hour must be 0-23 and minute 0-59 in schedule '" + input + "'");
        }
        return new ScheduleEntry(day, LocalTime.of(hour, minute));
    }

    /**
     * @param after a moment in the schedule's timezone
     * @return the first time this entry fires strictly after {@code after}
     */
    public ZonedDateTime nextAfter(ZonedDateTime after) {
        // ZonedDateTime.with(LocalTime) moves times that fall in a DST gap forward, so this never fails.
        ZonedDateTime candidate = after.with(time).withSecond(0).withNano(0);
        if (day == null) {
            if (!candidate.isAfter(after)) {
                candidate = candidate.plusDays(1).with(time);
            }
        } else {
            candidate = candidate.with(TemporalAdjusters.nextOrSame(day)).with(time);
            if (!candidate.isAfter(after)) {
                candidate = candidate.plusWeeks(1).with(time);
            }
        }
        return candidate;
    }

    @Override
    public String toString() {
        String dayName = day == null ? "Daily"
                : day.name().charAt(0) + day.name().substring(1).toLowerCase(Locale.ROOT);
        return String.format("%s;%02d;%02d", dayName, time.getHour(), time.getMinute());
    }
}
