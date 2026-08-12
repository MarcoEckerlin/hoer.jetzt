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
import java.util.ArrayList;
import java.util.List;

/**
 * Protokolliert Aktionen der Bot-Verwaltung.
 *
 * <p>Sobald mehr als eine Person Admin ist, muss nachvollziehbar sein, wer eine
 * Freischaltung erteilt oder einen Server entfernt hat. Das Schreiben laeuft
 * bewusst "best effort": ein Fehler beim Protokollieren darf die eigentliche
 * Aktion nicht scheitern lassen.
 */
@Service
public class AdminAuditService {

    private final int botId = Config.config.optInt("bot_id", 1);

    public void record(String actorUserId, String actorName, String action, String targetType, String targetId, String details) {
        if (!DB.isAvailable()) {
            return;
        }

        String sql = """
                INSERT INTO admin_audit_log (bot_id, actor_user_id, actor_name, action, target_type, target_id, details)
                VALUES (?,?,?,?,?,?,?)
                """;

        try (Connection connection = DB.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, botId);
            statement.setString(2, cut(actorUserId, 32));
            statement.setString(3, cut(actorName, 160));
            statement.setString(4, cut(action, 80));
            statement.setString(5, cut(targetType, 40));
            statement.setString(6, cut(targetId, 64));
            statement.setString(7, cut(details, 2000));
            statement.executeUpdate();
        } catch (SQLException exception) {
            Alert.send("WARN", "SECURITY", "Audit-Eintrag konnte nicht geschrieben werden: " + exception.getMessage());
        }
    }

    public List<AuditEntry> list(int limit) {
        List<AuditEntry> entries = new ArrayList<>();
        if (!DB.isAvailable()) {
            return entries;
        }

        String sql = """
                SELECT id, actor_user_id, actor_name, action, target_type, target_id, details, created_at
                FROM admin_audit_log
                WHERE bot_id = ?
                ORDER BY id DESC
                LIMIT ?
                """;

        try (Connection connection = DB.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, botId);
            statement.setInt(2, Math.min(500, Math.max(1, limit)));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Timestamp createdAt = resultSet.getTimestamp("created_at");
                    entries.add(new AuditEntry(
                            resultSet.getLong("id"),
                            text(resultSet.getString("actor_user_id")),
                            text(resultSet.getString("actor_name")),
                            text(resultSet.getString("action")),
                            text(resultSet.getString("target_type")),
                            text(resultSet.getString("target_id")),
                            text(resultSet.getString("details")),
                            createdAt == null ? "" : createdAt.toInstant().toString()
                    ));
                }
            }
        } catch (SQLException exception) {
            Alert.send("WARN", "SECURITY", "Audit-Log konnte nicht gelesen werden: " + exception.getMessage());
        }
        return entries;
    }

    private String cut(String value, int max) {
        String trimmed = text(value);
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }
}
