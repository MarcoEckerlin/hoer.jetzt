package jetzt.hoer.updater.daten;

import jetzt.hoer.updater.modell.Faehigkeit;
import jetzt.hoer.updater.modell.Modul;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Geheimnis, Module und Faehigkeiten eines Knotens.
 *
 * <p>Getrennt von {@link KnotenDaten}, obwohl dieselbe Tabelle beteiligt ist:
 * dort steht, was ein Knoten <em>gemeldet</em> hat, hier was er <em>darf</em>.
 * Die beiden Fragen werden an verschiedenen Stellen gestellt - der Torwaechter
 * fragt bei jeder Abbildschicht nach den Rechten und nie nach dem letzten
 * Zustandsbericht.</p>
 */
@Repository
public class AusweisDaten {

    private final JdbcClient db;

    public AusweisDaten(JdbcClient db) {
        this.db = db;
    }

    /**
     * Das gespeicherte Geheimnis eines Knotens - als Hash, nicht im Klartext.
     *
     * @return leer, wenn es die Kennung nicht gibt, sie gesperrt ist oder noch
     *         kein eigenes Geheimnis hinterlegt wurde
     */
    public Optional<String> geheimnisHash(String kennung) {
        return db.sql("SELECT geheimnis FROM knoten WHERE kennung = ? AND gesperrt = 0")
                .param(kennung)
                .query(String.class)
                .optional()
                .filter(h -> !h.isBlank());
    }

    /**
     * Was dieser Knoten darf.
     *
     * <p>Aus den Modulen abgeleitet und anschliessend um die ausdruecklich
     * gesperrten Eintraege beschnitten. Die Richtung ist Absicht: ein neu
     * hinzugefuegtes Modul bringt seine Faehigkeiten von selbst mit, ein
     * Entzug muss ausdruecklich eingetragen werden.</p>
     */
    public Set<Faehigkeit> faehigkeiten(String kennung) {
        Set<Faehigkeit> erlaubt = EnumSet.noneOf(Faehigkeit.class);
        for (Modul m : module(kennung)) {
            erlaubt.addAll(m.faehigkeiten());
        }
        for (String gesperrt : gesperrteFaehigkeiten(kennung)) {
            Faehigkeit.aus(gesperrt).ifPresent(erlaubt::remove);
        }
        return erlaubt;
    }

    public List<Modul> module(String kennung) {
        return db.sql("SELECT modul FROM knoten_modul WHERE kennung = ?")
                .param(kennung)
                .query(String.class)
                .list()
                .stream()
                .map(Modul::aus)
                .flatMap(Optional::stream)
                .toList();
    }

    private List<String> gesperrteFaehigkeiten(String kennung) {
        return db.sql("SELECT faehigkeit FROM knoten_faehigkeit WHERE kennung = ? AND aktiv = 0")
                .param(kennung)
                .query(String.class)
                .list();
    }

    // ------------------------------------------------------------- Schreiben

    public void modulSetzen(String kennung, Modul modul) {
        db.sql("""
                INSERT INTO knoten_modul (kennung, modul, angelegt) VALUES (?, ?, ?)
                ON CONFLICT(kennung, modul) DO NOTHING
                """)
                .params(kennung, modul.name(), Zeiten.text(Instant.now()))
                .update();
    }

    public void modulEntfernen(String kennung, Modul modul) {
        db.sql("DELETE FROM knoten_modul WHERE kennung = ? AND modul = ?")
                .params(kennung, modul.name())
                .update();
    }

    /**
     * Eine einzelne Faehigkeit sperren oder wieder freigeben, ohne das Modul
     * anzufassen. Gedacht fuer den Verdachtsfall: der Tresor eines Knotens
     * koennte abhandengekommen sein, aber der Knoten soll weiterlaufen.
     */
    public void faehigkeitSetzen(String kennung, Faehigkeit faehigkeit, boolean aktiv) {
        db.sql("""
                INSERT INTO knoten_faehigkeit (kennung, faehigkeit, aktiv, angelegt)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(kennung, faehigkeit) DO UPDATE SET aktiv = excluded.aktiv
                """)
                .params(kennung, faehigkeit.name(), aktiv ? 1 : 0, Zeiten.text(Instant.now()))
                .update();
    }

    public void geheimnisSetzen(String kennung, String hash) {
        db.sql("UPDATE knoten SET geheimnis = ? WHERE kennung = ?")
                .params(hash, kennung)
                .update();
    }

    public void sperren(String kennung, boolean gesperrt, String grund) {
        db.sql("UPDATE knoten SET gesperrt = ?, gesperrt_grund = ? WHERE kennung = ?")
                .params(gesperrt ? 1 : 0, grund == null ? "" : grund, kennung)
                .update();
    }

    /** Wie viele Knoten schon ein eigenes Geheimnis haben - fuer die Uebersicht. */
    /**
     * Entfernt Ausweis und Module eines Knotens vollstaendig.
     *
     * <p>Bis dahin gab es nur {@code KnotenDaten.loeschen} - und das nahm
     * allein die Zeile aus {@code knoten}. Ausweis, Geheimnis und Module
     * blieben stehen: der Knoten verschwand aus der Liste und konnte sich
     * weiter anmelden, woraufhin er beim naechsten Herzschlag wieder
     * auftauchte. "Entfernen" hiess also "kurz ausblenden".</p>
     */
    public void entfernen(String kennung) {
        // Nur die Zuordnungstabellen.
        //
        // Hier stand eine dritte Zeile auf "knoten_ausweis" - eine Tabelle,
        // die es nicht gibt. Das Geheimnis liegt als Spalte in "knoten", und
        // diese Zeile loescht Knotenverwaltung.entfernen() gleich danach.
        //
        // Folge des Fehlers: die Anweisung warf, der Aufruf endete mit 500,
        // und in der Oberflaeche sah es aus, als tue der Knopf nichts. Kein
        // Fehler, keine Meldung - der Knoten stand nach dem Neuladen einfach
        // wieder da.
        db.sql("DELETE FROM knoten_modul WHERE kennung = ?").param(kennung).update();
        db.sql("DELETE FROM knoten_faehigkeit WHERE kennung = ?").param(kennung).update();
    }

    public int mitEigenemGeheimnis() {
        Integer zahl = db.sql("SELECT COUNT(*) FROM knoten WHERE geheimnis <> ''")
                .query(Integer.class)
                .single();
        return zahl == null ? 0 : zahl;
    }
}
