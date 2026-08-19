package eckerlin.dev.web;

import eckerlin.dev.security.AccessGuard;
import eckerlin.dev.security.AdminAuditService;
import eckerlin.dev.security.AuditEntry;
import eckerlin.dev.security.BotAdminRole;
import eckerlin.dev.security.BotAdminService;
import eckerlin.dev.security.GuildEntitlementService;
import eckerlin.dev.security.GuildFeature;
import eckerlin.dev.security.GuildPermissionService;
import eckerlin.dev.services.DiscordBotService;
import eckerlin.dev.services.GuildStatistikService;
import eckerlin.dev.web.dto.ActionResponse;
import eckerlin.dev.web.dto.AdminGuildStats;
import eckerlin.dev.web.dto.AdminGuildView;
import eckerlin.dev.web.dto.BotAdminOverview;
import eckerlin.dev.web.dto.BotAdminRequest;
import eckerlin.dev.web.dto.DashboardSession;
import eckerlin.dev.web.dto.EntitlementRequest;
import eckerlin.dev.web.dto.PermissionDescriptor;
import eckerlin.dev.verbund.GuildSammler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Bot-Verwaltung: Admins, Server, Freischaltungen, Audit-Log.
 *
 * <p>Bewusst getrennt vom bestehenden {@link AdminApiController}, der die
 * Instanz- und Bot-Einstellungen bedient. Die Trennung haelt beide Klassen
 * ueberschaubar und macht die Rechtestufen pro Bereich sichtbar:
 * Lesen ab {@link BotAdminRole#SUPPORT}, Aendern ab {@link BotAdminRole#ADMIN},
 * die Verwaltung anderer Admins nur fuer {@link BotAdminRole#OWNER}.
 */
@RestController
@RequestMapping("/api/admin/management")
public class BotAdminApiController {

    private final AccessGuard accessGuard;
    private final BotAdminService botAdminService;
    private final GuildEntitlementService guildEntitlementService;
    private final GuildPermissionService guildPermissionService;
    private final AdminAuditService auditService;
    private final DiscordBotService discordBotService;
    private final GuildSammler guildSammler;
    private final GuildStatistikService guildStatistikService;

    public BotAdminApiController(
            AccessGuard accessGuard,
            BotAdminService botAdminService,
            GuildEntitlementService guildEntitlementService,
            GuildPermissionService guildPermissionService,
            AdminAuditService auditService,
            DiscordBotService discordBotService,
            GuildSammler guildSammler,
            GuildStatistikService guildStatistikService
    ) {
        this.accessGuard = accessGuard;
        this.botAdminService = botAdminService;
        this.guildEntitlementService = guildEntitlementService;
        this.guildPermissionService = guildPermissionService;
        this.auditService = auditService;
        this.discordBotService = discordBotService;
        this.guildSammler = guildSammler;
        this.guildStatistikService = guildStatistikService;
    }

    // ------------------------------------------------------------------
    // Admins
    // ------------------------------------------------------------------

    @GetMapping("/admins")
    public BotAdminOverview admins(HttpSession httpSession) {
        DashboardSession session = requireSession(httpSession);
        BotAdminRole role = accessGuard.requireBotAdmin(session, BotAdminRole.SUPPORT);

        // Owner steht nur einem Owner zur Wahl.
        //
        // Die Liste steuert allein die Anzeige - gespeichert wird ohnehin erst
        // nach requireBotAdmin(OWNER) in saveAdmin. Sie wird trotzdem hier
        // gefiltert, weil eine Stufe im Auswahlfeld, die beim Speichern
        // abgelehnt wird, dem Support-Nutzer ein Recht vorspiegelt.
        List<PermissionDescriptor> assignable = new ArrayList<>();
        if (role.atLeast(BotAdminRole.OWNER)) {
            assignable.add(new PermissionDescriptor(BotAdminRole.OWNER.name(), BotAdminRole.OWNER.label(),
                    "Darf alles, auch weitere Owner und Admins verwalten. Nur an Personen, denen der Betrieb gehört."));
        }
        assignable.add(new PermissionDescriptor(BotAdminRole.ADMIN.name(), BotAdminRole.ADMIN.label(),
                "Darf alles verwalten, aber keine weiteren Admins ernennen."));
        assignable.add(new PermissionDescriptor(BotAdminRole.SUPPORT.name(), BotAdminRole.SUPPORT.label(),
                "Darf Server und Protokolle einsehen, ändert aber nichts."));

        return new BotAdminOverview(
                session.userId(),
                role.name(),
                role.atLeast(BotAdminRole.OWNER),
                botAdminService.list(),
                assignable
        );
    }

    @PostMapping("/admins")
    public ActionResponse saveAdmin(@RequestBody BotAdminRequest request, HttpSession httpSession) {
        DashboardSession session = requireSession(httpSession);
        accessGuard.requireBotAdmin(session, BotAdminRole.OWNER);

        BotAdminRole role = BotAdminRole.fromKey(request == null ? null : request.role())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unbekannte Stufe."));

        try {
            botAdminService.save(request.userId(), role, request.displayName(), session.userId());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (SQLException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Der Admin konnte nicht gespeichert werden.");
        }

        auditService.record(session.userId(), session.username(), "ADMIN_SAVE", "USER", request.userId(),
                "Stufe: " + role.label());
        return new ActionResponse(true, "Der Eintrag wurde gespeichert.");
    }

    @DeleteMapping("/admins/{userId}")
    public ActionResponse removeAdmin(@PathVariable String userId, HttpSession httpSession) {
        DashboardSession session = requireSession(httpSession);
        accessGuard.requireBotAdmin(session, BotAdminRole.OWNER);

        if (userId != null && userId.equals(session.userId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Du kannst dich nicht selbst entfernen.");
        }

        try {
            botAdminService.remove(userId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (SQLException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Der Admin konnte nicht entfernt werden.");
        }

        auditService.record(session.userId(), session.username(), "ADMIN_REMOVE", "USER", userId, "");
        return new ActionResponse(true, "Der Eintrag wurde entfernt.");
    }

    // ------------------------------------------------------------------
    // Server
    // ------------------------------------------------------------------

    /**
     * Alle Server, auf denen der Bot ist.
     *
     * <p>Mit aufgeteilten Shards kennt diese Node nur ihren eigenen Teil -
     * ohne Zusammenfuehrung sah man je nach Lastverteiler vier oder drei von
     * sieben Servern, ohne dass etwas auf die fehlenden hingewiesen haette.</p>
     */
    @GetMapping("/guilds")
    public List<AdminGuildView> guilds(HttpSession httpSession, HttpServletRequest anfrage) {
        DashboardSession session = requireSession(httpSession);
        accessGuard.requireBotAdmin(session, BotAdminRole.SUPPORT);

        List<AdminGuildView> views = new ArrayList<>();
        for (Guild guild : discordBotService.getGuilds()) {
            Member owner = guild.getOwner();
            views.add(new AdminGuildView(
                    guild.getId(),
                    guild.getName(),
                    guild.getIconUrl(),
                    guild.getMemberCount(),
                    guild.getOwnerId(),
                    owner == null ? "" : owner.getEffectiveName(),
                    guild.getSelfMember().getTimeJoined().toInstant().toString(),
                    !guildPermissionService.matrix(guild.getId()).isEmpty(),
                    guildEntitlementService.list(guild.getId())
            ));
        }
        views.sort(Comparator.comparing(AdminGuildView::name, String.CASE_INSENSITIVE_ORDER));
        return guildSammler.ergaenzenVerwaltung(views, anfrage);
    }

    /**
     * Zahlen zu einem Server - erst beim Aufklappen der Zeile.
     *
     * <p>Absichtlich nicht Teil von {@code /guilds}: die Zahlen kosten je
     * Server vier Abfragen ueber das Netz zur Datenbank. In der Liste haetten
     * zwanzig Server also achtzig Abfragen gekostet, damit man eine davon
     * ansieht.</p>
     *
     * <p>SUPPORT reicht: hier wird nur gelesen. Wer die Liste sehen darf, darf
     * auch wissen, was auf den Servern los ist - das ist die Frage, wegen der
     * man eine Support-Stufe ueberhaupt vergibt.</p>
     */
    @GetMapping("/guilds/{guildId}/stats")
    public AdminGuildStats guildStats(@PathVariable String guildId, HttpSession httpSession) {
        DashboardSession session = requireSession(httpSession);
        accessGuard.requireBotAdmin(session, BotAdminRole.SUPPORT);

        if (!guildId.matches("\\d{5,32}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ungültige Server-ID.");
        }
        return guildStatistikService.fuerServer(guildId);
    }

    @PostMapping("/guilds/{guildId}/entitlements")
    public ActionResponse setEntitlement(
            @PathVariable String guildId,
            @RequestBody EntitlementRequest request,
            HttpSession httpSession
    ) {
        DashboardSession session = requireSession(httpSession);
        accessGuard.requireBotAdmin(session, BotAdminRole.ADMIN);

        GuildFeature feature = GuildFeature.fromKey(request == null ? null : request.feature())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unbekannte Funktion."));

        try {
            guildEntitlementService.set(guildId, feature, request.enabled(), request.dailyLimit(), request.note(), session.userId());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (SQLException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Die Freischaltung konnte nicht gespeichert werden.");
        }

        auditService.record(session.userId(), session.username(),
                request.enabled() ? "FEATURE_GRANT" : "FEATURE_REVOKE", "GUILD", guildId,
                "%s, Tageslimit %d".formatted(feature.label(), Math.max(0, request.dailyLimit())));

        return new ActionResponse(true, request.enabled()
                ? "%s ist für diesen Server freigeschaltet.".formatted(feature.label())
                : "%s wurde für diesen Server gesperrt.".formatted(feature.label()));
    }

    @PostMapping("/guilds/{guildId}/leave")
    public ActionResponse leaveGuild(@PathVariable String guildId, HttpSession httpSession) {
        DashboardSession session = requireSession(httpSession);
        accessGuard.requireBotAdmin(session, BotAdminRole.ADMIN);

        Guild guild = discordBotService.getGuildById(guildId)
                .orElse(null);

        if (guild == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Der Bot ist auf diesem Server nicht vorhanden.");
        }

        String name = guild.getName();
        guild.leave().queue();
        auditService.record(session.userId(), session.username(), "GUILD_LEAVE", "GUILD", guildId, name);
        return new ActionResponse(true, "Der Bot verlässt „%s\".".formatted(name));
    }

    // ------------------------------------------------------------------
    // Audit-Log
    // ------------------------------------------------------------------

    @GetMapping("/audit")
    public List<AuditEntry> audit(
            @RequestParam(required = false, defaultValue = "100") int limit,
            HttpSession httpSession
    ) {
        DashboardSession session = requireSession(httpSession);
        accessGuard.requireBotAdmin(session, BotAdminRole.SUPPORT);
        return auditService.list(limit);
    }

    private DashboardSession requireSession(HttpSession httpSession) {
        Object user = httpSession.getAttribute(DashboardController.SESSION_USER);
        if (user instanceof DashboardSession dashboardSession) {
            return dashboardSession;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Bitte zuerst über Discord anmelden.");
    }
}
