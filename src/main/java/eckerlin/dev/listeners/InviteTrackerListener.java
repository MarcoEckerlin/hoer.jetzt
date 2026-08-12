package eckerlin.dev.listeners;

import eckerlin.dev.services.InviteTrackerService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.invite.GuildInviteCreateEvent;
import net.dv8tion.jda.api.events.guild.invite.GuildInviteDeleteEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.stereotype.Component;

@Component
public class InviteTrackerListener extends ListenerAdapter {

    private final InviteTrackerService inviteTrackerService;

    public InviteTrackerListener(InviteTrackerService inviteTrackerService) {
        this.inviteTrackerService = inviteTrackerService;
    }

    @Override
    public void onReady(ReadyEvent event) {
        for (Guild guild : event.getJDA().getGuilds()) {
            inviteTrackerService.refreshCache(guild);
        }
    }

    @Override
    public void onGuildJoin(GuildJoinEvent event) {
        inviteTrackerService.refreshCache(event.getGuild());
    }

    @Override
    public void onGuildInviteCreate(GuildInviteCreateEvent event) {
        inviteTrackerService.refreshCache(event.getGuild());
    }

    @Override
    public void onGuildInviteDelete(GuildInviteDeleteEvent event) {
        inviteTrackerService.refreshCache(event.getGuild());
    }

    @Override
    public void onGuildMemberJoin(GuildMemberJoinEvent event) {
        inviteTrackerService.handleMemberJoin(event.getMember());
    }
}
