package eckerlin.dev.services;

import eckerlin.dev.audio.AudioService;
import eckerlin.dev.commands.SlashCommandListener;
import eckerlin.dev.listeners.AudioControlListener;
import eckerlin.dev.listeners.CommunityModuleListener;
import eckerlin.dev.listeners.InviteTrackerListener;
import eckerlin.dev.listeners.LlmModuleListener;
import eckerlin.dev.listeners.TicketModuleListener;
import eckerlin.dev.listeners.VoiceChannelModuleListener;
import eckerlin.dev.listeners.DiscordLoggingListener;
import eckerlin.dev.utils.Alert;
import eckerlin.dev.utils.Client;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class DiscordBotService {

    private final BotSettingsService botSettingsService;
    private final AudioService audioService;
    private final SlashCommandListener slashCommandListener;
    private final AudioControlListener audioControlListener;
    private final VoiceChannelModuleListener voiceChannelModuleListener;
    private final InviteTrackerListener inviteTrackerListener;
    private final CommunityModuleListener communityModuleListener;
    private final LlmModuleListener llmModuleListener;
    private final TicketModuleListener ticketModuleListener;
    private final DiscordLoggingListener discordLoggingListener;
    private final BotPresenceService botPresenceService;
    private final TicketModuleService ticketModuleService;
    private final CommunityModuleService communityModuleService;
    private volatile JDA jda;

    public DiscordBotService(
            BotSettingsService botSettingsService,
            AudioService audioService,
            SlashCommandListener slashCommandListener,
            AudioControlListener audioControlListener,
            VoiceChannelModuleListener voiceChannelModuleListener,
            InviteTrackerListener inviteTrackerListener,
            CommunityModuleListener communityModuleListener,
            LlmModuleListener llmModuleListener,
            TicketModuleListener ticketModuleListener,
            DiscordLoggingListener discordLoggingListener,
            BotPresenceService botPresenceService,
            TicketModuleService ticketModuleService,
            CommunityModuleService communityModuleService
    ) {
        this.botSettingsService = botSettingsService;
        this.audioService = audioService;
        this.slashCommandListener = slashCommandListener;
        this.audioControlListener = audioControlListener;
        this.voiceChannelModuleListener = voiceChannelModuleListener;
        this.inviteTrackerListener = inviteTrackerListener;
        this.communityModuleListener = communityModuleListener;
        this.llmModuleListener = llmModuleListener;
        this.ticketModuleListener = ticketModuleListener;
        this.discordLoggingListener = discordLoggingListener;
        this.botPresenceService = botPresenceService;
        this.ticketModuleService = ticketModuleService;
        this.communityModuleService = communityModuleService;
    }

    public synchronized JDA start() throws InterruptedException {
        if (jda != null) {
            return jda;
        }

        BotSettings settings = botSettingsService.load();
        Alert.send("INFO", "DISCORD", "Starte Discord Bot...");

        audioService.initialize(settings.token());
        jda = Client.build(
                settings,
                audioService.getVoiceDispatchInterceptor(),
                slashCommandListener,
                audioControlListener,
                voiceChannelModuleListener,
                inviteTrackerListener,
                communityModuleListener,
                llmModuleListener,
                ticketModuleListener,
                discordLoggingListener
        ).awaitReady();
        audioService.attachJda(jda);
        botPresenceService.attachJda(jda);
        jda.getGuilds().forEach(guild -> {
            ticketModuleService.syncPublishedPanels(guild);
            communityModuleService.syncReactionRoleMessage(guild);
            communityModuleService.syncVerifyMessage(guild);
        });
        Alert.send("SUCCESS", "DISCORD", "Discord Bot ist online.");
        return jda;
    }

    public Optional<JDA> getJdaOptional() {
        return Optional.ofNullable(jda);
    }

    public JDA requireJda() {
        if (jda == null) {
            throw new IllegalStateException("Discord Bot wurde noch nicht gestartet.");
        }
        return jda;
    }

    public List<Guild> getGuilds() {
        return jda == null ? List.of() : jda.getGuilds();
    }
}
