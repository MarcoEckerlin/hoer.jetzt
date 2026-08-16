package eckerlin.dev.audio;

import eckerlin.dev.utils.Alert;
import eckerlin.dev.utils.Config;
import eckerlin.dev.utils.DB;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

/**
 * Ein Knoten meldet sich selbst an.
 *
 * <p>Bisher war ein neuer Audio-Knoten zweigeteilt: erst {@code install.sh} auf
 * dem Knoten-Host, dann von Hand ein Eintrag im Adminbereich. Wer den zweiten
 * Schritt vergass, hatte einen laufenden Lavalink, den der Bot nicht kannte -
 * ohne jede Fehlermeldung, denn aus Sicht des Bots existierte er nicht.</p>
 *
 * <p>Jetzt traegt sich der Knoten beim Start selbst ein. Der Abgleich laeuft
 * ueber den Namen: derselbe Name aktualisiert die vorhandene Zeile, statt eine
 * zweite anzulegen. Das ist entscheidend, weil ein neu aufgesetzter Server eine
 * andere IP hat - ohne Abgleich waere nach jedem Neuaufsetzen ein Karteileichen-
 * eintrag mehr in der Tabelle, und der Lader verwirft bei doppelten Namen
 * stillschweigend den zweiten.</p>
 *
 * <h2>Warum kein eindeutiger Index</h2>
 *
 * <p>Naheliegend waere UNIQUE (bot_id, node_name) plus ON CONFLICT gewesen. In
 * bestehenden Installationen koennen aber bereits doppelte Namen liegen; der
 * Index liesse sich dort nicht anlegen und der Schemalauf wuerde den Start
 * abbrechen. Deshalb wird hier gelesen und dann geschrieben - in einer
 * Transaktion, damit zwei gleichzeitig startende Knoten sich nicht in die
 * Quere kommen.</p>
 */
@Service
public class KnotenRegistrierungService {

    private final int botId = Config.config.optInt("bot_id", 1);

    /** Was ein Knoten von sich erzaehlt. */
    public record Anmeldung(
            String name,
            String adresse,
            String passwort,
            String stufe,
            String agentUrl,
            Long hetznerId,
            boolean vomAutoscaling
    ) {
    }

    public enum Ergebnis { NEU, AKTUALISIERT }

    /**
     * Traegt den Knoten ein oder bringt eine vorhandene Zeile auf den neuen
     * Stand. Der Aufrufer muss den Absender bereits geprueft haben.
     */
    public Ergebnis anmelden(Anmeldung anmeldung) throws SQLException {
        String name = sauber(anmeldung.name());
        if (!name.matches("[A-Za-z0-9._-]{3,64}")) {
            throw new IllegalArgumentException(
                    "Ungueltiger Knotenname: nur Buchstaben, Ziffern, Punkt, Strich und Unterstrich, 3 bis 64 Zeichen.");
        }

        String adresse = sauber(anmeldung.adresse());
        if (adresse.isBlank()) {
            throw new IllegalArgumentException("Der Knoten hat keine Adresse mitgeschickt.");
        }

        String stufe = "premium".equalsIgnoreCase(sauber(anmeldung.stufe())) ? "premium" : "free";
        String herkunft = anmeldung.vomAutoscaling() ? "auto" : "selbst";

        try (Connection connection = DB.connection()) {
            boolean vorherigesAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                Long vorhanden = idVonName(connection, name);
                Ergebnis ergebnis = vorhanden == null ? Ergebnis.NEU : Ergebnis.AKTUALISIERT;

                if (vorhanden == null) {
                    einfuegen(connection, name, adresse, anmeldung, stufe, herkunft);
                } else {
                    aktualisieren(connection, vorhanden, adresse, anmeldung, stufe, herkunft);
                }

                connection.commit();
                Alert.send("INFO", "AUDIO", "Knoten %s hat sich angemeldet (%s, %s).".formatted(
                        name, adresse, ergebnis == Ergebnis.NEU ? "neu" : "aktualisiert"));
                return ergebnis;
            } catch (SQLException | RuntimeException fehler) {
                connection.rollback();
                throw fehler;
            } finally {
                connection.setAutoCommit(vorherigesAutoCommit);
            }
        }
    }

    /**
     * Meldet den Knoten ab. Faehrt ein Knoten geordnet herunter, soll der Bot
     * ihn nicht weiter anfragen.
     *
     * <p>Bewusst nur {@code enabled = false} statt DELETE: ein Neustart des
     * Knotens ist der Normalfall, und eine geloeschte Zeile haette Stufe und
     * Obergrenze mitgenommen. Beim naechsten Anmelden wird die Zeile wieder
     * scharf geschaltet.</p>
     */
    public boolean abmelden(String name) throws SQLException {
        String sauber = sauber(name);
        if (sauber.isBlank()) {
            return false;
        }

        try (Connection connection = DB.connection();
             PreparedStatement anweisung = connection.prepareStatement("""
                     UPDATE deployment_lavalink_nodes
                        SET enabled = false, updated_at = current_timestamp
                      WHERE bot_id = ? AND node_name = ? AND herkunft <> 'manuell'
                     """)) {
            anweisung.setInt(1, botId);
            anweisung.setString(2, sauber);
            return anweisung.executeUpdate() > 0;
        }
    }

    /** Lebenszeichen ohne vollstaendige Anmeldung. */
    public void gesehen(String name) {
        try (Connection connection = DB.connection();
             PreparedStatement anweisung = connection.prepareStatement(
                     "UPDATE deployment_lavalink_nodes SET zuletzt_gesehen = ? WHERE bot_id = ? AND node_name = ?")) {
            anweisung.setTimestamp(1, java.sql.Timestamp.from(Instant.now()));
            anweisung.setInt(2, botId);
            anweisung.setString(3, sauber(name));
            anweisung.executeUpdate();
        } catch (SQLException fehler) {
            // Ein verpasstes Lebenszeichen ist kein Grund, die Anmeldung
            // scheitern zu lassen - der naechste Takt holt es nach.
        }
    }

    public Optional<String> agentUrl(String name) {
        try (Connection connection = DB.connection();
             PreparedStatement anweisung = connection.prepareStatement(
                     "SELECT agent_url FROM deployment_lavalink_nodes WHERE bot_id = ? AND node_name = ? LIMIT 1")) {
            anweisung.setInt(1, botId);
            anweisung.setString(2, sauber(name));
            try (ResultSet ergebnis = anweisung.executeQuery()) {
                if (ergebnis.next()) {
                    String url = ergebnis.getString("agent_url");
                    return url == null || url.isBlank() ? Optional.empty() : Optional.of(url.trim());
                }
            }
        } catch (SQLException fehler) {
            Alert.send("WARN", "AUDIO", "Agent-Adresse von " + name + " nicht lesbar: " + fehler.getMessage());
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ intern

    private Long idVonName(Connection connection, String name) throws SQLException {
        try (PreparedStatement anweisung = connection.prepareStatement(
                "SELECT id FROM deployment_lavalink_nodes WHERE bot_id = ? AND node_name = ? ORDER BY id LIMIT 1")) {
            anweisung.setInt(1, botId);
            anweisung.setString(2, name);
            try (ResultSet ergebnis = anweisung.executeQuery()) {
                return ergebnis.next() ? ergebnis.getLong("id") : null;
            }
        }
    }

    private void einfuegen(
            Connection connection,
            String name,
            String adresse,
            Anmeldung anmeldung,
            String stufe,
            String herkunft
    ) throws SQLException {
        try (PreparedStatement anweisung = connection.prepareStatement("""
                INSERT INTO deployment_lavalink_nodes
                    (bot_id, deployment_key, node_name, server_uri, password, tier,
                     enabled, herkunft, agent_url, hetzner_id, zuletzt_gesehen)
                VALUES (?, ?, ?, ?, ?, ?, true, ?, ?, ?, ?)
                """)) {
            anweisung.setInt(1, botId);
            anweisung.setString(2, "standard");
            anweisung.setString(3, name);
            anweisung.setString(4, adresse);
            anweisung.setString(5, sauber(anmeldung.passwort()));
            anweisung.setString(6, stufe);
            anweisung.setString(7, herkunft);
            anweisung.setString(8, sauber(anmeldung.agentUrl()));
            setzeLangOderNull(anweisung, 9, anmeldung.hetznerId());
            anweisung.setTimestamp(10, java.sql.Timestamp.from(Instant.now()));
            anweisung.executeUpdate();
        }
    }

    /**
     * Aktualisiert eine bestehende Zeile.
     *
     * <p>Stufe und Herkunft bleiben unangetastet, wenn der Eintrag von Hand
     * angelegt wurde: wer im Adminbereich "premium" vergeben hat, will das
     * nicht durch einen Neustart des Knotens verlieren. Ein leer
     * mitgeschicktes Passwort ueberschreibt ebenfalls nichts - dieselbe Regel
     * wie im Adminbereich, und aus demselben Grund.</p>
     */
    private void aktualisieren(
            Connection connection,
            long id,
            String adresse,
            Anmeldung anmeldung,
            String stufe,
            String herkunft
    ) throws SQLException {
        try (PreparedStatement anweisung = connection.prepareStatement("""
                UPDATE deployment_lavalink_nodes SET
                    server_uri      = ?,
                    password        = COALESCE(NULLIF(?, ''), password),
                    tier            = CASE WHEN herkunft = 'manuell' THEN tier ELSE ? END,
                    herkunft        = CASE WHEN herkunft = 'manuell' THEN herkunft ELSE ? END,
                    agent_url       = COALESCE(NULLIF(?, ''), agent_url),
                    hetzner_id      = COALESCE(?, hetzner_id),
                    enabled         = true,
                    zuletzt_gesehen = ?,
                    updated_at      = current_timestamp
                WHERE id = ? AND bot_id = ?
                """)) {
            anweisung.setString(1, adresse);
            anweisung.setString(2, sauber(anmeldung.passwort()));
            anweisung.setString(3, stufe);
            anweisung.setString(4, herkunft);
            anweisung.setString(5, sauber(anmeldung.agentUrl()));
            setzeLangOderNull(anweisung, 6, anmeldung.hetznerId());
            anweisung.setTimestamp(7, java.sql.Timestamp.from(Instant.now()));
            anweisung.setLong(8, id);
            anweisung.setInt(9, botId);
            anweisung.executeUpdate();
        }
    }

    private void setzeLangOderNull(PreparedStatement anweisung, int stelle, Long wert) throws SQLException {
        if (wert == null) {
            anweisung.setNull(stelle, java.sql.Types.BIGINT);
        } else {
            anweisung.setLong(stelle, wert);
        }
    }

    private String sauber(String wert) {
        return wert == null ? "" : wert.trim();
    }
}
