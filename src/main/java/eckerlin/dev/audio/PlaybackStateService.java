package eckerlin.dev.audio;

import eckerlin.dev.utils.Config;
import eckerlin.dev.utils.DB;
import org.json.JSONArray;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Macht den Wiedergabezustand neustartfest.
 *
 * <p>Zwei getrennte Dinge, die zusammen erst den Nutzen ergeben:</p>
 *
 * <ol>
 *   <li><b>Session-ID je Audio-Knoten.</b> Lavalink haelt die laufenden Player
 *       nach einem Verbindungsabbruch noch eine Weile bereit und gibt sie nur
 *       an <em>dieselbe</em> Session zurueck. Ein neu gestarteter Bot bekommt
 *       normalerweise eine neue Session - die alten Player laufen ins Timeout
 *       und die Musik verstummt. Mit gespeicherter Session-ID meldet sich der
 *       Bot als derselbe an und uebernimmt die laufende Wiedergabe nahtlos.</li>
 *   <li><b>Warteschlange.</b> Die lag bisher nur im Arbeitsspeicher. Jeder
 *       Neustart hat sie verworfen - auch ohne Absturz, bei jedem Update.</li>
 * </ol>
 *
 * <p>Gespeichert wird der <em>kodierte</em> Track ({@code encoded}), nicht Titel
 * oder URL. Das ist genau die Zeichenkette, die Lavalink wieder zu einem Track
 * macht - ohne erneute Suche, ohne dass sich das Ergebnis unterwegs aendert.</p>
 */
@Service
public class PlaybackStateService {

    private final int botId = Config.config.optInt("bot_id", 1);

    // ---------------------------------------------------------------- Session

    /** Zuletzt bekannte Session-ID eines Knotens, oder {@code null}. */
    public String sessionIdOf(String nodeName) {
        try (Connection connection = DB.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT session_id FROM lavalink_sessions WHERE bot_id = ? AND node_name = ?")) {
            statement.setInt(1, botId);
            statement.setString(2, nodeName);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    String wert = resultSet.getString("session_id");
                    return wert == null || wert.isBlank() ? null : wert;
                }
            }
        } catch (SQLException exception) {
            // Ohne gespeicherte Session startet der Knoten eben frisch - das ist
            // der alte Zustand, kein Grund den Start abzubrechen.
        }
        return null;
    }

    public void rememberSession(String nodeName, String sessionId) {
        if (nodeName == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        try (Connection connection = DB.connection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO lavalink_sessions (bot_id, node_name, session_id)
                     VALUES (?, ?, ?)
                     ON DUPLICATE KEY UPDATE session_id = VALUES(session_id)
                     """)) {
            statement.setInt(1, botId);
            statement.setString(2, nodeName);
            statement.setString(3, sessionId);
            statement.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    // ---------------------------------------------------------------- Wiedergabe

    /**
     * Momentaufnahme eines Servers.
     *
     * @param currentEncoded laufender Track, kodiert - {@code null} wenn nichts laeuft
     * @param queueEncoded   Warteschlange in Reihenfolge, kodiert
     */
    public record Snapshot(
            String guildId,
            String voiceChannelId,
            String currentEncoded,
            long positionMs,
            List<String> queueEncoded,
            int volume,
            boolean repeatEnabled,
            boolean bassBoostEnabled,
            boolean smartRadioEnabled,
            String radioName,
            long updatedAtMs
    ) {
    }

    public void save(Snapshot snapshot) {
        if (snapshot == null || snapshot.guildId() == null) {
            return;
        }
        try (Connection connection = DB.connection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO guild_playback_state
                         (bot_id, guild_id, voice_channel_id, current_encoded, position_ms,
                          queue_encoded, volume, repeat_enabled, bass_enabled, smart_radio, radio_name)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     ON DUPLICATE KEY UPDATE
                         voice_channel_id = VALUES(voice_channel_id),
                         current_encoded  = VALUES(current_encoded),
                         position_ms      = VALUES(position_ms),
                         queue_encoded    = VALUES(queue_encoded),
                         volume           = VALUES(volume),
                         repeat_enabled   = VALUES(repeat_enabled),
                         bass_enabled     = VALUES(bass_enabled),
                         smart_radio      = VALUES(smart_radio),
                         radio_name       = VALUES(radio_name)
                     """)) {
            statement.setInt(1, botId);
            statement.setString(2, snapshot.guildId());
            statement.setString(3, snapshot.voiceChannelId());
            statement.setString(4, snapshot.currentEncoded());
            statement.setLong(5, Math.max(0L, snapshot.positionMs()));
            statement.setString(6, new JSONArray(
                    snapshot.queueEncoded() == null ? List.of() : snapshot.queueEncoded()).toString());
            statement.setInt(7, snapshot.volume());
            statement.setBoolean(8, snapshot.repeatEnabled());
            statement.setBoolean(9, snapshot.bassBoostEnabled());
            statement.setBoolean(10, snapshot.smartRadioEnabled());
            statement.setString(11, snapshot.radioName());
            statement.executeUpdate();
        } catch (SQLException ignored) {
            // Ein verlorener Schnappschuss ist aergerlich, aber kein Grund, die
            // laufende Wiedergabe mit einer Ausnahme zu stoeren.
        }
    }

    /** Entfernt den Zustand - wenn die Wiedergabe regulaer endet. */
    public void clear(String guildId) {
        try (Connection connection = DB.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM guild_playback_state WHERE bot_id = ? AND guild_id = ?")) {
            statement.setInt(1, botId);
            statement.setString(2, guildId);
            statement.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    /**
     * Alle gespeicherten Zustaende, die juenger sind als das Fenster.
     *
     * <p>Das Fenster verhindert, dass der Bot nach stundenlanger Auszeit
     * ploetzlich in einen Sprachkanal zurueckkehrt und Musik startet, die
     * niemand mehr erwartet.</p>
     */
    public Map<String, Snapshot> loadRecent(long maxAgeSeconds) {
        Map<String, Snapshot> ergebnis = new LinkedHashMap<>();
        try (Connection connection = DB.connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT guild_id, voice_channel_id, current_encoded, position_ms, queue_encoded,
                            volume, repeat_enabled, bass_enabled, smart_radio, radio_name,
                            UNIX_TIMESTAMP(updated_at) * 1000 AS updated_ms
                     FROM guild_playback_state
                     WHERE bot_id = ? AND updated_at >= (NOW() - INTERVAL ? SECOND)
                     """)) {
            statement.setInt(1, botId);
            statement.setLong(2, Math.max(1L, maxAgeSeconds));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    List<String> queue = new ArrayList<>();
                    String roh = resultSet.getString("queue_encoded");
                    if (roh != null && !roh.isBlank()) {
                        JSONArray liste = new JSONArray(roh);
                        for (int i = 0; i < liste.length(); i++) {
                            String eintrag = liste.optString(i, null);
                            if (eintrag != null && !eintrag.isBlank()) {
                                queue.add(eintrag);
                            }
                        }
                    }
                    String guildId = resultSet.getString("guild_id");
                    ergebnis.put(guildId, new Snapshot(
                            guildId,
                            resultSet.getString("voice_channel_id"),
                            resultSet.getString("current_encoded"),
                            resultSet.getLong("position_ms"),
                            queue,
                            resultSet.getInt("volume"),
                            resultSet.getBoolean("repeat_enabled"),
                            resultSet.getBoolean("bass_enabled"),
                            resultSet.getBoolean("smart_radio"),
                            resultSet.getString("radio_name"),
                            resultSet.getLong("updated_ms")
                    ));
                }
            }
        } catch (SQLException ignored) {
        }
        return ergebnis;
    }
}
