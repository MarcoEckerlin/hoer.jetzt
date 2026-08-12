package eckerlin.dev.services;

public record DeploymentSettings(
        String deploymentKey,
        String displayName,
        Integer webPort,
        String baseUrl,
        String redirectUri,
        boolean enabled,
        int sortOrder
) {
}
