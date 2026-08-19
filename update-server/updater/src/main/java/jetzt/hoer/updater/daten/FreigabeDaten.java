package jetzt.hoer.updater.daten;

import jetzt.hoer.updater.modell.Freigabe;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/** Die Freischaltungen. */
@Repository
public class FreigabeDaten {

    private final JdbcClient db;

    public FreigabeDaten(JdbcClient db) {
        this.db = db;
    }

    public List<Freigabe> alle() {
        return db.sql("""
                SELECT id, bereich, name, notiz, angelegt, laeuft_ab, aktiv
                  FROM freigabe
                 ORDER BY aktiv DESC, angelegt DESC
                """)
                .query((rs, zeile) -> new Freigabe(
                        rs.getLong("id"),
                        rs.getString("bereich"),
                        rs.getString("name"),
                        rs.getString("notiz"),
                        Zeiten.zeit(rs.getString("angelegt")),
                        Zeiten.zeit(rs.getString("laeuft_ab")),
                        rs.getInt("aktiv") == 1))
                .list();
    }

    /** Nur die, die gerade zaehlen - das ist die Liste, die der Torwaechter braucht. */
    public List<Freigabe> gueltige() {
        Instant jetzt = Instant.now();
        return alle().stream().filter(f -> f.gueltig(jetzt)).toList();
    }

    public void anlegen(String bereich, String name, String notiz, Instant laeuftAb) {
        // Beim zweiten Anlegen desselben Bereichs die vorhandene Zeile wieder
        // scharf schalten, statt an der Eindeutigkeit zu scheitern. Der Fall
        // tritt genau dann ein, wenn eine Maschine neu aufgesetzt wird - und
        // dann will man sie freischalten, nicht eine Fehlermeldung lesen.
        db.sql("""
                INSERT INTO freigabe (bereich, name, notiz, angelegt, laeuft_ab, aktiv)
                VALUES (?, ?, ?, ?, ?, 1)
                ON CONFLICT(bereich) DO UPDATE SET
                    name      = excluded.name,
                    notiz     = excluded.notiz,
                    angelegt  = excluded.angelegt,
                    laeuft_ab = excluded.laeuft_ab,
                    aktiv     = 1
                """)
                .params(bereich, name, notiz, Zeiten.text(Instant.now()), Zeiten.text(laeuftAb))
                .update();
    }

    /**
     * Sperren heisst hier: stehen lassen, aber unwirksam. Loeschen wuerde die
     * Spur mitnehmen, und die Frage "wer war das nochmal und wann hatte der
     * Zugang" stellt sich genau dann, wenn etwas passiert ist.
     */
    public void sperren(long id) {
        db.sql("UPDATE freigabe SET aktiv = 0 WHERE id = ?").param(id).update();
    }

    public void freigeben(long id) {
        db.sql("UPDATE freigabe SET aktiv = 1 WHERE id = ?").param(id).update();
    }

    public void loeschen(long id) {
        db.sql("DELETE FROM freigabe WHERE id = ?").param(id).update();
    }
}
