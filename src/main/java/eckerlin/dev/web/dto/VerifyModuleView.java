package eckerlin.dev.web.dto;

import java.util.List;

public record VerifyModuleView(
        boolean enabled,
        String publishChannelId,
        List<String> verifiedRoleIds,
        List<String> removedRoleIds,
        String title,
        String description,
        String imageUrl,
        String thumbnailUrl,
        String accentColor,
        EmbedVorlageDto embed,
        String embedVorlageId,
        String messageId,
        String notice
) {
}
