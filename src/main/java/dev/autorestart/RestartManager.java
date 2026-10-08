package dev.autorestart;

import dev.autorestart.core.TimeParser;
import dev.autorestart.core.WarningTracker;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

/**
 * Holds the pending restart and runs the countdown.
 * <p>
 * The countdown is checked against the real clock twice a second, so server lag can make a warning
 * show up a little late but can never push the restart itself later than planned.
 */
public final class RestartManager {

    public enum Source { SCHEDULE, MANUAL }

    private final AutoRestartPlugin plugin;
    private final Instant startedAt = Instant.now();

    private Instant target;
    private Source source = Source.SCHEDULE;
    private String reason;
    /** After a cancel, scheduled restarts at or before this moment are skipped. */
    private Instant skipUntil;

    private WarningTracker warnings;
    private WarningTracker discordAnnouncements;
    private BossBar bossBar;
    private boolean restarting;
    private BukkitTask task;

    public RestartManager(AutoRestartPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        rebuildTrackers();
        recalculateFromSchedule();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 10L);
    }

    public void stop() {
        if (task != null) task.cancel();
        hideBossBar();
    }

    /** Called after /autorestart reload. A manual restart keeps its time; a scheduled one is recalculated. */
    public void reload() {
        rebuildTrackers();
        if (source == Source.SCHEDULE) {
            recalculateFromSchedule();
        } else {
            resetTrackers();
        }
    }

    // ---------------------------------------------------------------- commands

    /** Restart {@code in} from now, replacing whatever was planned. */
    public void restartIn(Duration in, String reason) {
        target = Instant.now().plus(in);
        source = Source.MANUAL;
        this.reason = reason;
        resetTrackers();
    }

    /** @return false if there was no restart to delay */
    public boolean delay(Duration by, String reason) {
        if (target == null) return false;
        target = target.plus(by);
        source = Source.MANUAL;
        this.reason = reason;
        resetTrackers();
        return true;
    }

    /** Skip the pending restart. The next one comes from the schedule. @return false if nothing was pending */
    public boolean cancel() {
        if (target == null) return false;
        skipUntil = target;
        recalculateFromSchedule();
        return true;
    }

    // ---------------------------------------------------------------- queries

    public Optional<Instant> target() {
        return Optional.ofNullable(target);
    }

    public Optional<Duration> remaining() {
        if (target == null) return Optional.empty();
        Duration left = Duration.between(Instant.now(), target);
        return Optional.of(left.isNegative() ? Duration.ZERO : left);
    }

    public String reason() {
        return reason;
    }

    public Source source() {
        return source;
    }

    public String formatDate(Instant instant) {
        return plugin.settings().dateFormat.format(instant);
    }

    // ---------------------------------------------------------------- internals

    private void recalculateFromSchedule() {
        Settings settings = plugin.settings();
        target = settings.calculator.next(Instant.now(), startedAt, skipUntil).orElse(null);
        source = Source.SCHEDULE;
        reason = null;
        resetTrackers();
        if (target != null) {
            plugin.getLogger().info("Next restart: " + formatDate(target)
                    + " (in " + TimeParser.format(Duration.between(Instant.now(), target)) + ")");
        }
    }

    private void rebuildTrackers() {
        Settings settings = plugin.settings();
        warnings = new WarningTracker(settings.warnings.keySet());
        discordAnnouncements = new WarningTracker(settings.discord.announceAt());
    }

    private void resetTrackers() {
        long left = secondsLeft();
        warnings.reset(left);
        discordAnnouncements.reset(left);
        if (target == null) hideBossBar();
    }

    private long secondsLeft() {
        if (target == null) return Long.MAX_VALUE;
        long millis = Duration.between(Instant.now(), target).toMillis();
        return millis <= 0 ? 0 : (millis + 999) / 1000; // round up so "1s" shows until the very end
    }

    private void tick() {
        if (restarting || target == null) return;
        long left = secondsLeft();

        if (left <= 0) {
            performRestart();
            return;
        }

        Settings settings = plugin.settings();

        OptionalLong mark = warnings.poll(left);
        if (mark.isPresent()) {
            Settings.Warning warning = settings.warnings.get(mark.getAsLong());
            if (warning != null) announce(warning, TimeParser.format(Duration.ofSeconds(mark.getAsLong())), settings);
        }

        OptionalLong discordMark = discordAnnouncements.poll(left);
        if (discordMark.isPresent()) {
            plugin.discord().send(settings.discord, "warning", Map.of(
                    "time", TimeParser.format(Duration.ofSeconds(discordMark.getAsLong())),
                    "reason", reasonOrDefault(),
                    "player", "",
                    "date", formatDate(target)));
        }

        updateBossBar(left, settings);
    }

    private void announce(Settings.Warning warning, String time, Settings settings) {
        Messenger.Vars vars = new Messenger.Vars(time, reasonOrDefault(), null, formatDate(target));
        Messenger messenger = plugin.messenger();

        if (warning.chat() != null) {
            Bukkit.getServer().sendMessage(messenger.render(warning.chat(), vars));
        }
        if (warning.actionbar() != null) {
            Component bar = messenger.render(warning.actionbar(), vars);
            Bukkit.getOnlinePlayers().forEach(p -> p.sendActionBar(bar));
        }
        if (warning.title() != null || warning.subtitle() != null) {
            Title title = Title.title(
                    warning.title() == null ? Component.empty() : messenger.render(warning.title(), vars),
                    warning.subtitle() == null ? Component.empty() : messenger.render(warning.subtitle(), vars),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1500), Duration.ofMillis(300)));
            Bukkit.getOnlinePlayers().forEach(p -> p.showTitle(title));
        }
        if (warning.sound() != null) {
            Sound sound = Sound.sound(warning.sound(), Sound.Source.MASTER, settings.soundVolume, settings.soundPitch);
            Bukkit.getOnlinePlayers().forEach(p -> p.playSound(sound, Sound.Emitter.self()));
        }
    }

    private void updateBossBar(long left, Settings settings) {
        if (!settings.bossbarEnabled || settings.bossbarShowAt <= 0 || left > settings.bossbarShowAt) {
            hideBossBar();
            return;
        }
        Component name = plugin.messenger().render(settings.bossbarText,
                new Messenger.Vars(TimeParser.format(Duration.ofSeconds(left)), reasonOrDefault(), null, formatDate(target)));
        float progress = Math.max(0f, Math.min(1f, (float) left / settings.bossbarShowAt));

        if (bossBar == null) {
            bossBar = BossBar.bossBar(name, progress, settings.bossbarColor, settings.bossbarOverlay);
        } else {
            bossBar.name(name);
            bossBar.progress(progress);
            bossBar.color(settings.bossbarColor);
            bossBar.overlay(settings.bossbarOverlay);
        }
        // Showing again is harmless and covers players who joined mid-countdown.
        for (Player player : Bukkit.getOnlinePlayers()) player.showBossBar(bossBar);
    }

    private void hideBossBar() {
        if (bossBar == null) return;
        for (Player player : Bukkit.getOnlinePlayers()) player.hideBossBar(bossBar);
        bossBar = null;
    }

    private void performRestart() {
        restarting = true;
        Settings settings = plugin.settings();
        plugin.getLogger().info("Restarting the server" + (reason == null ? "." : " (reason: " + reason + ")."));
        hideBossBar();

        for (String command : settings.restartCommands) {
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.startsWith("/") ? command.substring(1) : command);
            } catch (Exception e) {
                plugin.getLogger().warning("Restart command '" + command + "' failed: " + e.getMessage());
            }
        }

        // Give the webhook a few seconds; the JVM would otherwise drop the request on exit.
        try {
            plugin.discord().send(settings.discord, "restarting", Map.of(
                            "time", "0s", "reason", reasonOrDefault(), "player", "", "date", formatDate(target)))
                    .get(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Already logged by the webhook, or it timed out; don't hold up the restart.
        }

        Component kick = plugin.messenger().render(settings.kickMessage,
                new Messenger.Vars("0s", reasonOrDefault(), null, formatDate(target)));
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.kick(kick);
        }

        // Pterodactyl (Wings) sees the process exit and starts the server again.
        Bukkit.shutdown();
    }

    private String reasonOrDefault() {
        return reason == null || reason.isBlank() ? plugin.settings().message("no-reason") : reason;
    }
}
