package eckerlin.dev.services;

import eckerlin.dev.security.AccessGuard;
import eckerlin.dev.security.GuildPermission;
import eckerlin.dev.security.GuildPermissionService;
import eckerlin.dev.web.dto.DashboardGuildView;
import eckerlin.dev.web.dto.DashboardSession;
import eckerlin.dev.web.dto.DiscordGuildAccess;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Welche Server darf der angemeldete Nutzer im Panel sehen und bedienen?
 *
 * <p>Die Entscheidung faellt in drei Stufen, absichtlich von billig nach teuer:
 *
 * <ol>
 *   <li>Bot-Admin? Dann alle Server, auf denen der Bot ist.</li>
 *   <li>Laut Discord Inhaber oder Administrator des Servers? Dann Zugriff, ohne
 *       weitere Abfrage.</li>
 *   <li>Sonst die Rechtematrix des Servers befragen. Erst hier werden die
 *       Rollen des Mitglieds ueber JDA nachgeschlagen - ein Aufruf, den man
 *       sich fuer die Mehrheit der Faelle spart.</li>
 * </ol>
 */
@Service
public class DashboardAccessService {

    private static final long ADMINISTRATOR = 0x8L;
    private static final long MANAGE_GUILD = 0x20L;

    private final DiscordBotService discordBotService;
    private final AccessGuard accessGuard;
    private final GuildPermissionService guildPermissionService;

    public DashboardAccessService(
            DiscordBotService discordBotService,
            AccessGuard accessGuard,
            GuildPermissionService guildPermissionService
    ) {
        this.discordBotService = discordBotService;
        this.accessGuard = accessGuard;
        this.guildPermissionService = guildPermissionService;
    }

    public List<DashboardGuildView> getManageableGuilds(DashboardSession session) {
        if (session == null) {
            return List.of();
        }

        Map<String, Guild> candidates = new LinkedHashMap<>();

        if (accessGuard.isBotAdmin(session)) {
            // Ein Bot-Admin verwaltet den Bot, nicht einen einzelnen Server -
            // er sieht deshalb alle Server, auch die, auf denen er selbst gar
            // kein Mitglied ist.
            for (Guild guild : discordBotService.getGuilds()) {
                candidates.put(guild.getId(), guild);
            }
        } else {
            for (DiscordGuildAccess access : safeGuilds(session)) {
                Guild guild = lookupGuild(access.id());
                if (guild != null && canOpenPanel(session, access, guild)) {
                    candidates.put(guild.getId(), guild);
                }
            }
        }

        List<DashboardGuildView> views = new ArrayList<>(candidates.size());
        for (Guild guild : candidates.values()) {
            AudioChannel voiceChannel = getUserVoiceChannel(guild, session.userId());
            views.add(new DashboardGuildView(
                    guild.getId(),
                    guild.getName(),
                    guild.getIconUrl(),
                    guild.getMemberCount(),
                    voiceChannel != null,
                    voiceChannel == null ? null : voiceChannel.getName()
            ));
        }
        return views;
    }

    /**
     * Zugriff auf einen einzelnen Server.
     *
     * <p>Standardrecht ist {@link GuildPermission#WEB_ACCESS}. Endpunkte, die
     * mehr verlangen — etwa Modulkonfiguration — pruefen zusaetzlich ueber
     * {@link AccessGuard#requirePermission}.
     */
    public Guild requireGuild(DashboardSession session, String guildId) {
        return accessGuard.requireGuild(session, guildId, GuildPermission.WEB_ACCESS);
    }

    public Guild requireGuild(DashboardSession session, String guildId, GuildPermission permission) {
        return accessGuard.requireGuild(session, guildId, permission);
    }

    public AudioChannel getUserVoiceChannel(Guild guild, String userId) {
        if (guild == null || userId == null || userId.isBlank()) {
            return null;
        }

        Member member = guild.getMemberById(userId);
        if (member == null) {
            try {
                member = guild.retrieveMemberById(userId).complete();
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        if (member == null || member.getVoiceState() == null) {
            return null;
        }

        return member.getVoiceState().getChannel();
    }

    private boolean canOpenPanel(DashboardSession session, DiscordGuildAccess access, Guild guild) {
        if (access.owner()
                || (access.permissions() & ADMINISTRATOR) == ADMINISTRATOR
                || (access.permissions() & MANAGE_GUILD) == MANAGE_GUILD) {
            return true;
        }

        // Ohne gepflegte Matrix gaebe es hier nichts zu gewinnen - dann sparen
        // wir uns den JDA-Aufruf fuer die Rollen.
        if (guildPermissionService.matrix(guild.getId()).isEmpty()) {
            return false;
        }

        return accessGuard.has(session, guild.getId(), GuildPermission.WEB_ACCESS);
    }

    private List<DiscordGuildAccess> safeGuilds(DashboardSession session) {
        return session.guilds() == null ? List.of() : session.guilds();
    }

    private Guild lookupGuild(String guildId) {
        if (guildId == null || !guildId.matches("\\d{5,32}")) {
            return null;
        }
        return discordBotService.getJdaOptional()
                .map(jda -> jda.getGuildById(guildId))
                .orElse(null);
    }
}
