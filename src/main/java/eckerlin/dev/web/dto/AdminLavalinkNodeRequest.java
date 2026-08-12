package eckerlin.dev.web.dto;

public record AdminLavalinkNodeRequest(
        String deploymentKey,
        String nodeName,
        String serverUri,
        String password,
        Integer httpTimeoutMs,
        Boolean resumeEnabled,
        Long resumeTimeoutSeconds,
        Boolean enabled
) {
}
