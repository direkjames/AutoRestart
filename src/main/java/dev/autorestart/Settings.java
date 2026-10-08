package dev.autorestart;

import dev.autorestart.core.ScheduleCalculator;
import dev.autorestart.core.ScheduleEntry;
import dev.autorestart.core.TimeParser;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/** Everything read from config.yml, validated once per load/reload. */
public final class Settings {

    /** One countdown warning. Any field can be null, meaning "don't send that part". */
    public record Warning(String chat, String actionbar, String title, String subtitle, Key sound) {
    }

    public record Discord(boolean enabled, String url, String username, String avatarUrl,
                          List<Long> announceAt, Map<String, String> messages, Map<String, Integer> colors) {
    }

    public enum RestartMethod { SHUTDOWN, PTERODACTYL }

    /** @param fallbackAfter shut down normally if the panel hasn't stopped the server after this long */
    public record Pterodactyl(String panelUrl, String serverId, String apiKey, Duration fallbackAfter) {
    }

    public final ZoneId zone;
    public final ScheduleCalculator calculator;
    public final DateTimeFormatter dateFormat;
    public final Map<Long, Warning> warnings;
    public final float soundVolume;
    public final float soundPitch;

    public final boolean bossbarEnabled;
    public final long bossbarShowAt;
    public final String bossbarText;
    public final BossBar.Color bossbarColor;
    public final BossBar.Overlay bossbarOverlay;

    public final List<String> restartCommands;
    public final String kickMessage;
    public final RestartMethod restartMethod;
    public final Pterodactyl pterodactyl;

    public final Discord discord;
    private final ConfigurationSection messages;

    public Settings(FileConfiguration config, Logger logger) {
        // Timezone
        String zoneText = config.getString("timezone", "Asia/Manila");
        ZoneId parsedZone;
        try {
            parsedZone = ZoneId.of(zoneText);
        } catch (Exception e) {
            parsedZone = ZoneId.systemDefault();
            logger.warning("Unknown timezone '" + zoneText + "', using the system timezone " + parsedZone + " instead.");
        }
        zone = parsedZone;

        // Schedules
        List<ScheduleEntry> entries = new ArrayList<>();
        for (String line : config.getStringList("schedules")) {
            try {
                entries.add(ScheduleEntry.parse(line));
            } catch (IllegalArgumentException e) {
                logger.warning("Skipping schedule: " + e.getMessage());
            }
        }
        Duration interval = optionalDuration(config.getString("interval", ""), "interval", logger);
        Duration minUptime = optionalDuration(config.getString("min-uptime", "10m"), "min-uptime", logger);
        calculator = new ScheduleCalculator(zone, entries, interval, minUptime);
        if (entries.isEmpty() && interval == null) {
            logger.warning("No valid schedules or interval are set. Restarts will only happen through /autorestart now.");
        }

        String pattern = config.getString("date-format", "EEE, MMM d 'at' h:mm a");
        DateTimeFormatter formatter;
        try {
            formatter = DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH);
        } catch (IllegalArgumentException e) {
            logger.warning("Invalid date-format '" + pattern + "', using the default.");
            formatter = DateTimeFormatter.ofPattern("EEE, MMM d 'at' h:mm a", Locale.ENGLISH);
        }
        dateFormat = formatter.withZone(zone);

        // Warnings
        Map<Long, Warning> parsedWarnings = new HashMap<>();
        ConfigurationSection warningSection = config.getConfigurationSection("warnings");
        if (warningSection != null) {
            for (String key : warningSection.getKeys(false)) {
                ConfigurationSection w = warningSection.getConfigurationSection(key);
                if (w == null) continue;
                long seconds;
                try {
                    seconds = TimeParser.parse(key).toSeconds();
                } catch (IllegalArgumentException e) {
                    logger.warning("Skipping warning '" + key + "': " + e.getMessage());
                    continue;
                }
                parsedWarnings.put(seconds, new Warning(
                        blankToNull(w.getString("chat")),
                        blankToNull(w.getString("actionbar")),
                        blankToNull(w.getString("title")),
                        blankToNull(w.getString("subtitle")),
                        parseSound(w.getString("sound"), logger)));
            }
        }
        warnings = Map.copyOf(parsedWarnings);
        soundVolume = (float) config.getDouble("sound-volume", 1.0);
        soundPitch = (float) config.getDouble("sound-pitch", 1.0);

        // Boss bar
        bossbarEnabled = config.getBoolean("bossbar.enabled", true);
        Duration showAt = optionalDuration(config.getString("bossbar.show-at", "2m"), "bossbar.show-at", logger);
        bossbarShowAt = showAt == null ? 0 : showAt.toSeconds();
        bossbarText = config.getString("bossbar.text", "<red>Server restarting in <white><time>");
        bossbarColor = parseEnum(BossBar.Color.class, config.getString("bossbar.color"), BossBar.Color.RED, logger);
        bossbarOverlay = parseEnum(BossBar.Overlay.class, config.getString("bossbar.overlay"), BossBar.Overlay.PROGRESS, logger);

        // Restart
        restartCommands = List.copyOf(config.getStringList("restart.commands"));
        kickMessage = config.getString("restart.kick-message", "<red>Server restarting");

        // Pterodactyl
        String panelUrl = config.getString("pterodactyl.panel-url", "").trim();
        String serverId = config.getString("pterodactyl.server-id", "").trim();
        if (serverId.isEmpty()) {
            // Pterodactyl passes the server's UUID into the container, so this is usually automatic.
            String fromEnv = System.getenv("P_SERVER_UUID");
            serverId = fromEnv == null ? "" : fromEnv.trim();
        }
        String apiKey = config.getString("pterodactyl.api-key", "").trim();
        Duration fallback = optionalDuration(config.getString("pterodactyl.fallback-after", "1m"),
                "pterodactyl.fallback-after", logger);
        pterodactyl = new Pterodactyl(panelUrl, serverId, apiKey, fallback == null ? Duration.ofMinutes(1) : fallback);

        RestartMethod method = parseEnum(RestartMethod.class, config.getString("restart.method"),
                RestartMethod.SHUTDOWN, logger);
        if (method == RestartMethod.PTERODACTYL) {
            List<String> missing = new ArrayList<>();
            if (panelUrl.isEmpty()) missing.add("panel-url");
            if (serverId.isEmpty()) missing.add("server-id (not set, and P_SERVER_UUID isn't available)");
            if (apiKey.isEmpty()) missing.add("api-key");
            if (!missing.isEmpty()) {
                logger.warning("restart.method is pterodactyl but pterodactyl." + String.join(", ", missing)
                        + " is missing. Falling back to a normal shutdown.");
                method = RestartMethod.SHUTDOWN;
            }
        }
        restartMethod = method;

        // Discord
        List<Long> announceAt = new ArrayList<>();
        for (String time : config.getStringList("discord.announce-at")) {
            try {
                announceAt.add(TimeParser.parse(time).toSeconds());
            } catch (IllegalArgumentException e) {
                logger.warning("Skipping discord.announce-at '" + time + "': " + e.getMessage());
            }
        }
        Map<String, String> discordMessages = new HashMap<>();
        Map<String, Integer> discordColors = new HashMap<>();
        for (String type : List.of("warning", "now", "delayed", "cancelled", "restarting")) {
            discordMessages.put(type, config.getString("discord.messages." + type, ""));
            String hex = config.getString("discord.colors." + type, "#FFFFFF").replace("#", "");
            try {
                discordColors.put(type, Integer.parseInt(hex, 16));
            } catch (NumberFormatException e) {
                discordColors.put(type, 0xFFFFFF);
            }
        }
        discord = new Discord(
                config.getBoolean("discord.enabled", false),
                config.getString("discord.url", ""),
                config.getString("discord.username", "AutoRestart"),
                config.getString("discord.avatar-url", ""),
                List.copyOf(announceAt), Map.copyOf(discordMessages), Map.copyOf(discordColors));

        messages = config.getConfigurationSection("messages");
    }

    /** @return the message at {@code messages.<key>}, or "" if missing */
    public String message(String key) {
        return messages == null ? "" : messages.getString(key, "");
    }

    private static Duration optionalDuration(String text, String path, Logger logger) {
        if (text == null || text.isBlank()) return null;
        try {
            return TimeParser.parse(text);
        } catch (IllegalArgumentException e) {
            logger.warning("Ignoring " + path + ": " + e.getMessage());
            return null;
        }
    }

    private static Key parseSound(String text, Logger logger) {
        if (text == null || text.isBlank()) return null;
        try {
            return Key.key(text.trim().toLowerCase(Locale.ROOT));
        } catch (Exception e) {
            logger.warning("Invalid sound '" + text + "', skipping it.");
            return null;
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String text, E fallback, Logger logger) {
        if (text == null || text.isBlank()) return fallback;
        try {
            return Enum.valueOf(type, text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            logger.warning("Invalid value '" + text + "' for " + type.getSimpleName() + ", using " + fallback);
            return fallback;
        }
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }
}
