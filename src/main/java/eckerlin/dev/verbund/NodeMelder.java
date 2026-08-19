package eckerlin.dev.verbund;

import eckerlin.dev.utils.Alert;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Haelt den Eintrag dieser Node im Verzeichnis frisch.
 *
 * <p>Bewusst ein eigener Faden statt {@code @Scheduled}: die Anwendung hat
 * keine Ablaufsteuerung aktiviert, und sie nur dafuer einzuschalten haette
 * jeden vorhandenen Dienst fuer Zeitgeber geoeffnet. Ein Faden, der alle zwei
 * Minuten eine Zeile schreibt, braucht das nicht.</p>
 *
 * <p>Ohne diese Meldung leitet keine andere Node hierher weiter - und nach
 * fuenf Minuten Stille faellt diese Node aus dem Verzeichnis. Das ist gewollt:
 * eine Weiterleitung an einen Prozess, der nicht mehr laeuft, kostet den
 * Benutzer nur eine Zeitueberschreitung.</p>
 */
@Component
public class NodeMelder {

    private static final long TAKT_SEKUNDEN = 120;

    private final KnotenVerzeichnis verzeichnis;
    private final EigeneNode eigene;
    private ScheduledExecutorService takt;

    public NodeMelder(KnotenVerzeichnis verzeichnis, EigeneNode eigene) {
        this.verzeichnis = verzeichnis;
        this.eigene = eigene;
    }

    @PostConstruct
    public void starten() {
        if (!eigene.meldbar()) {
            Alert.send("INFO", "VERBUND",
                    "Diese Node meldet sich nicht im Verzeichnis an - HJ_PRIVAT_IP oder HJ_NODE_NAME fehlt. "
                    + "Im Einzelbetrieb ist das richtig; im Verbund koennen andere Nodes dann nicht hierher "
                    + "weiterleiten.");
            return;
        }

        takt = Executors.newSingleThreadScheduledExecutor(auftrag -> {
            Thread faden = new Thread(auftrag, "node-melder");
            faden.setDaemon(true);
            return faden;
        });
        takt.scheduleWithFixedDelay(this::melden, 0, TAKT_SEKUNDEN, TimeUnit.SECONDS);

        Alert.send("INFO", "VERBUND", "Node %s meldet sich als %s, Shards %d-%d von %d."
                .formatted(eigene.name(), eigene.privatIp(), eigene.von(), eigene.bis(), eigene.gesamt()));
    }

    private void melden() {
        try {
            verzeichnis.melden(eigene.name(), eigene.privatIp(), eigene.von(), eigene.bis(), eigene.gesamt());
        } catch (RuntimeException fehler) {
            // Ein Zeitgeber, dessen Aufgabe eine Ausnahme wirft, wird von
            // scheduleWithFixedDelay stillschweigend eingestellt - danach
            // meldet sich die Node nie wieder und niemand weiss warum.
            Alert.send("WARN", "VERBUND", "Meldung fehlgeschlagen: " + fehler.getMessage());
        }
    }

    @PreDestroy
    public void beenden() {
        if (takt != null) {
            takt.shutdownNow();
        }
    }
}
