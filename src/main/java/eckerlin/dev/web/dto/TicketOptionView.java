package eckerlin.dev.web.dto;

import java.util.List;

public record TicketOptionView(
        String id,
        String label,
        String description,
        String emoji,
        String channelNameTemplate,
        List<String> supportRoleIds
) {
}
