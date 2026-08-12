package eckerlin.dev.web.dto;

import java.util.List;

public record VerifyModuleView(
        boolean enabled,
        String publishChannelId,
        List<String> verifiedRoleIds,
        String title,
        String description,
        String imageUrl,
        String thumbnailUrl,
        String accentColor,
        String messageId,
        String notice
) {
}
