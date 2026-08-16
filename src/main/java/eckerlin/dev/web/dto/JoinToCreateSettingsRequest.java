package eckerlin.dev.web.dto;

public record JoinToCreateSettingsRequest(
        Boolean enabled,
        Integer cleanupDelaySeconds,
        Integer audioIdleTimeoutSeconds,
        java.util.List<JoinToCreateEntryRequest> entries
) {
}
