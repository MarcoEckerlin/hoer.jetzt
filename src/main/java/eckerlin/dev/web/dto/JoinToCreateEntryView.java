package eckerlin.dev.web.dto;

public record JoinToCreateEntryView(
        String id,
        String sourceChannelId,
        String categoryId,
        String nameTemplate,
        int userLimit,
        int bitrateKbps,
        int nextCounter,
        boolean sendConfigPrompt
) {
}
