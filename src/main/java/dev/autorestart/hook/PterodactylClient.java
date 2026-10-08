package dev.autorestart.hook;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Asks the Pterodactyl panel to restart this server, the same as pressing the Restart button.
 * The panel then sends the egg's stop command (normally "stop"), waits for a clean exit and starts
 * the server again, so the console shows a normal restart instead of a crash.
 */
public final class PterodactylClient {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * @param panelUrl e.g. https://panel.example.com
     * @param serverId the 8-character ID from the server's panel URL, or its full UUID
     * @param apiKey   a client API key (Account → API Credentials), usually starting with ptlc_
     * @return a future holding null on success, or a description of what went wrong
     */
    public CompletableFuture<String> restart(String panelUrl, String serverId, String apiKey) {
        String base = panelUrl.endsWith("/") ? panelUrl.substring(0, panelUrl.length() - 1) : panelUrl;
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(base + "/api/client/servers/" + serverId + "/power"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"signal\":\"restart\"}"))
                    .build();
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture("invalid panel URL (" + e.getMessage() + ")");
        }

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> switch (response.statusCode()) {
                    case 204, 200 -> null;
                    case 401 -> "the API key was rejected (401). Check pterodactyl.api-key";
                    case 403 -> "the API key's user can't control this server's power (403)";
                    case 404 -> "server not found (404). Check pterodactyl.server-id and panel-url";
                    case 429 -> "the panel is rate limiting requests (429)";
                    default -> "the panel answered " + response.statusCode() + ": " + response.body();
                })
                .exceptionally(error -> "could not reach the panel: " + error.getMessage());
    }
}
