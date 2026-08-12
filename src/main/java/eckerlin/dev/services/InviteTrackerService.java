package eckerlin.dev.services;

import eckerlin.dev.web.dto.InviteJoinEventView;
import eckerlin.dev.web.dto.InviteTrackerView;
import eckerlin.dev.web.dto.TrackedInviteView;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Invite;
import net.dv8tion.jda.api.entities.Member;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class InviteTrackerService {

    private final GuildModuleSettingsService settingsService;
    private final Map<String, Map<String, Integer>> inviteUseCache = new ConcurrentHashMap<>();

    public InviteTrackerService(GuildModuleSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    public InviteTrackerView buildView(Guild guild) {
        boolean canReadInvites = canReadInvites(guild);
        List<TrackedInviteView> activeInvites = canReadInvites
                ? fetchInvites(guild).stream()
                .map(invite -> new TrackedInviteView(
                        invite.getCode(),
                        invite.getUses(),
                        invite.getInviter() == null ? "Unbekannt" : invite.getInviter().getAsTag(),
                        invite.isTemporary()
                ))
                .toList()
                : List.of();

        List<InviteJoinEventView> recentJoins = settingsService.getRecentInviteJoins(guild.getId()).stream()
                .map(entry -> new InviteJoinEventView(
                        entry.getMemberDisplay(),
                        entry.getInviteCode(),
                        entry.getInviterDisplay(),
                        entry.getUses(),
                        entry.getJoinedAt()
                ))
                .toList();

        String notice = canReadInvites
                ? "Invite-Status wird live aus Discord gelesen."
                : "Invite-Tracking braucht fuer den Bot die Berechtigung 'Server verwalten'.";

        return new InviteTrackerView(
                settingsService.isInviteTrackerEnabled(guild.getId()),
                canReadInvites,
                notice,
                activeInvites,
                recentJoins
        );
    }

    public void refreshCache(Guild guild) {
        if (!canReadInvites(guild)) {
            inviteUseCache.remove(guild.getId());
            return;
        }

        inviteUseCache.put(guild.getId(), toUseMap(fetchInvites(guild)));
    }

    public void handleMemberJoin(Member member) {
        Guild guild = member.getGuild();
        if (!settingsService.isInviteTrackerEnabled(guild.getId()) || !canReadInvites(guild)) {
            return;
        }

        List<Invite> invites = fetchInvites(guild);
        if (invites.isEmpty()) {
            return;
        }

        Map<String, Integer> previous = inviteUseCache.getOrDefault(guild.getId(), Map.of());
        Invite usedInvite = findUsedInvite(previous, invites);

        settingsService.recordInviteJoin(
                guild.getId(),
                member.getId(),
                member.getEffectiveName(),
                usedInvite == null ? "unbekannt" : usedInvite.getCode(),
                usedInvite == null || usedInvite.getInviter() == null ? "Unbekannt" : usedInvite.getInviter().getAsTag(),
                usedInvite == null ? null : usedInvite.getUses()
        );

        inviteUseCache.put(guild.getId(), toUseMap(invites));
    }

    public boolean canReadInvites(Guild guild) {
        return guild.getSelfMember() != null && guild.getSelfMember().hasPermission(Permission.MANAGE_SERVER);
    }

    private Invite findUsedInvite(Map<String, Integer> previousUses, List<Invite> currentInvites) {
        return currentInvites.stream()
                .filter(invite -> invite.getUses() > previousUses.getOrDefault(invite.getCode(), 0))
                .findFirst()
                .orElse(null);
    }

    private List<Invite> fetchInvites(Guild guild) {
        try {
            return guild.retrieveInvites().complete().stream()
                    .sorted((left, right) -> Integer.compare(
                            right.getUses(),
                            left.getUses()
                    ))
                    .toList();
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private Map<String, Integer> toUseMap(List<Invite> invites) {
        Map<String, Integer> uses = new LinkedHashMap<>();
        for (Invite invite : invites) {
            uses.put(invite.getCode(), invite.getUses());
        }
        return uses;
    }
}
