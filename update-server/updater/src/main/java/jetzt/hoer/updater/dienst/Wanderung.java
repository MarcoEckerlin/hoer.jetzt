package jetzt.hoer.updater.dienst;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Neue Spalten an bestehenden Tabellen.
 *
 * <p>{@code schema.sql} laeuft bei jedem Start und ist durchgehend
 * {@code IF NOT EXISTS} - fuer neue Tabellen genau richtig und der Grund,
 * warum es bisher ohne Wanderungsmechanik ging. Bei <em>Spalten</em> traegt
 * das nicht: SQLite kennt kein {@code ADD COLUMN IF NOT EXISTS}, und ein
 * zweiter Lauf braeche mit "duplicate column name" ab - also bei jedem
 * Neustart nach der ersten Wanderung.</p>
 *
 * <p>Deshalb wird gefragt statt versucht: {@code PRAGMA table_info} sagt, was
 * da ist. Das ist genauer als eine Versionsnummer in einer Tabelle, denn es
 * beschreibt den tatsaechlichen Zustand der Datenbank und nicht den, den
 * jemand einmal hineingeschrieben hat. Bei einer Datenbank, die von Hand
 * repariert werden kann - und eine SQLite-Datei wird von Hand repariert -
 * ist das der belastbarere der beiden Wege.</p>
 *
 * <p>Laeuft nach {@code ApplicationReadyEvent}, also nach {@code schema.sql}.
 * Umgekehrt gaebe es die Tabelle beim ersten Start noch nicht.</p>
 */
@Service
public class Wanderung {

    private static final Logger log = LoggerFactory.getLogger(Wanderung.class);

    /**
     * Spalte auf Definition. Reihenfolge bleibt erhalten, damit das Protokoll
     * beim ersten Lauf lesbar ist.
     *
     * <p>Ausnahmslos {@code NULL}-faehig oder mit Vorgabewert: SQLite kann
     * einer bestehenden Tabelle keine Spalte mit {@code NOT NULL} ohne
     * Vorgabe hinzufuegen, und die vorhandenen Zeilen haetten ohnehin keinen
     * sinnvollen Wert.</p>
     */
    private static final Map<String, String> KNOTEN_SPALTEN = new LinkedHashMap<>();

    static {
        // Das eigene Passwort dieses Knotens. Ersetzt das gemeinsame
        // HJ_TOKEN_KNOTEN. Leer heisst: noch nicht umgestellt - solche Knoten
        // laufen uebergangsweise weiter ueber das gemeinsame Passwort.
        KNOTEN_SPALTEN.put("geheimnis", "TEXT NOT NULL DEFAULT ''");
        KNOTEN_SPALTEN.put("angelegt", "TEXT");
        // Sperren statt loeschen - dieselbe Ueberlegung wie bei den Freigaben:
        // die Frage "wer war das und wann hatte der Zugang" stellt sich genau
        // dann, wenn etwas passiert ist.
        KNOTEN_SPALTEN.put("gesperrt", "INTEGER NOT NULL DEFAULT 0");
        KNOTEN_SPALTEN.put("gesperrt_grund", "TEXT NOT NULL DEFAULT ''");

        // Wartung. Drei Spalten statt einer: die Oberflaeche soll "seit wann,
        // warum und von wem" anzeigen koennen, und ein einzelnes Flag traegt
        // davon nichts.
        KNOTEN_SPALTEN.put("wartung_seit", "TEXT");
        KNOTEN_SPALTEN.put("wartung_grund", "TEXT NOT NULL DEFAULT ''");
        KNOTEN_SPALTEN.put("wartung_von", "TEXT NOT NULL DEFAULT ''");

        // Adressen. Bisher stand nur letzte_ip da - die Adresse, von der aus
        // zuletzt zugegriffen wurde. Fuer die IP-Freigabe reicht das, fuer die
        // Frage "welche Maschine ist das eigentlich" nicht.
        KNOTEN_SPALTEN.put("rechnername", "TEXT NOT NULL DEFAULT ''");
        KNOTEN_SPALTEN.put("privat_ip", "TEXT NOT NULL DEFAULT ''");
        KNOTEN_SPALTEN.put("oeffentlich_ipv4", "TEXT NOT NULL DEFAULT ''");
        KNOTEN_SPALTEN.put("oeffentlich_ipv6", "TEXT NOT NULL DEFAULT ''");

        KNOTEN_SPALTEN.put("agent_version", "TEXT NOT NULL DEFAULT ''");
        KNOTEN_SPALTEN.put("hetzner_id", "INTEGER");
        KNOTEN_SPALTEN.put("vorlage", "TEXT NOT NULL DEFAULT ''");
    }

    private final JdbcClient db;

    public Wanderung(JdbcClient db) {
        this.db = db;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void wandern() {
        int neu = spaltenErgaenzen("knoten", KNOTEN_SPALTEN);
        if (neu > 0) {
            log.info("Wanderung: {} Spalte(n) an knoten ergaenzt.", neu);
        }
    }

    private int spaltenErgaenzen(String tabelle, Map<String, String> gewuenscht) {
        Set<String> vorhanden = Set.copyOf(spalten(tabelle));
        int neu = 0;
        for (Map.Entry<String, String> e : gewuenscht.entrySet()) {
            if (vorhanden.contains(e.getKey())) {
                continue;
            }
            // Tabellen- und Spaltennamen stehen als Konstanten in dieser
            // Klasse und kommen nie von aussen - eine Parameterbindung ist an
            // dieser Stelle ohnehin nicht moeglich, SQLite bindet keine
            // Bezeichner.
            db.sql("ALTER TABLE " + tabelle + " ADD COLUMN " + e.getKey() + " " + e.getValue())
              .update();
            log.info("Wanderung: {}.{} angelegt.", tabelle, e.getKey());
            neu++;
        }
        return neu;
    }

    private List<String> spalten(String tabelle) {
        return db.sql("PRAGMA table_info(" + tabelle + ")")
                .query((rs, zeile) -> rs.getString("name"))
                .list();
    }
}
