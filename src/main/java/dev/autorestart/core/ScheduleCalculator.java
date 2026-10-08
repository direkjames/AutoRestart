package dev.autorestart.core;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Works out when the next scheduled restart is, from the fixed schedules and the optional uptime interval.
 *
 * @param zone      timezone the schedules are written in
 * @param entries   fixed schedules such as {@code Daily;06;00}
 * @param interval  restart every this long since startup, or {@code null} for off
 * @param minUptime the server must have been up at least this long before a restart
 */
public record ScheduleCalculator(ZoneId zone, List<ScheduleEntry> entries, Duration interval, Duration minUptime) {

    public ScheduleCalculator {
        entries = List.copyOf(entries);
        if (minUptime == null) minUptime = Duration.ZERO;
    }

    /**
     * @param now       the current time
     * @param startedAt when the server started
     * @param notBefore an extra lower bound (e.g. a restart that was cancelled), or {@code null}
     * @return the earliest restart time that is after now, after the minimum uptime and after {@code notBefore}
     */
    public Optional<Instant> next(Instant now, Instant startedAt, Instant notBefore) {
        Instant earliest = now;
        Instant uptimeReady = startedAt.plus(minUptime);
        if (uptimeReady.isAfter(earliest)) earliest = uptimeReady;
        if (notBefore != null && notBefore.isAfter(earliest)) earliest = notBefore;

        Instant best = null;

        // A cancelled restart must not be picked again, so search strictly after it.
        // Otherwise a schedule that lands exactly on "earliest" is still allowed.
        boolean strict = earliest.equals(notBefore);
        ZonedDateTime from = earliest.atZone(zone);
        if (!strict) from = from.minusNanos(1);
        for (ScheduleEntry entry : entries) {
            Instant candidate = entry.nextAfter(from).toInstant();
            if (best == null || candidate.isBefore(best)) best = candidate;
        }

        if (interval != null && !interval.isZero() && !interval.isNegative()) {
            long elapsed = Duration.between(startedAt, earliest).toMillis();
            long step = interval.toMillis();
            long cycles = Math.max(1, elapsed / step + 1);
            Instant candidate = startedAt.plusMillis(cycles * step);
            if (notBefore != null && !candidate.isAfter(notBefore)) {
                candidate = candidate.plusMillis(step);
            }
            if (best == null || candidate.isBefore(best)) best = candidate;
        }

        return Optional.ofNullable(best);
    }
}
