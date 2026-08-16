package eckerlin.dev.musicbrain;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class MusicBrainEngine {

    private static final List<String> BLOCKED_TERMS = List.of(
            "explicit",
            "uncensored",
            "nsfw",
            "18+",
            "porno",
            "porn",
            "sex",
            "fetish",
            "nazi",
            "hitler",
            "slur"
    );
    private static final List<String> DEFAULT_SAFE_QUERIES = List.of(
            "Coldplay Adventure of a Lifetime official audio",
            "Imagine Dragons Believer official audio",
            "OneRepublic I Lived official audio",
            "Alan Walker Faded official audio",
            "Kygo Firestone official audio",
            "Robin Schulz Sugar official audio",
            "Ed Sheeran Shivers official audio",
            "Dua Lipa Houdini official audio",
            "Ava Max Kings and Queens official audio",
            "David Guetta Titanium official audio",
            "The Weeknd Blinding Lights official audio",
            "Linkin Park Burn It Down official audio"
    );
    private static final List<String> INVALID_QUERY_TERMS = List.of(
            " mix",
            " playlist",
            " compilation",
            " full album",
            " full mix",
            " live stream",
            " 1 hour",
            " 10 hour",
            " loop"
    );
    private static final int MAX_OLLAMA_TIMEOUT_MS = 5_000;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public JSONObject buildRadioPlan(String guildId, int requestedLimit) throws SQLException {
        int limit = Math.max(4, Math.min(24, requestedLimit));
        int historyDays = Math.max(7, Config.config.optInt("history_days", 45));
        int botId = Config.config.optInt("bot_id", 1);

        TasteProfile profile = loadTasteProfile(botId, guildId, historyDays);
        if (profile.tracks().isEmpty() && profile.artists().isEmpty()) {
            profile = loadGlobalTasteProfile(botId, historyDays);
        }

        RadioPlan plan = generatePlan(profile, limit);
        return new JSONObject()
                .put("summary", plan.summary())
                .put("queries", plan.queries());
    }

    private TasteProfile loadTasteProfile(int botId, String guildId, int historyDays) throws SQLException {
        try (Connection connection = Database.connection()) {
            return new TasteProfile(
                    loadTracks(connection, botId, guildId, historyDays),
                    loadArtists(connection, botId, guildId, historyDays),
                    loadRecentTitles(connection, botId, guildId)
            );
        }
    }

    private TasteProfile loadGlobalTasteProfile(int botId, int historyDays) throws SQLException {
        try (Connection connection = Database.connection()) {
            return new TasteProfile(
                    loadTracks(connection, botId, null, historyDays),
                    loadArtists(connection, botId, null, historyDays),
                    List.of()
            );
        }
    }

    private List<TrackStats> loadTracks(Connection connection, int botId, String guildId, int historyDays) throws SQLException {
        String sql = """
                SELECT title,
                       author,
                       COUNT(*) AS plays,
                       SUM(CASE WHEN created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY) THEN 1 ELSE 0 END) AS recent_plays
                FROM music_track_events
                WHERE bot_id = ?
                  AND is_stream = 0
                  AND source_name IN ('manual', 'playlist')
                  AND created_at >= DATE_SUB(NOW(), INTERVAL ? DAY)
                  %s
                GROUP BY title, author
                ORDER BY recent_plays DESC, plays DESC, MAX(created_at) DESC
                LIMIT 24
                """.formatted(guildId == null ? "" : "AND guild_id = ?");

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, botId);
            statement.setInt(2, historyDays);
            if (guildId != null) {
                statement.setString(3, guildId);
            }

            List<TrackStats> tracks = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    tracks.add(new TrackStats(
                            safe(resultSet.getString("title")),
                            safe(resultSet.getString("author")),
                            resultSet.getInt("plays"),
                            resultSet.getInt("recent_plays")
                    ));
                }
            }
            return tracks;
        }
    }

    private List<ArtistStats> loadArtists(Connection connection, int botId, String guildId, int historyDays) throws SQLException {
        String sql = """
                SELECT author,
                       COUNT(*) AS plays,
                       SUM(CASE WHEN created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY) THEN 1 ELSE 0 END) AS recent_plays
                FROM music_track_events
                WHERE bot_id = ?
                  AND is_stream = 0
                  AND source_name IN ('manual', 'playlist')
                  AND author IS NOT NULL
                  AND author <> ''
                  AND created_at >= DATE_SUB(NOW(), INTERVAL ? DAY)
                  %s
                GROUP BY author
                ORDER BY recent_plays DESC, plays DESC, MAX(created_at) DESC
                LIMIT 12
                """.formatted(guildId == null ? "" : "AND guild_id = ?");

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, botId);
            statement.setInt(2, historyDays);
            if (guildId != null) {
                statement.setString(3, guildId);
            }

            List<ArtistStats> artists = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    artists.add(new ArtistStats(
                            safe(resultSet.getString("author")),
                            resultSet.getInt("plays"),
                            resultSet.getInt("recent_plays")
                    ));
                }
            }
            return artists;
        }
    }

    private List<String> loadRecentTitles(Connection connection, int botId, String guildId) throws SQLException {
        if (guildId == null) {
            return List.of();
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT title
                FROM music_track_events
                WHERE bot_id = ? AND guild_id = ? AND is_stream = 0
                  AND source_name IN ('manual', 'playlist')
                ORDER BY created_at DESC
                LIMIT 12
                """)) {
            statement.setInt(1, botId);
            statement.setString(2, guildId);

            List<String> titles = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    titles.add(safe(resultSet.getString("title")));
                }
            }
            return titles;
        }
    }

    private RadioPlan generatePlan(TasteProfile profile, int limit) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        String summary = buildSummary(profile);

        try {
            LlmSettings llmSettings = loadLlmSettings();
            if (llmSettings.enabled() && !llmSettings.url().isBlank() && !llmSettings.model().isBlank()) {
                RadioPlan llmPlan = requestQueriesFromOllama(profile, llmSettings, limit);
                queries.addAll(llmPlan.queries());
                if (!llmPlan.summary().isBlank()) {
                    summary = llmPlan.summary();
                }
            }
        } catch (Exception ignored) {
        }

        if (queries.isEmpty()) {
            queries.addAll(buildFallbackQueries(profile, limit));
        }

        return new RadioPlan(summary, queries.stream().limit(limit).toList());
    }

    private String buildSummary(TasteProfile profile) {
        String artists = profile.artists().stream()
                .limit(3)
                .map(ArtistStats::artist)
                .filter(value -> !value.isBlank())
                .reduce((left, right) -> left + ", " + right)
                .orElse("keine klaren Favoriten");

        if ("keine klaren Favoriten".equals(artists)) {
            return "Sauberer Mix mit populaeren Songs, bis genug Server-Historie gelernt wurde.";
        }

        return "Mix aus dem Servergeschmack rund um " + artists + ".";
    }

    private List<String> buildFallbackQueries(TasteProfile profile, int limit) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        if (profile.tracks().isEmpty() && profile.artists().isEmpty()) {
            DEFAULT_SAFE_QUERIES.forEach(query -> addQuery(queries, query));
            return queries.stream().limit(limit).toList();
        }

        LinkedHashSet<String> recentTokens = new LinkedHashSet<>(profile.recentTitles().stream()
                .map(title -> normalize(sanitizeTrackTitle(title, "")))
                .toList());

        for (TrackStats track : profile.tracks()) {
            String artist = sanitizeArtist(track.artist());
            String title = sanitizeTrackTitle(track.title(), artist);
            String normalizedTitle = normalize(title);
            if (!normalizedTitle.isBlank() && recentTokens.contains(normalizedTitle)) {
                continue;
            }
            if (!artist.isBlank() && !title.isBlank()) {
                addQuery(queries, artist + " " + title + " official audio");
            }
            if (!artist.isBlank()) {
                addQuery(queries, artist + " official audio");
                addQuery(queries, artist + " popular song official audio");
            }
            if (queries.size() >= limit) {
                break;
            }
        }

        for (ArtistStats artist : profile.artists()) {
            addQuery(queries, artist.artist() + " official audio");
            addQuery(queries, artist.artist() + " popular song official audio");
            if (queries.size() >= limit) {
                break;
            }
        }

        return queries.stream().limit(limit).toList();
    }

    private void addQuery(LinkedHashSet<String> queries, String query) {
        String trimmed = safe(query);
        if (trimmed.isBlank() || containsBlockedTerm(trimmed) || containsInvalidQueryTerm(trimmed)) {
            return;
        }
        queries.add(trimmed);
    }

    private RadioPlan requestQueriesFromOllama(TasteProfile profile, LlmSettings llmSettings, int limit) throws IOException, InterruptedException {
        String prompt = """
                Erstelle fuer ein Discord-Serverradio einen sauberen, familienfreundlichen Shuffle als JSON.
                Gib exakt ein JSON-Objekt mit den Schluesseln "summary" und "queries" zurueck.
                "queries" soll %d Suchanfragen fuer YouTube-Musik enthalten.
                Keine Erklaerungen, kein Markdown, keine Code-Fences.
                Nur einzelne Songs, keine Playlists, keine Mixe, keine Compilations und keine langen 1h-Videos.
                Vermeide explizite, sexualisierte, beleidigende, gewaltverherrlichende, extremistische oder drogenzentrierte Titel.
                Bevorzuge diese Artists: %s
                Beliebte Tracks: %s
                Zuletzt gespielt und moeglichst vermeiden: %s
                """.formatted(
                limit,
                joinArtists(profile.artists()),
                joinTracks(profile.tracks()),
                String.join(", ", profile.recentTitles())
        );

        JSONObject payload = new JSONObject()
                .put("model", llmSettings.model())
                .put("messages", new JSONArray()
                        .put(new JSONObject().put("role", "system").put("content",
                                "Du bist ein Musik-Kurator fuer ein sauberes Discord-Radio. Antworte nur mit JSON."))
                        .put(new JSONObject().put("role", "user").put("content", prompt)))
                .put("stream", false);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(llmSettings.url().replaceAll("/+$", "") + "/api/chat"))
                .timeout(Duration.ofMillis(Math.max(1_500, Math.min(llmSettings.timeoutMs(), MAX_OLLAMA_TIMEOUT_MS))))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Ollama HTTP " + response.statusCode());
        }

        String content = new JSONObject(response.body())
                .optJSONObject("message")
                .optString("content", "")
                .trim();
        JSONObject parsed = parseEmbeddedJsonObject(content);
        JSONArray queryArray = parsed.optJSONArray("queries");
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        if (queryArray != null) {
            for (int index = 0; index < queryArray.length(); index++) {
                addQuery(queries, queryArray.optString(index, ""));
            }
        }

        if (queries.isEmpty()) {
            queries.addAll(buildFallbackQueries(profile, limit));
        }

        return new RadioPlan(
                safe(parsed.optString("summary", buildSummary(profile))),
                queries.stream().limit(limit).toList()
        );
    }

    private LlmSettings loadLlmSettings() throws SQLException {
        try (Connection connection = Database.connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT llm_provider, llm_ollama_url, llm_model, llm_timeout_ms
                     FROM settings
                     WHERE id = ?
                     LIMIT 1
                     """)) {
            statement.setInt(1, Config.config.optInt("bot_id", 1));

            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next() && "ollama".equalsIgnoreCase(safe(resultSet.getString("llm_provider")))) {
                    return new LlmSettings(
                            true,
                            safe(resultSet.getString("llm_ollama_url")),
                            safe(resultSet.getString("llm_model")),
                            Math.max(1000, resultSet.getInt("llm_timeout_ms"))
                    );
                }
            }
        }

        JSONObject ollama = Config.config.optJSONObject("ollama");
        return new LlmSettings(
                ollama != null && ollama.optBoolean("enabled", true),
                ollama == null ? "" : safe(ollama.optString("url", "")),
                ollama == null ? "" : safe(ollama.optString("model", "")),
                ollama == null ? 30000 : Math.max(1000, ollama.optInt("timeout_ms", 30000))
        );
    }

    private JSONObject parseEmbeddedJsonObject(String value) {
        String trimmed = safe(value);
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return new JSONObject(trimmed.substring(start, end + 1));
        }
        throw new IllegalArgumentException("Kein JSON-Objekt in Ollama-Antwort gefunden.");
    }

    private boolean containsBlockedTerm(String value) {
        String normalized = normalize(value);
        return BLOCKED_TERMS.stream().anyMatch(normalized::contains);
    }

    private boolean containsInvalidQueryTerm(String value) {
        String normalized = normalize(value);
        return INVALID_QUERY_TERMS.stream().anyMatch(normalized::contains);
    }

    private String normalize(String value) {
        return safe(value).toLowerCase(Locale.ROOT);
    }

    private String sanitizeArtist(String artist) {
        return safe(artist)
                .replaceAll("(?i)\\btopic\\b", " ")
                .replaceAll("(?i)\\bvevo\\b", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    private String sanitizeTrackTitle(String title, String artist) {
        String cleaned = safe(title)
                .replace('|', ' ')
                .replaceAll("\\[[^\\]]*]", " ")
                .replaceAll("\\([^)]*\\)", " ")
                .replaceAll("(?i)\\b(official( music)? video|official audio|visualizer|lyrics?|lyric video|audio|video|hd|hq|remastered?|remaster)\\b", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();

        String cleanedArtist = sanitizeArtist(artist);
        if (!cleanedArtist.isBlank()) {
            cleaned = cleaned.replaceFirst("(?i)^" + Pattern.quote(cleanedArtist) + "\\s*[-:|]?\\s*", "");
        }

        return cleaned.replaceAll("\\s{2,}", " ").trim();
    }

    private String joinArtists(List<ArtistStats> artists) {
        return artists.stream()
                .limit(5)
                .map(ArtistStats::artist)
                .filter(value -> !value.isBlank())
                .reduce((left, right) -> left + ", " + right)
                .orElse("keine");
    }

    private String joinTracks(List<TrackStats> tracks) {
        return tracks.stream()
                .limit(8)
                .map(track -> {
                    String artist = sanitizeArtist(track.artist());
                    String title = sanitizeTrackTitle(track.title(), artist);
                    if (artist.isBlank()) {
                        return title;
                    }
                    if (title.isBlank()) {
                        return artist;
                    }
                    return artist + " - " + title;
                })
                .reduce((left, right) -> left + ", " + right)
                .orElse("keine");
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private record TrackStats(String title, String artist, int plays, int recentPlays) {
    }

    private record ArtistStats(String artist, int plays, int recentPlays) {
    }

    private record TasteProfile(List<TrackStats> tracks, List<ArtistStats> artists, List<String> recentTitles) {
    }

    private record LlmSettings(boolean enabled, String url, String model, int timeoutMs) {
    }

    private record RadioPlan(String summary, List<String> queries) {
    }
}
