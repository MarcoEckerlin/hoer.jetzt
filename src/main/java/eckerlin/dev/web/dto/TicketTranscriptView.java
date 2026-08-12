package eckerlin.dev.web.dto;

public record TicketTranscriptView(
        long id,
        String openerDisplay,
        String ticketSubject,
        String createdAt
) {
}
