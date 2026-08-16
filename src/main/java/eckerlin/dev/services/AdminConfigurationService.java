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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

        // Beliebig viele Knoten, eindeutig ueber den Namen. Das Passwort wird
        // bewusst nicht mit ausgeliefert: die Oberflaeche zeigt ein leeres Feld
        // und ein leeres Feld bedeutet "unveraendert".
        List<AdminLavalinkNodeView> lavalinkNodes = configService.getDeploymentNodes().stream()
                .map(node -> new AdminLavalinkNodeView(
                        node.id(),
                        node.deploymentKey(),
                        node.nodeName(),
                        node.serverUri(),
                        "",
                        node.httpTimeoutMs(),
                        node.resumeEnabled(),
                        node.resumeTimeoutSeconds(),
                        node.enabled(),
                        node.tier(),
                        node.maxPlayers()
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

    /**
     * Speichert, was geschickt wurde - und nur das.
     *
     * <p>Die Oberflaeche sendet ausschliesslich geaenderte Felder. Wuerde ein
     * fehlendes Feld als "leer" gelten, loeschte jedes Speichern alles, was in
     * diesem Moment nicht angefasst wurde: ein bearbeiteter Audio-Knoten haette
     * die Deployments mitgenommen, ein bearbeitetes Deployment die Knoten, und
     * beide nebenbei den Bot-Token. {@code null} heisst deshalb "unveraendert",
     * nicht "leer".
     */
    public void save(AdminSettingsRequest request) throws SQLException {
        StoredSettings alt = databaseSettingsService.loadSettings().orElse(null);

        NormalizedModelSettings modelSettings = normalizeModels(request);
        String normalizedActivityRotation = request.activityRotation() == null && request.activity() == null
                ? (alt == null ? "" : alt.activityRotation())
                : normalizeActivityRotation(request.activityRotation(), request.activity());
        StoredSettings settings = new StoredSettings(
                configService.getBotId(),
                uebernehmen(request.token(), alt == null ? null : alt.token()),
                firstActivity(normalizedActivityRotation),
                normalizedActivityRotation,
                uebernehmen(request.status(), alt == null ? null : alt.status()),
                uebernehmen(request.brandImageUrl(), alt == null ? null : alt.brandImageUrl()),
                uebernehmen(request.heroImageUrl(), alt == null ? null : alt.heroImageUrl()),
                request.maintenanceEnabled() != null
                        ? request.maintenanceEnabled()
                        : (alt == null ? Boolean.FALSE : alt.maintenanceEnabled()),
                uebernehmen(request.maintenanceMessage(), alt == null ? null : alt.maintenanceMessage()),
                uebernehmen(request.legalOwnerName(), alt == null ? null : alt.legalOwnerName()),
                uebernehmen(request.legalEmail(), alt == null ? null : alt.legalEmail()),
                uebernehmen(request.legalAddress(), alt == null ? null : alt.legalAddress()),
                uebernehmen(request.webBaseUrl(), alt == null ? null : alt.webBaseUrl()),
                uebernehmen(request.noGuildInviteUrl(), alt == null ? null : alt.noGuildInviteUrl()),
                uebernehmen(request.discordClientId(), alt == null ? null : alt.discordClientId()),
                uebernehmen(request.discordClientSecret(), alt == null ? null : alt.discordClientSecret()),
                uebernehmen(request.redirectUri(), alt == null ? null : alt.redirectUri()),
                resolveAdminUserIds(request),
                uebernehmen(request.llmProvider(), alt == null ? null : alt.llmProvider()),
                request.llmOllamaUrl() == null
                        ? (alt == null ? "" : alt.llmOllamaUrl())
                        : AppConfigService.normalizeOllamaBaseUrl(blank(request.llmOllamaUrl())),
                request.llmOpenAiBaseUrl() == null
                        ? (alt == null ? "" : alt.llmOpenAiBaseUrl())
                        : AppConfigService.normalizeOpenAiBaseUrl(blank(request.llmOpenAiBaseUrl())),
                uebernehmen(request.llmApiKey(), alt == null ? null : alt.llmApiKey()),
                modelSettings.defaultModel(),
                modelSettings.availableModels(),
                request.llmTimeoutMs() != null ? request.llmTimeoutMs() : (alt == null ? null : alt.llmTimeoutMs()),
                request.llmTemperature() != null ? request.llmTemperature() : (alt == null ? null : alt.llmTemperature()),
                request.llmMaxTokens() != null ? request.llmMaxTokens() : (alt == null ? null : alt.llmMaxTokens()),
                request.llmHistoryTurns() != null ? request.llmHistoryTurns() : (alt == null ? null : alt.llmHistoryTurns()),
                uebernehmen(request.llmSystemMessage(), alt == null ? null : alt.llmSystemMessage())
        );

        // null heisst "nicht geschickt" und damit "unveraendert". Eine leere
        // Liste heisst dagegen sehr wohl "alles loeschen" - das ist der Weg,
        // um den letzten Eintrag wieder loszuwerden.
        List<DeploymentSettings> deployments = null;
        if (request.deployments() != null) {
            for (AdminDeploymentRequest eintrag : request.deployments()) {
                if (eintrag == null || eintrag.deploymentKey() == null || eintrag.deploymentKey().isBlank()) {
                    throw new IllegalArgumentException(
                            "Jedes Deployment braucht einen Schluessel. Bitte ausfuellen oder die Zeile entfernen.");
                }
            }
            deployments = request.deployments().stream().map(this::mapDeployment).toList();
        }

        // Mehrere Knoten sind erwuenscht - doppelt vergebene Namen nicht. Unter
        // dem Namen laeuft die Lavalink-Session; zwei gleiche waeren dort nicht
        // zu unterscheiden.
        // Fehlende Angaben werden gemeldet statt verworfen: ein Eintrag, der
        // nach dem Speichern spurlos verschwindet, ist die aergerlichste Art,
        // einen Tippfehler mitzuteilen.
        List<LavalinkNodeSettings> nodes = null;
        if (request.lavalinkNodes() != null) {
            Set<String> namen = new LinkedHashSet<>();
            for (AdminLavalinkNodeRequest eintrag : request.lavalinkNodes()) {
                if (eintrag == null) {
                    continue;
                }
                String name = eintrag.nodeName() == null ? "" : eintrag.nodeName().trim();
                if (name.isBlank()) {
                    throw new IllegalArgumentException(
                            "Jeder Audio-Knoten braucht einen Namen - unter ihm meldet er sich an.");
                }
                if (eintrag.serverUri() == null || eintrag.serverUri().isBlank()) {
                    throw new IllegalArgumentException(
                            "Der Knoten \"" + name + "\" hat keine Adresse, z. B. http://127.0.0.1:2333.");
                }
                // Der Schluessel entscheidet seit den Knoten-Pools nichts mehr,
                // er ist nur noch Beschriftung. Eine Pflichtangabe waere reine
                // Schikane - fehlt er, gilt der laufende.
                if (!namen.add(name)) {
                    throw new IllegalArgumentException(
                            "Der Name \"" + name + "\" ist zweimal vergeben. Namen muessen eindeutig sein.");
                }
            }
            nodes = request.lavalinkNodes().stream()
                    .filter(java.util.Objects::nonNull)
                    .map(this::mapNode)
                    .toList();
        }

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
        String schluessel = request.deploymentKey() == null || request.deploymentKey().isBlank()
                ? configService.getCurrentDeploymentKey()
                : request.deploymentKey().trim();

        return new LavalinkNodeSettings(
                0L,
                schluessel,
                blank(request.nodeName()),
                blank(request.serverUri()),
                // null bleibt null: die Datenbankschicht liest daraufhin das
                // gespeicherte Passwort weiter. blank() wuerde daraus "" machen
                // und das Passwort loeschen.
                request.password() == null ? null : request.password().trim(),
                request.httpTimeoutMs() == null ? 10000 : request.httpTimeoutMs(),
                request.resumeEnabled() == null || request.resumeEnabled(),
                request.resumeTimeoutSeconds() == null ? 60L : request.resumeTimeoutSeconds(),
                request.enabled() == null || request.enabled(),
                "premium".equalsIgnoreCase(blank(request.tier())) ? "premium" : "free",
                request.maxPlayers() == null ? 0 : Math.max(0, request.maxPlayers())
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

    /** null = unveraendert, alles andere = neuer Wert. */
    private String uebernehmen(String neu, String gespeichert) {
        return neu == null ? (gespeichert == null ? "" : gespeichert) : neu.trim();
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
