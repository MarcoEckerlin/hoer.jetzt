package eckerlin.dev.verbund;

import eckerlin.dev.utils.Alert;
import eckerlin.dev.utils.DB;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Wer faehrt welchen Shard - und unter welcher Adresse ist er erreichbar.
 *
 * <h2>Wozu</h2>
 *
 * <p>Sobald die Shards auf mehrere Nodes verteilt sind, kennt jede Node nur
 * noch ihre eigene Haelfte der Server. Das ist bei Discord so gewollt und fuer
 * den Bot kein Problem - fuer das Webpanel schon: der Lastverteiler schickt
 * einen Benutzer auf irgendeine Node, und die hat seinen Server womoeglich gar
 * nicht. Ohne Gegenmassnahme sieht er die Haelfte seiner Server nicht und
 * bekommt beim Rest eine 404.</p>
 *
 * <p>Deshalb traegt sich jede Node hier ein, und {@link GuildWeiterleitung}
 * schlaegt nach, wohin eine Anfrage gehoert.</p>
 *
 * <h2>Warum die Shard-Nummer nicht gespeichert wird</h2>
 *
 * <p>Zu welchem Shard ein Server gehoert, ist keine Vereinbarung, sondern
 * Rechnung - Discord schreibt sie vor:
 * {@code (guild_id >> 22) % shards_gesamt}. Jede Node kommt damit ohne
 * Rueckfrage auf dasselbe Ergebnis. Eine Tabelle "Server X liegt auf Node Y"
 * waere eine zweite Wahrheit, die irgendwann von der ersten abweicht.</p>
 */
@Service
public class KnotenVerzeichnis {

    /** Nach dieser Zeit ohne Meldung gilt eine Node als weg. */
    private static final long KARENZ_SEKUNDEN = 300;

    public record Knoten(String name, String privatIp, int von, int bis, int gesamt) {
        public boolean haelt(int shard) {
            return shard >= von && shard <= bis;
        }

        /** Die Adresse, unter der die Weboberflaeche dieser Node antwortet. */
        public String basis() {
            return "http://" + privatIp + ":8080";
        }
    }

    /**
     * Traegt diese Node ein oder frischt ihren Eintrag auf.
     *
     * <p>Wird beim Start und danach im Takt der Knotenwache aufgerufen. Der
     * Zeitstempel ist wichtiger als der Rest: eine Node, die sich nicht mehr
     * meldet, darf keine Anfragen mehr bekommen.</p>
     */
    public void melden(String name, String privatIp, int von, int bis, int gesamt) {
        if (!DB.isAvailable() || name == null || name.isBlank()) {
            return;
        }

        // Geschrieben wird nur das Ist. Die Spalten shards_* gehoeren dem
        // Controller - schriebe der Melder sie mit, ueberholte er jede
        // Zuteilung, bevor der Agent sie umsetzen kann.
        String sql = """
                INSERT INTO cluster_nodes (node_name, privat_ip, ist_von, ist_bis, ist_gesamt, letzte_meldung)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (node_name) DO UPDATE SET
                    privat_ip = excluded.privat_ip,
                    ist_von = excluded.ist_von,
                    ist_bis = excluded.ist_bis,
                    ist_gesamt = excluded.ist_gesamt,
                    letzte_meldung = excluded.letzte_meldung
                """;

        try (Connection verbindung = DB.connection();
             PreparedStatement anweisung = verbindung.prepareStatement(sql)) {
            anweisung.setString(1, name);
            anweisung.setString(2, privatIp == null ? "" : privatIp);
            anweisung.setInt(3, von);
            anweisung.setInt(4, bis);
            anweisung.setInt(5, gesamt);
            anweisung.setTimestamp(6, Timestamp.from(Instant.now()));
            anweisung.executeUpdate();
        } catch (SQLException fehler) {
            Alert.send("WARN", "VERBUND", "Eigene Node konnte nicht gemeldet werden: " + fehler.getMessage());
        }
    }

    /** Alle Nodes, die sich zuletzt gemeldet haben - Schluessel ist der Name. */
    public Map<String, Knoten> alle() {
        Map<String, Knoten> ergebnis = new HashMap<>();
        if (!DB.isAvailable()) {
            return ergebnis;
        }

        // Gelesen wird das Ist - dort liegt der Server wirklich. Faellt es aus
        // (eine Node, die sich noch nie selbst gemeldet hat, etwa weil nur der
        // Verbund-Agent laeuft), gilt ersatzweise die Zuteilung.
        String sql = """
                SELECT node_name,
                       privat_ip,
                       coalesce(ist_von, shards_von)       AS von,
                       coalesce(ist_bis, shards_bis)       AS bis,
                       coalesce(ist_gesamt, shards_gesamt) AS gesamt
                FROM cluster_nodes
                WHERE letzte_meldung > now() - make_interval(secs => ?)
                """;

        try (Connection verbindung = DB.connection();
             PreparedStatement anweisung = verbindung.prepareStatement(sql)) {
            anweisung.setDouble(1, KARENZ_SEKUNDEN);
            try (ResultSet zeile = anweisung.executeQuery()) {
                while (zeile.next()) {
                    String ip = zeile.getString("privat_ip");
                    if (ip == null || ip.isBlank()) {
                        continue;
                    }
                    ergebnis.put(zeile.getString("node_name"), new Knoten(
                            zeile.getString("node_name"),
                            ip,
                            zeile.getInt("von"),
                            zeile.getInt("bis"),
                            zeile.getInt("gesamt")
                    ));
                }
            }
        } catch (SQLException fehler) {
            Alert.send("WARN", "VERBUND", "Knotenverzeichnis nicht lesbar: " + fehler.getMessage());
        }
        return ergebnis;
    }

    /**
     * Die Node, die diesen Server fuehrt - leer, wenn es die eigene ist oder
     * keine passende gefunden wurde.
     *
     * @param eigenerName Name dieser Node; sie kommt nie als Ziel zurueck.
     */
    public Optional<Knoten> fuer(String guildId, String eigenerName) {
        Map<String, Knoten> knoten = alle();
        if (knoten.size() < 2) {
            return Optional.empty();
        }

        int gesamt = knoten.values().stream()
                .mapToInt(Knoten::gesamt)
                .filter(wert -> wert > 0)
                .max()
                .orElse(1);
        if (gesamt <= 1) {
            return Optional.empty();
        }

        int shard = shardVon(guildId, gesamt);
        if (shard < 0) {
            return Optional.empty();
        }

        return knoten.values().stream()
                .filter(k -> !k.name().equals(eigenerName))
                .filter(k -> k.haelt(shard))
                .findFirst();
    }

    /**
     * Discords Formel: {@code (guild_id >> 22) % shards_gesamt}.
     *
     * @return -1, wenn die Kennung keine Zahl ist
     */
    public static int shardVon(String guildId, int gesamt) {
        if (guildId == null || gesamt <= 0) {
            return -1;
        }
        try {
            return (int) ((Long.parseUnsignedLong(guildId.trim()) >>> 22) % gesamt);
        } catch (NumberFormatException nichtNumerisch) {
            return -1;
        }
    }
}
