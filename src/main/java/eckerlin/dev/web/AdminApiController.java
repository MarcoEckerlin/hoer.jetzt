package eckerlin.dev.web;

import eckerlin.dev.audio.AudioService;
import eckerlin.dev.audio.AutoScaleService;
import eckerlin.dev.audio.ErreichbarkeitService;
import eckerlin.dev.audio.KnotenAgentService;
import eckerlin.dev.security.ZweiFaktorService;
import eckerlin.dev.web.dto.AudioNodeUsageView;
import eckerlin.dev.services.AdminAccessService;
import eckerlin.dev.services.AdminConfigurationService;
import eckerlin.dev.services.BotPresenceService;
import eckerlin.dev.services.BotPresentationService;
import eckerlin.dev.services.VmControlService;
import eckerlin.dev.web.dto.ActionResponse;
import eckerlin.dev.web.dto.AdminConfigurationView;
import eckerlin.dev.web.dto.BotRuntimeView;
import eckerlin.dev.web.dto.AdminSettingsRequest;
import eckerlin.dev.web.dto.DashboardSession;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminApiController {

    private final AdminAccessService adminAccessService;
    private final AdminConfigurationService adminConfigurationService;
    private final BotPresenceService botPresenceService;
    private final BotPresentationService botPresentationService;
    private final VmControlService vmControlService;
    private final AudioService audioService;
    private final KnotenAgentService knotenAgentService;
    private final AutoScaleService autoScaleService;
    private final ErreichbarkeitService erreichbarkeitService;
    private final ZweiFaktorService zweiFaktorService;

    public AdminApiController(
            AdminAccessService adminAccessService,
            AdminConfigurationService adminConfigurationService,
            BotPresenceService botPresenceService,
            BotPresentationService botPresentationService,
            VmControlService vmControlService,
            AudioService audioService,
            KnotenAgentService knotenAgentService,
            AutoScaleService autoScaleService,
            ErreichbarkeitService erreichbarkeitService,
            ZweiFaktorService zweiFaktorService
    ) {
        this.knotenAgentService = knotenAgentService;
        this.autoScaleService = autoScaleService;
        this.erreichbarkeitService = erreichbarkeitService;
        this.zweiFaktorService = zweiFaktorService;
        this.adminAccessService = adminAccessService;
        this.adminConfigurationService = adminConfigurationService;
        this.botPresenceService = botPresenceService;
        this.botPresentationService = botPresentationService;
        this.vmControlService = vmControlService;
        this.audioService = audioService;
    }

    @GetMapping("/config")
    public AdminConfigurationView config(HttpSession session) {
        adminAccessService.requireAdmin(requireSession(session));
        return adminConfigurationService.buildView();
    }

    @GetMapping("/runtime")
    public BotRuntimeView runtime(HttpSession session) {
        adminAccessService.requireAdmin(requireSession(session));
        return botPresentationService.buildRuntimeView();
    }

    @PostMapping("/config")
    public ActionResponse saveConfig(@RequestBody AdminSettingsRequest request, HttpSession session) {
        adminAccessService.requireAdmin(requireSession(session));
        try {
            adminConfigurationService.save(request);
            botPresenceService.refreshNow();

            // Ohne das hier stuende ein neuer Audio-Knoten zwar in der Liste,
            // der Bot kennte ihn aber erst nach einem Neustart - und die
            // Auswahl liefe weiter auf die alten Knoten.
            int knoten = audioService.knotenNeuEinlesen();
            if (knoten > 0) {
                return new ActionResponse(true,
                        "Gespeichert. " + knoten + " Audio-Knoten uebernommen - ohne Neustart.");
            }
        } catch (SQLException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Admin-Einstellungen konnten nicht gespeichert werden.");
        }
        return new ActionResponse(true, "Admin-Einstellungen wurden gespeichert.");
    }

    /** Auslastung der Audio-Knoten samt Zuordnung der Server. */
    @GetMapping("/audio/nodes")
    public List<AudioNodeUsageView> audioNodes(HttpSession session) {
        adminAccessService.requireAdmin(requireSession(session));
        return audioService.knotenAuslastung();
    }

    /**
     * Liest die Knotentabelle sofort neu ein.
     *
     * <p>Der Bot macht das von selbst - beim Speichern und danach im Takt der
     * Knotenwache. Dieser Weg ist fuer den Moment, in dem man nicht warten
     * will: neuer Host eingerichtet, eingetragen, und die Musik soll jetzt
     * darauf laufen.
     */
    @PostMapping("/actions/reload-audio-nodes")
    public ActionResponse reloadAudioNodes(HttpSession session) {
        adminAccessService.requireAdmin(requireSession(session));
        int geaendert = audioService.knotenNeuEinlesen();
        return new ActionResponse(true, geaendert == 0
                ? "Keine Aenderung - die angemeldeten Knoten stimmen mit der Liste ueberein."
                : geaendert + " Aenderung(en) uebernommen - ohne Neustart.");
    }

    /**
     * Trennt einen einzelnen Knoten und verbindet ihn sofort wieder.
     *
     * <p>Bewusst kein Neustart des fremden Dienstes: der Bot hat auf dem
     * Knoten-Host nichts verloren. Gegen eine haengende Verbindung oder eine
     * tote Session hilft das hier trotzdem.
     */
    @PostMapping("/audio/nodes/{name}/reconnect")
    public ActionResponse reconnectAudioNode(@PathVariable("name") String name, HttpSession session) {
        adminAccessService.requireAdmin(requireSession(session));
        if (!audioService.knotenNeuVerbinden(name)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Kein Knoten mit dem Namen \"" + name + "\" eingetragen.");
        }
        return new ActionResponse(true, "Knoten " + name
                + " neu verbunden. Laufende Server ziehen kurz um und kommen zurueck.");
    }

    /**
     * Startet den Lavalink-Container auf dem Knoten-Host wirklich neu.
     *
     * <p>Der Unterschied zu {@code /reconnect} ist der, auf den es im Ernstfall
     * ankommt: reconnect kappt die Verbindung von dieser Seite, ein haengender
     * Container blieb haengen. Hier uebernimmt der Agent auf dem Host.</p>
     *
     * <p>Schreibende Stufe, nicht nur lesende: ein Neustart nimmt jedem Server
     * auf diesem Knoten kurz den Ton.</p>
     */
    @PostMapping("/audio/nodes/{name}/restart")
    public ActionResponse restartAudioNode(@PathVariable("name") String name, HttpSession session) {
        adminAccessService.requireWriteAdmin(requireSession(session));
        KnotenAgentService.Antwort antwort = knotenAgentService.neustarten(name);
        if (!antwort.ok()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, antwort.meldung());
        }
        return new ActionResponse(true, antwort.meldung());
    }

    /** Holt den lavalink-Zweig auf dem Knoten-Host, baut neu und startet. */
    @PostMapping("/audio/nodes/{name}/update")
    public ActionResponse updateAudioNode(@PathVariable("name") String name, HttpSession session) {
        adminAccessService.requireWriteAdmin(requireSession(session));
        KnotenAgentService.Antwort antwort = knotenAgentService.aktualisieren(name);
        if (!antwort.ok()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, antwort.meldung());
        }
        return new ActionResponse(true, antwort.meldung());
    }

    /** Das Protokoll des letzten Aktualisierungslaufs auf dem Knoten-Host. */
    @GetMapping("/audio/nodes/{name}/log")
    public Map<String, Object> audioNodeLog(@PathVariable("name") String name, HttpSession session) {
        adminAccessService.requireAdmin(requireSession(session));
        return Map.of("protokoll", knotenAgentService.protokoll(name)
                .orElse("Kein Agent erreichbar oder noch kein Lauf."));
    }

    /** Zieht Server auf die Knotenstufe, die ihnen zusteht. */
    @PostMapping("/actions/rebalance-audio")
    public ActionResponse rebalanceAudio(HttpSession session) {
        adminAccessService.requireAdmin(requireSession(session));
        int umgezogen = audioService.stufenAngleichen();
        return new ActionResponse(true, umgezogen == 0
                ? "Alle Server liegen bereits auf der passenden Stufe."
                : umgezogen + " Server umgezogen.");
    }

    // ------------------------------------------------------------------
    // Autoscaling und zweiter Faktor
    // ------------------------------------------------------------------

    /** Zustand des Autoscalings samt Schwellen - fuer die Kopfzeile der Knotenansicht. */
    @GetMapping("/audio/autoscale")
    public AutoScaleService.Lage autoscale(HttpSession session) {
        adminAccessService.requireAdmin(requireSession(session));
        return autoScaleService.lage();
    }

    /** Antwortzeiten zum Loadbalancer, zu den Knoten und deren Agenten. */
    @GetMapping("/netz/erreichbarkeit")
    public List<ErreichbarkeitService.Messung> erreichbarkeit(HttpSession session) {
        adminAccessService.requireAdmin(requireSession(session));
        return erreichbarkeitService.messen();
    }

    /**
     * Legt von Hand einen Knoten bei Hetzner an - etwa einen Premium-Server.
     *
     * <p>Hier und nur hier steht der zweite Faktor. Das Autoscaling laeuft
     * bewusst ohne, weil es auf Last reagieren muss und nachts niemanden
     * fragen kann. Dieser Weg dagegen ist eine Entscheidung eines Menschen -
     * und ein uebernommener Adminzugang waere sonst gleichbedeutend mit einer
     * offenen Kreditkarte.</p>
     */
    @PostMapping("/audio/nodes/anlegen")
    public ActionResponse knotenAnlegen(@RequestBody Map<String, String> anfrage, HttpSession session) {
        DashboardSession angemeldet = requireSession(session);
        adminAccessService.requireWriteAdmin(angemeldet);

        if (!zweiFaktorService.eingerichtet(angemeldet.userId())) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "Dafür braucht es einen zweiten Faktor. Unter „Zugang“ einrichten.");
        }
        if (!zweiFaktorService.pruefen(angemeldet.userId(), anfrage.get("code"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Der Code stimmt nicht oder wurde schon benutzt.");
        }

        String stufe = "premium".equalsIgnoreCase(anfrage.getOrDefault("stufe", "free")) ? "premium" : "free";
        return autoScaleService.vonHandAnlegen(stufe)
                .map(name -> new ActionResponse(true, "Server %s wird angelegt. Er meldet sich in wenigen Minuten von selbst an.".formatted(name)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Hetzner hat den Server nicht angelegt - Einzelheiten stehen im Protokoll."));
    }

    @GetMapping("/2fa")
    public Map<String, Object> zweiFaktorZustand(HttpSession session) {
        DashboardSession angemeldet = requireSession(session);
        adminAccessService.requireAdmin(angemeldet);
        return Map.of("eingerichtet", zweiFaktorService.eingerichtet(angemeldet.userId()));
    }

    /**
     * Legt ein neues Geheimnis an und liefert die {@code otpauth:}-Adresse.
     *
     * <p>Sie wird genau einmal ausgeliefert und nirgends noch einmal angezeigt.
     * Wer sie verliert, richtet neu ein - das ist der Preis dafuer, dass sie
     * nicht dauerhaft ueber die API abrufbar ist.</p>
     */
    @PostMapping("/2fa/einrichten")
    public Map<String, Object> zweiFaktorEinrichten(HttpSession session) throws SQLException {
        DashboardSession angemeldet = requireSession(session);
        adminAccessService.requireWriteAdmin(angemeldet);
        return Map.of("otpauth", zweiFaktorService.einrichten(angemeldet.userId(), angemeldet.username()));
    }

    @PostMapping("/2fa/pruefen")
    public ActionResponse zweiFaktorPruefen(@RequestBody Map<String, String> anfrage, HttpSession session) {
        DashboardSession angemeldet = requireSession(session);
        adminAccessService.requireAdmin(angemeldet);
        if (!zweiFaktorService.pruefen(angemeldet.userId(), anfrage.get("code"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Der Code stimmt nicht.");
        }
        return new ActionResponse(true, "Der zweite Faktor ist eingerichtet.");
    }

    @PostMapping("/actions/restart-vm")
    public ActionResponse restartVm(HttpSession session) {
        adminAccessService.requireAdmin(requireSession(session));
        vmControlService.scheduleVmRestart();
        return new ActionResponse(true, "Der VM-Neustart wurde angefordert. Die Verbindung bricht in wenigen Sekunden ab.");
    }

    private DashboardSession requireSession(HttpSession session) {
        Object user = session.getAttribute(DashboardController.SESSION_USER);
        if (user instanceof DashboardSession dashboardSession) {
            return dashboardSession;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Bitte zuerst ueber Discord anmelden.");
    }
}
