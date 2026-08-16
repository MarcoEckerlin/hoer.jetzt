package eckerlin.dev.web.dto;

import java.util.List;

public record GuildConfigurationView(
        GuildEntitlementFlags entitlements,
        JoinToCreateView joinToCreate,
        InviteTrackerView inviteTracker,
        WelcomeModuleView welcome,
        ReactionRoleModuleView reactionRoles,
        VerifyModuleView verify,
        LlmModuleView llm,
        TicketModuleView tickets,
        DiscordLogView discordLogs,
        List<DashboardCommandView> commands,
        List<EmbedVorlageDto> embedVorlagen,
        List<CategoryChannelView> categories,
        List<TextChannelView> textChannels,
        List<RoleView> roles
) {
}
