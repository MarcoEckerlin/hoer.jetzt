package eckerlin.dev.web.dto;

public record DiscordLogSettingsRequest(
        Boolean enabled,
        String textChannelId,
        Boolean memberJoin,
        Boolean memberLeave,
        Boolean voiceJoin,
        Boolean voiceLeave,
        Boolean music,
        Boolean moderation,
        Boolean roleUpdates,
        Boolean nicknameUpdates,
        Boolean timeouts,
        Boolean kicks,
        Boolean bans,
        Boolean messageDeletes,
        Boolean voiceModeration,
        Boolean commands
) {
}
