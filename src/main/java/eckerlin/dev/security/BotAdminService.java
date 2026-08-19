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
                VALUES (?,?,?,?,?,true)
                ON CONFLICT (bot_id, user_id) DO UPDATE SET
                    role = 'OWNER',
                    application_owner = true,
                    -- Einen vorhandenen Namen nicht durch einen leeren ersetzen.
                    -- MariaDBs IF() gibt es hier nicht; NULLIF plus COALESCE
                    -- sagt dasselbe und ist Standard-SQL.
                    display_name = COALESCE(NULLIF(EXCLUDED.display_name, ''), bot_admins.display_name),
                    updated_at = current_timestamp
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
     * <p>OWNER darf hier vergeben werden - wer den Aufruf zulaesst, entscheidet
     * der Controller. Frueher wies diese Methode die Stufe rundweg zurueck, mit
     * der Begruendung, sie komme aus der Discord-Anwendung. Das stimmt fuer den
     * <em>ersten</em> Owner, nicht fuer jeden weiteren: Discord kennt genau
     * einen Anwendungsinhaber, ein Betrieb aber durchaus zwei Verantwortliche.
     * Der einzige verbleibende Weg war {@code HJ_BOT_ADMIN_IDS} - also SSH auf
     * beide Knoten und ein Neustart, um eine Person einzutragen.</p>
     *
     * <p>Die beiden Schutzregeln bleiben und tragen die Sicherheit allein:
     * der Discord-Anwendungsinhaber laesst sich nicht herabstufen, und niemand
     * kann sich die eigene Owner-Stufe nehmen. Damit ist ein Aussperren durch
     * einen Fehlgriff weiterhin ausgeschlossen.</p>
     *
     * @throws IllegalArgumentException bei ungueltiger Kennung, fehlender Stufe,
     *         Herabstufung des Anwendungsinhabers oder Selbst-Herabstufung
     */
    public void save(String userId, BotAdminRole role, String displayName, String actorUserId) throws SQLException {
        String normalized = userId == null ? "" : userId.trim();
        if (!normalized.matches("\\d{5,32}")) {
            throw new IllegalArgumentException("Bitte eine gültige Discord-Benutzer-ID angeben (nur Ziffern).");
        }
        if (role == null) {
            throw new IllegalArgumentException("Es wurde keine Stufe angegeben.");
        }

        BotAdmin existing = load().get(normalized);
        if (existing != null && existing.applicationOwner() && role != BotAdminRole.OWNER) {
            throw new IllegalArgumentException("Der Botinhaber kann nicht herabgestuft werden.");
        }

        // Sich selbst die Owner-Stufe nehmen ist kein sinnvoller Vorgang, wohl
        // aber ein moeglicher Fehlgriff - und danach kommt man an die
        // Verwaltung nicht mehr heran. Ein zweiter Owner kann es weiterhin.
        boolean selbst = actorUserId != null && actorUserId.trim().equals(normalized);
        if (selbst && existing != null && existing.role() == BotAdminRole.OWNER && role != BotAdminRole.OWNER) {
            throw new IllegalArgumentException("Die eigene Owner-Stufe kann nicht abgegeben werden. "
                    + "Ein anderer Owner kann das tun.");
        }

        String sql = """
                INSERT INTO bot_admins (bot_id, user_id, role, display_name, added_by, application_owner)
                VALUES (?,?,?,?,?,false)
                ON CONFLICT (bot_id, user_id) DO UPDATE SET
                    role = EXCLUDED.role,
                    display_name = EXCLUDED.display_name,
                    updated_at = current_timestamp
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

    /**
     * Traegt einen Notfall-Zugang als {@link BotAdminRole#OWNER} ein.
     *
     * <p>Getrennt von {@link #save}, und zwar aus einem konkreten Grund:
     * {@code save} weist die Stufe OWNER ausdruecklich zurueck, weil sie
     * normalerweise aus der Discord-Anwendung kommt und nicht von Hand vergeben
     * werden soll. Das ist richtig - fuer die Oberflaeche. Der Weg ueber
     * {@code HJ_BOT_ADMIN_IDS} ist aber genau der Fall, in dem die Uebernahme
     * aus Discord <em>nicht</em> greift: bei einer Team-Anwendung zaehlt nur
     * der Team-Eigentuemer.</p>
     *
     * <p>Wer die Umgebungsvariable setzen kann, hat Zugriff auf den Server -
     * eine zusaetzliche Huerde waere hier Theater.</p>
     *
     * <p>{@code application_owner} bleibt {@code false}: die Person ist
     * Eigentuemer im Sinne der Rechte, aber nicht die Person, der die Discord-
     * Anwendung gehoert. Sonst waere sie nachtraeglich nicht mehr entfernbar.</p>
     */
    public void notfallOwner(String userId, String displayName) throws SQLException {
        String normalized = userId == null ? "" : userId.trim();
        if (!normalized.matches("\\d{5,32}")) {
            throw new IllegalArgumentException("Ungueltige Discord-Benutzer-ID in HJ_BOT_ADMIN_IDS: " + normalized);
        }

        String sql = """
                INSERT INTO bot_admins (bot_id, user_id, role, display_name, added_by, application_owner)
                VALUES (?,?,?,?,?,false)
                ON CONFLICT (bot_id, user_id) DO UPDATE SET
                    role = EXCLUDED.role,
                    display_name = COALESCE(NULLIF(EXCLUDED.display_name, ''), bot_admins.display_name),
                    updated_at = current_timestamp
                """;

        try (Connection connection = DB.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, botId);
            statement.setString(2, normalized);
            statement.setString(3, BotAdminRole.OWNER.name());
            statement.setString(4, displayName == null ? "" : displayName.trim());
            statement.setString(5, "umgebung");
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

            // FIELD() gibt es nur in MariaDB/MySQL. Unter PostgreSQL scheiterte
            // diese Abfrage mit "function field(...) does not exist" - und weil
            // der Fehler unten nur zu einer WARN-Zeile fuehrt, blieb die Liste
            // leer und *niemand* war mehr Bot-Admin. Der Adminbereich antwortete
            // dann mit 403, auch ueber HJ_BOT_ADMIN_IDS: die Notfalltuer traegt
            // den Eintrag zwar ein, gelesen wird er aber ueber genau diese
            // Abfrage. CASE ist Standard-SQL und tut dasselbe.
            String sql = """
                    SELECT user_id, role, display_name, added_by, application_owner, created_at
                    FROM bot_admins
                    WHERE bot_id = ?
                    ORDER BY application_owner DESC,
                             CASE role WHEN 'OWNER' THEN 1 WHEN 'ADMIN' THEN 2 WHEN 'SUPPORT' THEN 3 ELSE 4 END,
                             created_at
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

            // INSERT IGNORE ist MySQL-Syntax; unter PostgreSQL ist das
            // Gegenstueck ON CONFLICT DO NOTHING. Ebenso muss der Wahrheitswert
            // false heissen, nicht 0.
            String insert = """
                    INSERT INTO bot_admins (bot_id, user_id, role, display_name, added_by, application_owner)
                    VALUES (?,?,?,'','migration',false)
                    ON CONFLICT (bot_id, user_id) DO NOTHING
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
