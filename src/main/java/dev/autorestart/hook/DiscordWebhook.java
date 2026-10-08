package dev.autorestart.hook;

import dev.autorestart.Settings;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/** Posts restart announcements to a Discord channel webhook. All sends are asynchronous. */
public final class DiscordWebhook {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final Logger logger;

    public DiscordWebhook(Logger logger) {
        this.logger = logger;
    }

    /**
     * @param type one of warning, now, delayed, cancelled, restarting
     * @param vars values for {time}, {reason}, {player}, {date}
     * @return a future that completes when Discord answers (or the send fails)
     */
    public CompletableFuture<Void> send(Settings.Discord config, String type, Map<String, String> vars) {
        if (!config.enabled() || config.url() == null || config.url().isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        String text = config.messages().getOrDefault(type, "");
        if (text.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        for (Map.Entry<String, String> var : vars.entrySet()) {
            text = text.replace("{" + var.getKey() + "}", var.getValue() == null ? "" : var.getValue());
        }

        StringBuilder json = new StringBuilder("{");
        json.append("\"username\":").append(quote(config.username())).append(',');
        if (config.avatarUrl() != null && !config.avatarUrl().isBlank()) {
            json.append("\"avatar_url\":").append(quote(config.avatarUrl())).append(',');
        }
        // Don't let a typed reason ping @everyone or roles
        json.append("\"allowed_mentions\":{\"parse\":[]},");
        json.append("\"embeds\":[{")
                .append("\"description\":").append(quote(text)).append(',')
                .append("\"color\":").append(config.colors().getOrDefault(type, 0xFFFFFF)).append(',')
                .append("\"timestamp\":").append(quote(Instant.now().toString()))
                .append("}]}");

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(config.url()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.toString()))
                    .build();
        } catch (IllegalArgumentException e) {
            logger.warning("Discord webhook URL is invalid: " + e.getMessage());
            return CompletableFuture.completedFuture(null);
        }

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() >= 300) {
                        logger.warning("Discord webhook returned " + response.statusCode() + ": " + response.body());
                    }
                })
                .exceptionally(error -> {
                    logger.warning("Could not reach the Discord webhook: " + error.getMessage());
                    return null;
                });
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
