package eckerlin.dev.web.dto;

import java.util.List;

public record WelcomeModuleView(
        boolean enabled,
        List<String> roleIds,
        String channelId,
        String welcomeText,
        boolean sendImage,
        String backgroundImageUrl,
        String accentColor,
        String notice
) {
}
