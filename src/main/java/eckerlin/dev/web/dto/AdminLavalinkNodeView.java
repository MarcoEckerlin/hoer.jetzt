package eckerlin.dev.web.dto;

public record AdminLavalinkNodeView(
        long id,
        String deploymentKey,
        String nodeName,
        String serverUri,
        String password,
        int httpTimeoutMs,
        boolean resumeEnabled,
        long resumeTimeoutSeconds,
        boolean enabled
) {
}
