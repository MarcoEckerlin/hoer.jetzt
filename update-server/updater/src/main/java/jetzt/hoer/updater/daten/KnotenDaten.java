package jetzt.hoer.updater.daten;

import jetzt.hoer.updater.modell.Knoten;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Was die Knoten von sich gemeldet haben. */
@Repository
public class KnotenDaten {

    private final JdbcClient db;

    public KnotenDaten(JdbcClient db) {
        this.db = db;
    }

    private static final String SPALTEN = """
            kennung, name, profil, version, vorher, zustand, ergebnis,
            letzte_ip, zuletzt_gemeldet, zuletzt_gesehen, update_angefordert
            """;

    private Knoten lesen(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Knoten(
                rs.getString("kennung"),
                rs.getString("name"),
                rs.getString("profil"),
                rs.getString("version"),
                rs.getString("vorher"),
                rs.getString("zustand"),
                rs.getString("ergebnis"),
                rs.getString("letzte_ip"),
                Zeiten.zeit(rs.getString("zuletzt_gemeldet")),
                Zeiten.zeit(rs.getString("zuletzt_gesehen")),
                rs.getInt("update_angefordert") == 1);
    }

    public List<Knoten> alle() {
        return db.sql("SELECT " + SPALTEN + " FROM knoten ORDER BY name, kennung")
                .query((rs, zeile) -> lesen(rs))
                .list();
    }

    public Optional<Knoten> einer(String kennung) {
        return db.sql("SELECT " + SPALTEN + " FROM knoten WHERE kennung = ?")
                .param(kennung)
                .query((rs, zeile) -> lesen(rs))
                .optional();
    }

    /**
     * Der Herzschlag nach einem Update-Lauf.
     *
     * Der Name wird nur beim ersten Mal aus der Meldung uebernommen: in der
     * Oberflaeche darf man ihn aendern, und ein naechtlicher Lauf soll diese
     * Aenderung nicht jedes Mal wieder ueberschreiben.
     */
    public void melden(String kennung, String name, String profil, String version,
                       String vorher, String zustand, String ergebnis, String ip) {
        String jetzt = Zeiten.text(Instant.now());
        db.sql("""
                INSERT INTO knoten (kennung, name, profil, version, vorher, zustand,
                                    ergebnis, letzte_ip, zuletzt_gemeldet, zuletzt_gesehen,
                                    update_angefordert)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                ON CONFLICT(kennung) DO UPDATE SET
                    name             = CASE WHEN knoten.name = '' THEN excluded.name ELSE knoten.name END,
                    profil           = excluded.profil,
                    version          = excluded.version,
                    vorher           = excluded.vorher,
                    zustand          = excluded.zustand,
                    ergebnis         = excluded.ergebnis,
                    letzte_ip        = excluded.letzte_ip,
                    zuletzt_gemeldet = excluded.zuletzt_gemeldet,
                    zuletzt_gesehen  = excluded.zuletzt_gesehen,
                    update_angefordert = 0
                """)
                .params(kennung, name == null ? "" : name, profil, version,
                        vorher, zustand, ergebnis, ip, jetzt, jetzt)
                .update();
    }

    /**
     * Nur "war da" - ohne Meldung. Kommt vom Torwaechter, wenn ein Knoten
     * Abbilder zieht. Legt bewusst keinen Knoten an: ein Zugriff allein sagt
     * noch nicht, wer es war, und die Uebersicht soll keine Karteileichen aus
     * Adressen bekommen, hinter denen nie ein Knoten stand.
     */
    public void gesehen(String kennung) {
        db.sql("UPDATE knoten SET zuletzt_gesehen = ? WHERE kennung = ?")
                .params(Zeiten.text(Instant.now()), kennung)
                .update();
    }

    public void umbenennen(String kennung, String name) {
        db.sql("UPDATE knoten SET name = ? WHERE kennung = ?").params(name, kennung).update();
    }

    /**
     * Merker fuer "beim naechsten Mal sofort aktualisieren". Es geht keine
     * Verbindung zum Knoten - er holt den Merker beim naechsten Herzschlag ab.
     */
    public void updateAnfordern(String kennung, boolean an) {
        db.sql("UPDATE knoten SET update_angefordert = ? WHERE kennung = ?")
                .params(an ? 1 : 0, kennung)
                .update();
    }

    public void loeschen(String kennung) {
        db.sql("DELETE FROM knoten WHERE kennung = ?").param(kennung).update();
    }
}
