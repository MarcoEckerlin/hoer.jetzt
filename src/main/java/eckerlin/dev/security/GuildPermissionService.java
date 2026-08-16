package eckerlin.dev.security;

import eckerlin.dev.utils.Alert;
import eckerlin.dev.utils.Config;
import eckerlin.dev.utils.DB;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ordnet Discord-Rollen die Rechte aus {@link GuildPermission} zu — pro Server.
 *
 * <p>Zwei Dinge sind hier wichtig:
 *
 * <ol>
 *   <li><strong>Rueckwaertskompatibel.</strong> Solange fuer einen Server keine
 *       Matrix hinterlegt ist, gilt die alte Regel: wer auf Discord
 *       {@code ADMINISTRATOR} oder {@code MANAGE_GUILD} hat, darf alles. Ohne
 *       diesen Rueckfall waeren nach dem Update alle Server ausgesperrt.</li>
 *   <li><strong>Gecacht.</strong> Die Datenbank liegt auf einem anderen Host;
 *       ein Roundtrip pro Slash-Command hat frueher das Drei-Sekunden-Fenster
 *       von Discord gerissen. Die Matrix aendert sich selten, also 60 Sekunden
 *       Cache pro Server.</li>
 * </ol>
 *
 * <p>Diese Klasse haengt bewusst an keinem anderen Spring-Service, damit sie
 * auch aus Listenern und dem AudioService heraus benutzbar ist, ohne einen
 * Abhaengigkeitszyklus zu erzeugen.
 */
@Service
public class GuildPermissionService {

    private static final long ADMINISTRATOR = 0x8L;
    private static final long MANAGE_GUILD = 0x20L;
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final int botId = Config.config.optInt("bot_id", 1);
    private final Map<String, CachedMatrix> cache = new ConcurrentHashMap<>();

    /** Rechte eines Mitglieds auf einem Server, wie JDA sie sieht. */
    public Set<GuildPermission> resolve(Guild guild, Member member) {
        if (guild == null || member == null) {
            return EnumSet.noneOf(GuildPermission.class);
        }

        List<String> roleIds = member.getRoles().stream().map(Role::getId).toList();
        boolean owner = member.isOwner();
        long permissions = Permission.getRaw(member.getPermissions());
        return resolve(guild.getId(), roleIds, owner, permissions);
    }

    /**
     * Rechte anhand roher Angaben — so nutzbar aus der Weboberflaeche, wo nur die
     * OAuth-Daten vorliegen und kein JDA-Member.
     *
     * @param roleIds Rollen-IDs ohne die @everyone-Rolle; diese wird ergaenzt,
     *                da ihre ID auf Discord der Server-ID entspricht
     */
    public Set<GuildPermission> resolve(String guildId, Collection<String> roleIds, boolean guildOwner, long discordPermissions) {
        // Serverinhaber und Discord-Administratoren behalten immer vollen
        // Zugriff. Alles andere waere ein Weg, sich selbst auszusperren.
        if (guildOwner || (discordPermissions & ADMINISTRATOR) == ADMINISTRATOR) {
            return EnumSet.allOf(GuildPermission.class);
        }

        Map<String, Set<GuildPermission>> matrix = matrix(guildId);
        if (matrix.isEmpty()) {
            // Kein eigenes Rechtemodell gepflegt: alte Regel weiterverwenden.
            return (discordPermissions & MANAGE_GUILD) == MANAGE_GUILD
                    ? EnumSet.allOf(GuildPermission.class)
                    : EnumSet.noneOf(GuildPermission.class);
        }

        Set<GuildPermission> granted = EnumSet.noneOf(GuildPermission.class);
        Set<GuildPermission> everyone = matrix.get(guildId);
        if (everyone != null) {
            granted.addAll(everyone);
        }
        if (roleIds != null) {
            for (String roleId : roleIds) {
                Set<GuildPermission> permissions = matrix.get(roleId);
                if (permissions != null) {
                    granted.addAll(permissions);
                }
            }
        }

        // Wer laut Discord den Server verwalten darf, behaelt zusaetzlich das
        // Recht, die Matrix selbst zu aendern - sonst koennte eine falsch
        // gesetzte Matrix niemanden mehr hereinlassen.
        if ((discordPermissions & MANAGE_GUILD) == MANAGE_GUILD) {
            granted.add(GuildPermission.WEB_ACCESS);
            granted.add(GuildPermission.PERMISSION_MANAGE);
        }

        return granted;
    }

    public boolean has(Guild guild, Member member, GuildPermission permission) {
        return resolve(guild, member).contains(permission);
    }

    public boolean has(String guildId, Collection<String> roleIds, boolean guildOwner, long discordPermissions, GuildPermission permission) {
        return resolve(guildId, roleIds, guildOwner, discordPermissions).contains(permission);
    }

    /** Die gespeicherte Matrix: Rollen-ID -&gt; Rechte. Leer, wenn nichts gepflegt ist. */
    public Map<String, Set<GuildPermission>> matrix(String guildId) {
        if (guildId == null || guildId.isBlank()) {
            return Map.of();
        }

        CachedMatrix cached = cache.get(guildId);
        if (cached != null && Instant.now().isBefore(cached.validUntil())) {
            return cached.matrix();
        }

        Map<String, Set<GuildPermission>> loaded = new LinkedHashMap<>();
        if (DB.isAvailable()) {
            String sql = """
                    SELECT role_id, permission
                    FROM guild_role_permissions
                    WHERE bot_id = ? AND guild_id = ?
                    """;
            try (Connection connection = DB.connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, botId);
                statement.setString(2, guildId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        String roleId = resultSet.getString("role_id");
                        GuildPermission.fromKey(resultSet.getString("permission")).ifPresent(permission ->
                                loaded.computeIfAbsent(roleId, key -> EnumSet.noneOf(GuildPermission.class)).add(permission));
                    }
                }
            } catch (SQLException exception) {
                Alert.send("WARN", "SECURITY", "Rollenrechte konnten nicht geladen werden: " + exception.getMessage());
                // Lieber den alten Stand kurz weiterverwenden als alle aussperren.
                if (cached != null) {
                    cache.put(guildId, new CachedMatrix(cached.matrix(), Instant.now().plusSeconds(10)));
                    return cached.matrix();
                }
            }
        }

        cache.put(guildId, new CachedMatrix(loaded, Instant.now().plus(CACHE_TTL)));
        return loaded;
    }

    /** Ersetzt die komplette Matrix eines Servers in einer Transaktion. */
    public void saveMatrix(String guildId, Map<String, Set<GuildPermission>> matrix) throws SQLException {
        if (guildId == null || guildId.isBlank()) {
            throw new IllegalArgumentException("Es wurde kein Server angegeben.");
        }

        try (Connection connection = DB.connection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM guild_role_permissions WHERE bot_id = ? AND guild_id = ?")) {
                    delete.setInt(1, botId);
                    delete.setString(2, guildId);
                    delete.executeUpdate();
                }

                if (matrix != null && !matrix.isEmpty()) {
                    // INSERT IGNORE gibt es unter PostgreSQL nicht - dort faengt
                    // ON CONFLICT DO NOTHING doppelte Zeilen ab. Ohne das
                    // scheiterte der Batch und die Rechtematrix liess sich nicht
                    // speichern.
                    String insert = """
                            INSERT INTO guild_role_permissions (bot_id, guild_id, role_id, permission)
                            VALUES (?,?,?,?)
                            ON CONFLICT (bot_id, guild_id, role_id, permission) DO NOTHING
                            """;
                    try (PreparedStatement statement = connection.prepareStatement(insert)) {
                        for (Map.Entry<String, Set<GuildPermission>> entry : matrix.entrySet()) {
                            String roleId = entry.getKey() == null ? "" : entry.getKey().trim();
                            if (roleId.isBlank() || entry.getValue() == null) {
                                continue;
                            }
                            for (GuildPermission permission : entry.getValue()) {
                                statement.setInt(1, botId);
                                statement.setString(2, guildId);
                                statement.setString(3, roleId);
                                statement.setString(4, permission.name());
                                statement.addBatch();
                            }
                        }
                        statement.executeBatch();
                    }
                }

                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }

        cache.remove(guildId);
    }

    public void invalidate(String guildId) {
        if (guildId == null) {
            cache.clear();
        } else {
            cache.remove(guildId);
        }
    }

    /** Alle Rechte als Beschreibung fuer die Oberflaeche. */
    public Set<String> permissionKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (GuildPermission permission : GuildPermission.values()) {
            keys.add(permission.name());
        }
        return keys;
    }

    private record CachedMatrix(Map<String, Set<GuildPermission>> matrix, Instant validUntil) {
    }
}
