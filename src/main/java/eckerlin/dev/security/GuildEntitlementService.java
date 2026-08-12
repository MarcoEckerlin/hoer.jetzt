package eckerlin.dev.security;

import eckerlin.dev.utils.Alert;
import eckerlin.dev.utils.Config;
import eckerlin.dev.utils.DB;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Freischaltung der kostspieligen Funktionen pro Discord-Server.
 *
 * <p>Standard ist <em>aus</em>. Wer nichts eingetragen hat, bekommt nichts —
 * das ist Absicht, damit ein neu hinzugefuegter Server nicht sofort Kosten
 * verursacht.
 *
 * <p>Wie {@link GuildPermissionService} bewusst ohne weitere Spring-Abhaengigkeiten,
 * damit die Pruefung auch aus {@code LlmService} und den Listenern heraus
 * moeglich ist.
 */
@Service
public class GuildEntitlementService {

    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final int botId = Config.config.optInt("bot_id", 1);
    private final Map<String, CachedEntitlements> cache = new ConcurrentHashMap<>();

    /** Ist die Funktion auf diesem Server freigeschaltet? Ohne Eintrag: nein. */
    public boolean isEnabled(String guildId, GuildFeature feature) {
        if (guildId == null || guildId.isBlank() || feature == null) {
            return false;
        }
        FeatureEntry entry = load(guildId).get(feature);
        return entry != null && entry.enabled();
    }

    /**
     * Prueft die Freischaltung und zaehlt bei Erfolg einen Aufruf auf das
     * Tageskontingent. Liefert einen Grund, wenn abgelehnt wird.
     *
     * <p>Die Zaehlung laeuft ueber ein {@code INSERT ... ON DUPLICATE KEY UPDATE},
     * ist also auch bei mehreren gleichzeitigen Anfragen korrekt.
     */
    public Decision tryConsume(String guildId, GuildFeature feature) {
        if (guildId == null || guildId.isBlank() || feature == null) {
            return Decision.denied("Diese Funktion ist hier nicht verfügbar.");
        }

        FeatureEntry entry = load(guildId).get(feature);
        if (entry == null || !entry.enabled()) {
            return Decision.denied("%s ist für diesen Server nicht freigeschaltet. Der Betreiber des Bots muss das freigeben."
                    .formatted(feature.label()));
        }

        if (entry.dailyLimit() <= 0) {
            return Decision.allow();
        }

        int used = incrementUsage(guildId, feature);
        if (used > entry.dailyLimit()) {
            return Decision.denied("Das Tageskontingent für %s ist aufgebraucht (%d Aufrufe). Morgen geht es weiter."
                    .formatted(feature.label(), entry.dailyLimit()));
        }
        return Decision.allow();
    }

    /** Alle Freischaltungen eines Servers, auch die nicht gesetzten. */
    public List<GuildEntitlement> list(String guildId) {
        Map<GuildFeature, FeatureEntry> stored = load(guildId);
        Map<GuildFeature, Integer> usage = usageToday(guildId);

        List<GuildEntitlement> result = new ArrayList<>();
        for (GuildFeature feature : GuildFeature.values()) {
            FeatureEntry entry = stored.get(feature);
            result.add(new GuildEntitlement(
                    guildId,
                    feature.name(),
                    feature.label(),
                    entry != null && entry.enabled(),
                    entry == null ? 0 : entry.dailyLimit(),
                    usage.getOrDefault(feature, 0),
                    entry == null ? "" : entry.note(),
                    entry == null ? "" : entry.grantedBy(),
                    entry == null ? "" : entry.updatedAt()
            ));
        }
        return result;
    }

    public void set(String guildId, GuildFeature feature, boolean enabled, int dailyLimit, String note, String actorUserId) throws SQLException {
        if (guildId == null || guildId.isBlank() || feature == null) {
            throw new IllegalArgumentException("Server oder Funktion fehlt.");
        }

        String sql = """
                INSERT INTO guild_entitlements (bot_id, guild_id, feature, enabled, daily_limit, note, granted_by)
                VALUES (?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE
                    enabled = VALUES(enabled),
                    daily_limit = VALUES(daily_limit),
                    note = VALUES(note),
                    granted_by = VALUES(granted_by)
                """;

        try (Connection connection = DB.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, botId);
            statement.setString(2, guildId.trim());
            statement.setString(3, feature.name());
            statement.setBoolean(4, enabled);
            statement.setInt(5, Math.max(0, dailyLimit));
            statement.setString(6, note == null ? "" : note.trim());
            statement.setString(7, actorUserId == null ? "" : actorUserId.trim());
            statement.executeUpdate();
        }

        cache.remove(guildId.trim());
    }

    public void invalidate(String guildId) {
        if (guildId == null) {
            cache.clear();
        } else {
            cache.remove(guildId);
        }
    }

    private Map<GuildFeature, FeatureEntry> load(String guildId) {
        String key = guildId.trim();
        CachedEntitlements cached = cache.get(key);
        if (cached != null && Instant.now().isBefore(cached.validUntil())) {
            return cached.entries();
        }

        Map<GuildFeature, FeatureEntry> loaded = new EnumMap<>(GuildFeature.class);
        if (DB.isAvailable()) {
            String sql = """
                    SELECT feature, enabled, daily_limit, note, granted_by, updated_at
                    FROM guild_entitlements
                    WHERE bot_id = ? AND guild_id = ?
                    """;
            try (Connection connection = DB.connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, botId);
                statement.setString(2, key);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        GuildFeature feature = GuildFeature.fromKey(resultSet.getString("feature")).orElse(null);
                        if (feature == null) {
                            continue;
                        }
                        Timestamp updatedAt = resultSet.getTimestamp("updated_at");
                        loaded.put(feature, new FeatureEntry(
                                resultSet.getBoolean("enabled"),
                                resultSet.getInt("daily_limit"),
                                text(resultSet.getString("note")),
                                text(resultSet.getString("granted_by")),
                                updatedAt == null ? "" : updatedAt.toInstant().toString()
                        ));
                    }
                }
            } catch (SQLException exception) {
                Alert.send("WARN", "SECURITY", "Freischaltungen konnten nicht geladen werden: " + exception.getMessage());
                // Im Zweifel nichts freischalten: der Fehlerfall darf keine
                // Kosten verursachen.
            }
        }

        cache.put(key, new CachedEntitlements(loaded, Instant.now().plus(CACHE_TTL)));
        return loaded;
    }

    private int incrementUsage(String guildId, GuildFeature feature) {
        String sql = """
                INSERT INTO guild_feature_usage (bot_id, guild_id, feature, usage_day, used_count)
                VALUES (?,?,?,?,1)
                ON DUPLICATE KEY UPDATE used_count = used_count + 1
                """;
        String read = """
                SELECT used_count FROM guild_feature_usage
                WHERE bot_id = ? AND guild_id = ? AND feature = ? AND usage_day = ?
                """;

        Date today = Date.valueOf(LocalDate.now());
        try (Connection connection = DB.connection()) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, botId);
                statement.setString(2, guildId.trim());
                statement.setString(3, feature.name());
                statement.setDate(4, today);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(read)) {
                statement.setInt(1, botId);
                statement.setString(2, guildId.trim());
                statement.setString(3, feature.name());
                statement.setDate(4, today);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return resultSet.getInt(1);
                    }
                }
            }
        } catch (SQLException exception) {
            Alert.send("WARN", "SECURITY", "Nutzungszähler konnte nicht fortgeschrieben werden: " + exception.getMessage());
        }
        return 0;
    }

    private Map<GuildFeature, Integer> usageToday(String guildId) {
        Map<GuildFeature, Integer> usage = new EnumMap<>(GuildFeature.class);
        if (guildId == null || guildId.isBlank() || !DB.isAvailable()) {
            return usage;
        }

        String sql = """
                SELECT feature, used_count FROM guild_feature_usage
                WHERE bot_id = ? AND guild_id = ? AND usage_day = ?
                """;
        try (Connection connection = DB.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, botId);
            statement.setString(2, guildId.trim());
            statement.setDate(3, Date.valueOf(LocalDate.now()));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    GuildFeature feature = GuildFeature.fromKey(resultSet.getString("feature")).orElse(null);
                    if (feature != null) {
                        usage.put(feature, resultSet.getInt("used_count"));
                    }
                }
            }
        } catch (SQLException ignored) {
            // Der Zaehler ist nur Anzeige - ein Fehler darf hier nichts blockieren.
        }
        return usage;
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    /** Ergebnis einer Freischaltungspruefung. */
    public record Decision(boolean allowed, String reason) {
        public static Decision allow() {
            return new Decision(true, "");
        }

        public static Decision denied(String reason) {
            return new Decision(false, reason);
        }
    }

    private record FeatureEntry(boolean enabled, int dailyLimit, String note, String grantedBy, String updatedAt) {
    }

    private record CachedEntitlements(Map<GuildFeature, FeatureEntry> entries, Instant validUntil) {
    }
}
