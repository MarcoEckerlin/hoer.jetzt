package eckerlin.dev.web.dto;

import java.util.List;

public record TicketPanelRequest(
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
        Boolean allowClaim,
        Boolean allowPause,
        Boolean allowCreatorClose,
        Boolean oneTicketPerUser,
        List<TicketOptionRequest> options
) {
}
