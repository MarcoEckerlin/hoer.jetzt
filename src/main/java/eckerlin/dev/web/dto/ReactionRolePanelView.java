package eckerlin.dev.web.dto;

import java.util.List;

public record ReactionRolePanelView(
        String id,
        String publishChannelId,
        String title,
        String description,
        String imageUrl,
        String thumbnailUrl,
        String accentColor,
        String messageId,
        List<ReactionRoleEntryView> entries
) {
}
