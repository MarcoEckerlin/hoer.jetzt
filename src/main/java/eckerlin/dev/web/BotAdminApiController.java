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
import eckerlin.dev.web.dto.ActionResponse;
import eckerlin.dev.web.dto.AdminGuildView;
import eckerlin.dev.web.dto.BotAdminOverview;
import eckerlin.dev.web.dto.BotAdminRequest;
import eckerlin.dev.web.dto.DashboardSession;
import eckerlin.dev.web.dto.EntitlementRequest;
import eckerlin.dev.web.dto.PermissionDescriptor;
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

    public BotAdminApiController(
            AccessGuard accessGuard,
            BotAdminService botAdminService,
            GuildEntitlementService guildEntitlementService,
            GuildPermissionService guildPermissionService,
            AdminAuditService auditService,
            DiscordBotService discordBotService
    ) {
        this.accessGuard = accessGuard;
        this.botAdminService = botAdminService;
        this.guildEntitlementService = guildEntitlementService;
        this.guildPermissionService = guildPermissionService;
        this.auditService = auditService;
        this.discordBotService = discordBotService;
    }

    // ------------------------------------------------------------------
    // Admins
    // ------------------------------------------------------------------

    @GetMapping("/admins")
    public BotAdminOverview admins(HttpSession httpSession) {
        DashboardSession session = requireSession(httpSession);
        BotAdminRole role = accessGuard.requireBotAdmin(session, BotAdminRole.SUPPORT);

        List<PermissionDescriptor> assignable = List.of(
                new PermissionDescriptor(BotAdminRole.ADMIN.name(), BotAdminRole.ADMIN.label(),
                        "Darf alles verwalten, aber keine weiteren Admins ernennen."),
                new PermissionDescriptor(BotAdminRole.SUPPORT.name(), BotAdminRole.SUPPORT.label(),
                        "Darf Server und Protokolle einsehen, ändert aber nichts.")
        );

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

    @GetMapping("/guilds")
    public List<AdminGuildView> guilds(HttpSession httpSession) {
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
        return views;
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

        Guild guild = discordBotService.getJdaOptional()
                .map(jda -> guildId != null && guildId.matches("\\d{5,32}") ? jda.getGuildById(guildId) : null)
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
