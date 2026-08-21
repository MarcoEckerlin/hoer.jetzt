package jetzt.hoer.updater.dienst;

import jetzt.hoer.updater.daten.VerwaltungDaten;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Releases: was gilt, was galt, und wie man zurueckkommt.
 *
 * <h2>Warum es das braucht</h2>
 *
 * Veroeffentlichen ging bisher nur vorwaerts. {@code veroeffentlichen.sh}
 * ueberschrieb {@code release/aktuell}, und damit war der vorherige Stand
 * weg - nicht die Abbilder, die liegen weiter in der Registry, aber die
 * Angabe, welche zusammengehoerten. Ein Zurueckrollen hiess: die alte
 * Versionsnummer von Hand in eine Datei schreiben, die man erst finden muss.
 *
 * <p>Genau dann, wenn man es braucht, ist das der falsche Moment dafuer.</p>
 *
 * <h2>Wie der Verlauf entsteht</h2>
 *
 * {@code veroeffentlichen.sh} legt jedes Manifest zusaetzlich unter
 * {@code release/verlauf/&lt;version&gt;} ab. Zurueckrollen ist dann das
 * Zurueckkopieren einer Datei - kein Neubau, kein Nachdenken darueber,
 * welche Abbild-Marken zusammengehoerten. Sie stehen ja darin.
 *
 * <h2>Was Zurueckrollen nicht tut</h2>
 *
 * Es fasst keinen Knoten an. Es geht keine Verbindung von hier zu ihnen -
 * sie holen sich den Stand beim naechsten Herzschlag. Wer es eilig hat,
 * merkt die Knoten zusaetzlich zum Update vor; dann ist es beim naechsten
 * Lauf des Agenten da, statt in der Nacht.
 */
@Service
public class Releaseverwaltung {

    private static final Logger log = LoggerFactory.getLogger(Releaseverwaltung.class);

    /** Was ein Release ausmacht - so, wie es im Manifest steht. */
    public record Stand(String version, Instant abgelegt, Map<String, String> teile,
                        boolean gilt) {
    }

    private final Path ausliefern;
    private final VerwaltungDaten protokoll;

    public Releaseverwaltung(@Value("${hj.ausliefern:/srv/ausliefern}") String ausliefern,
                             VerwaltungDaten protokoll) {
        this.ausliefern = Path.of(ausliefern);
        this.protokoll = protokoll;
    }

    private Path aktuell() {
        return ausliefern.resolve("release").resolve("aktuell");
    }

    private Path verlauf() {
        return ausliefern.resolve("release").resolve("verlauf");
    }

    /** Die Version, die gerade gilt - oder leer, wenn noch nichts veroeffentlicht ist. */
    public String laufendeVersion() {
        return wert(lesen(aktuell()), "version");
    }

    /**
     * Der laufende Stand mit allen Angaben.
     *
     * @return {@code null}, wenn noch nichts veroeffentlicht wurde
     */
    public Stand laufend() {
        String inhalt = lesen(aktuell());
        String version = wert(inhalt, "version");
        if (version.isBlank()) {
            return null;
        }
        return new Stand(version, zeitpunkt(aktuell()), teile(inhalt), true);
    }

    /**
     * Alle abgelegten Staende, neueste zuerst.
     *
     * <p>Sortiert nach Ablagezeit und nicht nach Namen: Versionen wie
     * {@code 2026.08.21.01} sortieren sich zwar zufaellig richtig, aber
     * darauf ist kein Verlass - eine Nachlieferung {@code 2026.08.21.01b}
     * stuende sonst vor {@code 2026.08.21.02}.</p>
     */
    public List<Stand> verlaufListe() {
        Path v = verlauf();
        if (!Files.isDirectory(v)) {
            return List.of();
        }
        String laeuft = laufendeVersion();
        try (Stream<Path> dateien = Files.list(v)) {
            List<Stand> liste = new ArrayList<>();
            for (Path p : dateien.filter(Files::isRegularFile).toList()) {
                String inhalt = lesen(p);
                String version = wert(inhalt, "version");
                if (version.isBlank()) {
                    version = p.getFileName().toString();
                }
                liste.add(new Stand(version, zeitpunkt(p), teile(inhalt),
                        version.equals(laeuft)));
            }
            liste.sort(Comparator.comparing(Stand::abgelegt,
                    Comparator.nullsLast(Comparator.reverseOrder())));
            return liste;
        } catch (IOException nichtLesbar) {
            log.warn("Verlauf nicht lesbar: {}", nichtLesbar.getMessage());
            return List.of();
        }
    }

    /**
     * Auf einen frueheren Stand zurueckgehen.
     *
     * <p>Vorher wird der laufende Stand in den Verlauf gelegt, falls er dort
     * fehlt. Sonst waere der Weg zurueck aus dem Rueckweg versperrt - man
     * rollt einmal zurueck und kommt nicht wieder vor.</p>
     *
     * @throws IllegalArgumentException wenn es diesen Stand nicht gibt
     */
    public void zurueckAuf(String version, String wer) {
        String sauber = sicherName(version);
        Path quelle = verlauf().resolve(sauber);
        if (!Files.isRegularFile(quelle)) {
            throw new IllegalArgumentException("Diesen Stand gibt es nicht: " + version);
        }

        String vorher = laufendeVersion();
        if (!vorher.isBlank() && !vorher.equals(sauber)) {
            sichern(vorher);
        }

        try {
            Files.createDirectories(aktuell().getParent());
            // Erst daneben, dann umbenennen: bricht es mittendrin ab, holt
            // sich kein Knoten ein halbes Manifest. Dieselbe Ueberlegung wie
            // in lib.sh beim Schreiben ins Auslieferungsverzeichnis.
            Path neben = aktuell().resolveSibling("aktuell.neu");
            Files.copy(quelle, neben, StandardCopyOption.REPLACE_EXISTING);
            Files.move(neben, aktuell(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException schreibfehler) {
            throw new IllegalStateException(
                    "Manifest liess sich nicht umschalten: " + schreibfehler.getMessage(),
                    schreibfehler);
        }

        protokoll.merken(wer, "Release zurueckgerollt", sauber,
                "vorher " + (vorher.isBlank() ? "(keines)" : vorher), "");
        log.warn("Release zurueckgerollt: {} -> {} (durch {})", vorher, sauber, wer);
    }

    /**
     * Einen Stand aus dem Verlauf entfernen.
     *
     * <p>Der laufende laesst sich nicht loeschen - er ist die Antwort auf die
     * Frage, was gerade gilt. Die Abbilder bleiben ohnehin in der Registry;
     * hier verschwindet nur der Zettel, welche zusammengehoerten.</p>
     */
    public void entfernen(String version, String wer) {
        String sauber = sicherName(version);
        if (sauber.equals(laufendeVersion())) {
            throw new IllegalArgumentException(
                    "Der laufende Stand laesst sich nicht entfernen. Erst zurueckrollen.");
        }
        try {
            if (Files.deleteIfExists(verlauf().resolve(sauber))) {
                protokoll.merken(wer, "Release aus dem Verlauf entfernt", sauber, "", "");
            }
        } catch (IOException fehler) {
            throw new IllegalStateException("Nicht loeschbar: " + fehler.getMessage(), fehler);
        }
    }

    /** Den laufenden Stand in den Verlauf legen, falls er dort fehlt. */
    public void sichern(String version) {
        String sauber = sicherName(version);
        try {
            Files.createDirectories(verlauf());
            Path ziel = verlauf().resolve(sauber);
            if (!Files.exists(ziel) && Files.isRegularFile(aktuell())) {
                Files.copy(aktuell(), ziel);
            }
        } catch (IOException fehler) {
            log.warn("Stand {} liess sich nicht sichern: {}", sauber, fehler.getMessage());
        }
    }

    // ----------------------------------------------------------- Werkzeug

    /**
     * Ein Dateiname, kein Pfad.
     *
     * <p>Die Version kommt aus einem Formular. Ohne diese Pruefung liesse
     * sich mit {@code ../../etc/irgendwas} eine beliebige Datei ins Manifest
     * kopieren - und mit {@code entfernen} eine beliebige loeschen.</p>
     */
    private static String sicherName(String roh) {
        if (roh == null || roh.isBlank()) {
            throw new IllegalArgumentException("Keine Version angegeben.");
        }
        String s = roh.trim();
        if (!s.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("Unbrauchbare Version: " + roh);
        }
        return s;
    }

    private String lesen(Path p) {
        try {
            return Files.isRegularFile(p) ? Files.readString(p, StandardCharsets.UTF_8) : "";
        } catch (IOException nichtLesbar) {
            return "";
        }
    }

    private Instant zeitpunkt(Path p) {
        try {
            return Files.isRegularFile(p) ? Files.getLastModifiedTime(p).toInstant() : null;
        } catch (IOException unbekannt) {
            return null;
        }
    }

    private static String wert(String inhalt, String schluessel) {
        for (String zeile : inhalt.split("\n")) {
            String z = zeile.trim();
            if (z.startsWith(schluessel + "=")) {
                return z.substring(schluessel.length() + 1).trim();
            }
        }
        return "";
    }

    /** Die Komponentenzeilen - core, lavalink, web, ki-radio. */
    private static Map<String, String> teile(String inhalt) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String zeile : inhalt.split("\n")) {
            String z = zeile.trim();
            if (z.isEmpty() || z.startsWith("#") || !z.contains("=")) {
                continue;
            }
            String name = z.substring(0, z.indexOf('=')).trim();
            // version und registry sind Angaben ueber das Release, keine
            // Komponenten. Die Digest-Zeilen gehoeren zur Komponente daneben
            // und wuerden die Liste nur verdoppeln.
            if (name.equals("version") || name.equals("registry") || name.endsWith("_digest")) {
                continue;
            }
            m.put(name, z.substring(z.indexOf('=') + 1).trim());
        }
        return m;
    }
}
