package eckerlin.dev.web.dto;

public record DashboardGuildView(
        String id,
        String name,
        String iconUrl,
        int memberCount,
        boolean userInVoiceChannel,
        String userVoiceChannelName
) {
}
