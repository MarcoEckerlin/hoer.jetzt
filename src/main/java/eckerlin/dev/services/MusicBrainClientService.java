package eckerlin.dev.services;

import eckerlin.dev.security.GuildEntitlementService;
import eckerlin.dev.security.GuildFeature;
import eckerlin.dev.security.FeatureNotEnabledException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@Service
public class MusicBrainClientService {

    private final AppConfigService configService;
    private final GuildEntitlementService entitlementService;
    private final HttpClient httpClient;

    public MusicBrainClientService(AppConfigService configService, GuildEntitlementService entitlementService) {
        this.configService = configService;
        this.entitlementService = entitlementService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(configService.getMusicBrainRequestTimeoutMs()))
                .build();
    }

    public CompletableFuture<MusicBrainRadioResponse> requestRadio(String guildId, int limit) {
        // Einziger Weg zum Music-Brain-Dienst - deshalb sitzt die
        // Freischaltungspruefung genau hier und nicht bei den Aufrufern.
        // Jeder Aufruf kostet Rechenzeit, also wird er auch gezaehlt.
        GuildEntitlementService.Decision decision = entitlementService.tryConsume(guildId, GuildFeature.AI_RADIO);
        if (!decision.allowed()) {
            return CompletableFuture.failedFuture(
                    new FeatureNotEnabledException(GuildFeature.AI_RADIO, decision.reason()));
        }

        JSONObject payload = new JSONObject()
                .put("limit", Math.max(1, limit));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(configService.getMusicBrainBaseUrl().replaceAll("/+$", "") + "/api/v1/guilds/" + guildId + "/radio"))
                .timeout(Duration.ofMillis(configService.getMusicBrainRequestTimeoutMs()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(this::parseResponse)
                .exceptionally(throwable -> {
                    throw new CompletionException(new IOException(rootMessage(throwable)));
                });
    }

    private MusicBrainRadioResponse parseResponse(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new CompletionException(new IOException("Music-Brain antwortet mit HTTP " + response.statusCode()));
        }

        JSONObject root = new JSONObject(response.body());
        JSONArray queriesArray = root.optJSONArray("queries");
        List<String> queries = queriesArray == null
                ? List.of()
                : queriesArray.toList().stream()
                .map(String::valueOf)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();

        return new MusicBrainRadioResponse(
                root.optString("summary", "").trim(),
                queries
        );
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
