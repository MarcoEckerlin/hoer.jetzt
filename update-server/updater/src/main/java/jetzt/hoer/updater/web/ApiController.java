package jetzt.hoer.updater.web;

import jetzt.hoer.updater.daten.AusweisDaten;
import jetzt.hoer.updater.daten.KnotenDaten;
import jetzt.hoer.updater.dienst.Knotenverwaltung;
import jetzt.hoer.updater.modell.Knoten;
import jetzt.hoer.updater.modell.Modul;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Die Maschinen-Schnittstelle zur Verwaltung - Abschnitte 42 und 59.
 *
 * <h2>Warum es sie gibt</h2>
 *
 * Bis hierher liess sich jeder Vorgang nur ueber ein Formular ausloesen. Fuer
 * einen Menschen ist das richtig; fuer alles Automatische ist es eine Wand.
 * Ein Update-Ablauf, der einen Knoten vorher in Wartung setzen soll
 * (Abschnitt 44), braucht einen Aufruf und kein Klicken.
 *
 * <h2>Wo sie haengt</h2>
 *
 * Am <b>Pult-Port</b>, nicht am Torwaechter-Port. Das ist eine bewusste
 * Entscheidung: hier werden Knoten gesperrt, entfernt und in Wartung gesetzt.
 * Das gehoert hinter dieselbe Anmeldung wie die Oberflaeche und nicht neben
 * die Schnittstelle, an der sich Knoten selbst melden.
 *
 * <p>{@code PortTrennung} sorgt dafuer, dass dieser Pfad am Torwaechter-Port
 * mit 404 antwortet - ein Knoten kommt also gar nicht erst her.</p>
 *
 * <h2>Was sie bewusst nicht kann</h2>
 *
 * Knoten anlegen. Dabei entsteht ein Aufsetz-Token, der genau einmal gezeigt
 * wird; eine Schnittstelle, die ihn zurueckgibt, legte ihn in
 * Aufrufprotokolle und Shell-Historien. Das bleibt beim Formular, bis es
 * einen Grund gibt, der das aufwiegt.
 */
@RestController
@RequestMapping("/api/v1")
public class ApiController {

    private final KnotenDaten knoten;
    private final AusweisDaten ausweise;
    private final Knotenverwaltung verwaltung;

    public ApiController(KnotenDaten knoten, AusweisDaten ausweise,
                         Knotenverwaltung verwaltung) {
        this.knoten = knoten;
        this.ausweise = ausweise;
        this.verwaltung = verwaltung;
    }

    // ------------------------------------------------------------- Knoten

    @GetMapping("/nodes")
    public List<Map<String, Object>> alle() {
        Instant jetzt = Instant.now();
        return knoten.alle().stream().map(k -> abbilden(k, jetzt)).toList();
    }

    @GetMapping("/nodes/{kennung}")
    public ResponseEntity<Map<String, Object>> einer(@PathVariable String kennung) {
        return knoten.alle().stream()
                .filter(k -> k.kennung().equals(kennung))
                .findFirst()
                .map(k -> ResponseEntity.ok(abbilden(k, Instant.now())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Was ein Knoten zuletzt gemeldet hat - Abschnitt 51.
     *
     * <p>Getrennt von {@code /nodes/{id}}, weil das die Frage ist, die man
     * oft und schnell stellt: geht es dieser Node gut.</p>
     */
    @GetMapping("/nodes/{kennung}/health")
    public ResponseEntity<Map<String, Object>> zustand(@PathVariable String kennung) {
        return knoten.alle().stream()
                .filter(k -> k.kennung().equals(kennung))
                .findFirst()
                .map(k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("kennung", k.kennung());
                    m.put("status", status(k, Instant.now()));
                    m.put("version", k.version());
                    m.put("zuletztGesehen", k.zuletztGesehen());
                    m.put("zustand", k.zustand());
                    m.put("ergebnis", k.ergebnis());
                    return ResponseEntity.ok(m);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ----------------------------------------------------------- Wartung

    /** Abschnitt 42: {@code POST /api/v1/nodes/{id}/maintenance}. */
    @PostMapping("/nodes/{kennung}/maintenance")
    public ResponseEntity<Map<String, Object>> wartungAn(
            @PathVariable String kennung,
            @RequestBody(required = false) Wartungswunsch wunsch,
            java.security.Principal wer) {

        if (!knoten.gibtEs(kennung)) {
            return ResponseEntity.notFound().build();
        }
        String grund = (wunsch == null || wunsch.grund() == null || wunsch.grund().isBlank())
                ? "ueber die API" : wunsch.grund().trim();
        verwaltung.wartung(kennung, true, grund, name(wer));
        return ResponseEntity.ok(Map.of("kennung", kennung, "wartung", true, "grund", grund));
    }

    /** Abschnitt 42: {@code DELETE /api/v1/nodes/{id}/maintenance}. */
    @DeleteMapping("/nodes/{kennung}/maintenance")
    public ResponseEntity<Map<String, Object>> wartungAus(@PathVariable String kennung,
                                                          java.security.Principal wer) {
        if (!knoten.gibtEs(kennung)) {
            return ResponseEntity.notFound().build();
        }
        verwaltung.wartung(kennung, false, "", name(wer));
        return ResponseEntity.ok(Map.of("kennung", kennung, "wartung", false));
    }

    // ------------------------------------------------------------ Update

    /**
     * Update vormerken - Abschnitt 44.
     *
     * <p>Es geht keine Verbindung von hier zum Knoten. Vorgemerkt heisst:
     * beim naechsten Herzschlag holt er es sich. Genau deshalb antwortet das
     * hier mit 202 und nicht mit 200 - angenommen, nicht erledigt.</p>
     */
    @PostMapping("/nodes/{kennung}/updates")
    public ResponseEntity<Map<String, Object>> updateVormerken(@PathVariable String kennung) {
        if (!knoten.gibtEs(kennung)) {
            return ResponseEntity.notFound().build();
        }
        knoten.updateAnfordern(kennung, true);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("kennung", kennung, "vorgemerkt", true,
                             "hinweis", "Wird beim naechsten Herzschlag abgeholt."));
    }

    // ----------------------------------------------------------- Werkzeug

    public record Wartungswunsch(String grund) {
    }

    private Map<String, Object> abbilden(Knoten k, Instant jetzt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kennung", k.kennung());
        m.put("name", k.name());
        m.put("status", status(k, jetzt));
        m.put("module", ausweise.module(k.kennung()).stream().map(Modul::name).toList());
        m.put("version", k.version());
        m.put("letzteIp", k.letzteIp());
        m.put("zuletztGesehen", k.zuletztGesehen());
        m.put("eigenesGeheimnis", k.geheimnisGesetzt());
        m.put("gesperrt", k.gesperrt());
        m.put("wartung", k.inWartung());
        return m;
    }

    /**
     * Ein Wort statt vier Boolescher - Abschnitt 51.
     *
     * <p>Die Reihenfolge ist die Rangfolge: gesperrt schlaegt Wartung,
     * Wartung schlaegt stumm. Ein gesperrter Knoten, der sich nicht meldet,
     * ist zuerst gesperrt - dass er still ist, folgt daraus.</p>
     */
    private String status(Knoten k, Instant jetzt) {
        if (k.gesperrt()) {
            return "GESPERRT";
        }
        if (k.inWartung()) {
            return "MAINTENANCE";
        }
        if (k.zuletztGesehen() == null) {
            return "NIE_GESEHEN";
        }
        return k.stumm(jetzt) ? "OFFLINE" : "ONLINE";
    }

    private String name(java.security.Principal wer) {
        return wer == null ? "(api)" : wer.getName();
    }
}
