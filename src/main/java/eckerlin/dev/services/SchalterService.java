package eckerlin.dev.services;

import eckerlin.dev.utils.Alert;
import eckerlin.dev.utils.Config;
import eckerlin.dev.utils.DB;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * Schalter, die zur Laufzeit umgelegt werden.
 *
 * <p>Bislang lagen solche Entscheidungen in der Umgebung. Fuer etwas, das man
 * einmal beim Aufsetzen festlegt, ist das richtig; fuer das Autoscaling nicht:
 * um es abzuschalten, muesste man den Prozess neu starten - ausgerechnet in dem
 * Moment, in dem er gerade Server anlegt.</p>
 *
 * <p>Der Wert liegt in der Datenbank und wird damit auf alle Nodes gespiegelt.
 * Ein Klick wirkt so im ganzen Verbund, nicht nur auf der Node, auf der man
 * gerade gelandet ist.</p>
 */
@Service
public class SchalterService {

    /** Autoscaling: legt Knoten bei Hetzner an und wieder ab. */
    public static final String AUTOSCALING = "autoscaling";

    private final int botId = Config.config.optInt("bot_id", 1);

    /**
     * Liest einen Schalter.
     *
     * @param vorgabe gilt, solange niemand etwas eingetragen hat - so bleibt
     *                das Verhalten einer frischen Installation unveraendert
     */
    public boolean an(String schluessel, boolean vorgabe) {
        if (!DB.isAvailable()) {
            return vorgabe;
        }

        try (Connection verbindung = DB.connection();
             PreparedStatement anweisung = verbindung.prepareStatement(
                     "SELECT wert FROM betrieb_schalter WHERE bot_id = ? AND schluessel = ?")) {
            anweisung.setInt(1, botId);
            anweisung.setString(2, schluessel);
            try (ResultSet zeile = anweisung.executeQuery()) {
                if (!zeile.next()) {
                    return vorgabe;
                }
                String wert = zeile.getString("wert");
                return wert == null || wert.isBlank() ? vorgabe : "true".equalsIgnoreCase(wert.trim());
            }
        } catch (SQLException fehler) {
            // Im Zweifel die Vorgabe: ein Schalter, der bei einem Lesefehler
            // umspringt, waere schlimmer als einer, der sich nicht aendert.
            return vorgabe;
        }
    }

    /** Legt einen Schalter um. */
    public void setzen(String schluessel, boolean an, String wer) throws SQLException {
        try (Connection verbindung = DB.connection();
             PreparedStatement anweisung = verbindung.prepareStatement("""
                     INSERT INTO betrieb_schalter (bot_id, schluessel, wert, geaendert_von, geaendert_am)
                     VALUES (?, ?, ?, ?, ?)
                     ON CONFLICT (bot_id, schluessel) DO UPDATE SET
                         wert = excluded.wert,
                         geaendert_von = excluded.geaendert_von,
                         geaendert_am = excluded.geaendert_am
                     """)) {
            anweisung.setInt(1, botId);
            anweisung.setString(2, schluessel);
            anweisung.setString(3, Boolean.toString(an));
            anweisung.setString(4, wer == null ? "" : wer);
            anweisung.setTimestamp(5, Timestamp.from(Instant.now()));
            anweisung.executeUpdate();
        }
        Alert.send("INFO", "BETRIEB", "Schalter " + schluessel + " steht jetzt auf "
                + (an ? "an" : "aus") + ".");
    }
}
