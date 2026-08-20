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
            letzte_ip, zuletzt_gemeldet, zuletzt_gesehen, update_angefordert,
            geheimnis, gesperrt, gesperrt_grund,
            wartung_seit, wartung_grund, wartung_von,
            rechnername, privat_ip, agent_version
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
                rs.getInt("update_angefordert") == 1,
                // Nur ob eines gesetzt ist, nie der Wert. Der Hash hat in
                // einem Modell, das an eine Vorlage geht, nichts verloren.
                !leer(rs.getString("geheimnis")).isBlank(),
                rs.getInt("gesperrt") == 1,
                leer(rs.getString("gesperrt_grund")),
                Zeiten.zeit(rs.getString("wartung_seit")),
                leer(rs.getString("wartung_grund")),
                leer(rs.getString("wartung_von")),
                leer(rs.getString("rechnername")),
                leer(rs.getString("privat_ip")),
                leer(rs.getString("agent_version")));
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
     * Legt einen Knoten an, bevor er das erste Mal da war.
     *
     * <p>Das ist die Umkehrung des bisherigen Wegs. Frueher entstand ein
     * Eintrag erst durch die Meldung des Knotens - was richtig war, solange
     * eine Kennung nichts bewies. Jetzt beweist sie etwas, und dann muss sie
     * hier vergeben werden und nicht dort behauptet.</p>
     *
     * @return false, wenn es die Kennung schon gibt
     */
    public boolean anlegen(String kennung, String name, String profil) {
        int zeilen = db.sql("""
                INSERT INTO knoten (kennung, name, profil, angelegt, update_angefordert)
                VALUES (?, ?, ?, ?, 0)
                ON CONFLICT(kennung) DO NOTHING
                """)
                .params(kennung, name == null ? "" : name,
                        profil == null ? "" : profil, Zeiten.text(Instant.now()))
                .update();
        return zeilen > 0;
    }

    /**
     * Der naechste freie Name zu einem Praefix.
     *
     * <p>Aus {@code lavalink} wird {@code lavalink-10}, wenn es bereits
     * {@code lavalink-9} gibt. Gezaehlt wird nach der <em>Zahl</em>, nicht
     * nach dem Text: sortiert man Namen alphabetisch, kommt {@code lavalink-9}
     * hinter {@code lavalink-10}, und der Vorschlag waere {@code lavalink-10}
     * - also der Name, den es schon gibt.</p>
     *
     * <p>Luecken werden nicht gefuellt. Fehlt {@code lavalink-3}, weil der
     * Knoten geloescht wurde, bleibt sie: eine Kennung taucht in Protokollen,
     * Sicherungsdateinamen und Freigaben auf, und sie ein zweites Mal zu
     * vergeben macht diese Spuren mehrdeutig.</p>
     */
    public String naechsteKennung(String praefix) {
        String sauber = praefix == null ? "" : praefix.trim().toLowerCase(java.util.Locale.ROOT);
        if (sauber.isBlank()) {
            return "";
        }
        return naechste(sauber, db.sql("SELECT kennung FROM knoten WHERE kennung LIKE ?")
                .param(sauber + "-%")
                .query(String.class)
                .list());
    }

    /**
     * Die Zaehlung selbst - ohne Datenbank, damit sie pruefbar ist.
     *
     * <p>Sichtbar fuer die Proben und sonst niemanden. Die Abfrage darueber
     * ist trivial, die Zaehlung nicht: sie ist der Grund, warum diese Methode
     * getrennt steht.</p>
     */
    static String naechste(String praefix, List<String> vorhandene) {
        int hoechste = 0;
        for (String k : vorhandene) {
            if (k == null || !k.startsWith(praefix + "-")) {
                continue;
            }
            String rest = k.substring(praefix.length() + 1);
            // Nur reine Zahlen. "lavalink-premium" darf die Zaehlung nicht
            // stoeren, und "lavalink-2b" ist keine 2.
            if (!rest.matches("[0-9]+")) {
                continue;
            }
            try {
                hoechste = Math.max(hoechste, Integer.parseInt(rest));
            } catch (NumberFormatException zuGross) {
                // Eine Zahl jenseits von int ist keine Nummerierung, sondern
                // ein Versehen. Uebergehen statt abbrechen.
            }
        }
        return praefix + "-" + (hoechste + 1);
    }

    public boolean gibtEs(String kennung) {
        Integer zahl = db.sql("SELECT COUNT(*) FROM knoten WHERE kennung = ?")
                .param(kennung)
                .query(Integer.class)
                .single();
        return zahl != null && zahl > 0;
    }

    /** Was der Knoten bei der Anmeldung ueber sich selbst mitteilt. */
    public void angabenSetzen(String kennung, String rechnername, String privatIp,
                              String ipv4, String ipv6, String agentVersion) {
        db.sql("""
                UPDATE knoten SET rechnername = ?, privat_ip = ?,
                    oeffentlich_ipv4 = ?, oeffentlich_ipv6 = ?, agent_version = ?
                WHERE kennung = ?
                """)
                .params(leer(rechnername), leer(privatIp), leer(ipv4), leer(ipv6),
                        leer(agentVersion), kennung)
                .update();
    }

    // ------------------------------------------------------------- Wartung

    /**
     * Setzt einen Knoten in Wartung oder holt ihn heraus.
     *
     * <p>Drei Spalten statt eines Schalters: die Uebersicht soll "seit wann,
     * warum und von wem" beantworten koennen. Beim Beenden werden alle drei
     * geleert - ein stehengebliebener Grund von letzter Woche waere
     * irrefuehrender als gar keiner.</p>
     */
    public void wartung(String kennung, boolean an, String grund, String von) {
        if (an) {
            db.sql("""
                    UPDATE knoten SET wartung_seit = ?, wartung_grund = ?, wartung_von = ?
                    WHERE kennung = ?
                    """)
                    .params(Zeiten.text(Instant.now()), leer(grund), leer(von), kennung)
                    .update();
        } else {
            db.sql("""
                    UPDATE knoten SET wartung_seit = NULL, wartung_grund = '', wartung_von = ''
                    WHERE kennung = ?
                    """)
                    .param(kennung)
                    .update();
        }
    }

    public boolean inWartung(String kennung) {
        return db.sql("SELECT wartung_seit FROM knoten WHERE kennung = ?")
                .param(kennung)
                .query(String.class)
                .optional()
                .filter(s -> s != null && !s.isBlank())
                .isPresent();
    }

    private static String leer(String s) {
        return s == null ? "" : s;
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
