package eckerlin.dev.security;

import eckerlin.dev.utils.Alert;
import eckerlin.dev.utils.Config;
import eckerlin.dev.utils.DB;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Verwaltung der instanzweiten Bot-Administratoren.
 *
 * <p><strong>Bewusst ohne Spring-Abhaengigkeiten.</strong> Der naheliegende Weg
 * waere gewesen, hier {@code DiscordApplicationOwnerService} einzubinden, um den
 * Botinhaber direkt bei Discord zu erfragen. Das haette aber genau den Zyklus
 * erzeugt, der in der Projektdokumentation als Stolperstein steht:
 * {@code DiscordBotService} -&gt; {@code SlashCommandListener} -&gt; diese Klasse
 * -&gt; {@code DiscordApplicationOwnerService} -&gt; {@code DiscordBotService}.
 * Stattdessen wird der Botinhaber aus der Weboberflaeche heraus einmalig ueber
 * {@link #syncApplicationOwner(String, String)} in die Tabelle geschrieben und
 * hier nur noch gelesen. Nebeneffekt: kein Discord-API-Aufruf pro Rechtepruefung.
 */
@Service
public class BotAdminService {

    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private final int botId = Config.config.optInt("bot_id", 1);
    private volatile Map<String, BotAdmin> cache;
    private volatile Instant cacheValidUntil = Instant.EPOCH;
    private volatile boolean legacyImportDone;

    /** Rolle des Nutzers, oder leer wenn er kein Bot-Admin ist. */
    public Optional<BotAdminRole> roleOf(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(load().get(userId.trim())).map(BotAdmin::role);
    }

    public boolean isBotAdmin(String userId) {
        return roleOf(userId).isPresent();
    }

    /** Prueft auf eine Mindeststufe, etwa {@code BotAdminRole.ADMIN} fuer schreibende Aktionen. */
    public boolean hasAtLeast(String userId, BotAdminRole required) {
        return roleOf(userId).map(role -> role.atLeast(required)).orElse(false);
    }

    public List<BotAdmin> list() {
        return new ArrayList<>(load().values());
    }

    public Optional<BotAdmin> find(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(load().get(userId.trim()));
    }

    /**
     * Uebernimmt den Discord-Application-Owner als {@link BotAdminRole#OWNER}.
     * Idempotent; wird aus der Weboberflaeche bei jedem Admin-Zugriff aufgerufen
     * und ist nach dem ersten Durchlauf ein reiner Cache-Treffer.
     */
    public void syncApplicationOwner(String ownerId, String ownerName) {
        if (ownerId == null || ownerId.isBlank()) {
            return;
        }

        BotAdmin existing = load().get(ownerId.trim());
        if (existing != null && existing.role() == BotAdminRole.OWNER && existing.applicationOwner()) {
            return;
        }

        String sql = """
                INSERT INTO bot_admins (bot_id, user_id, role, display_name, added_by, application_owner)
                VALUES (?,?,?,?,?,1)
                ON DUPLICATE KEY UPDATE
                    role = 'OWNER',
                    application_owner = 1,
                    display_name = IF(VALUES(display_name) = '', display_name, VALUES(display_name))
                """;

        try (Connection connection = DB.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, botId);
            statement.setString(2, ownerId.trim());
            statement.setString(3, BotAdminRole.OWNER.name());
            statement.setString(4, ownerName == null ? "" : ownerName.trim());
            statement.setString(5, "system");
            statement.executeUpdate();
            invalidate();
        } catch (SQLException exception) {
            Alert.send("WARN", "SECURITY", "Botinhaber konnte nicht in bot_admins übernommen werden: " + exception.getMessage());
        }
    }

    /**
     * Legt einen Admin an oder aendert dessen Stufe.
     *
     * @throws IllegalArgumentException wenn versucht wird, {@link BotAdminRole#OWNER} zu vergeben
     */
    public void save(String userId, BotAdminRole role, String displayName, String actorUserId) throws SQLException {
        String normalized = userId == null ? "" : userId.trim();
        if (!normalized.matches("\\d{5,32}")) {
            throw new IllegalArgumentException("Bitte eine gültige Discord-Benutzer-ID angeben (nur Ziffern).");
        }
        if (role == null) {
            throw new IllegalArgumentException("Es wurde keine Stufe angegeben.");
        }
        if (role == BotAdminRole.OWNER) {
            throw new IllegalArgumentException("Die Stufe Owner wird automatisch aus der Discord-Anwendung übernommen und kann nicht vergeben werden.");
        }

        BotAdmin existing = load().get(normalized);
        if (existing != null && existing.applicationOwner()) {
            throw new IllegalArgumentException("Der Botinhaber kann nicht herabgestuft werden.");
        }

        String sql = """
                INSERT INTO bot_admins (bot_id, user_id, role, display_name, added_by, application_owner)
                VALUES (?,?,?,?,?,0)
                ON DUPLICATE KEY UPDATE role = VALUES(role), display_name = VALUES(display_name)
                """;

        try (Connection connection = DB.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, botId);
            statement.setString(2, normalized);
            statement.setString(3, role.name());
            statement.setString(4, displayName == null ? "" : displayName.trim());
            statement.setString(5, actorUserId == null ? "" : actorUserId.trim());
            statement.executeUpdate();
        }
        invalidate();
    }

    public void remove(String userId) throws SQLException {
        String normalized = userId == null ? "" : userId.trim();
        BotAdmin existing = load().get(normalized);
        if (existing == null) {
            return;
        }
        if (existing.applicationOwner()) {
            throw new IllegalArgumentException("Der Botinhaber kann nicht entfernt werden.");
        }

        try (Connection connection = DB.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM bot_admins WHERE bot_id = ? AND user_id = ?")) {
            statement.setInt(1, botId);
            statement.setString(2, normalized);
            statement.executeUpdate();
        }
        invalidate();
    }

    public void invalidate() {
        cacheValidUntil = Instant.EPOCH;
    }

    private Map<String, BotAdmin> load() {
        Map<String, BotAdmin> snapshot = cache;
        if (snapshot != null && Instant.now().isBefore(cacheValidUntil)) {
            return snapshot;
        }

        synchronized (this) {
            snapshot = cache;
            if (snapshot != null && Instant.now().isBefore(cacheValidUntil)) {
                return snapshot;
            }

            Map<String, BotAdmin> loaded = new LinkedHashMap<>();
            if (!DB.isAvailable()) {
                // Ohne Datenbank lieber niemanden als Admin fuehren, statt zu raten.
                cache = loaded;
                cacheValidUntil = Instant.now().plusSeconds(5);
                return loaded;
            }

            importLegacyAdminsOnce();

            String sql = """
                    SELECT user_id, role, display_name, added_by, application_owner, created_at
                    FROM bot_admins
                    WHERE bot_id = ?
                    ORDER BY application_owner DESC, FIELD(role,'OWNER','ADMIN','SUPPORT'), created_at
                    """;

            try (Connection connection = DB.connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, botId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        String userId = trim(resultSet.getString("user_id"));
                        BotAdminRole role = BotAdminRole.fromKey(resultSet.getString("role"))
                                .orElse(BotAdminRole.SUPPORT);
                        loaded.put(userId, new BotAdmin(
                                userId,
                                trim(resultSet.getString("display_name")),
                                role,
                                trim(resultSet.getString("added_by")),
                                iso(resultSet.getTimestamp("created_at")),
                                resultSet.getBoolean("application_owner")
                        ));
                    }
                }
            } catch (SQLException exception) {
                Alert.send("WARN", "SECURITY", "Bot-Admins konnten nicht geladen werden: " + exception.getMessage());
                // Bei einem Datenbankfehler den alten Stand behalten, statt alle
                // Admins auszusperren.
                if (cache != null) {
                    cacheValidUntil = Instant.now().plusSeconds(5);
                    return cache;
                }
            }

            cache = loaded;
            cacheValidUntil = Instant.now().plus(CACHE_TTL);
            return loaded;
        }
    }

    /**
     * Uebernimmt die alte Freitextliste {@code settings.admin_user_ids} einmalig
     * in die neue Tabelle, damit bestehende Installationen beim Update nicht
     * ploetzlich ohne Admins dastehen.
     */
    private void importLegacyAdminsOnce() {
        if (legacyImportDone) {
            return;
        }
        legacyImportDone = true;

        try (Connection connection = DB.connection()) {
            boolean empty;
            try (PreparedStatement count = connection.prepareStatement(
                    "SELECT COUNT(*) FROM bot_admins WHERE bot_id = ?")) {
                count.setInt(1, botId);
                try (ResultSet resultSet = count.executeQuery()) {
                    empty = resultSet.next() && resultSet.getInt(1) == 0;
                }
            }
            if (!empty) {
                return;
            }

            String legacy = "";
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT admin_user_ids FROM settings WHERE id = ? LIMIT 1")) {
                select.setInt(1, botId);
                try (ResultSet resultSet = select.executeQuery()) {
                    if (resultSet.next()) {
                        legacy = trim(resultSet.getString("admin_user_ids"));
                    }
                }
            }
            if (legacy.isBlank()) {
                return;
            }

            String insert = """
                    INSERT IGNORE INTO bot_admins (bot_id, user_id, role, display_name, added_by, application_owner)
                    VALUES (?,?,?,'','migration',0)
                    """;
            int imported = 0;
            try (PreparedStatement statement = connection.prepareStatement(insert)) {
                for (String raw : legacy.split("[,;\\s]+")) {
                    String candidate = raw.trim();
                    if (!candidate.matches("\\d{5,32}")) {
                        continue;
                    }
                    statement.setInt(1, botId);
                    statement.setString(2, candidate);
                    statement.setString(3, BotAdminRole.ADMIN.name());
                    statement.addBatch();
                    imported++;
                }
                if (imported > 0) {
                    statement.executeBatch();
                    Alert.send("INFO", "SECURITY", imported + " Admin(s) aus der alten Liste übernommen.");
                }
            }
        } catch (SQLException exception) {
            Alert.send("WARN", "SECURITY", "Alte Admin-Liste konnte nicht übernommen werden: " + exception.getMessage());
        }
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private String iso(Timestamp timestamp) {
        return timestamp == null ? "" : timestamp.toInstant().toString();
    }
}
