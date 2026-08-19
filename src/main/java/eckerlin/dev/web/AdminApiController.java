package eckerlin.dev.web;

import eckerlin.dev.audio.AudioService;
import eckerlin.dev.audio.AutoScaleService;
import eckerlin.dev.audio.ErreichbarkeitService;
import eckerlin.dev.audio.HetznerService;
import eckerlin.dev.audio.KnotenAgentService;
import eckerlin.dev.audio.KnotenRegistrierungService;
import eckerlin.dev.security.ZweiFaktorService;
import eckerlin.dev.web.dto.AudioNodeUsageView;
import eckerlin.dev.web.dto.KnotenUebersichtView;
import eckerlin.dev.services.AdminAccessService;
import eckerlin.dev.services.AdminConfigurationService;
import eckerlin.dev.services.BotPresenceService;
import eckerlin.dev.services.BotPresentationService;
import eckerlin.dev.services.RadioStationService;
import eckerlin.dev.services.VmControlService;
import eckerlin.dev.audio.RadioStation;
import eckerlin.dev.web.dto.RadioSenderRequest;
import eckerlin.dev.web.dto.ActionResponse;
import eckerlin.dev.web.dto.AdminConfigurationView;
import eckerlin.dev.web.dto.BotRuntimeView;
import eckerlin.dev.web.dto.AdminSettingsRequest;
import eckerlin.dev.web.dto.DashboardSession;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final KnotenRegistrierungService knotenRegistrierungService;
    private final RadioStationService radioStationService;
    private final HetznerService hetznerService;

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
            ZweiFaktorService zweiFaktorService,
            KnotenRegistrierungService knotenRegistrierungService,
            RadioStationService radioStationService,
            HetznerService hetznerService
    ) {
        this.radioStationService = radioStationService;
        this.hetznerService = hetznerService;
        this.knotenAgentService = knotenAgentService;
        this.autoScaleService = autoScaleService;
        this.erreichbarkeitService = erreichbarkeitService;
        this.zweiFaktorService = zweiFaktorService;
        this.knotenRegistrierungService = knotenRegistrierungService;
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

    // ------------------------------------------------------------------
    // Globale Radiosender
    //
    // Die Gegenstuecke fuer die eigenen Sender eines Servers liegen im
    // DashboardApiController. Hier fehlt die guild_id ueberall - und genau
    // das ist der Unterschied: ein globaler Sender steht auf jedem Server,
    // deshalb darf ihn auch nur der Betreiber anlegen.
    // ------------------------------------------------------------------

    @GetMapping("/radio")
    public List<RadioStation> globaleSender(HttpSession session) throws SQLException {
        adminAccessService.requireAdmin(requireSession(session));
        return radioStationService.findGlobale();
    }

    @PostMapping("/radio")
    public ActionResponse globalenSenderSpeichern(
            @RequestBody RadioSenderRequest anfrage,
            HttpSession session
    ) throws SQLException {
        DashboardSession sitzung = requireSession(session);
        adminAccessService.requireWriteAdmin(sitzung);

        try {
            radioStationService.speichern(anfrage.id(), null, anfrage.name(), anfrage.url(),
                    anfrage.logoUrl(), sitzung.userId());
        } catch (IllegalArgumentException fehler) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fehler.getMessage());
        }

        return new ActionResponse(true, anfrage.id() == null
                ? "Der Sender wurde angelegt und steht ab sofort auf allen Servern."
                : "Der Sender wurde gespeichert.");
    }

    @DeleteMapping("/radio/{id}")
    public ActionResponse globalenSenderLoeschen(@PathVariable int id, HttpSession session) throws SQLException {
        adminAccessService.requireWriteAdmin(requireSession(session));
        try {
            radioStationService.loeschen(id, null);
        } catch (IllegalArgumentException fehler) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fehler.getMessage());
        }
        return new ActionResponse(true, "Der Sender wurde entfernt.");
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

    /**
     * Knoten fuer die Betriebsansicht - Tabelle und Lavalink zusammengefuehrt.
     *
     * <p>Getrennt von {@code /audio/nodes}, das die bisherige
     * Verwaltungsoberflaeche benutzt. Deren Antwortform zu aendern haette dort
     * stillschweigend Felder verschwinden lassen.</p>
     */
    @GetMapping("/audio/knoten")
    public List<KnotenUebersichtView> audioKnoten(HttpSession session) {
        adminAccessService.requireAdmin(requireSession(session));

        List<AudioNodeUsageView> verbunden = audioService.knotenAuslastung();
        List<KnotenUebersichtView> ergebnis = new java.util.ArrayList<>();
        java.util.Set<String> gesehen = new java.util.HashSet<>();

        for (KnotenRegistrierungService.Eintrag eintrag : knotenRegistrierungService.alleEintraege()) {
            AudioNodeUsageView live = verbunden.stream()
                    .filter(k -> k.name().equals(eintrag.name()))
                    .findFirst()
                    .orElse(null);
            gesehen.add(eintrag.name());

            // "anmarsch" ist der Zustand, den es vorher nicht gab: das
            // Autoscaling hat den Server angelegt, er installiert sich gerade
            // und meldet sich in ein paar Minuten. Ohne eigenen Zustand saehe
            // er aus wie ein ausgefallener Knoten.
            String zustand = live != null && live.erreichbar() ? "verbunden"
                    : !eintrag.aktiv() && "auto".equals(eintrag.herkunft()) ? "anmarsch"
                    : "still";

            ergebnis.add(new KnotenUebersichtView(
                    eintrag.name(),
                    eintrag.adresse(),
                    live != null ? live.stufe() : eintrag.stufe(),
                    eintrag.herkunft(),
                    zustand,
                    live != null && live.erreichbar(),
                    !eintrag.agentUrl().isBlank(),
                    eintrag.hetznerId(),
                    eintrag.zuletztGesehen(),
                    live == null ? 0 : live.obergrenze(),
                    live == null ? 0 : live.spielend(),
                    live == null ? 0 : live.gesamt(),
                    live == null ? 0 : live.cpuLast(),
                    live == null ? 0 : live.laufzeitSekunden(),
                    live == null ? -1 : live.strafpunkte(),
                    live == null ? List.of() : live.server()
            ));
        }

        // Knoten, die verbunden sind, aber nicht in der Tabelle stehen. Das
        // kommt bei einem Deployment-Knoten aus der Konfiguration vor - er
        // gehoert trotzdem in die Ansicht, sonst fehlt in der Uebersicht
        // ausgerechnet der, auf dem alles laeuft.
        for (AudioNodeUsageView live : verbunden) {
            if (gesehen.contains(live.name())) {
                continue;
            }
            ergebnis.add(new KnotenUebersichtView(
                    live.name(), live.adresse(), live.stufe(), "konfiguration",
                    live.erreichbar() ? "verbunden" : "still",
                    live.erreichbar(), false, null, "",
                    live.obergrenze(), live.spielend(), live.gesamt(),
                    live.cpuLast(), live.laufzeitSekunden(), live.strafpunkte(), live.server()
            ));
        }

        return ergebnis;
    }

    // ------------------------------------------------------------------
    // Autoscaling und zweiter Faktor
    // ------------------------------------------------------------------

    /** Zustand des Autoscalings samt Schwellen - fuer die Kopfzeile der Knotenansicht. */
    /**
     * Autoscaling ein- oder ausschalten.
     *
     * <p>Schreibende Stufe: der Schalter entscheidet darueber, ob von selbst
     * Server angelegt werden, die Geld kosten. Kein zweiter Faktor - anders als
     * beim Anlegen von Hand ist das Abschalten die harmlose Richtung, und wer
     * es einschaltet, loest damit noch keine Bestellung aus.</p>
     */
    @PostMapping("/audio/autoscale")
    public ActionResponse autoscaleSchalten(@RequestBody Map<String, Object> anfrage, HttpSession session)
            throws SQLException {
        DashboardSession sitzung = requireSession(session);
        adminAccessService.requireWriteAdmin(sitzung);

        boolean an = Boolean.parseBoolean(String.valueOf(anfrage.get("enabled")));
        autoScaleService.schalten(an, sitzung.userId());
        return new ActionResponse(true, an
                ? "Autoscaling ist an - Knoten kommen bei Bedarf von selbst dazu."
                : "Autoscaling ist aus. Vorhandene Knoten bleiben, es kommen keine neuen dazu.");
    }

    /**
     * Entfernt einen Knoten aus der Tabelle - auf Wunsch samt Server.
     *
     * <p>Gedacht fuer den Fall, der in der Praxis am haeufigsten vorkommt: die
     * Erstinstallation ist gescheitert, der Eintrag zeigt auf eine Maschine,
     * auf der nichts laeuft, und das Autoscaling zaehlt sie trotzdem mit.</p>
     *
     * @param server {@code true} loescht zusaetzlich den Hetzner-Server. Ohne
     *               das bleibt eine Maschine stehen, die weiter Geld kostet -
     *               deshalb ist es eine bewusste Angabe und keine Vorgabe.
     */
    @DeleteMapping("/audio/nodes/{name}")
    public ActionResponse knotenEntfernen(
            @PathVariable("name") String name,
            @RequestParam(required = false, defaultValue = "false") boolean server,
            HttpSession session
    ) throws SQLException {
        DashboardSession sitzung = requireSession(session);
        adminAccessService.requireWriteAdmin(sitzung);

        java.util.Optional<Long> hetznerId;
        try {
            hetznerId = knotenRegistrierungService.entfernen(name);
        } catch (IllegalArgumentException fehler) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, fehler.getMessage());
        }

        String zusatz = "";
        if (server && hetznerId.isPresent()) {
            zusatz = hetznerService.loeschen(hetznerId.get())
                    ? " Der Hetzner-Server wurde ebenfalls geloescht."
                    : " Der Hetzner-Server liess sich nicht loeschen - bitte in der Hetzner-Konsole nachsehen.";
        } else if (server) {
            zusatz = " Ein Hetzner-Server war diesem Knoten nicht zugeordnet.";
        } else if (hetznerId.isPresent()) {
            zusatz = " Achtung: der Hetzner-Server laeuft weiter und kostet weiter.";
        }

        audioService.knotenNeuEinlesen();
        return new ActionResponse(true, "Knoten " + name + " entfernt." + zusatz);
    }

    /**
     * Der Befehl, mit dem ein beliebiger Server zum Audio-Knoten wird.
     *
     * <p>Bis hierhin ging das nur ueber Hetzner. Ein Knoten muss aber nirgends
     * bestimmtes stehen - er braucht Docker, eine erreichbare Adresse und die
     * beiden Geheimnisse. Genau die setzt dieser Befehl ein, damit niemand sie
     * von Hand zusammensuchen muss.</p>
     *
     * <p>Der Befehl enthaelt Geheimnisse und geht deshalb nur an einen
     * angemeldeten Admin mit Schreibrecht - nicht in ein oeffentliches
     * Handbuch.</p>
     */
    @GetMapping("/audio/nodes/befehl")
    public Map<String, String> knotenBefehl(
            @RequestParam(required = false, defaultValue = "free") String stufe,
            HttpSession session
    ) {
        adminAccessService.requireWriteAdmin(requireSession(session));

        String nodeToken = System.getenv("HJ_NODE_TOKEN");
        String agentToken = System.getenv("HJ_AGENT_TOKEN");
        String lavalinkPasswort = System.getenv("HJ_LAVALINK_PASSWORD");
        String coreUrl = adminConfigurationService.buildView().webBaseUrl();

        boolean vollstaendig = nodeToken != null && !nodeToken.isBlank()
                && agentToken != null && !agentToken.isBlank();

        /*
         * Die Variablennamen sind die von install.sh - nicht die des Agenten.
         *
         * Beides sind eigene Skripte mit eigenen Namen, und der erste Anlauf
         * hier benutzte die des Agenten (HJ_NODE_TIER, HJ_NODE_ADDRESS).
         * install.sh kannte sie nicht, fragte alles noch einmal ab und erzeugte
         * ein eigenes Lavalink-Passwort: der Knoten trug am Ende andere Werte,
         * als der Bot erwartete, und meldete sich nicht an.
         */
        String befehl = """
                # Auf dem neuen Server als root ausfuehren.
                # Voraussetzung: Debian oder Ubuntu. Docker wird mitinstalliert.

                export HJ_CORE_URL='%s'
                export HJ_NODE_TOKEN='%s'
                export HJ_AGENT_TOKEN='%s'
                export HJ_LAVALINK_PASSWORD='%s'
                export LAVALINK_TIER='%s'

                apt-get update && apt-get install -y git curl python3
                command -v docker >/dev/null || curl -fsSL https://get.docker.com | sh

                # Vorhandenes Verzeichnis auffrischen statt daran zu scheitern -
                # ein zweiter Anlauf ist der Normalfall, nicht die Ausnahme.
                if [ -d /opt/hoerjetzt-node/.git ]; then
                  git -C /opt/hoerjetzt-node fetch origin lavalink
                  git -C /opt/hoerjetzt-node reset --hard origin/lavalink
                else
                  rm -rf /opt/hoerjetzt-node
                  git clone -b lavalink https://github.com/MarcoEckerlin/hoer.jetzt.git /opt/hoerjetzt-node
                fi
                cd /opt/hoerjetzt-node && bash install.sh
                """.formatted(
                coreUrl == null ? "" : coreUrl,
                nodeToken == null ? "" : nodeToken,
                agentToken == null ? "" : agentToken,
                lavalinkPasswort == null ? "" : lavalinkPasswort,
                "premium".equalsIgnoreCase(stufe) ? "premium" : "free");

        return Map.of(
                "befehl", befehl,
                "vollstaendig", Boolean.toString(vollstaendig),
                "hinweis", vollstaendig
                        ? "Der Knoten meldet sich nach der Installation von selbst an."
                        : "HJ_NODE_TOKEN oder HJ_AGENT_TOKEN fehlen in der .env - ohne sie kann sich "
                          + "der Knoten nicht anmelden.");
    }

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
