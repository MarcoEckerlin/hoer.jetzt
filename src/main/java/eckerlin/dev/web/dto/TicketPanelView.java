package eckerlin.dev.web.dto;

import java.util.List;

public record TicketPanelView(
        String id,
        String title,
        String description,
        String interactionMode,
        String publishChannelId,
        String categoryId,
        String placeholder,
        String welcomeMessage,
        String imageUrl,
        String thumbnailUrl,
        String accentColor,
        String notifyRoleId,
        List<String> supportRoleIds,
        boolean allowClaim,
        boolean allowPause,
        boolean allowCreatorClose,
        boolean oneTicketPerUser,
        String messageId,
        List<TicketOptionView> options
) {
}
