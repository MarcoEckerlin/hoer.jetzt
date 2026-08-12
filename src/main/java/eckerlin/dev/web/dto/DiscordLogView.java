package eckerlin.dev.web.dto;

public record DiscordLogView(
        boolean enabled,
        String textChannelId,
        boolean memberJoin,
        boolean memberLeave,
        boolean voiceJoin,
        boolean voiceLeave,
        boolean music,
        boolean moderation,
        boolean roleUpdates,
        boolean nicknameUpdates,
        boolean timeouts,
        boolean kicks,
        boolean bans,
        boolean messageDeletes,
        boolean voiceModeration,
        boolean commands,
        String notice
) {
}
