package eckerlin.dev.web.dto;

import java.util.List;

public record WelcomeSettingsRequest(
        Boolean enabled,
        List<String> roleIds,
        String channelId,
        String welcomeText,
        Boolean sendImage,
        String backgroundImageUrl,
        String accentColor
) {
}
