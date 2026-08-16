package eckerlin.dev.services;

import dev.arbjerg.lavalink.client.player.Track;
import eckerlin.dev.audio.GuildAudioState;
import eckerlin.dev.utils.Alert;
import eckerlin.dev.utils.DB;
import eckerlin.dev.web.dto.PublicStatsChartPointView;
import eckerlin.dev.web.dto.PublicStatsChartView;
import eckerlin.dev.web.dto.PublicStatsLiveItemView;
import eckerlin.dev.web.dto.PublicStatsRankedItemView;
import eckerlin.dev.web.dto.PublicStatsSummaryView;
import eckerlin.dev.web.dto.PublicStatsView;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class ListenerStatsService {

    private static final ZoneId EUROPE_BERLIN = ZoneId.of("Europe/Berlin");
    private static final DateTimeFormatter GENERATED_AT_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.GERMAN)
            .withZone(EUROPE_BERLIN);
    private static final DateTimeFormatter HOUR_MINUTE_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN);
    private static final DateTimeFormatter DAY_MONTH_FORMAT = DateTimeFormatter.ofPattern("dd.MM.", Locale.GERMAN);
    private static final DateTimeFormatter DAY_MONTH_TIME_FORMAT = DateTimeFormatter.ofPattern("dd.MM. HH:mm", Locale.GERMAN);
    private static final DateTimeFormatter MONTH_YEAR_FORMAT = DateTimeFormatter.ofPattern("MM/yyyy", Locale.GERMAN);

    private static final List<RangePreset> RANGE_PRESETS = List.of(
            new RangePreset("1y", "1 Jahr", Duration.ofDays(365), Duration.ofDays(30)),
            new RangePreset("6m", "6 Monate", Duration.ofDays(182), Duration.ofDays(7)),
            new RangePreset("30t", "30 Tage", Duration.ofDays(30), Duration.ofDays(1)),
            new RangePreset("7t", "7 Tage", Duration.ofDays(7), Duration.ofHours(6)),
            new RangePreset("3t", "3 Tage", Duration.ofDays(3), Duration.ofHours(3)),
            new RangePreset("24h", "24 Stunden", Duration.ofHours(24), Duration.ofHours(1)),
            new RangePreset("12h", "12 Stunden", Duration.ofHours(12), Duration.ofMinutes(30)),
            new RangePreset("3h", "3 Stunden", Duration.ofHours(3), Duration.ofMinutes(10)),
            new RangePreset("1h", "1 Stunde", Duration.ofHours(1), Duration.ofMinutes(5))
    );

    private final AppConfigService configService;
    private final ConcurrentMap<String, ActiveListenerSession> activeSessions = new ConcurrentHashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor();

    public ListenerStatsService(AppConfigService configService) {
        this.configService = configService;
    }

    public synchronized void syncAudience(
            Guild guild,
            VoiceChannel connectedChannel,
            GuildAudioState state,
            Track currentTrack,
            boolean audiblePlayback
    ) {
        if (guild == null) {
            return;
        }

        Instant now = Instant.now();
        String guildId = guild.getId();

        if (!audiblePlayback || connectedChannel == null || currentTrack == null) {
            closeGuildSessions(guildId, now);
            return;
        }

        PlaybackSnapshot playback = buildPlaybackSnapshot(state, currentTrack);
        Map<String, Member> listeners = new LinkedHashMap<>();
        for (Member member : connectedChannel.getMembers()) {
            if (member.getUser().isBot()) {
                continue;
            }
            listeners.put(member.getId(), member);
        }

        if (listeners.isEmpty()) {
            closeGuildSessions(guildId, now);
            return;
        }

        List<String> staleKeys = new ArrayList<>();
        for (Map.Entry<String, ActiveListenerSession> entry : activeSessions.entrySet()) {
            ActiveListenerSession session = entry.getValue();
            if (!session.guildId().equals(guildId)) {
                continue;
            }
            if (!listeners.containsKey(session.userId()) || !session.playbackFingerprint().equals(playback.fingerprint())) {
                closeSession(session, now);
                staleKeys.add(entry.getKey());
            }
        }
        staleKeys.forEach(activeSessions::remove);

        for (Member member : listeners.values()) {
            String sessionKey = sessionKey(guildId, member.getId());
            if (activeSessions.containsKey(sessionKey)) {
                continue;
            }

            activeSessions.put(sessionKey, new ActiveListenerSession(
                    guildId,
                    member.getId(),
                    hashListenerId(member.getId()),
                    playback.kind(),
                    playback.title(),
                    playback.author(),
                    playback.identifier(),
                    playback.sourceLabel(),
                    playback.stream(),
                    playback.fingerprint(),
                    now
            ));
        }
    }

    public synchronized void clearGuildSessions(Guild guild) {
        if (guild == null) {
            return;
        }
        closeGuildSessions(guild.getId(), Instant.now());
    }

    public PublicStatsView buildPublicView() {
        List<ActiveListenerSession> liveSnapshot = snapshotActiveSessions();
        List<PublicStatsLiveItemView> liveItems = buildLiveItems(liveSnapshot);
        SummaryAggregate summary = loadSummary(Duration.ofDays(30));
        return new PublicStatsView(
                new PublicStatsSummaryView(
                        liveSnapshot.size(),
                        liveItems.size(),
                        summary.uniqueListeners(),
                        summary.trackedSessions(),
                        summary.trackedGuilds(),
                        formatDuration(summary.listenedSeconds()),
                        GENERATED_AT_FORMAT.format(Instant.now())
                ),
                liveItems,
                loadTopTracks(),
                loadTopArtists(),
                loadTopSources()
        );
    }

    public PublicStatsChartView buildChartView(String rangeKey) {
        RangePreset preset = resolveRange(rangeKey);
        List<BucketData> buckets = loadBuckets(preset);

        long totalListenedSeconds = buckets.stream().mapToLong(BucketData::listenedSeconds).sum();
        long peakListeners = buckets.stream().mapToLong(BucketData::uniqueListeners).max().orElse(0L);
        long totalUniqueListeners = countDistinctListeners(preset.duration());

        return new PublicStatsChartView(
                preset.key(),
                preset.label(),
                formatDuration(totalListenedSeconds),
                totalUniqueListeners,
                peakListeners,
                buckets.stream()
                        .map(bucket -> new PublicStatsChartPointView(
                                bucket.label(),
                                bucket.listenedSeconds(),
                                bucket.uniqueListeners()
                        ))
                        .toList()
        );
    }

    public List<String> getAvailableRanges() {
        return RANGE_PRESETS.stream().map(RangePreset::key).toList();
    }

    private synchronized List<ActiveListenerSession> snapshotActiveSessions() {
        return List.copyOf(activeSessions.values());
    }

    private void closeGuildSessions(String guildId, Instant endedAt) {
        List<String> keysToRemove = new ArrayList<>();
        for (Map.Entry<String, ActiveListenerSession> entry : activeSessions.entrySet()) {
            ActiveListenerSession session = entry.getValue();
            if (!session.guildId().equals(guildId)) {
                continue;
            }
            closeSession(session, endedAt);
            keysToRemove.add(entry.getKey());
        }
        keysToRemove.forEach(activeSessions::remove);
    }

    private void closeSession(ActiveListenerSession session, Instant endedAt) {
        long listenedSeconds = Math.max(1L, endedAt.getEpochSecond() - session.startedAt().getEpochSecond());
        if (!DB.isAvailable()) {
            return;
        }

        writer.execute(() -> {
            try (Connection connection = DB.connection();
                 PreparedStatement statement = connection.prepareStatement("""
                         INSERT INTO music_listener_events (
                             bot_id,
                             guild_id,
                             listener_hash,
                             playback_kind,
                             title,
                             author,
                             identifier,
                             source_label,
                             listened_seconds,
                             is_stream,
                             started_at,
                             ended_at
                         ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                         """)) {
                statement.setInt(1, configService.getBotId());
                statement.setString(2, session.guildId());
                statement.setString(3, session.listenerHash());
                statement.setString(4, session.playbackKind());
                statement.setString(5, safe(session.title(), 255));
                statement.setString(6, safe(session.author(), 255));
                statement.setString(7, safe(session.identifier(), 255));
                statement.setString(8, safe(session.sourceLabel(), 190));
                statement.setLong(9, listenedSeconds);
                statement.setBoolean(10, session.stream());
                statement.setTimestamp(11, Timestamp.from(session.startedAt()));
                statement.setTimestamp(12, Timestamp.from(endedAt));
                statement.executeUpdate();
            } catch (SQLException exception) {
                Alert.send("WARN", "STATS", "Hörer-Statistik konnte nicht gespeichert werden: " + exception.getMessage());
            }
        });
    }

    private PlaybackSnapshot buildPlaybackSnapshot(GuildAudioState state, Track currentTrack) {
        String playbackKind = state.smartRadioEnabled()
                ? "ai_radio"
                : currentTrack.getInfo().isStream() || !state.activeRadioName().isBlank()
                ? "radio"
                : "music";

        String title = firstNonBlank(
                safe(currentTrack.getInfo().getTitle(), 255),
                state.activeRadioName(),
                "Unbekannter Titel"
        );
        String author = firstNonBlank(
                safe(currentTrack.getInfo().getAuthor(), 255),
                "ai_radio".equals(playbackKind) ? "AI Radio" : "",
                "radio".equals(playbackKind) ? state.activeRadioName() : ""
        );
        String sourceLabel = switch (playbackKind) {
            case "ai_radio" -> "AI Radio";
            case "radio" -> firstNonBlank(
                    state.activeRadioName(),
                    safe(currentTrack.getInfo().getAuthor(), 190),
                    "Radio"
            );
            default -> firstNonBlank(
                    safe(currentTrack.getInfo().getSourceName(), 190),
                    "Musik"
            );
        };
        String identifier = firstNonBlank(
                safe(currentTrack.getInfo().getIdentifier(), 255),
                safe(currentTrack.getInfo().getUri(), 255),
                playbackKind + ":" + state.playbackRevision()
        );
        String fingerprint = playbackKind
                + "|"
                + state.playbackRevision()
                + "|"
                + identifier
                + "|"
                + safe(sourceLabel, 190);

        return new PlaybackSnapshot(
                playbackKind,
                title,
                author,
                identifier,
                sourceLabel,
                currentTrack.getInfo().isStream(),
                fingerprint
        );
    }

    private List<PublicStatsLiveItemView> buildLiveItems(List<ActiveListenerSession> sessions) {
        Map<String, LiveAggregate> grouped = new LinkedHashMap<>();
        for (ActiveListenerSession session : sessions) {
            grouped.compute(session.playbackFingerprint(), (key, existing) -> {
                if (existing == null) {
                    return new LiveAggregate(
                            modeLabel(session.playbackKind()),
                            safeDisplay(session.title()),
                            liveSubtitle(session),
                            1
                    );
                }
                return new LiveAggregate(
                        existing.modeLabel(),
                        existing.title(),
                        existing.subtitle(),
                        existing.listenerCount() + 1
                );
            });
        }

        return grouped.values().stream()
                .sorted(Comparator.comparingLong(LiveAggregate::listenerCount).reversed())
                .map(aggregate -> new PublicStatsLiveItemView(
                        aggregate.modeLabel(),
                        aggregate.title(),
                        aggregate.subtitle(),
                        aggregate.listenerCount()
                ))
                .toList();
    }

    private SummaryAggregate loadSummary(Duration duration) {
        if (!DB.isAvailable()) {
            return new SummaryAggregate(0L, 0L, 0L, 0L);
        }

        try (Connection connection = DB.connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT
                         COALESCE(SUM(listened_seconds), 0) AS listened_seconds,
                         COUNT(*) AS tracked_sessions,
                         COUNT(DISTINCT listener_hash) AS unique_listeners,
                         COUNT(DISTINCT guild_id) AS tracked_guilds
                     FROM music_listener_events
                     WHERE bot_id = ?
                       AND started_at >= ?
                     """)) {
            statement.setInt(1, configService.getBotId());
            statement.setTimestamp(2, Timestamp.from(Instant.now().minus(duration)));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return new SummaryAggregate(
                            resultSet.getLong("listened_seconds"),
                            resultSet.getLong("tracked_sessions"),
                            resultSet.getLong("unique_listeners"),
                            resultSet.getLong("tracked_guilds")
                    );
                }
            }
        } catch (SQLException exception) {
            Alert.send("WARN", "STATS", "Öffentliche Statistik konnte nicht geladen werden: " + exception.getMessage());
        }

        return new SummaryAggregate(0L, 0L, 0L, 0L);
    }

    private List<PublicStatsRankedItemView> loadTopTracks() {
        return loadRankedItems("""
                SELECT
                    title,
                    COALESCE(author, '') AS subtitle,
                    SUM(listened_seconds) AS listened_seconds,
                    COUNT(DISTINCT listener_hash) AS unique_listeners
                FROM music_listener_events
                WHERE bot_id = ?
                  AND started_at >= ?
                  AND title <> ''
                GROUP BY title, subtitle
                ORDER BY listened_seconds DESC, unique_listeners DESC
                LIMIT 8
                """, Duration.ofDays(30));
    }

    private List<PublicStatsRankedItemView> loadTopArtists() {
        return loadRankedItems("""
                SELECT
                    author AS title,
                    '' AS subtitle,
                    SUM(listened_seconds) AS listened_seconds,
                    COUNT(DISTINCT listener_hash) AS unique_listeners
                FROM music_listener_events
                WHERE bot_id = ?
                  AND started_at >= ?
                  AND author IS NOT NULL
                  AND author <> ''
                GROUP BY author
                ORDER BY listened_seconds DESC, unique_listeners DESC
                LIMIT 8
                """, Duration.ofDays(30));
    }

    private List<PublicStatsRankedItemView> loadTopSources() {
        return loadRankedItems("""
                SELECT
                    COALESCE(source_label, title) AS title,
                    playback_kind AS subtitle,
                    SUM(listened_seconds) AS listened_seconds,
                    COUNT(DISTINCT listener_hash) AS unique_listeners
                FROM music_listener_events
                WHERE bot_id = ?
                  AND started_at >= ?
                  AND playback_kind IN ('radio', 'ai_radio')
                GROUP BY COALESCE(source_label, title), playback_kind
                ORDER BY listened_seconds DESC, unique_listeners DESC
                LIMIT 8
                """, Duration.ofDays(30));
    }

    private List<PublicStatsRankedItemView> loadRankedItems(String sql, Duration duration) {
        if (!DB.isAvailable()) {
            return List.of();
        }

        List<PublicStatsRankedItemView> items = new ArrayList<>();
        try (Connection connection = DB.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, configService.getBotId());
            statement.setTimestamp(2, Timestamp.from(Instant.now().minus(duration)));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    long listenedSeconds = resultSet.getLong("listened_seconds");
                    long uniqueListeners = resultSet.getLong("unique_listeners");
                    String subtitle = resultSet.getString("subtitle");
                    subtitle = subtitle == null || subtitle.isBlank() ? "" : subtitle.trim();
                    if ("radio".equalsIgnoreCase(subtitle)) {
                        subtitle = "Radio";
                    } else if ("ai_radio".equalsIgnoreCase(subtitle)) {
                        subtitle = "AI Radio";
                    }
                    items.add(new PublicStatsRankedItemView(
                            safeDisplay(resultSet.getString("title")),
                            subtitle,
                            uniqueListeners + " eindeutige Hörer · " + formatDuration(listenedSeconds)
                    ));
                }
            }
        } catch (SQLException exception) {
            Alert.send("WARN", "STATS", "Toplisten konnten nicht geladen werden: " + exception.getMessage());
        }
        return items;
    }

    private List<BucketData> loadBuckets(RangePreset preset) {
        ZonedDateTime now = ZonedDateTime.now(EUROPE_BERLIN);
        Instant start = now.minus(preset.duration()).toInstant();

        List<ZonedDateTime> bucketStarts = new ArrayList<>();
        ZonedDateTime cursor = now.minus(preset.duration());
        while (cursor.isBefore(now)) {
            bucketStarts.add(cursor);
            cursor = cursor.plus(preset.bucketSize());
        }
        if (bucketStarts.isEmpty()) {
            bucketStarts.add(now.minus(preset.duration()));
        }

        List<MutableBucket> buckets = new ArrayList<>();
        for (ZonedDateTime bucketStart : bucketStarts) {
            buckets.add(new MutableBucket(labelFor(bucketStart, preset), 0L, new HashSet<>()));
        }

        if (!DB.isAvailable()) {
            return buckets.stream().map(MutableBucket::toView).toList();
        }

        try (Connection connection = DB.connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT listener_hash, listened_seconds, started_at
                     FROM music_listener_events
                     WHERE bot_id = ?
                       AND started_at >= ?
                     ORDER BY started_at ASC
                     """)) {
            statement.setInt(1, configService.getBotId());
            statement.setTimestamp(2, Timestamp.from(start));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Timestamp timestamp = resultSet.getTimestamp("started_at");
                    if (timestamp == null) {
                        continue;
                    }
                    ZonedDateTime startedAt = timestamp.toInstant().atZone(EUROPE_BERLIN);
                    int bucketIndex = (int) (Duration.between(bucketStarts.get(0), startedAt).toMillis() / preset.bucketSize().toMillis());
                    if (bucketIndex < 0 || bucketIndex >= buckets.size()) {
                        continue;
                    }
                    MutableBucket bucket = buckets.get(bucketIndex);
                    bucket.listenedSeconds += Math.max(0L, resultSet.getLong("listened_seconds"));
                    String listenerHash = resultSet.getString("listener_hash");
                    if (listenerHash != null && !listenerHash.isBlank()) {
                        bucket.listenerHashes.add(listenerHash);
                    }
                }
            }
        } catch (SQLException exception) {
            Alert.send("WARN", "STATS", "Diagrammdaten konnten nicht geladen werden: " + exception.getMessage());
        }

        return buckets.stream().map(MutableBucket::toView).toList();
    }

    private long countDistinctListeners(Duration duration) {
        if (!DB.isAvailable()) {
            return 0L;
        }

        try (Connection connection = DB.connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(DISTINCT listener_hash) AS total
                     FROM music_listener_events
                     WHERE bot_id = ?
                       AND started_at >= ?
                     """)) {
            statement.setInt(1, configService.getBotId());
            statement.setTimestamp(2, Timestamp.from(Instant.now().minus(duration)));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getLong("total");
                }
            }
        } catch (SQLException exception) {
            Alert.send("WARN", "STATS", "Hörerzahl konnte nicht geladen werden: " + exception.getMessage());
        }
        return 0L;
    }

    private RangePreset resolveRange(String rangeKey) {
        if (rangeKey != null) {
            for (RangePreset preset : RANGE_PRESETS) {
                if (preset.key().equalsIgnoreCase(rangeKey.trim())) {
                    return preset;
                }
            }
        }
        return RANGE_PRESETS.stream()
                .filter(preset -> "30t".equals(preset.key()))
                .findFirst()
                .orElse(RANGE_PRESETS.get(0));
    }

    private String labelFor(ZonedDateTime bucketStart, RangePreset preset) {
        return switch (preset.key()) {
            case "1y", "6m" -> MONTH_YEAR_FORMAT.format(bucketStart);
            case "30t" -> DAY_MONTH_FORMAT.format(bucketStart);
            case "7t", "3t" -> DAY_MONTH_TIME_FORMAT.format(bucketStart);
            default -> HOUR_MINUTE_FORMAT.format(bucketStart);
        };
    }

    private String hashListenerId(String userId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(resolveSalt().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) ':');
            digest.update(userId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            return Integer.toHexString((resolveSalt() + ":" + userId).hashCode());
        }
    }

    private String resolveSalt() {
        String clientSecret = configService.getDiscordClientSecret().trim();
        if (!clientSecret.isBlank()) {
            return clientSecret;
        }

        String token = configService.getConfiguredBotToken().trim();
        if (!token.isBlank()) {
            return token;
        }

        return "listener-stats:" + configService.getBotId();
    }

    private String liveSubtitle(ActiveListenerSession session) {
        if (!session.author().isBlank() && !"AI Radio".equals(session.author())) {
            return session.author();
        }
        if (!session.sourceLabel().isBlank()) {
            return session.sourceLabel();
        }
        return "Live";
    }

    private String modeLabel(String playbackKind) {
        return switch (playbackKind == null ? "" : playbackKind) {
            case "ai_radio" -> "AI Radio";
            case "radio" -> "Radio";
            default -> "Musik";
        };
    }

    private String formatDuration(long listenedSeconds) {
        long hours = listenedSeconds / 3600;
        long minutes = (listenedSeconds % 3600) / 60;
        if (hours > 0) {
            return hours + " h " + String.format(Locale.ROOT, "%02d", minutes) + " min";
        }
        long displayMinutes = listenedSeconds > 0 && minutes == 0 ? 1 : Math.max(0L, minutes);
        return displayMinutes + " min";
    }

    private String safe(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private String safeDisplay(String value) {
        if (value == null || value.isBlank()) {
            return "Unbekannt";
        }
        return value.trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private record ActiveListenerSession(
            String guildId,
            String userId,
            String listenerHash,
            String playbackKind,
            String title,
            String author,
            String identifier,
            String sourceLabel,
            boolean stream,
            String playbackFingerprint,
            Instant startedAt
    ) {
    }

    private record PlaybackSnapshot(
            String kind,
            String title,
            String author,
            String identifier,
            String sourceLabel,
            boolean stream,
            String fingerprint
    ) {
    }

    private record LiveAggregate(
            String modeLabel,
            String title,
            String subtitle,
            long listenerCount
    ) {
    }

    private record SummaryAggregate(
            long listenedSeconds,
            long trackedSessions,
            long uniqueListeners,
            long trackedGuilds
    ) {
    }

    private record RangePreset(
            String key,
            String label,
            Duration duration,
            Duration bucketSize
    ) {
    }

    private record BucketData(
            String label,
            long listenedSeconds,
            long uniqueListeners
    ) {
    }

    private static final class MutableBucket {
        private final String label;
        private long listenedSeconds;
        private final Set<String> listenerHashes;

        private MutableBucket(String label, long listenedSeconds, Set<String> listenerHashes) {
            this.label = label;
            this.listenedSeconds = listenedSeconds;
            this.listenerHashes = listenerHashes;
        }

        private BucketData toView() {
            return new BucketData(label, listenedSeconds, listenerHashes.size());
        }
    }

    private String sessionKey(String guildId, String userId) {
        return guildId + ":" + userId;
    }
}
