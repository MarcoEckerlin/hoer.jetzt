package eckerlin.dev.web.dto;

public record LlmSettingsRequest(
        Boolean enabled,
        String textChannelId,
        String model
) {
}
