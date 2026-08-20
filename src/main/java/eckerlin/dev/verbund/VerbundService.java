package eckerlin.dev.verbund;

import eckerlin.dev.utils.Alert;
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
import java.util.List;

/**
 * Die zentrale Steuerung des Verbunds.
 *
 * <p>Sie tut genau zwei Dinge, die keine Node fuer sich tun kann:</p>
 *
 * <ol>
 *   <li><b>Shard-Nummern vergeben.</b> Discord erlaubt je Nummer genau eine
 *       Verbindung und wirft <em>beide</em> hinaus, die sich streiten. Wer
 *       Nummern von Hand verteilt, macht das genau einmal falsch - und der
 *       Fehler aeussert sich als Bot, der auf zwei Maschinen abwechselnd
 *       offline geht.</li>
 *   <li><b>Ein Ziel-Release nennen.</b> Damit ein Rollout eine Entscheidung
 *       ist und nicht zwoelf einzelne Anmeldungen per SSH.</li>
 * </ol>
 *
 * <p>Der Controller ist bewusst <b>kein</b> eigener Dienst, sondern lebt in
 * core auf der Steuer-Node. Ein weiteres Deployment fuer zwei Tabellen und
 * drei Endpunkte waere Aufwand ohne Gegenwert - und die Datenbank, die er
 * braucht, steht ohnehin schon da.</p>
 */
@Service
public class VerbundService {

    /**
     * Wie lange eine Node stumm sein darf, bevor ihre Shards neu vergeben
     * werden.
     *
     * <p>Der Agent meldet sich jede Minute. Fuenf Minuten Karenz fangen einen
     * Neustart und einen Netzwackler ab. Kuerzer waere gefaehrlich: Shards
     * umzuverteilen reisst jede laufende Wiedergabe auf dieser Node ab, und
     * das fuer eine Node zu tun, die gleich wieder da ist, waere der teuerste
     * Weg, gar nichts zu gewinnen.</p>
     */
    private static final Duration KARENZ = Duration.ofMinutes(5);

    /**
     * Nimmt die Meldung einer Node entgegen und sagt ihr, was sie fahren soll.
     */
    public synchronized NodeAntwort anmelden(NodeMeldung meldung) {
        if (meldung == null || meldung.nodeName() == null || meldung.nodeName().isBlank()) {
            throw new IllegalArgumentException("nodeName fehlt.");
        }
        if (!DB.isAvailable()) {
            throw new IllegalStateException("Datenbank nicht erreichbar.");
        }

        schreibeMeldung(meldung);
        verteileShards();
        return leseVorgabe(meldung.nodeName());
    }

    private void schreibeMeldung(NodeMeldung meldung) {
        // wartung_seit wird nur beim Wechsel gesetzt bzw. geleert - sonst
        // stuende dort bei jeder Meldung die aktuelle Zeit, und "seit wann in
        // Wartung" waere immer "seit einer Minute".
        String sql = "INSERT INTO cluster_nodes"
                + " (node_name, privat_ip, node_nr, release_version, zustand_json,"
                + "  wartung, wartung_seit, letzte_meldung)"
                + " VALUES (?,?,?,?,?,?, CASE WHEN ? THEN current_timestamp ELSE NULL END,"
                + "         current_timestamp)"
                + " ON CONFLICT (node_name) DO UPDATE SET"
                + "   privat_ip = EXCLUDED.privat_ip,"
                + "   node_nr = EXCLUDED.node_nr,"
                + "   release_version = EXCLUDED.release_version,"
                + "   zustand_json = EXCLUDED.zustand_json,"
                + "   wartung = EXCLUDED.wartung,"
                + "   wartung_seit = CASE"
                + "       WHEN EXCLUDED.wartung AND NOT cluster_nodes.wartung THEN current_timestamp"
                + "       WHEN NOT EXCLUDED.wartung THEN NULL"
                + "       ELSE cluster_nodes.wartung_seit END,"
                + "   letzte_meldung = current_timestamp";
        try (Connection verbindung = DB.connection();
             PreparedStatement anweisung = verbindung.prepareStatement(sql)) {
            anweisung.setString(1, meldung.nodeName().trim());
            anweisung.setString(2, meldung.privatIp());
            anweisung.setInt(3, meldung.nodeNr() <= 0 ? 1 : meldung.nodeNr());
            anweisung.setString(4, meldung.releaseVersion());
            anweisung.setString(5, meldung.zustandJson());
            anweisung.setBoolean(6, meldung.inWartung());
            anweisung.setBoolean(7, meldung.inWartung());
            anweisung.executeUpdate();
        } catch (SQLException fehler) {
            throw new IllegalStateException("Meldung konnte nicht gespeichert werden: " + fehler.getMessage(), fehler);
        }
    }

    /**
     * Teilt die Shards auf die lebenden Nodes auf.
     *
     * <p>Gleichmaessig und nach Namen sortiert - nicht nach Meldezeit. Die
     * Reihenfolge muss stabil sein: waere sie es nicht, wanderten die Shards
     * bei jeder Meldung ein Stueck weiter, und mit ihnen die Wiedergabe.</p>
     *
     * <p>Es wird nur geschrieben, was sich aendert. Ein Schreibvorgang je
     * Minute und Node waere fuer die Multi-Master-Replikation unnoetiger
     * Verkehr - und jede Aenderung hier kostet auf der betroffenen Node einen
     * Neustart.</p>
     */
    private void verteileShards() {
        List<String> lebend = new ArrayList<>();
        int gesamt = 1;

        // Nodes in Wartung bekommen keine Shards.
        //
        // Sie melden sich weiter und sind erreichbar - "lebend" allein reicht
        // deshalb nicht als Bedingung. Wer in Wartung ist, soll keine neuen
        // Aufgaben uebernehmen; die Shards gehen an die uebrigen. Faellt die
        // letzte Node in Wartung, bleibt die Liste leer und die Aufteilung
        // unveraendert - das ist gewollt, sonst stuende der Verbund still,
        // weil jemand die letzte Maschine warten wollte.
        String sql = "SELECT node_name FROM cluster_nodes"
                + " WHERE letzte_meldung > ? AND wartung = false ORDER BY node_name";
        try (Connection verbindung = DB.connection()) {
            try (PreparedStatement anweisung = verbindung.prepareStatement(sql)) {
                anweisung.setTimestamp(1, Timestamp.from(Instant.now().minus(KARENZ)));
                try (ResultSet ergebnis = anweisung.executeQuery()) {
                    while (ergebnis.next()) {
                        lebend.add(ergebnis.getString("node_name"));
                    }
                }
            }

            try (PreparedStatement anweisung = verbindung.prepareStatement(
                    "SELECT shards_gesamt FROM cluster_ziel WHERE id = 1")) {
                try (ResultSet ergebnis = anweisung.executeQuery()) {
                    if (ergebnis.next()) {
                        gesamt = Math.max(1, ergebnis.getInt("shards_gesamt"));
                    }
                }
            }

            if (lebend.isEmpty()) {
                return;
            }
            // Weniger Shards als Nodes hiesse: eine Node bekaeme keinen und
            // stuende nutzlos da. Dann lieber je Node einen.
            if (gesamt < lebend.size()) {
                gesamt = lebend.size();
            }

            int proNode = gesamt / lebend.size();
            int rest = gesamt % lebend.size();
            int naechster = 0;

            try (PreparedStatement anweisung = verbindung.prepareStatement(
                    "UPDATE cluster_nodes SET shards_von = ?, shards_bis = ?, shards_gesamt = ?"
                            + " WHERE node_name = ?"
                            + "   AND (shards_von IS DISTINCT FROM ? OR shards_bis IS DISTINCT FROM ?"
                            + "        OR shards_gesamt IS DISTINCT FROM ?)")) {
                for (int i = 0; i < lebend.size(); i++) {
                    int anzahl = proNode + (i < rest ? 1 : 0);
                    int von = naechster;
                    int bis = naechster + anzahl - 1;
                    naechster = bis + 1;

                    anweisung.setInt(1, von);
                    anweisung.setInt(2, bis);
                    anweisung.setInt(3, gesamt);
                    anweisung.setString(4, lebend.get(i));
                    anweisung.setInt(5, von);
                    anweisung.setInt(6, bis);
                    anweisung.setInt(7, gesamt);
                    if (anweisung.executeUpdate() > 0) {
                        Alert.send("INFO", "VERBUND", "Node " + lebend.get(i)
                                + " bekommt Shards " + von + "-" + bis + " von " + gesamt + ".");
                    }
                }
            }
        } catch (SQLException fehler) {
            Alert.send("WARN", "VERBUND", "Shard-Verteilung fehlgeschlagen: " + fehler.getMessage());
        }
    }

    private NodeAntwort leseVorgabe(String nodeName) {
        String sql = "SELECT n.shards_von, n.shards_bis, n.shards_gesamt, z.release_version"
                + " FROM cluster_nodes n LEFT JOIN cluster_ziel z ON z.id = 1"
                + " WHERE n.node_name = ?";
        try (Connection verbindung = DB.connection();
             PreparedStatement anweisung = verbindung.prepareStatement(sql)) {
            anweisung.setString(1, nodeName.trim());
            try (ResultSet ergebnis = anweisung.executeQuery()) {
                if (ergebnis.next()) {
                    return new NodeAntwort(
                            ergebnis.getString("release_version"),
                            ergebnis.getObject("shards_gesamt") == null ? null : ergebnis.getInt("shards_gesamt"),
                            ergebnis.getObject("shards_von") == null ? null : ergebnis.getInt("shards_von"),
                            ergebnis.getObject("shards_bis") == null ? null : ergebnis.getInt("shards_bis")
                    );
                }
            }
        } catch (SQLException fehler) {
            Alert.send("WARN", "VERBUND", "Vorgabe konnte nicht gelesen werden: " + fehler.getMessage());
        }
        return new NodeAntwort(null, null, null, null);
    }

    /** Alle bekannten Nodes samt Zustand - fuer den Adminbereich. */
    public List<NodeUebersicht> uebersicht() {
        List<NodeUebersicht> liste = new ArrayList<>();
        if (!DB.isAvailable()) {
            return liste;
        }
        String sql = "SELECT node_name, privat_ip, node_nr, shards_von, shards_bis, shards_gesamt,"
                + " release_version, zustand_json, letzte_meldung, wartung"
                + " FROM cluster_nodes ORDER BY node_name";
        try (Connection verbindung = DB.connection();
             PreparedStatement anweisung = verbindung.prepareStatement(sql);
             ResultSet ergebnis = anweisung.executeQuery()) {
            Instant grenze = Instant.now().minus(KARENZ);
            while (ergebnis.next()) {
                Timestamp gemeldet = ergebnis.getTimestamp("letzte_meldung");
                liste.add(new NodeUebersicht(
                        ergebnis.getString("node_name"),
                        ergebnis.getString("privat_ip"),
                        ergebnis.getInt("node_nr"),
                        ergebnis.getObject("shards_von") == null ? null : ergebnis.getInt("shards_von"),
                        ergebnis.getObject("shards_bis") == null ? null : ergebnis.getInt("shards_bis"),
                        ergebnis.getObject("shards_gesamt") == null ? null : ergebnis.getInt("shards_gesamt"),
                        ergebnis.getString("release_version"),
                        ergebnis.getString("zustand_json"),
                        gemeldet == null ? null : gemeldet.toInstant().toString(),
                        gemeldet != null && gemeldet.toInstant().isAfter(grenze),
                        ergebnis.getBoolean("wartung")
                ));
            }
        } catch (SQLException fehler) {
            Alert.send("WARN", "VERBUND", "Uebersicht fehlgeschlagen: " + fehler.getMessage());
        }
        return liste;
    }

    /**
     * Das aktuell gesetzte Ziel.
     *
     * <p>Der Adminbereich braucht es, bevor er ein neues setzt. Ohne diese
     * Abfrage muesste man das Feld leer anzeigen und der Bedienende koennte
     * nicht unterscheiden, ob nichts gesetzt ist oder ob die Oberflaeche es
     * nur nicht weiss - und wuerde im Zweifel ueberschreiben.</p>
     */
    public Ziel ziel() {
        if (!DB.isAvailable()) {
            return new Ziel(null, null, null, null);
        }
        String sql = "SELECT release_version, shards_gesamt, gesetzt_von, gesetzt_am"
                + " FROM cluster_ziel WHERE id = 1";
        try (Connection verbindung = DB.connection();
             PreparedStatement anweisung = verbindung.prepareStatement(sql);
             ResultSet ergebnis = anweisung.executeQuery()) {
            if (ergebnis.next()) {
                Timestamp am = ergebnis.getTimestamp("gesetzt_am");
                return new Ziel(
                        ergebnis.getString("release_version"),
                        ergebnis.getObject("shards_gesamt") == null ? null : ergebnis.getInt("shards_gesamt"),
                        ergebnis.getString("gesetzt_von"),
                        am == null ? null : am.toInstant().toString()
                );
            }
        } catch (SQLException fehler) {
            Alert.send("WARN", "VERBUND", "Ziel konnte nicht gelesen werden: " + fehler.getMessage());
        }
        return new Ziel(null, null, null, null);
    }

    public record Ziel(
            String releaseVersion,
            Integer shardsGesamt,
            String gesetztVon,
            String gesetztAm
    ) {
    }

    /** Setzt das Ziel fuer den ganzen Verbund. Die Agenten holen es sich ab. */
    public synchronized void zielSetzen(String releaseVersion, Integer shardsGesamt, String wer) {
        String sql = "INSERT INTO cluster_ziel (id, release_version, shards_gesamt, gesetzt_von, gesetzt_am)"
                + " VALUES (1, ?, ?, ?, current_timestamp)"
                + " ON CONFLICT (id) DO UPDATE SET"
                + "   release_version = COALESCE(EXCLUDED.release_version, cluster_ziel.release_version),"
                + "   shards_gesamt = COALESCE(EXCLUDED.shards_gesamt, cluster_ziel.shards_gesamt),"
                + "   gesetzt_von = EXCLUDED.gesetzt_von,"
                + "   gesetzt_am = current_timestamp";
        try (Connection verbindung = DB.connection();
             PreparedStatement anweisung = verbindung.prepareStatement(sql)) {
            anweisung.setString(1, releaseVersion == null || releaseVersion.isBlank() ? null : releaseVersion.trim());
            if (shardsGesamt == null || shardsGesamt <= 0) {
                anweisung.setNull(2, java.sql.Types.INTEGER);
            } else {
                anweisung.setInt(2, shardsGesamt);
            }
            anweisung.setString(3, wer);
            anweisung.executeUpdate();
            Alert.send("INFO", "VERBUND", "Ziel gesetzt: Release " + releaseVersion
                    + ", Shards gesamt " + shardsGesamt + " (von " + wer + ").");
        } catch (SQLException fehler) {
            throw new IllegalStateException("Ziel konnte nicht gesetzt werden: " + fehler.getMessage(), fehler);
        }
    }

    public record NodeMeldung(
            String nodeName,
            String privatIp,
            int nodeNr,
            String releaseVersion,
            String zustandJson,
            /*
             * Ob diese Node gerade in Wartung ist.
             *
             * Als Boolean und nicht als boolean: aeltere Agenten schicken das
             * Feld nicht mit, und Jackson setzt dann null. Bei einem einfachen
             * boolean waere daraus false geworden - hier zufaellig das
             * Richtige, beim naechsten Feld mit umgekehrter Vorgabe aber
             * nicht mehr. Die Absicht steht so ausdruecklich da.
             */
            Boolean wartung
    ) {
        /** Fehlende Angabe heisst Betrieb, nicht Wartung. */
        public boolean inWartung() {
            return Boolean.TRUE.equals(wartung);
        }
    }

    public record NodeAntwort(
            String zielRelease,
            Integer shardsGesamt,
            Integer shardsVon,
            Integer shardsBis
    ) {
    }

    public record NodeUebersicht(
            String nodeName,
            String privatIp,
            int nodeNr,
            Integer shardsVon,
            Integer shardsBis,
            Integer shardsGesamt,
            String releaseVersion,
            String zustandJson,
            String letzteMeldung,
            boolean lebt,
            /*
             * Getrennt von "lebt": eine Node in Wartung meldet sich weiter und
             * ist erreichbar - sie uebernimmt nur nichts Neues. Beides in ein
             * Feld zu legen hiesse, genau den Unterschied zu verlieren, um den
             * es geht: eine stumme Node ist ein Problem, eine in Wartung ist
             * eine Ansage.
             */
            boolean wartung
    ) {
    }
}
