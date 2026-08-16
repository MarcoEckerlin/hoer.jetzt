package eckerlin.dev.web;

import eckerlin.dev.audio.AudioService;
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

@RestController
@RequestMapping("/api/admin")
public class AdminApiController {

    private final AdminAccessService adminAccessService;
    private final AdminConfigurationService adminConfigurationService;
    private final BotPresenceService botPresenceService;
    private final BotPresentationService botPresentationService;
    private final VmControlService vmControlService;
    private final AudioService audioService;

    public AdminApiController(
            AdminAccessService adminAccessService,
            AdminConfigurationService adminConfigurationService,
            BotPresenceService botPresenceService,
            BotPresentationService botPresentationService,
            VmControlService vmControlService,
            AudioService audioService
    ) {
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

    /** Zieht Server auf die Knotenstufe, die ihnen zusteht. */
    @PostMapping("/actions/rebalance-audio")
    public ActionResponse rebalanceAudio(HttpSession session) {
        adminAccessService.requireAdmin(requireSession(session));
        int umgezogen = audioService.stufenAngleichen();
        return new ActionResponse(true, umgezogen == 0
                ? "Alle Server liegen bereits auf der passenden Stufe."
                : umgezogen + " Server umgezogen.");
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
