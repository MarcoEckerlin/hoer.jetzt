package eckerlin.dev.web.dto;

import java.util.List;

public record ReactionRolePanelRequest(
        String id,
        String publishChannelId,
        String title,
        String description,
        String imageUrl,
        String thumbnailUrl,
        String accentColor,
        List<ReactionRoleEntryRequest> entries
) {
}
