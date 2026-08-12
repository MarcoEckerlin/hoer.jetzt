package eckerlin.dev.web.dto;

import java.util.List;

public record TicketSettingsRequest(
        Boolean enabled,
        String transcriptChannelId,
        List<TicketPanelRequest> panels
) {
}
