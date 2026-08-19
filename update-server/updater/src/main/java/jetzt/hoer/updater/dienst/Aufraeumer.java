package jetzt.hoer.updater.dienst;

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
    private final int tage;

    public Aufraeumer(ZugriffDaten zugriffe, @Value("${hj.protokoll-tage:21}") int tage) {
        this.zugriffe = zugriffe;
        this.tage = tage;
    }

    /** Taeglich um 04:30 - nach dem naechtlichen Update-Fenster der Knoten. */
    @Scheduled(cron = "0 30 4 * * *")
    public void aufraeumen() {
        try {
            int weg = zugriffe.aufraeumen(tage);
            if (weg > 0) {
                log.info("Zugriffsprotokoll: {} Eintraege aelter als {} Tage entfernt.", weg, tage);
            }
        } catch (RuntimeException e) {
            log.warn("Aufraeumen fehlgeschlagen: {}", e.getMessage());
        }
    }
}
