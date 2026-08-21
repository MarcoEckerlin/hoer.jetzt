package jetzt.hoer.updater.dienst;

import jetzt.hoer.updater.daten.AusweisDaten;
import jetzt.hoer.updater.modell.Ausweis;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wer darf herein - und als wer.
 *
 * <h2>Wie sich das geaendert hat</h2>
 *
 * Frueher gab es genau zwei Passwoerter, und der Benutzername wurde gelesen
 * und verworfen: "es gibt genau ein Passwort je Bereich; ein zusaetzlicher
 * Name waere ein zweites Geheimnis, das keines ist - er steht in jeder
 * Anleitung."
 *
 * Das stimmte, solange alle Knoten dasselbe Passwort teilten. Genau daran hing
 * aber die Luecke: ein aufgemachter Audio-Knoten gab Bot-Token, Datenbank-
 * Passwort und Client-Secret preis, und widerrufen liess sich nur die Adresse
 * - die wechselt, sobald eine Hetzner-Maschine neu aufgesetzt wird.
 *
 * Jetzt traegt der Benutzername die Kennung des Knotens. Das ist der kleinste
 * moegliche Eingriff: Basic-Auth schickt beide Felder ohnehin, "docker login"
 * kann es unveraendert, und es kommt kein zweites Verfahren dazu.
 *
 * <h2>Warum SHA-256 und nicht bcrypt</h2>
 *
 * Ein Knoten-Geheimnis ist 256 Bit aus {@link SecureRandom} - kein von einem
 * Menschen gewaehltes Passwort. bcrypts Arbeitsfaktor schuetzt gegen das
 * Durchprobieren wahrscheinlicher Eingaben; bei gleichverteiltem Zufall in
 * dieser Groesse gibt es nichts durchzuprobieren. Dazu kaeme bcrypts Grenze
 * bei 72 Byte und das Dollarzeichen im Hash, das Docker Compose in der
 * {@code .env} als Variable liest - beides bereits einmal teuer gewesen.
 *
 * Der Klartext steht damit nirgends mehr: wer die Datenbank liest, bekommt
 * Hashes. Vorher stand das gemeinsame Passwort im Klartext in der
 * Konfiguration.
 */
@Service
public class Zugang {

    /**
     * Wie lange eine Anmeldung wiederverwendet wird.
     *
     * <p>Kein Feinschliff, sondern noetig: ein {@code docker pull} fragt je
     * Abbildschicht einmal an. Ohne Zwischenspeicher waere das je Schicht eine
     * Datenbankabfrage plus ein Hash - bei vier Abbildern mehrere hundert in
     * wenigen Sekunden. Dieselbe Ueberlegung wie im {@link Torwaechter}, und
     * bewusst dieselbe Dauer.</p>
     */
    private static final Duration HALTBAR = Duration.ofSeconds(30);

    private record Gemerkt(Ausweis ausweis, Instant ablauf) {
    }

    private final byte[] gemeinsamesGeheimnis;
    private final byte[] aufsetzen;

    /** Der Name, unter dem der Update-Server selbst in seine Registry schiebt. */
    public static final String VEROEFFENTLICHER = "veroeffentlichen";
    private final byte[] veroeffentlichen;
    private final boolean gemeinsamErlaubt;
    private final AusweisDaten ausweise;

    private final Map<String, Gemerkt> zwischenspeicher = new ConcurrentHashMap<>();

    public Zugang(@Value("${hj.token.knoten}") String knoten,
                  @Value("${hj.token.aufsetzen}") String aufsetzen,
                  @Value("${hj.token.veroeffentlichen:}") String veroeffentlichen,
                  // Vorgabe aus. Ein Knoten ohne eigenes Geheimnis kommt
                  // damit nirgends durch - siehe application.yml.
                  @Value("${hj.token.gemeinsam-erlauben:false}") boolean gemeinsamErlaubt,
                  AusweisDaten ausweise) {
        this.gemeinsamesGeheimnis = knoten.trim().getBytes(StandardCharsets.UTF_8);
        this.aufsetzen = aufsetzen.trim().getBytes(StandardCharsets.UTF_8);
        this.veroeffentlichen = veroeffentlichen.trim().getBytes(StandardCharsets.UTF_8);
        this.gemeinsamErlaubt = gemeinsamErlaubt;
        this.ausweise = ausweise;
    }

    /**
     * Prueft die Anmeldung eines Knotens.
     *
     * <p>Zuerst der benannte Knoten, danach - und nur wenn erlaubt - das
     * gemeinsame Passwort. Die Reihenfolge ist wichtig fuer den Uebergang:
     * ein bereits umgestellter Knoten soll nicht versehentlich als
     * "gemeinsam" durchgehen und damit wieder alle Rechte bekommen.</p>
     *
     * @param kopf der vollstaendige Authorization-Kopf
     * @return leer, wenn die Anmeldung nicht stimmt
     */
    public Optional<Ausweis> anmelden(String kopf) {
        Anmeldedaten daten = zerlegen(kopf);
        if (daten == null) {
            return Optional.empty();
        }

        String schluessel = daten.benutzer() + " " + hashen(daten.passwort());
        Instant jetzt = Instant.now();

        Gemerkt gemerkt = zwischenspeicher.get(schluessel);
        if (gemerkt != null && gemerkt.ablauf().isAfter(jetzt)) {
            return Optional.ofNullable(gemerkt.ausweis());
        }

        Ausweis ausweis = pruefen(daten);
        zwischenspeicher.put(schluessel, new Gemerkt(ausweis, jetzt.plus(HALTBAR)));
        return Optional.ofNullable(ausweis);
    }

    private Ausweis pruefen(Anmeldedaten daten) {
        // Der Veroeffentlicher.
        //
        // Der Update-Server schiebt seine eigenen Abbilder ueber
        // 127.0.0.1 in die eigene Registry. Er ist dabei kein Knoten - er
        // hat keine Kennung, keine Module und kein Geheimnis aus der
        // Anmeldung.
        //
        // Vorher benutzte er dafuer den Benutzer "knoten" mit dem
        // gemeinsamen Knoten-Passwort. Seit hj.token.gemeinsam-erlauben auf
        // false steht, wird das abgewiesen - und das Veroeffentlichen
        // scheiterte mit "no basic auth credentials". Richtig so: das
        // gemeinsame Passwort soll nicht mehr gelten.
        //
        // Also ein eigener Zugang mit eigenem Passwort. Er darf alles, was
        // die Registry braucht, und existiert nur auf diesem Host: das
        // Passwort steht in der .env des Update-Servers und wird nirgends
        // ausgeliefert.
        if (VEROEFFENTLICHER.equals(daten.benutzer())
                && veroeffentlichen.length > 0
                && MessageDigest.isEqual(daten.passwort().getBytes(StandardCharsets.UTF_8),
                                         veroeffentlichen)) {
            return Ausweis.fuer(VEROEFFENTLICHER,
                    java.util.EnumSet.allOf(jetzt.hoer.updater.modell.Faehigkeit.class));
        }

        if (!daten.benutzer().isBlank()) {
            Optional<String> hash = ausweise.geheimnisHash(daten.benutzer());
            if (hash.isPresent()) {
                if (!MessageDigest.isEqual(hashen(daten.passwort()).getBytes(StandardCharsets.UTF_8),
                                           hash.get().getBytes(StandardCharsets.UTF_8))) {
                    // Ein benannter Knoten mit falschem Passwort faellt hier
                    // durch und nicht in den gemeinsamen Fall darunter. Sonst
                    // liesse sich die Knotenpruefung umgehen, indem man einen
                    // beliebigen Namen und das alte Passwort schickt.
                    return null;
                }
                return Ausweis.fuer(daten.benutzer(), ausweise.faehigkeiten(daten.benutzer()));
            }
        }

        if (gemeinsamErlaubt
                && MessageDigest.isEqual(daten.passwort().getBytes(StandardCharsets.UTF_8),
                                         gemeinsamesGeheimnis)) {
            return Ausweis.mitGemeinsamemPasswort();
        }
        return null;
    }

    /** Das kurze Passwort fuer {@code /knoten/}. Unveraendert. */
    public boolean aufsetzPasswort(String kopf) {
        Anmeldedaten daten = zerlegen(kopf);
        return daten != null
                && MessageDigest.isEqual(daten.passwort().getBytes(StandardCharsets.UTF_8), aufsetzen);
    }

    /**
     * Die Anmeldedaten zerlegt - fuer Aufrufer, die selbst entscheiden wollen.
     *
     * <p>Gebraucht fuer den Aufsetz-Token: er steht als Passwort im Kopf, die
     * Kennung des Knotens als Benutzername. Beides zusammen prueft
     * {@code Knotenverwaltung}, nicht diese Klasse - hier liegt nur das
     * Zerlegen.</p>
     */
    public Anmeldedaten anmeldedaten(String kopf) {
        return zerlegen(kopf);
    }

    /**
     * Nach jeder Aenderung an Knoten, Modulen oder Faehigkeiten aufzurufen.
     * Ohne das wirkte eine Sperre erst nach Ablauf der Haltbarkeit - und genau
     * in dem Moment, in dem man einen Knoten sperrt, will man nicht warten.
     */
    public void verwerfen() {
        zwischenspeicher.clear();
    }

    // -------------------------------------------------------------- Werkzeug

    /**
     * Erzeugt ein neues Knoten-Geheimnis. 256 Bit, Base64 ohne Polsterung -
     * kurz genug fuer eine {@code .env}-Zeile und ohne Zeichen, die Docker
     * Compose oder eine Shell umdeuten wuerden.
     */
    public static String geheimnisErzeugen() {
        byte[] roh = new byte[32];
        new SecureRandom().nextBytes(roh);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(roh);
    }

    public static String hashen(String klartext) {
        try {
            byte[] summe = MessageDigest.getInstance("SHA-256")
                    .digest(klartext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(summe);
        } catch (NoSuchAlgorithmException nichtMoeglich) {
            // SHA-256 gehoert zum Pflichtumfang jeder JVM.
            throw new IllegalStateException("SHA-256 fehlt", nichtMoeglich);
        }
    }

    public record Anmeldedaten(String benutzer, String passwort) {
    }

    private static Anmeldedaten zerlegen(String kopf) {
        if (kopf == null || !kopf.regionMatches(true, 0, "Basic ", 0, 6)) {
            return null;
        }
        try {
            String roh = new String(Base64.getDecoder().decode(kopf.substring(6).trim()),
                    StandardCharsets.UTF_8);
            int doppel = roh.indexOf(':');
            if (doppel < 0) {
                return new Anmeldedaten("", "");
            }
            return new Anmeldedaten(roh.substring(0, doppel), roh.substring(doppel + 1));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
