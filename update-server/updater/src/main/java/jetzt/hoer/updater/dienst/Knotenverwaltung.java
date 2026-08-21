package jetzt.hoer.updater.dienst;

import jetzt.hoer.updater.daten.AnmeldungDaten;
import jetzt.hoer.updater.daten.AusweisDaten;
import jetzt.hoer.updater.daten.KnotenDaten;
import jetzt.hoer.updater.daten.VerwaltungDaten;
import jetzt.hoer.updater.modell.Modul;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Knoten anlegen, anmelden, sperren, in Wartung setzen.
 *
 * <p>Die eine Stelle, an der ein Knoten entsteht und Rechte bekommt. Bewusst
 * nicht im Controller: dieselben Schritte laufen ueber die Oberflaeche, ueber
 * die Maschinen-Schnittstelle und spaeter ueber die Hetzner-Automatik. Drei
 * Aufrufer, ein Ablauf - sonst laufen die drei auseinander, und zwar
 * ausgerechnet bei der Rechtevergabe.</p>
 */
@Service
public class Knotenverwaltung {

    private static final Logger log = LoggerFactory.getLogger(Knotenverwaltung.class);

    /**
     * Alphabet fuer Bootstrap-Token. Ohne {@code 0/O} und {@code 1/l/I} -
     * dieselbe Ueberlegung wie beim Aufsetz-Passwort: der Token wird
     * abgetippt, und diese Paare sind genau die, bei denen man sich vertut.
     */
    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyzACDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final KnotenDaten knoten;
    private final AusweisDaten ausweise;
    private final AnmeldungDaten anmeldungen;
    private final VerwaltungDaten protokoll;
    private final Zugang zugang;
    private final jetzt.hoer.updater.daten.SchluesselDaten schluessel;
    private final SecureRandom zufall = new SecureRandom();

    public Knotenverwaltung(KnotenDaten knoten, AusweisDaten ausweise,
                           AnmeldungDaten anmeldungen, VerwaltungDaten protokoll,
                           Zugang zugang,
                           jetzt.hoer.updater.daten.SchluesselDaten schluessel) {
        this.schluessel = schluessel;
        this.knoten = knoten;
        this.ausweise = ausweise;
        this.anmeldungen = anmeldungen;
        this.protokoll = protokoll;
        this.zugang = zugang;
    }

    /** Was der Verwalter genau einmal zu sehen bekommt. */
    public record Aufsetzhilfe(String kennung, String token) {
    }

    // ------------------------------------------------------------- Anlegen

    /**
     * Legt einen Knoten an und gibt seinen Bootstrap-Token zurueck.
     *
     * <p>Der Token wird hier im Klartext zurueckgegeben und ist danach nicht
     * mehr zu bekommen - gespeichert wird nur sein Hash. Das ist unbequem und
     * richtig: ein Token, den der Server jederzeit wieder anzeigen kann, ist
     * ein Geheimnis, das dauerhaft in einer Datenbank liegt.</p>
     *
     * @throws IllegalArgumentException bei unbrauchbarer Kennung oder wenn es
     *                                  sie schon gibt
     */
    @Transactional
    public Aufsetzhilfe anlegen(String rohKennung, String name, List<Modul> module, String wer) {
        String kennung = kennungPruefen(rohKennung);

        if (!knoten.anlegen(kennung, name, profilAus(module))) {
            throw new IllegalArgumentException("Die Kennung " + kennung + " gibt es bereits.");
        }
        for (Modul m : module) {
            ausweise.modulSetzen(kennung, m);
        }

        String token = tokenErzeugen();
        anmeldungen.anlegen(kennung + ":" + System.currentTimeMillis(), kennung, Zugang.hashen(token));

        protokoll.merken(wer, "Knoten angelegt", kennung,
                "Module: " + module.stream().map(Modul::name).toList(), "");
        log.info("Knoten {} angelegt mit Modulen {}", kennung, module);

        return new Aufsetzhilfe(kennung, token);
    }

    /**
     * Neuer Bootstrap-Token fuer einen bestehenden Knoten.
     *
     * <p>Die offenen Token werden dabei widerrufen. Sonst haette ein Knoten,
     * bei dem die Installation zweimal angestossen wurde, zwei gueltige
     * Eintrittskarten - und "einmalig" waere es nur je Token, nicht je
     * Knoten.</p>
     */
    @Transactional
    public String neuerToken(String kennung, String wer) {
        if (!knoten.gibtEs(kennung)) {
            throw new IllegalArgumentException("Unbekannter Knoten: " + kennung);
        }
        anmeldungen.alleWiderrufen(kennung);

        String token = tokenErzeugen();
        anmeldungen.anlegen(kennung + ":" + System.currentTimeMillis(), kennung, Zugang.hashen(token));

        protokoll.merken(wer, "Aufsetz-Token erneuert", kennung, "vorherige widerrufen", "");
        return token;
    }

    // ------------------------------------------------------------ Anmelden

    /** Was ein Knoten bei der Anmeldung ueber sich sagt. */
    public record Selbstauskunft(String rechnername, String privatIp,
                                 String ipv4, String ipv6, String agentVersion) {
    }

    /**
     * Gilt dieser Aufsetz-Token - ohne ihn zu verbrauchen?
     *
     * <p>Fuer den Download unter {@code /knoten/}. Bis dahin oeffnete den nur
     * das globale Aufsetz-Passwort, und ein Einzeiler zum Aufsetzen haette
     * damit zwei Geheimnisse gebraucht: eines zum Holen, eines zum Anmelden.
     * Auf der Knotenseite steht aber nur der Token.</p>
     *
     * <p>Der Token ist die bessere Wahl von beiden: er gilt zwei Stunden,
     * gehoert genau einem Knoten und laesst sich einzeln widerrufen. Das
     * globale Passwort gilt, bis jemand es tauscht.</p>
     *
     * <p><b>Nicht verbrauchen.</b> Ein Aufsetzlauf holt mehrere Dateien, und
     * die Anmeldung kommt erst danach. Wuerde schon der erste Download den
     * Token entwerten, koennte sich der Knoten nie anmelden.</p>
     */
    public boolean aufsetzTokenGueltig(String kennung, String token) {
        if (kennung == null || kennung.isBlank() || token == null || token.isBlank()) {
            return false;
        }
        return anmeldungen.gueltige(kennung, Zugang.hashen(token)).isPresent();
    }

    /**
     * Loest einen Bootstrap-Token gegen das dauerhafte Geheimnis ein.
     *
     * <p>Das Geheimnis wird <em>hier</em> erzeugt und nicht vom Knoten
     * mitgebracht. Ein Knoten, der sich sein eigenes Passwort aussucht, waehlt
     * es irgendwann schlecht - und die Guete dieses Werts ist die gesamte
     * Sicherheit des Verfahrens.</p>
     *
     * @return das Geheimnis im Klartext, genau einmal
     */
    public Optional<String> anmelden(String kennung, String token,
                                     Selbstauskunft auskunft, String ip) {
        Optional<String> anmeldungId = anmeldungen.gueltige(kennung, Zugang.hashen(token));
        if (anmeldungId.isEmpty()) {
            // Bewusst ohne Unterscheidung nach aussen: ob die Kennung nicht
            // existiert, der Token falsch ist oder er schon verbraucht wurde,
            // geht den Anrufer nichts an.
            log.warn("Anmeldung fuer {} von {} abgewiesen.", kennung, ip);
            protokoll.merken("(Knoten)", "Anmeldung abgewiesen", kennung, "Token ungueltig", ip);
            return Optional.empty();
        }

        String geheimnis = Zugang.geheimnisErzeugen();
        ausweise.geheimnisSetzen(kennung, Zugang.hashen(geheimnis));
        anmeldungen.verbrauchen(anmeldungId.get(), ip);

        if (auskunft != null) {
            knoten.angabenSetzen(kennung, auskunft.rechnername(), auskunft.privatIp(),
                    auskunft.ipv4(), auskunft.ipv6(), auskunft.agentVersion());
        }

        // Ohne das truege der Zwischenspeicher bis zu dreissig Sekunden lang
        // noch das alte Ergebnis - bei einer Neuanmeldung also ein Nein.
        zugang.verwerfen();

        protokoll.merken("(Knoten)", "Angemeldet", kennung, "Geheimnis erzeugt", ip);
        log.info("Knoten {} hat sich angemeldet ({}).", kennung, ip);
        return Optional.of(geheimnis);
    }

    // -------------------------------------------------------------- Sperren

    @Transactional
    public void sperren(String kennung, String grund, String wer) {
        ausweise.sperren(kennung, true, grund);
        anmeldungen.alleWiderrufen(kennung);
        zugang.verwerfen();
        protokoll.merken(wer, "Knoten gesperrt", kennung, grund, "");
        log.warn("Knoten {} gesperrt: {}", kennung, grund);
    }

    @Transactional
    public void entsperren(String kennung, String wer) {
        ausweise.sperren(kennung, false, "");
        zugang.verwerfen();
        protokoll.merken(wer, "Sperre aufgehoben", kennung, "", "");
    }

    /**
     * Tauscht das Geheimnis eines Knotens.
     *
     * <p>Der Knoten ist danach ausgesperrt, bis der neue Wert bei ihm
     * ankommt - es geht keine Verbindung von hier zu ihm. Das ist der Preis
     * dafuer, dass die Knoten hinter fremdem NAT stehen duerfen, und der
     * Grund, warum die Sperre ueber die Adresse der schnellere Griff bleibt.</p>
     */
    @Transactional
    public String geheimnisTauschen(String kennung, String wer) {
        if (!knoten.gibtEs(kennung)) {
            throw new IllegalArgumentException("Unbekannter Knoten: " + kennung);
        }
        String neu = Zugang.geheimnisErzeugen();
        ausweise.geheimnisSetzen(kennung, Zugang.hashen(neu));
        zugang.verwerfen();
        protokoll.merken(wer, "Geheimnis getauscht", kennung,
                "Knoten ist bis zum Nachtragen ausgesperrt", "");
        return neu;
    }

    /**
     * Entfernt einen Knoten vollstaendig - Eintrag, Ausweis, Module, Token.
     *
     * <p>Das eigentliche "Entfernen". Der Knopf in der Uebersicht loeschte
     * bisher nur die Zeile aus {@code knoten}; Ausweis und Geheimnis blieben,
     * der Knoten konnte sich weiter anmelden und stand beim naechsten
     * Herzschlag wieder da. Der Bestaetigungstext sagte das sogar - was die
     * Frage aufwirft, wozu der Knopf dann gut war.</p>
     *
     * <p>Reihenfolge: erst die offenen Aufsetz-Token widerrufen, dann den
     * Ausweis, dann den Eintrag. Andersherum bliebe zwischendurch ein Token
     * gueltig, dessen Knoten es nicht mehr gibt - und der wuerde beim
     * Einloesen einen Ausweis fuer eine geloeschte Kennung anlegen.</p>
     */
    @Transactional
    public void entfernen(String kennung, String wer) {
        anmeldungen.alleWiderrufen(kennung);
        ausweise.entfernen(kennung);
        schluessel.entfernen(kennung);
        knoten.loeschen(kennung);
        zugang.verwerfen();
        protokoll.merken(wer, "Knoten entfernt", kennung, "vollstaendig", "");
        log.warn("Knoten {} vollstaendig entfernt.", kennung);
    }

    // -------------------------------------------------------------- Wartung

    @Transactional
    public void wartung(String kennung, boolean an, String grund, String wer) {
        knoten.wartung(kennung, an, grund, wer);
        protokoll.merken(wer, an ? "In Wartung gesetzt" : "Wartung beendet", kennung,
                an ? grund : "", "");
        log.info("Knoten {} {}", kennung, an ? "in Wartung: " + grund : "wieder im Betrieb");
    }

    // ------------------------------------------------------------- Werkzeug

    /**
     * Eine Kennung muss in einen Docker-Benutzernamen, eine {@code .env}-Zeile
     * und einen Dateinamen passen. Deshalb eng gefasst: Kleinbuchstaben,
     * Ziffern und Bindestrich.
     */
    static String kennungPruefen(String roh) {
        if (roh == null || roh.isBlank()) {
            throw new IllegalArgumentException("Kennung fehlt.");
        }
        String k = roh.trim().toLowerCase(Locale.ROOT);
        if (!k.matches("[a-z0-9][a-z0-9-]{1,38}[a-z0-9]")) {
            throw new IllegalArgumentException(
                    "Kennung darf nur Kleinbuchstaben, Ziffern und Bindestriche enthalten "
                    + "(3 bis 40 Zeichen, nicht mit Bindestrich beginnen oder enden): " + roh);
        }
        return k;
    }

    /**
     * Der Token. Form {@code hj-XXXX-XXXX-XXXX} - dieselbe Bauart wie das
     * Aufsetz-Passwort, damit man beim Abtippen nicht umdenken muss. Rund
     * 71 Bit, und er lebt zwei Stunden.
     */
    private String tokenErzeugen() {
        StringBuilder bau = new StringBuilder("hj");
        for (int gruppe = 0; gruppe < 3; gruppe++) {
            bau.append('-');
            for (int i = 0; i < 4; i++) {
                bau.append(ALPHABET.charAt(zufall.nextInt(ALPHABET.length())));
            }
        }
        return bau.toString();
    }

    /**
     * Das Profil ist die kurze Beschreibung fuer die Uebersicht. Es ersetzt
     * die Module nicht - massgeblich fuer Rechte ist ausschliesslich
     * {@code knoten_modul}.
     */
    private static String profilAus(List<Modul> module) {
        return module.stream().map(Modul::anzeige).reduce((a, b) -> a + "+" + b).orElse("");
    }
}
