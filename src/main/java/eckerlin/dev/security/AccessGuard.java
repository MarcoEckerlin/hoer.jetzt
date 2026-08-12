package eckerlin.dev.security;

import eckerlin.dev.services.DiscordApplicationOwnerService;
import eckerlin.dev.services.DiscordBotService;
import eckerlin.dev.web.dto.DashboardSession;
import eckerlin.dev.web.dto.DiscordGuildAccess;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Eine Anlaufstelle fuer alle Zugriffsfragen der Weboberflaeche.
 *
 * <p>Vorher lagen die Pruefungen verstreut: {@code AdminAccessService} fragte den
 * Discord-Botinhaber ab, {@code DashboardAccessService} rechnete mit rohen
 * Discord-Berechtigungsbits, und die Modulendpunkte pruefen teils gar nichts.
 * Diese Klasse buendelt das, damit eine Regel genau einmal existiert.
 *
 * <p><strong>Grundregel:</strong> ein Bot-Admin darf auf jedem Server alles,
 * unabhaengig von seinen Discord-Rollen dort. Fuer alle anderen entscheidet die
 * Rechtematrix des jeweiligen Servers.
 *
 * <p>Diese Klasse wird ausschliesslich aus der Webschicht benutzt. Listener und
 * der AudioService greifen direkt auf {@link GuildPermissionService} und
 * {@link GuildEntitlementService} zu, um keinen Abhaengigkeitszyklus ueber
 * {@code DiscordBotService} zu erzeugen.
 */
@Service
public class AccessGuard {

    private final BotAdminService botAdminService;
    private final GuildPermissionService guildPermissionService;
    private final GuildEntitlementService guildEntitlementService;
    private final DiscordApplicationOwnerService discordApplicationOwnerService;
    private final DiscordBotService discordBotService;

    public AccessGuard(
            BotAdminService botAdminService,
            GuildPermissionService guildPermissionService,
            GuildEntitlementService guildEntitlementService,
            DiscordApplicationOwnerService discordApplicationOwnerService,
            DiscordBotService discordBotService
    ) {
        this.botAdminService = botAdminService;
        this.guildPermissionService = guildPermissionService;
        this.guildEntitlementService = guildEntitlementService;
        this.discordApplicationOwnerService = discordApplicationOwnerService;
        this.discordBotService = discordBotService;
    }

    // ------------------------------------------------------------------
    // Bot-Verwaltung
    // ------------------------------------------------------------------

    /**
     * Stufe des angemeldeten Nutzers in der Bot-Verwaltung.
     *
     * <p>Nebenbei wird der Discord-Application-Owner in die Tabelle uebernommen,
     * falls er noch fehlt. Das passiert hier und nicht im
     * {@link BotAdminService}, weil dort der Zugriff auf Discord einen
     * Abhaengigkeitszyklus erzeugen wuerde.
     */
    public Optional<BotAdminRole> botAdminRole(DashboardSession session) {
        if (session == null || session.userId() == null || session.userId().isBlank()) {
            return Optional.empty();
        }

        discordApplicationOwnerService.getApplicationOwner().ifPresent(owner ->
                botAdminService.syncApplicationOwner(owner.ownerId(), owner.ownerName()));

        return botAdminService.roleOf(session.userId());
    }

    public boolean isBotAdmin(DashboardSession session) {
        return botAdminRole(session).isPresent();
    }

    public BotAdminRole requireBotAdmin(DashboardSession session, BotAdminRole minimum) {
        BotAdminRole role = botAdminRole(session).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.FORBIDDEN, "Dieser Bereich ist der Bot-Verwaltung vorbehalten."));

        if (!role.atLeast(minimum)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Dafür wird mindestens die Stufe %s benötigt.".formatted(minimum.label()));
        }
        return role;
    }

    // ------------------------------------------------------------------
    // Rechte auf einem Discord-Server
    // ------------------------------------------------------------------

    /** Rechte des angemeldeten Nutzers auf einem Server. Bot-Admins bekommen alles. */
    public Set<GuildPermission> permissions(DashboardSession session, String guildId) {
        if (session == null || guildId == null || guildId.isBlank()) {
            return EnumSet.noneOf(GuildPermission.class);
        }
        if (isBotAdmin(session)) {
            return EnumSet.allOf(GuildPermission.class);
        }

        DiscordGuildAccess access = session.guilds() == null ? null : session.guilds().stream()
                .filter(entry -> guildId.equals(entry.id()))
                .findFirst()
                .orElse(null);

        if (access == null) {
            return EnumSet.noneOf(GuildPermission.class);
        }

        // Die OAuth-Antwort von Discord enthaelt keine Rollen-IDs, nur ein
        // Berechtigungs-Bitfeld. Die Rollen holen wir daher ueber JDA - und
        // fallen still auf das Bitfeld zurueck, wenn der Bot das Mitglied
        // gerade nicht kennt.
        List<String> roleIds = lookupRoleIds(guildId, session.userId());
        return guildPermissionService.resolve(guildId, roleIds, access.owner(), access.permissions());
    }

    public boolean has(DashboardSession session, String guildId, GuildPermission permission) {
        return permissions(session, guildId).contains(permission);
    }

    /**
     * Prueft das Recht und liefert den Server. Ersetzt den bisherigen
     * {@code DashboardAccessService.requireGuild}.
     */
    public Guild requireGuild(DashboardSession session, String guildId, GuildPermission permission) {
        if (guildId == null || guildId.isBlank() || !guildId.matches("\\d{5,32}")) {
            // jda.getGuildById wirft bei nicht-numerischer Eingabe eine
            // Exception statt null zu liefern - deshalb vorher pruefen.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ungültige Server-ID.");
        }

        if (!permissions(session, guildId).contains(permission)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Dafür fehlt dir das Recht „%s\" auf diesem Server.".formatted(permission.label()));
        }

        Guild guild = discordBotService.getJdaOptional()
                .map(jda -> jda.getGuildById(guildId))
                .orElse(null);

        if (guild == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Der Bot ist auf diesem Server nicht (mehr) vorhanden.");
        }
        return guild;
    }

    public void requirePermission(DashboardSession session, String guildId, GuildPermission permission) {
        if (!permissions(session, guildId).contains(permission)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Dafür fehlt dir das Recht „%s\" auf diesem Server.".formatted(permission.label()));
        }
    }

    // ------------------------------------------------------------------
    // Freischaltungen
    // ------------------------------------------------------------------

    public boolean isFeatureEnabled(String guildId, GuildFeature feature) {
        return guildEntitlementService.isEnabled(guildId, feature);
    }

    /**
     * Bricht ab, wenn die Funktion fuer diesen Server nicht freigeschaltet ist.
     * Gilt auch fuer Bot-Admins: die Freischaltung ist eine Kostenentscheidung,
     * keine Rechtefrage — wer sie will, schaltet sie im Adminpanel frei.
     */
    public void requireFeature(String guildId, GuildFeature feature) {
        if (!guildEntitlementService.isEnabled(guildId, feature)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "%s ist für diesen Server nicht freigeschaltet.".formatted(feature.label()));
        }
    }

    private List<String> lookupRoleIds(String guildId, String userId) {
        try {
            Guild guild = discordBotService.getJdaOptional()
                    .map(jda -> jda.getGuildById(guildId))
                    .orElse(null);
            if (guild == null) {
                return List.of();
            }

            Member member = guild.getMemberById(userId);
            if (member == null) {
                member = guild.retrieveMemberById(userId).complete();
            }
            if (member == null) {
                return List.of();
            }
            return member.getRoles().stream().map(Role::getId).toList();
        } catch (RuntimeException exception) {
            return List.of();
        }
    }
}
