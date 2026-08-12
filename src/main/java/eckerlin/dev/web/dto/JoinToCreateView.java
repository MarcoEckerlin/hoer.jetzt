package eckerlin.dev.web.dto;

public record JoinToCreateView(
        boolean enabled,
        int managedChannelCount,
        int cleanupDelaySeconds,
        int audioIdleTimeoutSeconds,
        int maxBitrateKbps,
        java.util.List<JoinToCreateEntryView> entries
) {
}
