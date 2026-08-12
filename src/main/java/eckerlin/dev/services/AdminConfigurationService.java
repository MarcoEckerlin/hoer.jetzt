package eckerlin.dev.services;

import eckerlin.dev.web.dto.AdminConfigurationView;
import eckerlin.dev.web.dto.AdminDeploymentRequest;
import eckerlin.dev.web.dto.AdminDeploymentView;
import eckerlin.dev.web.dto.AdminLavalinkNodeRequest;
import eckerlin.dev.web.dto.AdminLavalinkNodeView;
import eckerlin.dev.web.dto.AdminSettingsRequest;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AdminConfigurationService {

    private final AppConfigService configService;
    private final DatabaseSettingsService databaseSettingsService;
    private final DiscordApplicationOwnerService discordApplicationOwnerService;

    public AdminConfigurationService(
            AppConfigService configService,
            DatabaseSettingsService databaseSettingsService,
            DiscordApplicationOwnerService discordApplicationOwnerService
    ) {
        this.configService = configService;
        this.databaseSettingsService = databaseSettingsService;
        this.discordApplicationOwnerService = discordApplicationOwnerService;
    }

    public AdminConfigurationView buildView() {
        DiscordApplicationOwner applicationOwner = discordApplicationOwnerService.getApplicationOwner().orElse(null);
        List<AdminDeploymentView> deployments = configService.getDeployments().stream()
                .map(deployment -> new AdminDeploymentView(
                        deployment.deploymentKey(),
                        deployment.displayName(),
                        deployment.webPort(),
                        deployment.baseUrl(),
                        deployment.redirectUri(),
                        deployment.enabled(),
                        deployment.sortOrder()
                ))
                .toList();

        // Genau ein Node je Deployment - die Datenbankschicht filtert
        // Altbestaende mit mehreren Eintraegen bereits heraus.
        List<AdminLavalinkNodeView> lavalinkNodes = configService.getDeploymentNodes().stream()
                .map(node -> new AdminLavalinkNodeView(
                        node.id(),
                        node.deploymentKey(),
                        node.nodeName(),
                        node.serverUri(),
                        node.password(),
                        node.httpTimeoutMs(),
                        node.resumeEnabled(),
                        node.resumeTimeoutSeconds(),
                        node.enabled()
                ))
                .toList();

        return new AdminConfigurationView(
                configService.getBotId(),
                configService.getCurrentDeploymentKey(),
                configService.getCurrentDeploymentDisplayName(),
                applicationOwner == null ? "" : applicationOwner.applicationId(),
                applicationOwner == null ? "" : applicationOwner.ownerId(),
                applicationOwner == null ? "" : applicationOwner.ownerName(),
                configService.getConfiguredBotToken(),
                configService.getBotActivity(),
                String.join("\n", configService.getBotActivityRotation()),
                configService.getBotStatus(),
                configService.getBrandImageUrl(),
                configService.getHeroImageUrl(),
                configService.isMaintenanceEnabled(),
                configService.getMaintenanceMessage(),
                configService.getLegalOwnerName(),
                configService.getLegalEmail(),
                configService.getLegalAddress(),
                configService.getWebBaseUrl(),
                configService.getNoGuildInviteUrl(),
                configService.getDiscordClientId(),
                configService.getDiscordClientSecret(),
                configService.getDiscordRedirectUri(),
                configService.getLlmProvider(),
                configService.getLlmOllamaUrl(),
                configService.getLlmOpenAiBaseUrl(),
                configService.getLlmApiKey(),
                configService.getLlmModel(),
                configService.getAvailableLlmModels(),
                configService.getLlmTimeoutMs(),
                configService.getLlmTemperature(),
                configService.getLlmMaxTokens(),
                configService.getLlmHistoryTurns(),
                configService.getLlmSystemMessage(),
                deployments,
                lavalinkNodes
        );
    }

    public void save(AdminSettingsRequest request) throws SQLException {
        NormalizedModelSettings modelSettings = normalizeModels(request);
        String normalizedActivityRotation = normalizeActivityRotation(request.activityRotation(), request.activity());
        StoredSettings settings = new StoredSettings(
                configService.getBotId(),
                blank(request.token()),
                firstActivity(normalizedActivityRotation),
                normalizedActivityRotation,
                blank(request.status()),
                blank(request.brandImageUrl()),
                blank(request.heroImageUrl()),
                request.maintenanceEnabled(),
                blank(request.maintenanceMessage()),
                blank(request.legalOwnerName()),
                blank(request.legalEmail()),
                blank(request.legalAddress()),
                blank(request.webBaseUrl()),
                blank(request.noGuildInviteUrl()),
                blank(request.discordClientId()),
                blank(request.discordClientSecret()),
                blank(request.redirectUri()),
                resolveAdminUserIds(request),
                blank(request.llmProvider()),
                AppConfigService.normalizeOllamaBaseUrl(blank(request.llmOllamaUrl())),
                AppConfigService.normalizeOpenAiBaseUrl(blank(request.llmOpenAiBaseUrl())),
                blank(request.llmApiKey()),
                modelSettings.defaultModel(),
                modelSettings.availableModels(),
                request.llmTimeoutMs(),
                request.llmTemperature(),
                request.llmMaxTokens(),
                request.llmHistoryTurns(),
                blank(request.llmSystemMessage())
        );

        List<DeploymentSettings> deployments = request.deployments() == null
                ? List.of()
                : request.deployments().stream()
                .filter(item -> item != null && item.deploymentKey() != null && !item.deploymentKey().isBlank())
                .map(this::mapDeployment)
                .toList();

        // Doppelte Deployment-Keys werden verworfen: pro Deployment gibt es
        // genau einen Lavalink-Node.
        List<LavalinkNodeSettings> nodes = request.lavalinkNodes() == null
                ? List.of()
                : request.lavalinkNodes().stream()
                .filter(item -> item != null && item.deploymentKey() != null && !item.deploymentKey().isBlank())
                .collect(Collectors.toMap(
                        item -> item.deploymentKey().trim(),
                        this::mapNode,
                        (first, duplicate) -> first,
                        LinkedHashMap::new
                ))
                .values()
                .stream()
                .toList();

        databaseSettingsService.saveAll(settings, deployments, nodes);
        discordApplicationOwnerService.evictCache();
    }

    private String normalizeActivityRotation(String activityRotation, String legacyActivity) {
        Set<String> entries = new LinkedHashSet<>();
        addActivityEntries(entries, activityRotation);

        if (entries.isEmpty()) {
            addActivityEntries(entries, legacyActivity);
        }

        return String.join("\n", entries);
    }

    private String firstActivity(String normalizedActivityRotation) {
        return Arrays.stream((normalizedActivityRotation == null ? "" : normalizedActivityRotation).split("\\r?\\n"))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse("");
    }

    private void addActivityEntries(Set<String> target, String raw) {
        if (target == null || raw == null || raw.isBlank()) {
            return;
        }

        Arrays.stream(raw.split("[\\r\\n]+"))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .forEach(target::add);
    }

    private DeploymentSettings mapDeployment(AdminDeploymentRequest request) {
        return new DeploymentSettings(
                request.deploymentKey().trim(),
                blank(request.displayName()),
                request.webPort(),
                blank(request.baseUrl()),
                blank(request.redirectUri()),
                request.enabled() == null || request.enabled(),
                request.sortOrder() == null ? 0 : request.sortOrder()
        );
    }

    private LavalinkNodeSettings mapNode(AdminLavalinkNodeRequest request) {
        return new LavalinkNodeSettings(
                0L,
                request.deploymentKey().trim(),
                blank(request.nodeName()),
                blank(request.serverUri()),
                blank(request.password()),
                request.httpTimeoutMs() == null ? 10000 : request.httpTimeoutMs(),
                request.resumeEnabled() == null || request.resumeEnabled(),
                request.resumeTimeoutSeconds() == null ? 60L : request.resumeTimeoutSeconds(),
                request.enabled() == null || request.enabled()
        );
    }

    private NormalizedModelSettings normalizeModels(AdminSettingsRequest request) {
        Set<String> models = new LinkedHashSet<>();
        addModels(models, request.llmAvailableModels());

        String defaultModel = blank(request.llmModel());
        if (defaultModel.isBlank()) {
            defaultModel = models.stream().findFirst().orElse(blank(configService.getLlmModel()));
        }

        Set<String> ordered = new LinkedHashSet<>();
        addModel(ordered, defaultModel);
        ordered.addAll(models);

        return new NormalizedModelSettings(
                defaultModel,
                String.join("\n", ordered)
        );
    }

    private String blank(String value) {
        return value == null ? "" : value.trim();
    }

    private String resolveAdminUserIds(AdminSettingsRequest request) {
        if (request.adminUserIds() != null) {
            return blank(request.adminUserIds());
        }

        return configService.loadStoredSettings()
                .map(StoredSettings::adminUserIds)
                .map(this::blank)
                .orElse(String.join("\n", configService.getAdminUserIds()));
    }

    private void addModels(Set<String> models, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }

        Arrays.stream(raw.split("[,;\\r\\n]+"))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .forEach(value -> addModel(models, value));
    }

    private void addModel(Set<String> models, String model) {
        if (models == null || model == null) {
            return;
        }

        String trimmed = model.trim();
        if (!trimmed.isBlank()) {
            models.add(trimmed);
        }
    }

    private record NormalizedModelSettings(String defaultModel, String availableModels) {
    }
}
