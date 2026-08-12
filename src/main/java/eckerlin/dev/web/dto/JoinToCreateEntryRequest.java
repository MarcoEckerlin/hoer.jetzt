package eckerlin.dev.web.dto;

public record JoinToCreateEntryRequest(
        String id,
        String sourceChannelId,
        String categoryId,
        String nameTemplate,
        Integer userLimit,
        Integer bitrateKbps,
        Boolean sendConfigPrompt
) {
}
