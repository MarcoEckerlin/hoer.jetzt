package eckerlin.dev.services;

import dev.arbjerg.lavalink.client.player.Track;
import eckerlin.dev.utils.Alert;
import eckerlin.dev.utils.DB;
import net.dv8tion.jda.api.entities.Guild;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class MusicTrackEventService {

    private static final java.util.Set<String> LEARNABLE_SOURCES = java.util.Set.of("manual", "playlist");

    private final AppConfigService configService;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();

    public MusicTrackEventService(AppConfigService configService) {
        this.configService = configService;
    }

    public void recordTrackStarted(Guild guild, Track track, String sourceName) {
        if (guild == null || track == null || track.getInfo() == null || track.getInfo().isStream() || !DB.isAvailable()) {
            return;
        }

        String normalizedSource = sourceName == null ? "" : sourceName.trim().toLowerCase();
        if (!LEARNABLE_SOURCES.contains(normalizedSource)) {
            return;
        }

        String title = safe(track.getInfo().getTitle(), 255);
        if (title.isBlank()) {
            return;
        }

        writer.execute(() -> {
            try (Connection connection = DB.connection();
                 PreparedStatement statement = connection.prepareStatement("""
                         INSERT INTO music_track_events (
                             bot_id,
                             guild_id,
                             title,
                             author,
                             uri,
                             identifier,
                             source_name,
                             duration_ms,
                             is_stream
                         ) VALUES (?,?,?,?,?,?,?,?,?)
                         """)) {
                statement.setInt(1, configService.getBotId());
                statement.setString(2, guild.getId());
                statement.setString(3, title);
                statement.setString(4, safe(track.getInfo().getAuthor(), 255));
                statement.setString(5, safe(track.getInfo().getUri(), 1024));
                statement.setString(6, safe(track.getInfo().getIdentifier(), 255));
                statement.setString(7, safe(normalizedSource, 80));
                statement.setLong(8, Math.max(0L, track.getInfo().getLength()));
                statement.setBoolean(9, false);
                statement.executeUpdate();
            } catch (SQLException exception) {
                Alert.send("WARN", "MUSIC-BRAIN", "Musik-Historie konnte nicht gespeichert werden: " + exception.getMessage());
            }
        });
    }

    private String safe(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }

        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
