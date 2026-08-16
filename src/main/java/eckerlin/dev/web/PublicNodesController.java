package eckerlin.dev.web;

import eckerlin.dev.audio.AudioService;
import eckerlin.dev.web.dto.AudioNodeUsageView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Die Audio-Knoten fuer die oeffentliche Statusseite.
 *
 * <h2>Warum ein zweiter Endpunkt</h2>
 *
 * <p>Es gibt bereits {@code /api/admin/audio/nodes}. Der liefert aber Adresse,
 * Strafpunkte und die Liste der Server, die auf dem Knoten liegen - alles
 * Angaben, die auf einer Seite ohne Anmeldung nichts verloren haben. Eine
 * Adresse plus die Erkenntnis, dass dort ein Lavalink lauscht, ist eine
 * Einladung.</p>
 *
 * <p>Deshalb eine eigene, magere Ansicht: Name, Stufe, erreichbar, wie viele
 * Wiedergaben. Genau das, was die Frage "laeuft alles?" beantwortet, und
 * nichts, was beim Hineinkommen hilft.</p>
 */
@RestController
public class PublicNodesController {

    private final AudioService audioService;

    public PublicNodesController(AudioService audioService) {
        this.audioService = audioService;
    }

    @GetMapping("/api/public/nodes")
    public List<KnotenAnsicht> nodes() {
        List<AudioNodeUsageView> alle = audioService.knotenAuslastung();
        return alle.stream()
                .map(k -> new KnotenAnsicht(
                        k.name(),
                        k.stufe(),
                        k.erreichbar(),
                        k.spielend(),
                        k.gesamt(),
                        // Auf zwei Nachkommastellen gerundet: die dritte sagt
                        // nichts und laesst die Zahl vor jedem Abruf anders
                        // aussehen.
                        Math.round(k.cpuLast() * 100.0) / 100.0,
                        k.laufzeitSekunden()))
                .toList();
    }

    public record KnotenAnsicht(
            String name,
            String stufe,
            boolean erreichbar,
            int spielend,
            int server,
            double cpuLast,
            long laufzeitSekunden
    ) {
    }
}
