package eckerlin.dev.web;

import eckerlin.dev.security.AccessGuard;
import eckerlin.dev.security.AdminAuditService;
import eckerlin.dev.security.GuildPermission;
import eckerlin.dev.security.GuildPermissionService;
import eckerlin.dev.web.dto.ActionResponse;
import eckerlin.dev.web.dto.DashboardSession;
import eckerlin.dev.web.dto.GuildPermissionMatrixRequest;
import eckerlin.dev.web.dto.GuildPermissionMatrixView;
import eckerlin.dev.web.dto.GuildRoleView;
import eckerlin.dev.web.dto.PermissionDescriptor;
import jakarta.servlet.http.HttpSession;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.awt.Color;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rechtematrix eines Discord-Servers: welche Rolle darf was.
 *
 * <p>Ersetzt die bisherige Alles-oder-nichts-Regel „wer den Server verwalten
 * darf, darf im Panel alles".
 */
@RestController
@RequestMapping("/api/dashboard/guilds/{guildId}/permissions")
public class GuildPermissionApiController {

    private final AccessGuard accessGuard;
    private final GuildPermissionService guildPermissionService;
    private final AdminAuditService auditService;

    public GuildPermissionApiController(
            AccessGuard accessGuard,
            GuildPermissionService guildPermissionService,
            AdminAuditService auditService
    ) {
        this.accessGuard = accessGuard;
        this.guildPermissionService = guildPermissionService;
        this.auditService = auditService;
    }

    @GetMapping
    public GuildPermissionMatrixView view(@PathVariable String guildId, HttpSession httpSession) {
        DashboardSession session = requireSession(httpSession);
        Guild guild = accessGuard.requireGuild(session, guildId, GuildPermission.WEB_ACCESS);

        Map<String, Set<GuildPermission>> stored = guildPermissionService.matrix(guildId);

        List<PermissionDescriptor> catalog = new ArrayList<>();
        for (GuildPermission permission : GuildPermission.values()) {
            catalog.add(new PermissionDescriptor(permission.name(), permission.label(), permission.description()));
        }

        List<GuildRoleView> roles = new ArrayList<>();
        for (Role role : guild.getRoles()) {
            // Bot- und Integrationsrollen lassen sich Mitgliedern nicht frei
            // zuweisen; sie stehen trotzdem in der Liste, damit ein bestehender
            // Eintrag nicht unsichtbar wird.
            roles.add(new GuildRoleView(
                    role.getId(),
                    role.getName(),
                    hex(role.getColor()),
                    role.getPosition(),
                    role.isManaged(),
                    role.isPublicRole()
            ));
        }

        Map<String, List<String>> matrix = new LinkedHashMap<>();
        stored.forEach((roleId, permissions) -> matrix.put(roleId, permissions.stream().map(Enum::name).toList()));

        List<String> own = accessGuard.permissions(session, guildId).stream().map(Enum::name).toList();

        return new GuildPermissionMatrixView(
                !stored.isEmpty(),
                catalog,
                roles,
                matrix,
                own,
                accessGuard.isBotAdmin(session)
        );
    }

    @PostMapping
    public ActionResponse save(
            @PathVariable String guildId,
            @RequestBody GuildPermissionMatrixRequest request,
            HttpSession httpSession
    ) {
        DashboardSession session = requireSession(httpSession);
        Guild guild = accessGuard.requireGuild(session, guildId, GuildPermission.PERMISSION_MANAGE);

        Map<String, Set<GuildPermission>> matrix = new LinkedHashMap<>();
        Map<String, List<String>> incoming = request == null || request.matrix() == null ? Map.of() : request.matrix();

        for (Map.Entry<String, List<String>> entry : incoming.entrySet()) {
            String roleId = entry.getKey() == null ? "" : entry.getKey().trim();
            if (roleId.isBlank() || entry.getValue() == null) {
                continue;
            }
            // Nur Rollen speichern, die es auf dem Server wirklich gibt -
            // sonst sammeln sich Karteileichen von geloeschten Rollen an.
            if (guild.getRoleById(roleId) == null) {
                continue;
            }

            Set<GuildPermission> permissions = EnumSet.noneOf(GuildPermission.class);
            for (String key : entry.getValue()) {
                GuildPermission.fromKey(key).ifPresent(permissions::add);
            }
            if (!permissions.isEmpty()) {
                matrix.put(roleId, permissions);
            }
        }

        // Absicherung gegen das Aussperren: mindestens eine Rolle muss das
        // Recht behalten, die Matrix selbst wieder aendern zu koennen.
        boolean someoneCanManage = matrix.values().stream().anyMatch(set -> set.contains(GuildPermission.PERMISSION_MANAGE));
        if (!matrix.isEmpty() && !someoneCanManage) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Mindestens eine Rolle braucht das Recht „Rollenrechte verwalten\" — sonst kommt hier niemand mehr heran.");
        }

        try {
            guildPermissionService.saveMatrix(guildId, matrix);
        } catch (SQLException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Die Rollenrechte konnten nicht gespeichert werden.");
        }

        auditService.record(session.userId(), session.username(), "PERMISSIONS_SAVE", "GUILD", guildId,
                matrix.size() + " Rolle(n) mit Rechten");

        return new ActionResponse(true, matrix.isEmpty()
                ? "Die Rechtematrix wurde geleert. Es gilt wieder die Discord-Berechtigung „Server verwalten\"."
                : "Die Rollenrechte wurden gespeichert.");
    }

    private String hex(Color color) {
        if (color == null) {
            return "";
        }
        return "#%02x%02x%02x".formatted(color.getRed(), color.getGreen(), color.getBlue());
    }

    private DashboardSession requireSession(HttpSession httpSession) {
        Object user = httpSession.getAttribute(DashboardController.SESSION_USER);
        if (user instanceof DashboardSession dashboardSession) {
            return dashboardSession;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Bitte zuerst über Discord anmelden.");
    }
}
