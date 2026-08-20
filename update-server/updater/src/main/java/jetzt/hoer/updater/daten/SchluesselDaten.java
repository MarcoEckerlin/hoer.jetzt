package jetzt.hoer.updater.daten;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Die oeffentlichen Schluessel der Knoten.
 *
 * <p>Nur der oeffentliche Teil. Der private wird auf dem Knoten erzeugt und
 * verlaesst ihn nie - deshalb kann dieser Server einen Umschlag, den er
 * geschrieben hat, anschliessend selbst nicht mehr oeffnen. Das ist der
 * Unterschied zum heutigen Tresor, der im Klartext hier liegt.</p>
 *
 * <p>Getrennt nach Zweck ({@link Zweck}), wie Abschnitt 31 verlangt. Ein
 * Schluessel fuer alles hiesse, dass ein abgefangener Update-Bezug auch die
 * Zugangsdaten oeffnet.</p>
 *
 * <p>Alte Schluessel werden nicht geloescht, sondern mit einem Ablosedatum
 * versehen. Grund: nach einem Tausch liegen auf dem Knoten unter Umstaenden
 * noch Umschlaege an den alten Schluessel, und die Frage "womit war das
 * verschluesselt" muss beantwortbar bleiben.</p>
 */
@Repository
public class SchluesselDaten {

    /** Wofuer ein Schluesselpaar da ist. */
    public enum Zweck {
        /** Richtet Zugangsdaten an genau diesen Knoten. */
        TRESOR,
        /** Sichert den Update-Bezug ab. */
        UPDATE
    }

    private final JdbcClient db;

    public SchluesselDaten(JdbcClient db) {
        this.db = db;
    }

    /**
     * Hinterlegt einen Schluessel und loest den bisherigen ab.
     *
     * <p>Beides in einem Schritt und in dieser Reihenfolge: erst abloesen,
     * dann anlegen. Andersherum gaebe es einen Augenblick mit zwei gueltigen
     * Schluesseln, und {@link #aktueller} muesste raten, welcher gemeint
     * ist.</p>
     */
    public void hinterlegen(String kennung, Zweck zweck, String oeffentlichPem) {
        String jetzt = Zeiten.text(Instant.now());
        db.sql("""
                UPDATE knoten_schluessel SET abgeloest = ?
                WHERE kennung = ? AND zweck = ? AND abgeloest IS NULL
                """)
                .params(jetzt, kennung, zweck.name())
                .update();
        db.sql("""
                INSERT INTO knoten_schluessel (kennung, zweck, oeffentlich, angelegt)
                VALUES (?, ?, ?, ?)
                """)
                .params(kennung, zweck.name(), oeffentlichPem, jetzt)
                .update();
    }

    /** Der gueltige Schluessel eines Knotens, sofern hinterlegt. */
    public Optional<String> aktueller(String kennung, Zweck zweck) {
        return db.sql("""
                SELECT oeffentlich FROM knoten_schluessel
                WHERE kennung = ? AND zweck = ? AND abgeloest IS NULL
                ORDER BY angelegt DESC LIMIT 1
                """)
                .params(kennung, zweck.name())
                .query(String.class)
                .optional();
    }

    public record Eintrag(String kennung, String zweck, String oeffentlich,
                          Instant angelegt, Instant abgeloest) {
        public boolean gueltig() {
            return abgeloest == null;
        }
    }

    public List<Eintrag> zuKnoten(String kennung) {
        return db.sql("""
                SELECT kennung, zweck, oeffentlich, angelegt, abgeloest
                FROM knoten_schluessel WHERE kennung = ? ORDER BY angelegt DESC
                """)
                .param(kennung)
                .query((rs, zeile) -> new Eintrag(
                        rs.getString("kennung"),
                        rs.getString("zweck"),
                        rs.getString("oeffentlich"),
                        Zeiten.zeit(rs.getString("angelegt")),
                        Zeiten.zeit(rs.getString("abgeloest"))))
                .list();
    }

    /** Wie viele Knoten einen gueltigen Tresor-Schluessel haben. */
    public int mitSchluessel(Zweck zweck) {
        Integer zahl = db.sql("""
                SELECT COUNT(DISTINCT kennung) FROM knoten_schluessel
                WHERE zweck = ? AND abgeloest IS NULL
                """)
                .param(zweck.name())
                .query(Integer.class)
                .single();
        return zahl == null ? 0 : zahl;
    }

    public void alleAbloesen(String kennung) {
        db.sql("""
                UPDATE knoten_schluessel SET abgeloest = ?
                WHERE kennung = ? AND abgeloest IS NULL
                """)
                .params(Zeiten.text(Instant.now()), kennung)
                .update();
    }
}
