package eckerlin.dev.listeners;

import eckerlin.dev.services.DiscordLoggingService;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.guild.GuildAuditLogEntryCreateEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.stereotype.Component;

@Component
public class DiscordLoggingListener extends ListenerAdapter {

    private final DiscordLoggingService discordLoggingService;

    public DiscordLoggingListener(DiscordLoggingService discordLoggingService) {
        this.discordLoggingService = discordLoggingService;
    }

    @Override
    public void onGuildMemberJoin(GuildMemberJoinEvent event) {
        discordLoggingService.logMemberJoin(event.getMember());
    }

    @Override
    public void onGuildMemberRemove(GuildMemberRemoveEvent event) {
        discordLoggingService.logMemberLeave(event.getGuild(), event.getUser());
    }

    @Override
    public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
        if (event.getMember().getUser().isBot()) {
            return;
        }

        if (event.getChannelJoined() instanceof VoiceChannel joinedChannel) {
            discordLoggingService.logVoiceJoin(event.getMember(), joinedChannel.getName());
        }

        if (event.getChannelLeft() instanceof VoiceChannel leftChannel) {
            discordLoggingService.logVoiceLeave(event.getMember(), leftChannel.getName());
        }
    }

    @Override
    public void onGuildAuditLogEntryCreate(GuildAuditLogEntryCreateEvent event) {
        discordLoggingService.logModerationAudit(event.getEntry());
    }
}
