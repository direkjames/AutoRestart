package dev.autorestart.hook;

import dev.autorestart.AutoRestartPlugin;
import dev.autorestart.RestartManager;
import dev.autorestart.core.TimeParser;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.time.Duration;

/**
 * PlaceholderAPI placeholders:
 * <ul>
 *     <li>{@code %autorestart_time_left%} — e.g. "1h 5m", or "-" if none</li>
 *     <li>{@code %autorestart_time_left_seconds%} — e.g. "3900", or "-1" if none</li>
 *     <li>{@code %autorestart_next%} — the restart date, using date-format</li>
 *     <li>{@code %autorestart_reason%} — the reason, or the no-reason text</li>
 *     <li>{@code %autorestart_type%} — "schedule" or "manual"</li>
 * </ul>
 * Only loaded when PlaceholderAPI is installed.
 */
public final class AutoRestartExpansion extends PlaceholderExpansion {

    private final AutoRestartPlugin plugin;

    public AutoRestartExpansion(AutoRestartPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "autorestart";
    }

    @Override
    public String getAuthor() {
        String authors = String.join(", ", plugin.getPluginMeta().getAuthors());
        return authors.isEmpty() ? "AutoRestart" : authors;
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // keep working after /papi reload
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        RestartManager manager = plugin.restartManager();
        return switch (params.toLowerCase()) {
            case "time_left" -> manager.remaining().map(TimeParser::format).orElse("-");
            case "time_left_seconds" -> manager.remaining().map(Duration::toSeconds).map(String::valueOf).orElse("-1");
            case "next" -> manager.target().map(manager::formatDate).orElse("-");
            case "reason" -> {
                String reason = manager.reason();
                yield reason == null || reason.isBlank() ? plugin.settings().message("no-reason") : reason;
            }
            case "type" -> manager.source().name().toLowerCase();
            default -> null;
        };
    }
}
