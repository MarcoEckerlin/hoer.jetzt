package jetzt.hoer.updater.dienst;

import jetzt.hoer.updater.daten.VerwaltungDaten;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Sicherungen - die der Controller schickt und die eigene.
 *
 * <h2>Warum der Update-Server sie aufbewahrt</h2>
 *
 * Weil er die einzige Maschine ist, die keine der beiden Controller-Datenbanken
 * fuehrt. Eine Sicherung auf demselben Host, den sie retten soll, ist keine.
 *
 * <h2>Und warum er seine eigene ebenfalls sichert</h2>
 *
 * Hier stehen Knoten-Identitaeten, Faehigkeiten und Freigaben. Ohne sie kommt
 * kein Knoten mehr an ein Update - der Update-Server ist damit selbst ein
 * Ausfallpunkt, und zwar einer, den man leicht uebersieht, weil er "nur" der
 * Update-Server ist.
 */
@Service
public class Sicherungen {

    private static final Logger log = LoggerFactory.getLogger(Sicherungen.class);

    private static final DateTimeFormatter STEMPEL =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

    /**
     * Wie viele Sicherungen je Herkunft bleiben.
     *
     * <p>Bei einem Lauf alle drei Stunden sind 56 rund eine Woche. Die Grenze
     * ist bewusst je Herkunft und nicht insgesamt: sonst verdraengte der
     * Controller mit der groesseren Datenbank irgendwann den anderen.</p>
     */
    private static final int BEHALTEN = 56;

    /** Groesste Sicherung, die angenommen wird. */
    private static final long HOECHSTENS = 2L * 1024 * 1024 * 1024;

    private final Path ablage;
    private final Path datenbank;
    private final JdbcClient db;
    private final VerwaltungDaten protokoll;

    public Sicherungen(@Value("${hj.sicherungen:/daten/sicherungen}") String ablage,
                       @Value("${hj.daten:/daten}") String daten,
                       JdbcClient db, VerwaltungDaten protokoll) {
        this.ablage = Path.of(ablage);
        this.datenbank = Path.of(daten).resolve("updater.db");
        this.db = db;
        this.protokoll = protokoll;
    }

    // ------------------------------------------------------- Entgegennehmen

    /**
     * Nimmt die Sicherung eines Controllers entgegen.
     *
     * <p>Der Dateiname wird <em>nicht</em> uebernommen, sondern neu gebildet.
     * Ein Name aus der Anfrage, der in einen Pfad wandert, ist der klassische
     * Weg zu {@code ../../etc/cron.d/} - und dieser Dienst schreibt mit den
     * Rechten des Containers. Was vom Vorschlag bleibt, ist hoechstens ein
     * Vermerk im Protokoll.</p>
     *
     * @return der tatsaechlich verwendete Name
     */
    public String annehmen(String kennung, byte[] inhalt) throws IOException {
        if (inhalt == null || inhalt.length == 0) {
            throw new IllegalArgumentException("Leere Sicherung.");
        }
        if (inhalt.length > HOECHSTENS) {
            throw new IllegalArgumentException("Sicherung ist groesser als erlaubt.");
        }
        // gzip beginnt mit 1f 8b. Die Pruefung faengt den Fall ab, dass ein
        // Skript eine Fehlermeldung statt eines Dumps hochlaedt - das sieht
        // sonst wie eine gelungene Sicherung aus, bis man sie braucht.
        if (inhalt.length < 2 || (inhalt[0] & 0xFF) != 0x1f || (inhalt[1] & 0xFF) != 0x8b) {
            throw new IllegalArgumentException("Das ist kein gzip - Sicherung abgelehnt.");
        }

        Files.createDirectories(ablage);
        String sauber = kennung.replaceAll("[^a-z0-9-]", "");
        String name = sauber + "-" + STEMPEL.format(Instant.now()) + ".sql.gz";
        Path ziel = ablage.resolve(name);

        // Erst daneben schreiben, dann umbenennen. Ein abgebrochener Upload
        // hinterlaesst sonst eine Datei, die aussieht wie eine Sicherung und
        // keine ist.
        Path teil = ablage.resolve(name + ".teil");
        Files.write(teil, inhalt);
        Files.move(teil, ziel, StandardCopyOption.REPLACE_EXISTING);

        aufraeumen(sauber);
        protokoll.merken("(Knoten)", "Sicherung abgelegt", kennung,
                name + " (" + (inhalt.length / 1024) + " KB)", "");
        log.info("Sicherung von {} abgelegt: {} ({} KB)", kennung, name, inhalt.length / 1024);
        return name;
    }

    // ------------------------------------------------------- Eigene Sicherung

    /**
     * Sichert die eigene SQLite-Datenbank - alle drei Stunden, wie die
     * Controller.
     *
     * <p>{@code VACUUM INTO} und keine Dateikopie: SQLite schreibt mit
     * aktivem WAL in zwei Dateien, und eine Kopie der einen ohne die andere
     * ergibt einen Stand, den es nie gab. {@code VACUUM INTO} laeuft in einer
     * Transaktion und liefert eine in sich geschlossene Datei - im laufenden
     * Betrieb, ohne Sperre fuer die Leser.</p>
     */
    @Scheduled(cron = "0 12 0,3,6,9,12,15,18,21 * * *")
    public void eigeneSichern() {
        try {
            Files.createDirectories(ablage);
            String name = "updater-" + STEMPEL.format(Instant.now()) + ".db";
            Path ziel = ablage.resolve(name);

            // Der Pfad geht als Zeichenkette in die Anweisung - SQLite bindet
            // hier keinen Parameter. Er wird nicht von aussen gebildet,
            // sondern aus Konfiguration und Zeitstempel.
            db.sql("VACUUM INTO '" + ziel.toString().replace("'", "''") + "'").update();

            aufraeumen("updater");
            log.info("Eigene Datenbank gesichert: {} ({} KB)", name, Files.size(ziel) / 1024);
        } catch (Exception fehler) {
            // Kein Abbruch des Zeitgebers: der naechste Lauf kommt in drei
            // Stunden, und eine ausgefallene Sicherung darf nicht dazu
            // fuehren, dass gar keine mehr laeuft.
            log.warn("Eigene Sicherung fehlgeschlagen: {}", fehler.getMessage());
        }
    }

    // ------------------------------------------------------------ Uebersicht

    public record Eintrag(String name, long groesse, Instant zeit) {
    }

    public List<Eintrag> alle() {
        if (!Files.isDirectory(ablage)) {
            return List.of();
        }
        try (var lauf = Files.list(ablage)) {
            return lauf
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().endsWith(".teil"))
                    .map(p -> {
                        try {
                            return new Eintrag(p.getFileName().toString(), Files.size(p),
                                    Files.getLastModifiedTime(p).toInstant());
                        } catch (IOException nichtLesbar) {
                            return new Eintrag(p.getFileName().toString(), 0, Instant.EPOCH);
                        }
                    })
                    .sorted(Comparator.comparing(Eintrag::zeit).reversed())
                    .toList();
        } catch (IOException fehler) {
            log.warn("Sicherungen nicht auflistbar: {}", fehler.getMessage());
            return List.of();
        }
    }

    public Path datei(String name) {
        // Nur der reine Dateiname, nie ein Pfad. Ohne diese Pruefung liesse
        // sich ueber "../" jede Datei im Container herunterladen - auch die
        // Datenbank mit den Knoten-Geheimnissen.
        String sauber = Path.of(name).getFileName().toString();
        Path pfad = ablage.resolve(sauber).normalize();
        if (!pfad.startsWith(ablage)) {
            throw new IllegalArgumentException("Ungueltiger Name.");
        }
        return pfad;
    }

    public boolean loeschen(String name, String wer) {
        try {
            Path pfad = datei(name);
            boolean weg = Files.deleteIfExists(pfad);
            if (weg) {
                protokoll.merken(wer, "Sicherung geloescht", pfad.getFileName().toString(), "", "");
            }
            return weg;
        } catch (Exception fehler) {
            log.warn("Sicherung {} nicht loeschbar: {}", name, fehler.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------ Aufraeumen

    private void aufraeumen(String praefix) {
        try (var lauf = Files.list(ablage)) {
            List<Path> meine = lauf
                    .filter(p -> p.getFileName().toString().startsWith(praefix + "-"))
                    .sorted(Comparator.comparing((Path p) -> {
                        try {
                            return Files.getLastModifiedTime(p).toInstant();
                        } catch (IOException e) {
                            return Instant.EPOCH;
                        }
                    }).reversed())
                    .toList();

            for (int i = BEHALTEN; i < meine.size(); i++) {
                Files.deleteIfExists(meine.get(i));
                log.info("Alte Sicherung entfernt: {}", meine.get(i).getFileName());
            }
        } catch (IOException fehler) {
            log.warn("Aufraeumen der Sicherungen fehlgeschlagen: {}", fehler.getMessage());
        }
    }
}
