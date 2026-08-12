package eckerlin.dev.services;

import eckerlin.dev.utils.Config;
import eckerlin.dev.utils.DB;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class TicketTranscriptService {

    private final int botId = Config.config.optInt("bot_id", 1);

    public long saveTranscript(
            String guildId,
            String channelId,
            String openerUserId,
            String openerDisplay,
            String ticketSubject,
            String transcriptText
    ) throws SQLException {
        String sql = """
                INSERT INTO ticket_transcripts (
                    bot_id,
                    guild_id,
                    channel_id,
                    opener_user_id,
                    opener_display,
                    ticket_subject,
                    transcript_text
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection connection = DB.connection();
             PreparedStatement statement = connection.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {
            statement.setInt(1, botId);
            statement.setString(2, blank(guildId));
            statement.setString(3, blank(channelId));
            statement.setString(4, blank(openerUserId));
            statement.setString(5, blank(openerDisplay));
            statement.setString(6, blank(ticketSubject));
            statement.setString(7, transcriptText == null ? "" : transcriptText);
            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }

        throw new SQLException("Transcript-ID konnte nicht erzeugt werden.");
    }

    public List<TicketTranscriptEntry> findByGuild(String guildId, int limit) {
        if (!DB.isAvailable()) {
            return List.of();
        }

        String sql = """
                SELECT id, opener_display, ticket_subject, created_at
                FROM ticket_transcripts
                WHERE bot_id = ? AND guild_id = ?
                ORDER BY id DESC
                LIMIT ?
                """;

        List<TicketTranscriptEntry> transcripts = new ArrayList<>();
        try (Connection connection = DB.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, botId);
            statement.setString(2, blank(guildId));
            statement.setInt(3, Math.max(1, Math.min(100, limit)));

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    transcripts.add(new TicketTranscriptEntry(
                            resultSet.getLong("id"),
                            text(resultSet, "opener_display"),
                            text(resultSet, "ticket_subject"),
                            resultSet.getTimestamp("created_at") == null ? "" : resultSet.getTimestamp("created_at").toInstant().toString()
                    ));
                }
            }
        } catch (SQLException ignored) {
            return List.of();
        }

        return transcripts;
    }

    public Optional<TicketTranscriptContent> findTranscript(String guildId, long transcriptId) {
        if (!DB.isAvailable()) {
            return Optional.empty();
        }

        String sql = """
                SELECT id, opener_display, ticket_subject, transcript_text, created_at
                FROM ticket_transcripts
                WHERE bot_id = ? AND guild_id = ? AND id = ?
                LIMIT 1
                """;

        try (Connection connection = DB.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, botId);
            statement.setString(2, blank(guildId));
            statement.setLong(3, transcriptId);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(new TicketTranscriptContent(
                            resultSet.getLong("id"),
                            text(resultSet, "opener_display"),
                            text(resultSet, "ticket_subject"),
                            text(resultSet, "transcript_text"),
                            resultSet.getTimestamp("created_at") == null ? "" : resultSet.getTimestamp("created_at").toInstant().toString()
                    ));
                }
            }
        } catch (SQLException ignored) {
            return Optional.empty();
        }

        return Optional.empty();
    }

    private String blank(String value) {
        return value == null ? "" : value.trim();
    }

    private String text(ResultSet resultSet, String column) throws SQLException {
        String value = resultSet.getString(column);
        return value == null ? "" : value;
    }

    public record TicketTranscriptEntry(
            long id,
            String openerDisplay,
            String ticketSubject,
            String createdAt
    ) {
    }

    public record TicketTranscriptContent(
            long id,
            String openerDisplay,
            String ticketSubject,
            String transcriptText,
            String createdAt
    ) {
    }
}
