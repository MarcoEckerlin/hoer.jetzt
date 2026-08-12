package eckerlin.dev.web.dto;

public record AdminDeploymentView(
        String deploymentKey,
        String displayName,
        Integer webPort,
        String baseUrl,
        String redirectUri,
        boolean enabled,
        int sortOrder
) {
}
