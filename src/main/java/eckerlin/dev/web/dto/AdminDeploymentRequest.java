package eckerlin.dev.web.dto;

public record AdminDeploymentRequest(
        String deploymentKey,
        String displayName,
        Integer webPort,
        String baseUrl,
        String redirectUri,
        Boolean enabled,
        Integer sortOrder
) {
}
