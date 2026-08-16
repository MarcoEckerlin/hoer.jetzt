package eckerlin.dev.web;

import eckerlin.dev.audio.AudioService;
import eckerlin.dev.audio.PlayerState;
import eckerlin.dev.audio.RadioStation;
import eckerlin.dev.security.GuildEntitlementService;
import eckerlin.dev.security.AccessGuard;
import eckerlin.dev.security.GuildFeature;
import eckerlin.dev.security.GuildPermission;
import eckerlin.dev.services.AdminAccessService;
import eckerlin.dev.services.AppConfigService;
import eckerlin.dev.services.CommunityModuleService;
import eckerlin.dev.services.DashboardCommandCatalogService;
import eckerlin.dev.services.DashboardAccessService;
import eckerlin.dev.services.DiscordLoggingService;
import eckerlin.dev.services.GuildModuleSettingsService;
import eckerlin.dev.services.InviteTrackerService;
import eckerlin.dev.services.RadioStationService;
import eckerlin.dev.services.TicketModuleService;
import eckerlin.dev.services.TicketTranscriptService;
import eckerlin.dev.web.dto.ActionResponse;
import eckerlin.dev.web.dto.CategoryChannelView;
import eckerlin.dev.web.dto.DashboardGuildView;
import eckerlin.dev.web.dto.GuildConfigurationView;
import eckerlin.dev.web.dto.GuildEntitlementFlags;
import eckerlin.dev.web.dto.GuildStreamView;
import eckerlin.dev.web.dto.DashboardSession;
import eckerlin.dev.web.dto.DiscordLogSettingsRequest;
import eckerlin.dev.web.dto.JoinToCreateEntryRequest;
import eckerlin.dev.web.dto.JoinToCreateEntryView;
import eckerlin.dev.web.dto.JoinToCreateSettingsRequest;
import eckerlin.dev.web.dto.JoinToCreateView;
import eckerlin.dev.web.dto.LlmModuleView;
import eckerlin.dev.web.dto.LlmSettingsRequest;
import eckerlin.dev.web.dto.PlaybackRequest;
import eckerlin.dev.web.dto.QueueMoveRequest;
import eckerlin.dev.web.dto.QueueRemoveRequest;
import eckerlin.dev.web.dto.RadioRequest;
import eckerlin.dev.embeds.EmbedVorlageMapper;
import eckerlin.dev.web.dto.EmbedVorlageDto;
import eckerlin.dev.web.dto.InviteLinkRequest;
import eckerlin.dev.web.dto.InviteLinkView;
import eckerlin.dev.web.dto.ReactionRoleEntryRequest;
import eckerlin.dev.web.dto.ReactionRolePanelRequest;
import eckerlin.dev.web.dto.ReactionRoleSettingsRequest;
import eckerlin.dev.web.dto.RoleView;
import eckerlin.dev.web.dto.TicketOptionRequest;
import eckerlin.dev.web.dto.TicketPanelRequest;
import eckerlin.dev.web.dto.TicketSettingsRequest;
import eckerlin.dev.web.dto.TextChannelView;
import eckerlin.dev.web.dto.ToggleRequest;
import eckerlin.dev.web.dto.VerifySettingsRequest;
import eckerlin.dev.web.dto.VoiceChannelView;
import eckerlin.dev.web.dto.VolumeRequest;
import eckerlin.dev.web.dto.WelcomeSettingsRequest;
import jakarta.servlet.http.HttpSession;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardApiController {

    /**
     * Nach dieser Zeit meldet die API einen Zwischenstand zurueck, statt den
     * Browser weiter warten zu lassen. Muss unter
     * {@code spring.mvc.async.request-timeout} liegen.
     */
    private static final long PLAYER_ACTION_TIMEOUT_SECONDS = 8L;

    private final AudioService audioService;
    private final DashboardAccessService dashboardAccessService;
    private final DashboardCommandCatalogService commandCatalogService;
    private final GuildModuleSettingsService settingsService;
    private final InviteTrackerService inviteTrackerService;
    private final DiscordLoggingService discordLoggingService;
    private final RadioStationService radioStationService;
    private final TicketModuleService ticketModuleService;
    private final TicketTranscriptService ticketTranscriptService;
    private final AppConfigService configService;
    private final AdminAccessService adminAccessService;
    private final AccessGuard accessGuard;
    private final CommunityModuleService communityModuleService;
    private final GuildEntitlementService entitlementService;

    public DashboardApiController(
            AudioService audioService,
            DashboardAccessService dashboardAccessService,
            DashboardCommandCatalogService commandCatalogService,
            GuildModuleSettingsService settingsService,
            InviteTrackerService inviteTrackerService,
            DiscordLoggingService discordLoggingService,
            RadioStationService radioStationService,
            TicketModuleService ticketModuleService,
            TicketTranscriptService ticketTranscriptService,
            AppConfigService configService,
            AdminAccessService adminAccessService,
            CommunityModuleService communityModuleService,
            GuildEntitlementService entitlementService,
            AccessGuard accessGuard
    ) {
        this.audioService = audioService;
        this.dashboardAccessService = dashboardAccessService;
        this.commandCatalogService = commandCatalogService;
        this.settingsService = settingsService;
        this.inviteTrackerService = inviteTrackerService;
        this.discordLoggingService = discordLoggingService;
        this.radioStationService = radioStationService;
        this.ticketModuleService = ticketModuleService;
        this.ticketTranscriptService = ticketTranscriptService;
        this.configService = configService;
        this.adminAccessService = adminAccessService;
        this.communityModuleService = communityModuleService;
        this.entitlementService = entitlementService;
        this.accessGuard = accessGuard;
    }

    @GetMapping("/guilds")
    public List<DashboardGuildView> guilds(HttpSession session) {
        return dashboardAccessService.getManageableGuilds(requireDashboardSession(session));
    }

    @GetMapping("/guilds/{guildId}/config")
    public GuildConfigurationView configuration(@PathVariable String guildId, HttpSession session) {
        Guild guild = dashboardAccessService.requireGuild(requireDashboardSession(session), guildId);
        return new GuildConfigurationView(
                new GuildEntitlementFlags(
                        entitlementService.isEnabled(guild.getId(), GuildFeature.LLM_CHAT),
                        entitlementService.isEnabled(guild.getId(), GuildFeature.AI_RADIO),
                        entitlementService.isEnabled(guild.getId(), GuildFeature.PREMIUM_AUDIO)
                ),
                toJoinToCreateView(guild),
                inviteTrackerService.buildView(guild),
                communityModuleService.buildWelcomeView(guild),
                communityModuleService.buildReactionRoleView(guild),
                communityModuleService.buildVerifyView(guild),
                toLlmView(guild.getId()),
                ticketModuleService.buildView(guild),
                discordLoggingService.buildView(guild),
                commandCatalogService.getCommandViews(guild.getId(), settingsService),
                settingsService.getEmbedVorlagen(guild.getId()).stream()
                        .map(EmbedVorlageMapper::zurOberflaeche)
                        .toList(),
                guild.getCategories().stream()
                        .map(this::toCategoryView)
                        .toList(),
                guild.getTextChannels().stream()
                        .map(this::toTextChannelView)
                        .toList(),
                guild.getRoles().stream()
                        .filter(role -> !role.isPublicRole() && !role.isManaged())
                        .map(this::toRoleView)
                        .toList()
        );
    }

    /**
     * Ohne Server-Bezug bleibt das AI-Radio aussen vor: die Freigabe gilt je
     * Server, und ohne zu wissen um welchen es geht, ist "nein" die richtige
     * Antwort.
     */
    @GetMapping("/radio/stations")
    public List<RadioStation> stations(HttpSession session, @RequestParam(required = false) String guildId) {
        DashboardSession dashboardSession = requireDashboardSession(session);
        if (guildId == null || guildId.isBlank()) {
            return radioStationService.findAllForConfiguredBot(false);
        }

        Guild guild = dashboardAccessService.requireGuild(dashboardSession, guildId);
        return radioStationService.findAllForConfiguredBot(
                entitlementService.isEnabled(guild.getId(), GuildFeature.AI_RADIO));
    }

    @GetMapping("/guilds/{guildId}/voice-channels")
    public List<VoiceChannelView> voiceChannels(@PathVariable String guildId, HttpSession session) {
        DashboardSession dashboardSession = requireDashboardSession(session);
        Guild guild = dashboardAccessService.requireGuild(dashboardSession, guildId);
        return guild.getVoiceChannels().stream()
                .map(channel -> new VoiceChannelView(
                        channel.getId(),
                        channel.getName(),
                        channel.getMembers().size(),
                        Math.max(8, channel.getBitrate() / 1000)
                ))
                .toList();
    }

    @GetMapping("/guilds/{guildId}/player")
    public PlayerState player(@PathVariable String guildId, HttpSession session) {
        DashboardSession dashboardSession = requireDashboardSession(session);
        Guild guild = dashboardAccessService.requireGuild(dashboardSession, guildId);
        return audioService.getPlayerState(guild, dashboardAccessService.getUserVoiceChannel(guild, dashboardSession.userId()));
    }

    /**
     * Technische Sicht auf die Wiedergabe - nur fuer das Team.
     *
     * <p>Wer Module konfigurieren darf, darf auch sehen, welcher Knoten
     * bedient. Fuer alle anderen gibt es hier nichts zu holen, deshalb 403
     * statt einer leeren Antwort: eine leere Antwort saehe aus wie ein Fehler.
     */
    @GetMapping("/guilds/{guildId}/stream")
    public GuildStreamView stream(@PathVariable String guildId, HttpSession session) {
        DashboardSession dashboardSession = requireDashboardSession(session);
        Guild guild = dashboardAccessService.requireGuild(dashboardSession, guildId);

        boolean darfSehen = adminAccessService.isAdmin(dashboardSession)
                || accessGuard.has(dashboardSession, guildId, GuildPermission.MODULE_CONFIG);
        if (!darfSehen) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Diese Ansicht ist dem Server-Team vorbehalten.");
        }

        return audioService.streamInfo(guild);
    }

    @PostMapping("/guilds/{guildId}/player/play")
    public CompletableFuture<ActionResponse> play(
            @PathVariable String guildId,
            @RequestBody PlaybackRequest request,
            HttpSession session
    ) {
        DashboardSession dashboardSession = requireDashboardSession(session);
        Guild guild = dashboardAccessService.requireGuild(dashboardSession, guildId);
        AudioChannel voiceChannel = resolveRequestedAudioChannel(guild, request.voiceChannelId(), dashboardSession);

        if (request.query() == null || request.query().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bitte einen Suchbegriff oder eine URL angeben.");
        }

        // Ohne Zeitlimit haengt der Browser an dieser Anfrage, bis der
        // Container die Verbindung kappt. Der Ladevorgang laeuft im Bot
        // weiter, das Dashboard bekommt aber sofort eine verwertbare Antwort.
        return audioService.queueTrack(guild, voiceChannel, request.query())
                .thenApply(message -> new ActionResponse(true, message))
                .completeOnTimeout(
                        new ActionResponse(true, "Titel wird geladen..."),
                        PLAYER_ACTION_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS
                );
    }

    @PostMapping("/guilds/{guildId}/player/radio")
    public CompletableFuture<ActionResponse> radio(
            @PathVariable String guildId,
            @RequestBody RadioRequest request,
            HttpSession session
    ) {
        DashboardSession dashboardSession = requireDashboardSession(session);
        Guild guild = dashboardAccessService.requireGuild(dashboardSession, guildId);
        AudioChannel voiceChannel = resolveRequestedAudioChannel(guild, request.voiceChannelId(), dashboardSession);

        if (request.radioId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bitte eine Radio-ID angeben.");
        }

        return audioService.startRadio(guild, voiceChannel, request.radioId())
                .thenApply(message -> new ActionResponse(!audioService.isRadioCooldownMessage(message), message))
                .completeOnTimeout(
                        new ActionResponse(true, "Sender wird verbunden..."),
                        PLAYER_ACTION_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS
                );
    }

    @PostMapping("/guilds/{guildId}/player/pause")
    public ActionResponse pause(@PathVariable String guildId, HttpSession session) {
        DashboardSession dashboardSession = requireDashboardSession(session);
        Guild guild = dashboardAccessService.requireGuild(dashboardSession, guildId);
        requireUserAudioChannel(guild, dashboardSession);
        return new ActionResponse(true, audioService.pause(guild));
    }

    @PostMapping("/guilds/{guildId}/player/resume")
    public ActionResponse resume(@PathVariable String guildId, HttpSession session) {
        DashboardSession dashboardSession = requireDashboardSession(session);
        Guild guild = dashboardAccessService.requireGuild(dashboardSession, guildId);
        requireUserAudioChannel(guild, dashboardSession);
        return new ActionResponse(true, audioService.resume(guild));
    }

    @PostMapping("/guilds/{guildId}/player/skip")
    public ActionResponse skip(@PathVariable String guildId, HttpSession session) {
        DashboardSession dashboardSession = requireDashboardSession(session);
        Guild guild = dashboardAccessService.requireGuild(dashboardSession, guildId);
        requireUserAudioChannel(guild, dashboardSession);
        return new ActionResponse(true, audioService.skip(guild));
    }

    @PostMapping("/guilds/{guildId}/player/stop")
    public ActionResponse stop(@PathVariable String guildId, HttpSession session) {
        DashboardSession dashboardSession = requireDashboardSession(session);
        Guild guild = dashboardAccessService.requireGuild(dashboardSession, guildId);
        requireUserAudioChannel(guild, dashboardSession);
        return new ActionResponse(true, audioService.stop(guild));
    }

    @PostMapping("/guilds/{guildId}/player/repeat")
    public ActionResponse repeat(
            @PathVariable String guildId,
            @RequestBody ToggleRequest request,
            HttpSession session
    ) {
        DashboardSession dashboardSession = requireDashboardSession(session);
        Guild guild = dashboardAccessService.requireGuild(dashboardSession, guildId);
        requireUserAudioChannel(guild, dashboardSession);
        boolean enabled = request.enabled() == null || request.enabled();
        audioService.setRepeatEnabled(guild, enabled);
        return new ActionResponse(true, enabled
                ? "Dauer-Repeat wurde aktiviert."
                : "Dauer-Repeat wurde deaktiviert.");
    }

    @PostMapping("/guilds/{guildId}/player/bass")
    public ActionResponse bass(
            @PathVariable String guildId,
            @RequestBody ToggleRequest request,
            HttpSession session
    ) {
        DashboardSession dashboardSession = requireDashboardSession(session);
        Guild guild = dashboardAccessService.requireGuild(dashboardSession, guildId);
        requireUserAudioChannel(guild, dashboardSession);
        boolean enabled = request.enabled() == null || request.enabled();
        audioService.setBassBoostEnabled(guild, enabled);
        return new ActionResponse(true, enabled
                ? "Bass-Boost wurde aktiviert."
                : "Bass-Boost wurde deaktiviert.");
    }

    @PostMapping("/guilds/{guildId}/player/volume")
    public ActionResponse volume(
            @PathVariable String guildId,
            @RequestBody VolumeRequest request,
            HttpSession session
    ) {
        DashboardSession dashboardSession = requireDashboardSession(session);
        Guild guild = dashboardAccessService.requireGuild(dashboardSession, guildId);
        requireUserAudioChannel(guild, dashboardSession);
        int volume = audioService.setVolume(guild, request.volume());
        return new ActionResponse(true, "Lautstaerke auf " + volume + "% gesetzt.");
    }

    @PostMapping("/guilds/{guildId}/player/queue/move")
    public ActionResponse moveQueueItem(
            @PathVariable String guildId,
            @RequestBody QueueMoveRequest request,
            HttpSession session
    ) {
        DashboardSession dashboardSession = requireDashboardSession(session);
        Guild guild = dashboardAccessService.requireGuild(dashboardSession, guildId);
        requireUserAudioChannel(guild, dashboardSession);

        if (request.fromIndex() == null || request.toIndex() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bitte gueltige Queue-Positionen angeben.");
        }

        return new ActionResponse(true, audioService.moveQueueItem(guild, request.fromIndex(), request.toIndex()));
    }

    @PostMapping("/guilds/{guildId}/player/queue/remove")
    public ActionResponse removeQueueItem(
            @PathVariable String guildId,
            @RequestBody QueueRemoveRequest request,
            HttpSession session
    ) {
        DashboardSession dashboardSession = requireDashboardSession(session);
        Guild guild = dashboardAccessService.requireGuild(dashboardSession, guildId);
        requireUserAudioChannel(guild, dashboardSession);

        if (request.index() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bitte eine gueltige Queue-Position angeben.");
        }

        return new ActionResponse(true, audioService.removeQueueItem(guild, request.index()));
    }

    @PostMapping("/guilds/{guildId}/modules/join-to-create")
    public ActionResponse saveJoinToCreate(
            @PathVariable String guildId,
            @RequestBody JoinToCreateSettingsRequest request,
            HttpSession session
    ) {
        Guild guild = dashboardAccessService.requireGuild(requireDashboardSession(session), guildId);
        boolean enabled = request.enabled() != null && request.enabled();
        int maxBitrateKbps = Math.max(8, guild.getMaxBitrate() / 1000);

        List<GuildModuleSettingsService.JoinToCreateEntry> entries = new ArrayList<>();
        Set<String> seenSourceChannels = new HashSet<>();
        for (JoinToCreateEntryRequest entryRequest : request.entries() == null ? List.<JoinToCreateEntryRequest>of() : request.entries()) {
            if (entryRequest == null || entryRequest.sourceChannelId() == null || entryRequest.sourceChannelId().isBlank()) {
                continue;
            }

            if (!seenSourceChannels.add(entryRequest.sourceChannelId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Jeder Startkanal darf nur einmal verwendet werden.");
            }

            VoiceChannel sourceChannel = requireVoiceChannel(guild, entryRequest.sourceChannelId());
            requireCategoryIfPresent(guild, entryRequest.categoryId());
            int bitrateKbps = entryRequest.bitrateKbps() == null ? 0 : entryRequest.bitrateKbps();
            if (bitrateKbps < 0 || bitrateKbps > maxBitrateKbps) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Die Bitrate muss zwischen 0 und " + maxBitrateKbps + " kbps liegen."
                );
            }

            entries.add(new GuildModuleSettingsService.JoinToCreateEntry(
                    entryRequest.id(),
                    sourceChannel.getId(),
                    entryRequest.categoryId(),
                    entryRequest.nameTemplate(),
                    entryRequest.userLimit() == null ? 0 : entryRequest.userLimit(),
                    bitrateKbps,
                    1,
                    entryRequest.sendConfigPrompt() == null || entryRequest.sendConfigPrompt()
            ));
        }

        if (enabled && entries.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bitte mindestens einen Startkanal konfigurieren.");
        }

        settingsService.saveJoinToCreate(
                guild.getId(),
                enabled,
                entries,
                request.cleanupDelaySeconds() == null ? 60 : request.cleanupDelaySeconds(),
                request.audioIdleTimeoutSeconds() == null ? 60 : request.audioIdleTimeoutSeconds()
        );

        return new ActionResponse(true, enabled
                ? "Automatische Sprachkanäle wurden gespeichert."
                : "Automatische Sprachkanäle wurden deaktiviert.");
    }

    @PostMapping("/guilds/{guildId}/modules/invite-tracker")
    public ActionResponse saveInviteTracker(
            @PathVariable String guildId,
            @RequestBody ToggleRequest request,
            HttpSession session
    ) {
        Guild guild = dashboardAccessService.requireGuild(requireDashboardSession(session), guildId);
        boolean enabled = request.enabled() != null && request.enabled();
        settingsService.saveInviteTracker(guild.getId(), enabled);
        inviteTrackerService.refreshCache(guild);
        return new ActionResponse(true, enabled
                ? "Invite-Tracker wurde aktiviert."
                : "Invite-Tracker wurde deaktiviert.");
    }

    @PostMapping("/guilds/{guildId}/modules/welcome")
    public ActionResponse saveWelcome(
            @PathVariable String guildId,
            @RequestBody WelcomeSettingsRequest request,
            HttpSession session
    ) {
        Guild guild = dashboardAccessService.requireGuild(requireDashboardSession(session), guildId);
        boolean enabled = request.enabled() != null && request.enabled();
        List<Role> roles = requireRolesIfPresent(guild, request.roleIds());
        TextChannel channel = requireTextChannelIfPresent(guild, request.channelId());

        communityModuleService.buildWelcomeView(guild);
        settingsService.saveWelcomeState(
                guild.getId(),
                enabled,
                roles.stream().map(Role::getId).toList(),
                channel == null ? "" : channel.getId(),
                request.welcomeText(),
                request.sendImage() != null && request.sendImage(),
                request.backgroundImageUrl(),
                request.accentColor(),
                EmbedVorlageMapper.ausOberflaeche(request.embed()),
                request.embedVorlageId()
        );

        return new ActionResponse(true, enabled
                ? "Join-Rolle und Willkommen wurden gespeichert."
                : "Join-Rolle und Willkommen wurden deaktiviert.");
    }

    @PostMapping("/guilds/{guildId}/modules/reaction-roles")
    public ActionResponse saveReactionRoles(
            @PathVariable String guildId,
            @RequestBody ReactionRoleSettingsRequest request,
            HttpSession session
    ) {
        Guild guild = dashboardAccessService.requireGuild(requireDashboardSession(session), guildId);
        boolean enabled = request.enabled() != null && request.enabled();
        List<GuildModuleSettingsService.ReactionRolePanel> panels = new ArrayList<>();
        for (ReactionRolePanelRequest panelRequest : request.panels() == null ? List.<ReactionRolePanelRequest>of() : request.panels()) {
            if (panelRequest == null) {
                continue;
            }
            TextChannel channel = requireTextChannelIfPresent(guild, panelRequest.publishChannelId());
            if (channel == null) {
                continue;
            }

            List<GuildModuleSettingsService.ReactionRoleEntry> entries = new ArrayList<>();
            for (ReactionRoleEntryRequest entryRequest : panelRequest.entries() == null ? List.<ReactionRoleEntryRequest>of() : panelRequest.entries()) {
                if (entryRequest == null || entryRequest.emoji() == null || entryRequest.emoji().isBlank()) {
                    continue;
                }
                List<Role> roles = requireRolesIfPresent(guild, entryRequest.roleIds());
                if (roles.isEmpty()) {
                    continue;
                }
                GuildModuleSettingsService.ReactionRoleEntry entry = new GuildModuleSettingsService.ReactionRoleEntry();
                entry.setId(entryRequest.id());
                entry.setEmoji(entryRequest.emoji());
                entry.setRoleIds(roles.stream().map(Role::getId).toList());
                // Darf leer sein - im Gegensatz zur Vergabeliste ist der Entzug
                // freiwillig, ein Eintrag ohne ihn bleibt gueltig.
                entry.setRemovedRoleIds(requireRolesIfPresent(guild, entryRequest.removedRoleIds())
                        .stream().map(Role::getId).toList());
                entry.setLabel(entryRequest.label());
                entry.setDescription(entryRequest.description());
                entries.add(entry);
            }

            GuildModuleSettingsService.ReactionRolePanel panel = new GuildModuleSettingsService.ReactionRolePanel();
            panel.setId(panelRequest.id());
            panel.setPublishChannelId(channel.getId());
            panel.setTitle(panelRequest.title());
            panel.setDescription(panelRequest.description());
            panel.setImageUrl(panelRequest.imageUrl());
            panel.setThumbnailUrl(panelRequest.thumbnailUrl());
            panel.setAccentColor(panelRequest.accentColor());
            panel.setEmbed(EmbedVorlageMapper.ausOberflaeche(panelRequest.embed()));
            panel.setEmbedVorlageId(panelRequest.embedVorlageId());
            panel.setEntries(entries);
            panels.add(panel);
        }

        settingsService.saveReactionRoleState(
                guild.getId(),
                enabled,
                panels
        );
        String syncMessage = communityModuleService.syncReactionRoleMessage(guild);
        return new ActionResponse(true, enabled
                ? "Reaction Roles wurden gespeichert. " + syncMessage
                : "Reaction Roles wurden deaktiviert. " + syncMessage);
    }

    /**
     * Die gemeinsame Vorlagen-Bibliothek des Servers.
     *
     * <p>Eigener Weg statt eines Moduls: die Vorlagen gehoeren keinem Modul,
     * sondern werden von mehreren benutzt.
     */
    @PostMapping("/guilds/{guildId}/embed-vorlagen")
    public ActionResponse saveEmbedVorlagen(
            @PathVariable String guildId,
            @RequestBody List<EmbedVorlageDto> request,
            HttpSession session
    ) {
        Guild guild = dashboardAccessService.requireGuild(requireDashboardSession(session), guildId);
        settingsService.saveEmbedVorlagen(guild.getId(), (request == null ? List.<EmbedVorlageDto>of() : request)
                .stream()
                .map(EmbedVorlageMapper::ausOberflaeche)
                .toList());
        int anzahl = settingsService.getEmbedVorlagen(guild.getId()).size();
        return new ActionResponse(true, anzahl == 0
                ? "Alle Vorlagen entfernt."
                : anzahl + " Vorlage(n) gespeichert. Module können sie jetzt auswählen.");
    }

    // ------------------------------------------------------------------
    // Einladungs-Kurzlink
    // ------------------------------------------------------------------

    @GetMapping("/guilds/{guildId}/invite")
    public InviteLinkView invite(@PathVariable String guildId, HttpSession session) {
        Guild guild = dashboardAccessService.requireGuild(requireDashboardSession(session), guildId);
        return buildInviteView(guild);
    }

    @PostMapping("/guilds/{guildId}/invite")
    public ActionResponse saveInvite(
            @PathVariable String guildId,
            @RequestBody InviteLinkRequest request,
            HttpSession session
    ) {
        Guild guild = dashboardAccessService.requireGuild(requireDashboardSession(session), guildId);
        boolean enabled = request.enabled() != null && request.enabled();

        // Der Dienst prueft selbst und gibt den Grund zurueck, statt still zu
        // scheitern - ein vergebener Kurzname muss beim Nutzer ankommen.
        String fehler = settingsService.saveInviteState(
                guild.getId(), enabled, request.slug(), request.targetUrl());
        if (!fehler.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fehler);
        }

        InviteLinkView aktuell = buildInviteView(guild);
        return new ActionResponse(true, enabled
                ? "Gespeichert. Der Link lautet " + aktuell.publicUrl()
                : "Der Einladungslink wurde abgeschaltet.");
    }

    private InviteLinkView buildInviteView(Guild guild) {
        GuildModuleSettingsService.InviteLinkState state = settingsService.getInviteState(guild.getId());
        String basis = configService.getWebBaseUrl();
        if (basis.endsWith("/")) {
            basis = basis.substring(0, basis.length() - 1);
        }
        return new InviteLinkView(
                state.isEnabled(),
                state.getSlug(),
                state.getTargetUrl(),
                state.getClicks(),
                state.getSlug().isBlank() ? "" : basis + "/invite/" + state.getSlug()
        );
    }

    @PostMapping("/guilds/{guildId}/modules/verify")
    public ActionResponse saveVerify(
            @PathVariable String guildId,
            @RequestBody VerifySettingsRequest request,
            HttpSession session
    ) {
        Guild guild = dashboardAccessService.requireGuild(requireDashboardSession(session), guildId);
        boolean enabled = request.enabled() != null && request.enabled();
        TextChannel channel = requireTextChannelIfPresent(guild, request.publishChannelId());
        List<Role> roles = requireRolesIfPresent(guild, request.verifiedRoleIds());
        List<Role> entzug = requireRolesIfPresent(guild, request.removedRoleIds());

        settingsService.saveVerifyState(
                guild.getId(),
                enabled,
                channel == null ? "" : channel.getId(),
                roles.stream().map(Role::getId).toList(),
                entzug.stream().map(Role::getId).toList(),
                request.title(),
                request.description(),
                request.imageUrl(),
                request.thumbnailUrl(),
                request.accentColor(),
                EmbedVorlageMapper.ausOberflaeche(request.embed()),
                request.embedVorlageId()
        );
        String syncMessage = communityModuleService.syncVerifyMessage(guild);
        return new ActionResponse(true, enabled
                ? "Verify wurde gespeichert. " + syncMessage
                : "Verify wurde deaktiviert. " + syncMessage);
    }

    @PostMapping("/guilds/{guildId}/modules/llm")
    public ActionResponse saveLlm(
            @PathVariable String guildId,
            @RequestBody LlmSettingsRequest request,
            HttpSession session
    ) {
        Guild guild = dashboardAccessService.requireGuild(requireDashboardSession(session), guildId);
        boolean enabled = request.enabled() != null && request.enabled();
        String requestedModel = request.model() == null ? "" : request.model().trim();

        if (enabled && !configService.isLlmConfigured()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "LLM ist global noch nicht konfiguriert.");
        }

        // Kostenpflichtig und rechtlich heikel: der Serverbetreiber kann das
        // nicht selbst einschalten. Ausschalten bleibt jederzeit moeglich -
        // sonst haenge ein Server nach dem Entzug der Freischaltung mit einem
        // aktiven Modul fest, das er nicht mehr loswird.
        if (enabled && !entitlementService.isEnabled(guild.getId(), GuildFeature.LLM_CHAT)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Der KI-Chat ist für diesen Server nicht freigeschaltet. Bitte wende dich an den Betreiber des Bots.");
        }

        if (!requestedModel.isBlank() && !configService.isAllowedLlmModel(requestedModel)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dieses Modell ist nicht fuer Nutzer freigegeben.");
        }

        if (enabled) {
            requireTextChannelIfPresent(guild, request.textChannelId());
        }

        settingsService.saveLlmState(
                guild.getId(),
                enabled,
                request.textChannelId(),
                configService.resolveAllowedLlmModel(requestedModel)
        );

        return new ActionResponse(true, enabled
                ? "LLM-Modul wurde gespeichert."
                : "LLM-Modul wurde deaktiviert.");
    }

    @PostMapping("/guilds/{guildId}/modules/discord-logs")
    public ActionResponse saveDiscordLogs(
            @PathVariable String guildId,
            @RequestBody DiscordLogSettingsRequest request,
            HttpSession session
    ) {
        Guild guild = dashboardAccessService.requireGuild(requireDashboardSession(session), guildId);
        boolean enabled = request.enabled() != null && request.enabled();

        if (enabled) {
            if (request.textChannelId() == null || request.textChannelId().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bitte einen Text-Channel fuer die Discord-Logs auswaehlen.");
            }
            requireTextChannelIfPresent(guild, request.textChannelId());
        }

        settingsService.saveDiscordLogState(
                guild.getId(),
                enabled,
                request.textChannelId(),
                request.memberJoin() == null || request.memberJoin(),
                request.memberLeave() == null || request.memberLeave(),
                request.voiceJoin() != null && request.voiceJoin(),
                request.voiceLeave() != null && request.voiceLeave(),
                request.music() == null || request.music(),
                request.moderation() != null && request.moderation(),
                request.roleUpdates() != null && request.roleUpdates(),
                request.nicknameUpdates() != null && request.nicknameUpdates(),
                request.timeouts() != null && request.timeouts(),
                request.kicks() != null && request.kicks(),
                request.bans() != null && request.bans(),
                request.messageDeletes() != null && request.messageDeletes(),
                request.voiceModeration() != null && request.voiceModeration(),
                request.commands() != null && request.commands()
        );

        return new ActionResponse(true, enabled
                ? "Discord-Logs wurden gespeichert."
                : "Discord-Logs wurden deaktiviert.");
    }

    @PostMapping("/guilds/{guildId}/modules/tickets")
    public ActionResponse saveTickets(
            @PathVariable String guildId,
            @RequestBody TicketSettingsRequest request,
            HttpSession session
    ) {
        Guild guild = dashboardAccessService.requireGuild(requireDashboardSession(session), guildId);
        boolean enabled = request.enabled() != null && request.enabled();

        if (enabled && (request.panels() == null || request.panels().isEmpty())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bitte mindestens ein Ticket-Panel konfigurieren.");
        }

        List<GuildModuleSettingsService.TicketPanel> panels = new ArrayList<>();
        for (TicketPanelRequest panelRequest : request.panels() == null ? List.<TicketPanelRequest>of() : request.panels()) {
            if (panelRequest == null || panelRequest.publishChannelId() == null || panelRequest.publishChannelId().isBlank()) {
                continue;
            }

            requireTextChannelIfPresent(guild, panelRequest.publishChannelId());
            requireCategoryIfPresent(guild, panelRequest.categoryId());

            List<GuildModuleSettingsService.TicketOption> options = new ArrayList<>();
            for (TicketOptionRequest optionRequest : panelRequest.options() == null ? List.<TicketOptionRequest>of() : panelRequest.options()) {
                if (optionRequest == null || optionRequest.label() == null || optionRequest.label().isBlank()) {
                    continue;
                }

                List<String> optionSupportRoleIds = new ArrayList<>();
                for (String roleId : optionRequest.supportRoleIds() == null ? List.<String>of() : optionRequest.supportRoleIds()) {
                    Role role = requireRoleIfPresent(guild, roleId);
                    if (role != null && !optionSupportRoleIds.contains(role.getId())) {
                        optionSupportRoleIds.add(role.getId());
                    }
                }

                GuildModuleSettingsService.TicketOption option = new GuildModuleSettingsService.TicketOption();
                option.setId(optionRequest.id());
                option.setLabel(optionRequest.label());
                option.setDescription(optionRequest.description());
                option.setEmoji(optionRequest.emoji());
                option.setChannelNameTemplate(optionRequest.channelNameTemplate());
                option.setSupportRoleIds(optionSupportRoleIds);
                options.add(option);
            }

            if (enabled && options.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Jedes Ticket-Panel braucht mindestens eine Dropdown-Option.");
            }

            GuildModuleSettingsService.TicketPanel panel = new GuildModuleSettingsService.TicketPanel();
            panel.setId(panelRequest.id());
            panel.setTitle(panelRequest.title());
            panel.setDescription(panelRequest.description());
            panel.setInteractionMode(panelRequest.interactionMode());
            panel.setPublishChannelId(panelRequest.publishChannelId());
            panel.setCategoryId(panelRequest.categoryId());
            panel.setPlaceholder(panelRequest.placeholder());
            panel.setWelcomeMessage(panelRequest.welcomeMessage());
            panel.setImageUrl(panelRequest.imageUrl());
            panel.setThumbnailUrl(panelRequest.thumbnailUrl());
            panel.setAccentColor(panelRequest.accentColor());
            panel.setEmbed(EmbedVorlageMapper.ausOberflaeche(panelRequest.embed()));
            panel.setEmbedVorlageId(panelRequest.embedVorlageId());
            Role notifyRole = requireRoleIfPresent(guild, panelRequest.notifyRoleId());
            panel.setNotifyRoleId(notifyRole == null ? "" : notifyRole.getId());
            List<String> supportRoleIds = new ArrayList<>();
            for (String roleId : panelRequest.supportRoleIds() == null ? List.<String>of() : panelRequest.supportRoleIds()) {
                Role role = requireRoleIfPresent(guild, roleId);
                if (role != null && !supportRoleIds.contains(role.getId())) {
                    supportRoleIds.add(role.getId());
                }
            }
            panel.setSupportRoleIds(supportRoleIds);
            panel.setAllowClaim(panelRequest.allowClaim() == null || panelRequest.allowClaim());
            panel.setAllowPause(panelRequest.allowPause() == null || panelRequest.allowPause());
            panel.setAllowCreatorClose(panelRequest.allowCreatorClose() == null || panelRequest.allowCreatorClose());
            panel.setOneTicketPerUser(panelRequest.oneTicketPerUser() != null && panelRequest.oneTicketPerUser());
            panel.setOptions(options);
            panels.add(panel);
        }

        if (enabled && panels.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bitte mindestens ein gueltiges Ticket-Panel speichern.");
        }

        requireTextChannelIfPresent(guild, request.transcriptChannelId());
        settingsService.saveTicketState(guild.getId(), enabled, request.transcriptChannelId(), panels);
        String syncMessage = ticketModuleService.syncPublishedPanels(guild);

        return new ActionResponse(true, enabled
                ? "Ticket-System wurde gespeichert. " + syncMessage
                : "Ticket-System wurde deaktiviert. " + syncMessage);
    }

    @GetMapping("/guilds/{guildId}/tickets/transcripts/{transcriptId}")
    public ResponseEntity<byte[]> downloadTranscript(
            @PathVariable String guildId,
            @PathVariable long transcriptId,
            HttpSession session
    ) {
        dashboardAccessService.requireGuild(requireDashboardSession(session), guildId);
        var transcript = ticketTranscriptService.findTranscript(guildId, transcriptId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transcript nicht gefunden."));

        String safeName = (transcript.ticketSubject() == null || transcript.ticketSubject().isBlank()
                ? "ticket-transcript-" + transcript.id()
                : transcript.ticketSubject().toLowerCase().replaceAll("[^a-z0-9]+", "-"))
                .replaceAll("(^-+|-+$)", "");
        if (safeName.isBlank()) {
            safeName = "ticket-transcript-" + transcript.id();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeName + "-" + transcript.id() + ".txt\"")
                .body(transcript.transcriptText().getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/guilds/{guildId}/commands/{commandName}")
    public ActionResponse saveCommandState(
            @PathVariable String guildId,
            @PathVariable String commandName,
            @RequestBody ToggleRequest request,
            HttpSession session
    ) {
        Guild guild = dashboardAccessService.requireGuild(requireDashboardSession(session), guildId);
        if (!commandCatalogService.hasCommand(commandName)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Command nicht gefunden.");
        }

        boolean enabled = request.enabled() == null || request.enabled();
        settingsService.saveCommandState(guild.getId(), commandName, enabled);
        return new ActionResponse(true, "Command /" + commandName + " ist jetzt " + (enabled ? "aktiv." : "deaktiviert."));
    }

    private DashboardSession requireSession(HttpSession session) {
        Object user = session.getAttribute(DashboardController.SESSION_USER);
        if (user instanceof DashboardSession dashboardSession) {
            return dashboardSession;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Bitte zuerst ueber Discord anmelden.");
    }

    private DashboardSession requireDashboardSession(HttpSession session) {
        DashboardSession dashboardSession = requireSession(session);
        if (configService.isMaintenanceEnabled() && !adminAccessService.isAdmin(dashboardSession)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, configService.getMaintenanceMessage());
        }
        return dashboardSession;
    }

    private VoiceChannel requireVoiceChannel(Guild guild, String voiceChannelId) {
        if (voiceChannelId == null || voiceChannelId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bitte einen Voice-Channel auswaehlen.");
        }

        VoiceChannel channel = guild.getVoiceChannelById(voiceChannelId);
        if (channel == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Voice-Channel nicht gefunden.");
        }
        return channel;
    }

    private AudioChannel requireUserAudioChannel(Guild guild, DashboardSession dashboardSession) {
        AudioChannel channel = dashboardAccessService.getUserVoiceChannel(guild, dashboardSession.userId());
        if (channel == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Web-Audio ist nur verfuegbar, wenn du gerade in einem Voice-Channel auf diesem Server bist."
            );
        }
        return channel;
    }

    private AudioChannel resolveRequestedAudioChannel(Guild guild, String voiceChannelId, DashboardSession dashboardSession) {
        AudioChannel userChannel = requireUserAudioChannel(guild, dashboardSession);
        if (voiceChannelId == null || voiceChannelId.isBlank()) {
            return userChannel;
        }
        return requireVoiceChannel(guild, voiceChannelId);
    }

    private Category requireCategoryIfPresent(Guild guild, String categoryId) {
        if (categoryId == null || categoryId.isBlank()) {
            return null;
        }

        Category category = guild.getCategoryById(categoryId);
        if (category == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kategorie nicht gefunden.");
        }
        return category;
    }

    private TextChannel requireTextChannelIfPresent(Guild guild, String textChannelId) {
        if (textChannelId == null || textChannelId.isBlank()) {
            return null;
        }

        TextChannel textChannel = guild.getTextChannelById(textChannelId);
        if (textChannel == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Text-Channel nicht gefunden.");
        }
        return textChannel;
    }

    private Role requireRoleIfPresent(Guild guild, String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return null;
        }

        Role role = guild.getRoleById(roleId);
        if (role == null || role.isPublicRole()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rolle nicht gefunden.");
        }
        return role;
    }

    private List<Role> requireRolesIfPresent(Guild guild, List<String> roleIds) {
        List<Role> roles = new ArrayList<>();
        for (String roleId : roleIds == null ? List.<String>of() : roleIds) {
            Role role = requireRoleIfPresent(guild, roleId);
            if (role != null) {
                roles.add(role);
            }
        }
        return roles;
    }

    private JoinToCreateView toJoinToCreateView(Guild guild) {
        GuildModuleSettingsService.JoinToCreateState state = settingsService.getJoinToCreateState(guild.getId());
        return new JoinToCreateView(
                state.isEnabled(),
                state.getManagedChannels().size(),
                state.getCleanupDelaySeconds(),
                state.getAudioIdleTimeoutSeconds(),
                Math.max(8, guild.getMaxBitrate() / 1000),
                state.getEntries().stream()
                        .map(entry -> new JoinToCreateEntryView(
                                entry.getId(),
                                entry.getSourceChannelId(),
                                entry.getCategoryId(),
                                entry.getNameTemplate(),
                                entry.getUserLimit(),
                                entry.getBitrateKbps(),
                                entry.getNextCounter(),
                                entry.isSendConfigPrompt()
                        ))
                        .toList()
        );
    }

    private LlmModuleView toLlmView(String guildId) {
        GuildModuleSettingsService.LlmState state = settingsService.getLlmState(guildId);
        boolean configured = configService.isLlmConfigured();
        List<String> availableModels = configService.getAvailableLlmModels();
        String selectedModel = configService.resolveAllowedLlmModel(state.getModel());
        boolean hadRemovedModel = !state.getModel().isBlank() && !configService.isAllowedLlmModel(state.getModel());
        String notice = !configured
                ? "LLM braucht noch eine globale Konfiguration in den Admin-Einstellungen."
                : availableModels.isEmpty()
                ? "Der Admin muss erst mindestens ein Modell freigeben."
                : hadRemovedModel
                ? "Das zuvor gewaehlte Modell ist nicht mehr freigegeben. Es wird automatisch auf das Standardmodell zurueckgefallen."
                : "Der Bot antwortet nur, wenn du ihn im Discord-Chat direkt erwaehnst. Hier waehlt ihr nur Channel und Modell pro Server.";

        return new LlmModuleView(
                state.isEnabled(),
                state.getTextChannelId(),
                configured,
                configService.getLlmProvider(),
                selectedModel,
                availableModels,
                notice
        );
    }

    private CategoryChannelView toCategoryView(Category category) {
        return new CategoryChannelView(category.getId(), category.getName());
    }

    private TextChannelView toTextChannelView(TextChannel channel) {
        return new TextChannelView(channel.getId(), channel.getName());
    }

    private RoleView toRoleView(Role role) {
        return new RoleView(role.getId(), role.getName());
    }
}
