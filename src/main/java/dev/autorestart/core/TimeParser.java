package dev.autorestart.core;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses durations typed in commands or config.
 * <p>
 * Accepted formats:
 * <ul>
 *     <li>{@code 300} — plain seconds</li>
 *     <li>{@code 5m}, {@code 1h30m}, {@code 2h 15m 10s}, {@code 1d} — unit suffixes</li>
 *     <li>{@code 1:30} — HOURS:MINUTES (same as UAR's "HOUR:MINUTE" format)</li>
 * </ul>
 */
public final class TimeParser {

    private static final Pattern PLAIN_SECONDS = Pattern.compile("\\d+");
    private static final Pattern HOURS_MINUTES = Pattern.compile("(\\d+):(\\d{1,2})");
    private static final Pattern UNIT_PART = Pattern.compile("(\\d+)\\s*([dhms])");
    private static final Pattern UNIT_FULL = Pattern.compile("(\\s*\\d+\\s*[dhms]\\s*)+");

    private TimeParser() {
    }

    /**
     * @return the parsed duration
     * @throws IllegalArgumentException if the input isn't a valid, positive duration
     */
    public static Duration parse(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Time is empty");
        }
        String text = input.trim().toLowerCase(Locale.ROOT);

        Duration result;
        if (PLAIN_SECONDS.matcher(text).matches()) {
            result = Duration.ofSeconds(Long.parseLong(text));
        } else {
            Matcher hm = HOURS_MINUTES.matcher(text);
            if (hm.matches()) {
                int minutes = Integer.parseInt(hm.group(2));
                if (minutes > 59) {
                    throw new IllegalArgumentException("Minutes must be 0-59 in '" + input + "'");
                }
                result = Duration.ofHours(Long.parseLong(hm.group(1))).plusMinutes(minutes);
            } else if (UNIT_FULL.matcher(text).matches()) {
                result = Duration.ZERO;
                Matcher part = UNIT_PART.matcher(text);
                while (part.find()) {
                    long amount = Long.parseLong(part.group(1));
                    result = switch (part.group(2)) {
                        case "d" -> result.plusDays(amount);
                        case "h" -> result.plusHours(amount);
                        case "m" -> result.plusMinutes(amount);
                        default -> result.plusSeconds(amount);
                    };
                }
            } else {
                throw new IllegalArgumentException("Invalid time '" + input + "'. Use 300, 5m, 1h30m or 1:30");
            }
        }

        if (result.isZero() || result.isNegative()) {
            throw new IllegalArgumentException("Time must be greater than 0");
        }
        return result;
    }

    /** Formats a duration as e.g. {@code 1h 5m}, {@code 4m 30s} or {@code 10s}. */
    public static String format(Duration duration) {
        long total = Math.max(0, duration.toSeconds());
        long days = total / 86_400;
        long hours = (total % 86_400) / 3_600;
        long minutes = (total % 3_600) / 60;
        long seconds = total % 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        // Seconds are noise for long waits, so only show them under an hour.
        if (seconds > 0 && days == 0 && hours == 0) sb.append(seconds).append("s ");
        if (sb.isEmpty()) sb.append("0s");
        return sb.toString().trim();
    }
}
