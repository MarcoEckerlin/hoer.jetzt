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

    /**
     * Der Deployment-Schluessel dieser Instanz.
     *
     * <p>Hier stand fest verdrahtet {@code "standard"}, waehrend die Instanz
     * unter {@code "local"} lief. Sichtbar wurde es nicht, weil die Knotenliste
     * nach {@code bot_id} sucht und nicht nach dem Schluessel - im Adminbereich
     * landeten die Knoten aber unter einem Deployment, das es nicht gab. Wer
     * dort etwas speicherte, schrieb an ihnen vorbei.</p>
     */
    private final String deploymentKey = Config.config.optJSONObject("deployment") == null
            ? "local"
            : Config.config.optJSONObject("deployment").optString("key", "local").trim();

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

    /**
     * Entfernt einen Knoten endgueltig aus der Tabelle.
     *
     * <p>Unterschied zu {@link #abmelden(String)}: dort wird ein Knoten nur
     * stillgelegt und bleibt stehen, weil er sich jederzeit wieder melden
     * kann. Hier soll er weg - etwa weil der Server dahinter geloescht wurde
     * oder die Erstinstallation gescheitert ist und ein Eintrag zurueckblieb,
     * der auf nichts mehr zeigt.</p>
     *
     * <p>Anders als beim Abmelden sind hier auch von Hand eingetragene Knoten
     * erfasst: was ein Mensch eingetragen hat, darf ein Mensch auch loeschen.</p>
     *
     * @return die Hetzner-Server-ID, falls es ein automatisch angelegter Knoten
     *         war - der Aufrufer entscheidet dann, ob der Server mit weg soll
     */
    public Optional<Long> entfernen(String name) throws SQLException {
        String sauber = sauber(name);
        if (sauber.isBlank()) {
            return Optional.empty();
        }

        Long hetznerId = null;
        try (Connection connection = DB.connection()) {
            try (PreparedStatement lesen = connection.prepareStatement(
                    "SELECT hetzner_id FROM deployment_lavalink_nodes WHERE bot_id = ? AND node_name = ?")) {
                lesen.setInt(1, botId);
                lesen.setString(2, sauber);
                try (java.sql.ResultSet zeile = lesen.executeQuery()) {
                    if (zeile.next()) {
                        long wert = zeile.getLong("hetzner_id");
                        // wasNull() muss unmittelbar nach dem Lesen kommen -
                        // jede weitere Spalte setzt das Kennzeichen neu.
                        if (!zeile.wasNull()) {
                            hetznerId = wert;
                        }
                    }
                }
            }

            try (PreparedStatement anweisung = connection.prepareStatement(
                    "DELETE FROM deployment_lavalink_nodes WHERE bot_id = ? AND node_name = ?")) {
                anweisung.setInt(1, botId);
                anweisung.setString(2, sauber);
                if (anweisung.executeUpdate() == 0) {
                    throw new IllegalArgumentException("Kein Knoten mit dem Namen \"" + sauber + "\".");
                }
            }

            // Die Session gehoert zu einem Knoten, den es nicht mehr gibt.
            try (PreparedStatement anweisung = connection.prepareStatement(
                    "DELETE FROM lavalink_sessions WHERE bot_id = ? AND node_name = ?")) {
                anweisung.setInt(1, botId);
                anweisung.setString(2, sauber);
                anweisung.executeUpdate();
            }
        }

        Alert.send("INFO", "AUDIO", "Knoten " + sauber + " aus der Tabelle entfernt.");
        return Optional.ofNullable(hetznerId);
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

    /** Was in der Tabelle steht - auch ueber Knoten, die noch nicht verbunden sind. */
    public record Eintrag(
            String name,
            String adresse,
            String stufe,
            String herkunft,
            String agentUrl,
            Long hetznerId,
            String zuletztGesehen,
            boolean aktiv
    ) {
    }

    /**
     * Alle eingetragenen Knoten.
     *
     * <p>Bewusst aus der Tabelle und nicht aus der Lavalink-Bibliothek: die
     * kennt nur, was verbunden ist. Ein gerade erst erzeugter Server taucht
     * dort minutenlang nicht auf - und genau in dieser Zeit will man in der
     * Oberflaeche sehen, dass er im Kommen ist, statt sich zu fragen, ob das
     * Anlegen ueberhaupt geklappt hat.</p>
     */
    public java.util.List<Eintrag> alleEintraege() {
        java.util.List<Eintrag> gefunden = new java.util.ArrayList<>();
        String sql = """
                SELECT node_name, server_uri, tier, herkunft, agent_url, hetzner_id, zuletzt_gesehen, enabled
                  FROM deployment_lavalink_nodes
                 WHERE bot_id = ?
                 ORDER BY herkunft, node_name
                """;

        try (Connection verbindung = DB.connection();
             PreparedStatement anweisung = verbindung.prepareStatement(sql)) {
            anweisung.setInt(1, botId);
            try (ResultSet ergebnis = anweisung.executeQuery()) {
                while (ergebnis.next()) {
                    // wasNull() bezieht sich immer auf die zuletzt gelesene
                    // Spalte. Erst den Zeitstempel zu lesen und danach zu
                    // fragen, haette die Antwort fuer den Zeitstempel geliefert
                    // - und hetzner_id waere nie null gewesen.
                    long hetzner = ergebnis.getLong("hetzner_id");
                    Long hetznerId = ergebnis.wasNull() ? null : hetzner;
                    java.sql.Timestamp gesehen = ergebnis.getTimestamp("zuletzt_gesehen");
                    gefunden.add(new Eintrag(
                            sauber(ergebnis.getString("node_name")),
                            sauber(ergebnis.getString("server_uri")),
                            sauber(ergebnis.getString("tier")),
                            sauber(ergebnis.getString("herkunft")),
                            sauber(ergebnis.getString("agent_url")),
                            hetznerId,
                            gesehen == null ? "" : gesehen.toInstant().toString(),
                            ergebnis.getBoolean("enabled")
                    ));
                }
            }
        } catch (SQLException fehler) {
            Alert.send("WARN", "AUDIO", "Knotentabelle nicht lesbar: " + fehler.getMessage());
        }
        return gefunden;
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
            anweisung.setString(2, deploymentKey.isBlank() ? "local" : deploymentKey);
            anweisung.setString(3, name);
            anweisung.setString(4, adresse);
            anweisung.setString(5, sauber(anmeldung.passwort()));
            anweisung.setString(6, stufe);
            anweisung.setString(7, herkunft);
            anweisung.setString(8, agentAdresse(adresse, anmeldung.agentUrl(), name));
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
            anweisung.setString(5, agentAdresse(adresse, anmeldung.agentUrl(), anmeldung.name()));
            setzeLangOderNull(anweisung, 6, anmeldung.hetznerId());
            anweisung.setTimestamp(7, java.sql.Timestamp.from(Instant.now()));
            anweisung.setLong(8, id);
            anweisung.setInt(9, botId);
            anweisung.executeUpdate();
        }
    }

    /**
     * Die Adresse, unter der dieser Knoten-Agent wirklich erreichbar ist.
     *
     * <p>Der Agent meldet selbst, wo er lauscht - und liegt manchmal daneben.
     * Laeuft auf dem Knoten Docker, findet die Adressermittlung dort schnell
     * die Bruecke {@code 172.18.0.1} statt der echten Adresse des Rechners.
     * Eingetragen wurde das ungeprueft, und der Bot lief anschliessend in
     * "Connection refused" - gegen eine Adresse, die es nur auf dem fremden
     * Host gibt.</p>
     *
     * <p>Die Korrektur braucht kein Raten: der Agent laeuft immer auf
     * demselben Rechner wie sein Lavalink, und dessen Adresse kennt der Bot,
     * weil er sich dorthin verbindet. Stimmen die beiden Rechnernamen nicht
     * ueberein, gewinnt der von Lavalink; der Port des Agenten bleibt.</p>
     *
     * <p>Bewusst nicht "private Adressen verwerfen": im Hetzner-Verbund sind
     * 10.0.0.2 und 10.0.0.3 die richtigen Adressen. Es geht um die
     * Abweichung, nicht um den Adressbereich.</p>
     */
    private String agentAdresse(String lavalinkAdresse, String gemeldet, String knotenName) {
        String agent = sauber(gemeldet);
        if (agent.isBlank() || lavalinkAdresse == null || lavalinkAdresse.isBlank()) {
            return agent;
        }

        try {
            java.net.URI agentUri = java.net.URI.create(agent);
            java.net.URI lavalinkUri = java.net.URI.create(lavalinkAdresse);
            String agentHost = agentUri.getHost();
            String lavalinkHost = lavalinkUri.getHost();
            if (agentHost == null || lavalinkHost == null || agentHost.equalsIgnoreCase(lavalinkHost)) {
                return agent;
            }

            int port = agentUri.getPort();
            String korrigiert = agentUri.getScheme() + "://" + lavalinkHost + (port > 0 ? ":" + port : "");
            Alert.send("INFO", "AUDIO", "Agent-Adresse von " + knotenName + " korrigiert: "
                    + agent + " -> " + korrigiert + " (gemeldeter Rechner passt nicht zu Lavalink).");
            return korrigiert;
        } catch (RuntimeException nichtLesbar) {
            // Keine deutbare Adresse - dann lieber unveraendert eintragen, als
            // sie zu verwerfen. Der Zustand ist dann sichtbar falsch statt leer.
            return agent;
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
