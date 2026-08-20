package jetzt.hoer.updater.dienst;

import jetzt.hoer.updater.daten.AnmeldungDaten;
import jetzt.hoer.updater.daten.VerwaltungDaten;
import jetzt.hoer.updater.daten.ZugriffDaten;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Haelt das Zugriffsprotokoll klein.
 *
 * Ein Docker-Pull erzeugt trotz der Bremse im Torwaechter mehrere Eintraege je
 * Knoten und Nacht. Ohne Aufraeumen waechst die Datei stetig, und die Frage,
 * die man an das Protokoll stellt - "warum kommt dieser Knoten nicht durch" -
 * betrifft ohnehin immer die letzten Tage.
 */
@Component
public class Aufraeumer {

    private static final Logger log = LoggerFactory.getLogger(Aufraeumer.class);

    private final ZugriffDaten zugriffe;
    private final AnmeldungDaten anmeldungen;
    private final VerwaltungDaten verwaltung;
    private final int tage;

    /**
     * Das Verwaltungsprotokoll wird deutlich laenger aufbewahrt als das
     * Zugriffsprotokoll - ein Jahr statt drei Wochen.
     *
     * <p>Der Grund ist der Unterschied im Zweck. Das Zugriffsprotokoll
     * beantwortet "warum kommt dieser Knoten seit gestern nicht durch", und
     * das betrifft immer die letzten Tage. Das Verwaltungsprotokoll
     * beantwortet "wer hat diesem Knoten wann welche Rechte gegeben" - und
     * diese Frage stellt sich erfahrungsgemaess spaet.</p>
     */
    private static final int VERWALTUNG_TAGE = 365;

    public Aufraeumer(ZugriffDaten zugriffe, AnmeldungDaten anmeldungen,
                      VerwaltungDaten verwaltung,
                      @Value("${hj.protokoll-tage:21}") int tage) {
        this.zugriffe = zugriffe;
        this.anmeldungen = anmeldungen;
        this.verwaltung = verwaltung;
        this.tage = tage;
    }

    /** Taeglich um 04:30 - nach dem naechtlichen Update-Fenster der Knoten. */
    @Scheduled(cron = "0 30 4 * * *")
    public void aufraeumen() {
        raeumen("Zugriffsprotokoll", () -> zugriffe.aufraeumen(tage));
        // Nur die *unbenutzten* abgelaufenen Token. Die verbrauchten bleiben -
        // sie sind die Spur, um die es geht.
        raeumen("abgelaufene Aufsetz-Token", anmeldungen::aufraeumen);
        raeumen("Verwaltungsprotokoll", () -> verwaltung.aelterLoeschen(VERWALTUNG_TAGE));
    }

    /**
     * Ein fehlgeschlagener Schritt darf die uebrigen nicht mitnehmen. Vorher
     * lag alles in einem try - ein voller Datentraeger beim ersten Schritt
     * haette die anderen beiden nie laufen lassen, und genau dann braucht man
     * sie.
     */
    private void raeumen(String was, java.util.function.IntSupplier arbeit) {
        try {
            int weg = arbeit.getAsInt();
            if (weg > 0) {
                log.info("{}: {} Eintraege entfernt.", was, weg);
            }
        } catch (RuntimeException e) {
            log.warn("Aufraeumen ({}) fehlgeschlagen: {}", was, e.getMessage());
        }
    }
}
