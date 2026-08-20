package jetzt.hoer.updater.daten;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Was ein Mensch veranlasst hat.
 *
 * <p>Getrennt von {@code zugriff}: dort steht der Maschinenverkehr - je
 * Abbildschicht eine Zeile, zehntausende am Tag. Eine Verwaltungshandlung
 * darin zu suchen waere aussichtslos, und genau danach sucht man, wenn etwas
 * passiert ist.</p>
 *
 * <p><strong>Keine Geheimnisse.</strong> Weder Token noch Passwoerter noch
 * deren Hashes gehen hier hinein. Ein Protokoll wird gelesen, kopiert und
 * herumgeschickt - es ist der falsche Ort dafuer. Vermerkt wird, <em>dass</em>
 * ein Geheimnis erzeugt oder getauscht wurde, nie welches.</p>
 */
@Repository
public class VerwaltungDaten {

    private final JdbcClient db;

    public VerwaltungDaten(JdbcClient db) {
        this.db = db;
    }

    public void merken(String wer, String handlung, String ziel, String ergebnis, String quellIp) {
        db.sql("""
                INSERT INTO verwaltung_protokoll (zeit, wer, handlung, ziel, ergebnis, quell_ip)
                VALUES (?, ?, ?, ?, ?, ?)
                """)
                .params(Zeiten.text(Instant.now()),
                        leer(wer), leer(handlung), leer(ziel), leer(ergebnis), leer(quellIp))
                .update();
    }

    public record Eintrag(Instant zeit, String wer, String handlung,
                          String ziel, String ergebnis, String quellIp) {
    }

    public List<Eintrag> letzte(int wieviele) {
        return db.sql("""
                SELECT zeit, wer, handlung, ziel, ergebnis, quell_ip
                FROM verwaltung_protokoll ORDER BY zeit DESC LIMIT ?
                """)
                .param(wieviele)
                .query((rs, zeile) -> new Eintrag(
                        Zeiten.zeit(rs.getString("zeit")),
                        rs.getString("wer"),
                        rs.getString("handlung"),
                        rs.getString("ziel"),
                        rs.getString("ergebnis"),
                        rs.getString("quell_ip")))
                .list();
    }

    /** Was mit einem bestimmten Knoten geschehen ist. */
    public List<Eintrag> zuZiel(String ziel, int wieviele) {
        return db.sql("""
                SELECT zeit, wer, handlung, ziel, ergebnis, quell_ip
                FROM verwaltung_protokoll WHERE ziel = ? ORDER BY zeit DESC LIMIT ?
                """)
                .params(ziel, wieviele)
                .query((rs, zeile) -> new Eintrag(
                        Zeiten.zeit(rs.getString("zeit")),
                        rs.getString("wer"),
                        rs.getString("handlung"),
                        rs.getString("ziel"),
                        rs.getString("ergebnis"),
                        rs.getString("quell_ip")))
                .list();
    }

    public int aelterLoeschen(int tage) {
        return db.sql("DELETE FROM verwaltung_protokoll WHERE zeit < ?")
                .param(Zeiten.text(Instant.now().minusSeconds((long) tage * 24 * 3600)))
                .update();
    }

    private static String leer(String s) {
        return s == null ? "" : s;
    }
}
