package jetzt.hoer.updater.daten;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Die Bootstrap-Token: kurzlebig, einmalig, widerrufbar, knotenspezifisch.
 *
 * <p>Sie loesen fuer neue Knoten das gemeinsame Aufsetz-Passwort ab. Der
 * Ablauf dreht sich dabei um: erst wird der Knoten hier angelegt und bekommt
 * einen Token, dann laeuft die Installation. Der Server kennt den Knoten
 * also, bevor dieser zum ersten Mal anklopft.</p>
 *
 * <p>Das ist nicht nur Formsache. Das bisherige Aufsetz-Passwort oeffnet
 * {@code /knoten/} fuer jeden, der es hat, beliebig oft und ohne Ablauf - es
 * steht auf jedem Host, der je aufgesetzt wurde. Ein Bootstrap-Token oeffnet
 * genau eine Anmeldung fuer genau einen Knoten.</p>
 */
@Repository
public class AnmeldungDaten {

    /**
     * Wie lange ein Token gilt.
     *
     * <p>Zwei Stunden: lang genug fuer "Server bestellen, warten bis er da
     * ist, Skript laufen lassen", kurz genug, dass ein vergessener Token nicht
     * wochenlang in einer Zwischenablage weiterlebt. Verlaengern geht durch
     * einen neuen Token - das ist ein Klick und hinterlaesst eine Spur.</p>
     */
    public static final long GUELTIG_STUNDEN = 2;

    private final JdbcClient db;

    public AnmeldungDaten(JdbcClient db) {
        this.db = db;
    }

    /**
     * @param tokenHash SHA-256 des Tokens - der Klartext wird genau einmal
     *                  angezeigt und danach nirgends gespeichert
     */
    public void anlegen(String anmeldungId, String kennung, String tokenHash) {
        Instant jetzt = Instant.now();
        db.sql("""
                INSERT INTO knoten_anmeldung
                    (anmeldung_id, kennung, token_hash, angelegt, laeuft_ab, widerrufen)
                VALUES (?, ?, ?, ?, ?, 0)
                """)
                .params(anmeldungId, kennung, tokenHash,
                        Zeiten.text(jetzt),
                        Zeiten.text(jetzt.plusSeconds(GUELTIG_STUNDEN * 3600)))
                .update();
    }

    /**
     * Sucht einen gueltigen Token fuer diese Kennung.
     *
     * <p>Die Bedingungen stehen bewusst alle in der Abfrage und nicht im
     * Java-Code darueber: verbraucht, widerrufen und abgelaufen sind drei
     * Wege, auf denen ein Token ungueltig wird, und jeder einzelne davon muss
     * greifen. In drei getrennten Pruefungen vergisst man eine.</p>
     *
     * @return die Anmeldungs-Kennung, wenn der Token passt
     */
    public Optional<String> gueltige(String kennung, String tokenHash) {
        return db.sql("""
                SELECT anmeldung_id FROM knoten_anmeldung
                WHERE kennung = ?
                  AND token_hash = ?
                  AND verbraucht IS NULL
                  AND widerrufen = 0
                  AND laeuft_ab > ?
                """)
                .params(kennung, tokenHash, Zeiten.text(Instant.now()))
                .query(String.class)
                .optional();
    }

    /**
     * Einmalig heisst einmalig. Wird unmittelbar nach der erfolgreichen
     * Anmeldung aufgerufen - und nicht geloescht, damit die Frage "wer hat
     * sich wann womit angemeldet" beantwortbar bleibt.
     */
    public void verbrauchen(String anmeldungId, String ip) {
        db.sql("""
                UPDATE knoten_anmeldung
                SET verbraucht = ?, verbraucht_von = ?
                WHERE anmeldung_id = ? AND verbraucht IS NULL
                """)
                .params(Zeiten.text(Instant.now()), ip == null ? "" : ip, anmeldungId)
                .update();
    }

    public void widerrufen(String anmeldungId) {
        db.sql("UPDATE knoten_anmeldung SET widerrufen = 1 WHERE anmeldung_id = ?")
                .param(anmeldungId)
                .update();
    }

    /** Alle offenen Token eines Knotens widerrufen - etwa beim Sperren. */
    public void alleWiderrufen(String kennung) {
        db.sql("UPDATE knoten_anmeldung SET widerrufen = 1 WHERE kennung = ? AND verbraucht IS NULL")
                .param(kennung)
                .update();
    }

    public record Offen(String anmeldungId, String kennung, Instant laeuftAb) {
    }

    /** Was gerade aussteht - fuer die Uebersicht. */
    public List<Offen> offene() {
        return db.sql("""
                SELECT anmeldung_id, kennung, laeuft_ab FROM knoten_anmeldung
                WHERE verbraucht IS NULL AND widerrufen = 0 AND laeuft_ab > ?
                ORDER BY laeuft_ab
                """)
                .param(Zeiten.text(Instant.now()))
                .query((rs, zeile) -> new Offen(
                        rs.getString("anmeldung_id"),
                        rs.getString("kennung"),
                        Zeiten.zeit(rs.getString("laeuft_ab"))))
                .list();
    }

    /**
     * Raeumt ab, was seit einer Woche abgelaufen ist. Die verbrauchten bleiben
     * - sie sind die Spur, um die es geht.
     */
    public int aufraeumen() {
        return db.sql("""
                DELETE FROM knoten_anmeldung
                WHERE verbraucht IS NULL AND laeuft_ab < ?
                """)
                .param(Zeiten.text(Instant.now().minusSeconds(7 * 24 * 3600)))
                .update();
    }
}
