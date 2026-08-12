package eckerlin.dev.web.dto;

import java.util.List;

public record TicketModuleView(
        boolean enabled,
        String transcriptChannelId,
        String notice,
        int activeTicketCount,
        List<TicketPanelView> panels,
        List<TicketTranscriptView> transcripts
) {
}
