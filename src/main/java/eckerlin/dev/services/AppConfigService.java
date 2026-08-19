package eckerlin.dev.services;

import eckerlin.dev.utils.Config;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
public class AppConfigService {

    private final DatabaseSettingsService databaseSettingsService;

    public AppConfigService(DatabaseSettingsService databaseSettingsService) {
        this.databaseSettingsService = databaseSettingsService;
    }

    public JSONObject root() {
        return Config.config;
    }

    public JSONObject bot() {
        return root().optJSONObject("bot") == null ? new JSONObject() : root().optJSONObject("bot");
    }

    public JSONObject web() {
        return root().optJSONObject("webinterface") == null ? new JSONObject() : root().optJSONObject("webinterface");
    }

    public JSONObject lavalink() {
        return root().optJSONObject("lavalink") == null ? new JSONObject() : root().optJSONObject("lavalink");
    }

    public JSONObject llm() {
        return root().optJSONObject("llm") == null ? new JSONObject() : root().optJSONObject("llm");
    }

    public JSONObject musicBrain() {
        return root().optJSONObject("music_brain") == null ? new JSONObject() : root().optJSONObject("music_brain");
    }

    /**
     * Ob das Sprachmodell den Bot per Function-Calling steuern darf
     * ("@Bot spiel mal ..."). Laesst sich in der config.json unter
     * {@code llm.tools_enabled} abschalten.
     */
    public boolean isLlmToolsEnabled() {
        return llm().optBoolean("tools_enabled", true);
    }

    public JSONObject mcp() {
        return root().optJSONObject("mcp") == null ? new JSONObject() : root().optJSONObject("mcp");
    }

    /**
     * Der MCP-Endpunkt ist nur aktiv, wenn er ausdruecklich eingeschaltet und
     * mit einem Token versehen wurde. Ein offener Endpunkt wuerde jedem
     * Erreichbaren die Steuerung des Bots erlauben.
     */
    public boolean isMcpEnabled() {
        return mcp().optBoolean("enabled", false) && !getMcpToken().isBlank();
    }

    public String getMcpToken() {
        return mcp().optString("token", "").trim();
    }

    public JSONObject deployment() {
        return root().optJSONObject("deployment") == null ? new JSONObject() : root().optJSONObject("deployment");
    }

    public int getBotId() {
        return databaseSettingsService.getBotId();
    }

    public String getCurrentDeploymentKey() {
        String configured = deployment().optString("key", "").trim();
        return configured.isBlank() ? "local" : configured;
    }

    public String getCurrentDeploymentDisplayName() {
        Optional<DeploymentSettings> deployment = databaseSettingsService.loadDeployment(getCurrentDeploymentKey());
        if (deployment.isPresent() && !deployment.get().displayName().isBlank()) {
            return deployment.get().displayName();
        }

        String configured = deployment().optString("display_name", "").trim();
        return configured.isBlank() ? getCurrentDeploymentKey() : configured;
    }

    public String getConfiguredBotToken() {
        return firstNonBlank(loadSettings().map(StoredSettings::token).orElse(""), bot().optString("token", ""));
    }

    public String getBotActivity() {
        return getBotActivityRotation().stream()
                .findFirst()
                .orElseGet(() -> firstNonBlank(
                        loadSettings().map(StoredSettings::activity).orElse(""),
                        bot().optString("activity", "")
                ));
    }

    public String getBotStatus() {
        return firstNonBlank(loadSettings().map(StoredSettings::status).orElse(""), bot().optString("status", "IDLE"));
    }

    public List<String> getBotActivityRotation() {
        Set<String> activities = new LinkedHashSet<>();
        addEntries(activities, loadSettings().map(StoredSettings::activityRotation).orElse(""));
        addEntries(activities, bot().optString("activity_rotation", ""));

        if (activities.isEmpty()) {
            addEntry(activities, loadSettings().map(StoredSettings::activity).orElse(""));
            addEntry(activities, bot().optString("activity", ""));
        }

        return List.copyOf(activities);
    }

    public String getBrandImageUrl() {
        return firstNonBlank(
                loadSettings().map(StoredSettings::brandImageUrl).orElse(""),
                web().optString("brand_image_url", "")
        );
    }

    public String getHeroImageUrl() {
        return firstNonBlank(
                loadSettings().map(StoredSettings::heroImageUrl).orElse(""),
                web().optString("hero_image_url", "")
        );
    }

    public boolean isMaintenanceEnabled() {
        Boolean stored = loadSettings().map(StoredSettings::maintenanceEnabled).orElse(null);
        if (stored != null) {
            return stored;
        }
        return web().optBoolean("maintenance_enabled", false);
    }

    public String getMaintenanceMessage() {
        return firstNonBlank(
                loadSettings().map(StoredSettings::maintenanceMessage).orElse(""),
                web().optString("maintenance_message", ""),
                "Wartungsmodus aktiv. Das Dashboard ist fuer kurze Arbeiten voruebergehend gesperrt."
        );
    }

    public String getLegalOwnerName() {
        return firstNonBlank(
                loadSettings().map(StoredSettings::legalOwnerName).orElse(""),
                web().optString("legal_owner_name", "")
        );
    }

    public String getLegalEmail() {
        return firstNonBlank(
                loadSettings().map(StoredSettings::legalEmail).orElse(""),
                web().optString("legal_email", "")
        );
    }

    public String getLegalAddress() {
        return firstNonBlank(
                loadSettings().map(StoredSettings::legalAddress).orElse(""),
                web().optString("legal_address", "")
        );
    }

    public int getWebPort() {
        return databaseSettingsService.loadDeployment(getCurrentDeploymentKey())
                .map(DeploymentSettings::webPort)
                .filter(value -> value != null && value > 0)
                .orElse(web().optInt("port", 8080));
    }

    public String getWebBaseUrl() {
        String deploymentUrl = databaseSettingsService.loadDeployment(getCurrentDeploymentKey())
                .map(DeploymentSettings::baseUrl)
                .orElse("");
        String settingsUrl = loadSettings().map(StoredSettings::webBaseUrl).orElse("");
        String configuredUrl = firstNonBlank(deploymentUrl, settingsUrl, web().optString("base_url", ""));
        if (!configuredUrl.isBlank()) {
            return configuredUrl;
        }
        return "http://localhost:" + getWebPort();
    }

    /**
     * Adresse des Support-Servers - eine Einladung, kein Panel-Pfad.
     *
     * <p>Leer ist ein gueltiger Zustand und heisst "es gibt keinen": die
     * Oberflaeche laesst den Knopf dann weg, und der Befehl /support sagt es.
     * Ein Vorgabewert waere hier falsch - er zeigte auf einen Server, den es
     * vielleicht gar nicht gibt.</p>
     */
    public String getSupportUrl() {
        return firstNonBlank(
                loadSettings().map(StoredSettings::supportUrl).orElse(""),
                web().optString("support_url", "")
        ).trim();
    }

    public String getNoGuildInviteUrl() {
        String configured = firstNonBlank(
                loadSettings().map(StoredSettings::noGuildInviteUrl).orElse(""),
                web().optString("no_guild_invite_url", "")
        );
        if (!configured.isBlank()) {
            return configured;
        }

        String clientId = getDiscordClientId();
        if (clientId.isBlank()) {
            return "";
        }

        // Nicht Administrator (8) anfragen. Wer einen Musikbot einlaedt, soll
        // ihm nicht den ganzen Server ueberschreiben duerfen - das ist genau
        // der Satz Rechte, den der Bot tatsaechlich braucht.
        String scope = URLEncoder.encode("bot applications.commands", StandardCharsets.UTF_8);
        return "https://discord.com/oauth2/authorize?client_id=" + clientId.trim()
                + "&scope=" + scope
                + "&permissions=1101960178806";
    }

    public String getDiscordClientId() {
        return firstNonBlank(loadSettings().map(StoredSettings::discordClientId).orElse(""), web().optString("discord_client_id", ""));
    }

    public String getDiscordClientSecret() {
        return firstNonBlank(loadSettings().map(StoredSettings::discordClientSecret).orElse(""), web().optString("discord_client_secret", ""));
    }

    public String getDiscordRedirectUri() {
        String deploymentRedirect = databaseSettingsService.loadDeployment(getCurrentDeploymentKey())
                .map(DeploymentSettings::redirectUri)
                .orElse("");
        String settingsRedirect = loadSettings().map(StoredSettings::redirectUri).orElse("");
        String redirectUri = firstNonBlank(deploymentRedirect, settingsRedirect, web().optString("redirect_uri", ""));
        if (!redirectUri.isBlank()) {
            return redirectUri;
        }
        return getWebBaseUrl() + "/auth/discord/callback";
    }

    public boolean isDiscordOAuthConfigured() {
        return !getDiscordClientId().isBlank() && !getDiscordClientSecret().isBlank();
    }

    public Set<String> getAdminUserIds() {
        Set<String> ids = parseUserIds(loadSettings().map(StoredSettings::adminUserIds).orElse(""));
        if (!ids.isEmpty()) {
            return ids;
        }

        JSONArray configuredArray = web().optJSONArray("admin_user_ids");
        if (configuredArray != null) {
            Set<String> fromArray = new LinkedHashSet<>();
            configuredArray.forEach(value -> {
                String id = String.valueOf(value).trim();
                if (!id.isBlank()) {
                    fromArray.add(id);
                }
            });
            if (!fromArray.isEmpty()) {
                return fromArray;
            }
        }

        return parseUserIds(web().optString("admin_user_ids", ""));
    }

    public List<DeploymentSettings> getDeployments() {
        return databaseSettingsService.loadDeployments();
    }

    public List<LavalinkNodeSettings> getDeploymentNodes() {
        return databaseSettingsService.loadDeploymentNodes();
    }

    /**
     * Wie oft der Bot die Knotentabelle nachliest, in Sekunden.
     *
     * <p>0 schaltet die Wache ab. Werte unter 10 Sekunden werden angehoben -
     * haeufiger nachzusehen bringt nichts ausser Last auf der Datenbank, denn
     * ein neuer Knoten braucht ohnehin einige Sekunden, bis er antwortet.
     */
    /**
     * Duerfen Standard-Server auf Premium-Knoten ausweichen, wenn die
     * Standard-Knoten ausgelastet sind?
     *
     * <p>An heisst: kein Server bleibt stumm, solange irgendwo Kapazitaet
     * frei ist. Aus heisst: die Premium-Maschine bleibt unter allen Umstaenden
     * denen vorbehalten, die dafuer freigeschaltet sind.
     */
    public boolean isFreeOverflowAllowed() {
        return lavalink().optBoolean("free_overflow", true);
    }

    /**
     * Ab welcher Systemlast ein Knoten als ausgelastet gilt, 0..1.
     *
     * <p>Unterhalb von 0,5 waere jeder normal arbeitende Knoten "voll", oberhalb
     * von 0,99 nie einer - beides macht den Ueberlauf sinnlos.
     */
    public double getOverflowCpuThreshold() {
        double wert = lavalink().optDouble("overflow_cpu", 0.85d);
        return Math.min(0.99d, Math.max(0.5d, wert));
    }

    /**
     * Wie viele Plaetze auf einem Premium-Knoten fuer Premium-Server
     * freigehalten werden, wenn dort Standard-Server im Ueberlauf liegen.
     */
    public int getPremiumReserve() {
        return Math.max(0, lavalink().optInt("premium_reserve", 1));
    }

    public long getLavalinkWatchSeconds() {
        long wert = lavalink().optLong("watch_seconds", 30L);
        if (wert <= 0) {
            return 0;
        }
        return Math.max(10L, wert);
    }

    /**
     * Liefert den Lavalink-Node des aktuellen Deployments.
     *
     * <p>Der Bot arbeitet mit genau einem Node. Frueher wurde hier eine Liste
     * zurueckgegeben und in {@code AudioService} ueber alle Eintraege iteriert;
     * mehrere Nodes brachten ohne echtes Load-Balancing aber keinen Nutzen und
     * machten Fehlersuche unnoetig kompliziert.
     *
     * <p>Reihenfolge der Quellen: Datenbank vor {@code config.json}.
     */
    public LavalinkNodeSettings getLavalinkNode() {
        return databaseSettingsService.loadDeploymentNode(getCurrentDeploymentKey())
                .filter(node -> !node.serverUri().isBlank())
                .orElseGet(this::lavalinkNodeFromConfigFile);
    }

    private LavalinkNodeSettings lavalinkNodeFromConfigFile() {
        return new LavalinkNodeSettings(
                0L,
                getCurrentDeploymentKey(),
                firstNonBlank(lavalink().optString("name", ""), "main-node"),
                firstNonBlank(lavalink().optString("uri", "").trim(), "http://127.0.0.1:2333"),
                lavalink().optString("password", "youshallnotpass").trim(),
                Math.max(1000, lavalink().optInt("http_timeout_ms", 15000)),
                lavalink().optBoolean("resume_enabled", true),
                // 60 Sekunden reichten nicht: ein Knoten-Neustart samt Plugin-Laden
                // dauert laenger, und danach waren die Player weg statt wieder da.
                Math.max(10L, lavalink().optLong("resume_timeout_seconds", 180L)),
                true,
                "free",
                0
        );
    }

    public String getLlmProvider() {
        return firstNonBlank(loadSettings().map(StoredSettings::llmProvider).orElse(""), llm().optString("provider", "ollama"))
                .trim()
                .toLowerCase();
    }

    public String getLlmOllamaUrl() {
        return normalizeOllamaBaseUrl(
                firstNonBlank(loadSettings().map(StoredSettings::llmOllamaUrl).orElse(""), llm().optString("ollama_url", "http://127.0.0.1:11434"))
        );
    }

    public String getLlmOpenAiBaseUrl() {
        return normalizeOpenAiBaseUrl(
                firstNonBlank(loadSettings().map(StoredSettings::llmOpenAiBaseUrl).orElse(""), llm().optString("openai_base_url", "http://127.0.0.1:1234"))
        );
    }

    public String getLlmApiKey() {
        return firstNonBlank(loadSettings().map(StoredSettings::llmApiKey).orElse(""), llm().optString("api_key", ""));
    }

    public String getLlmModel() {
        return firstNonBlank(loadSettings().map(StoredSettings::llmModel).orElse(""), llm().optString("model", "phi-3.5-mini-instruct"));
    }

    public List<String> getAvailableLlmModels() {
        Set<String> models = new LinkedHashSet<>();
        addModels(models, loadSettings().map(StoredSettings::llmAvailableModels).orElse(""));

        if (models.isEmpty()) {
            JSONArray configuredArray = llm().optJSONArray("available_models");
            if (configuredArray != null) {
                configuredArray.forEach(value -> addModel(models, String.valueOf(value)));
            }
            addModels(models, llm().optString("available_models", ""));
        }

        addModel(models, getLlmModel());
        return List.copyOf(models);
    }

    public boolean isAllowedLlmModel(String model) {
        String trimmed = model == null ? "" : model.trim();
        return !trimmed.isBlank() && getAvailableLlmModels().stream().anyMatch(trimmed::equals);
    }

    public String resolveAllowedLlmModel(String model) {
        String trimmed = model == null ? "" : model.trim();
        if (!trimmed.isBlank() && isAllowedLlmModel(trimmed)) {
            return trimmed;
        }
        return getLlmModel();
    }

    public int getLlmTimeoutMs() {
        Integer stored = loadSettings().map(StoredSettings::llmTimeoutMs).orElse(null);
        return Math.max(1000, stored != null ? stored : llm().optInt("timeout_ms", 30000));
    }

    public double getLlmTemperature() {
        Double stored = loadSettings().map(StoredSettings::llmTemperature).orElse(null);
        return stored != null ? stored : llm().optDouble("temperature", 0.7d);
    }

    public int getLlmMaxTokens() {
        Integer stored = loadSettings().map(StoredSettings::llmMaxTokens).orElse(null);
        return Math.max(32, stored != null ? stored : llm().optInt("max_tokens", 220));
    }

    public int getLlmHistoryTurns() {
        Integer stored = loadSettings().map(StoredSettings::llmHistoryTurns).orElse(null);
        return Math.max(0, stored != null ? stored : llm().optInt("history_turns", 6));
    }

    public String getLlmSystemMessage() {
        return firstNonBlank(
                loadSettings().map(StoredSettings::llmSystemMessage).orElse(""),
                llm().optString(
                        "system_message",
                        "Du bist ein hilfreicher Discord-Assistent. Antworte kurz, freundlich und auf Deutsch."
                )
        ).trim();
    }

    public boolean isLlmConfigured() {
        return !getLlmModel().isBlank() && switch (getLlmProvider()) {
            case "openai", "openai-compatible" -> !getLlmOpenAiBaseUrl().isBlank();
            default -> !getLlmOllamaUrl().isBlank();
        };
    }

    public String getMusicBrainBaseUrl() {
        return firstNonBlank(musicBrain().optString("base_url", ""), "http://127.0.0.1:8091");
    }

    public int getMusicBrainRequestTimeoutMs() {
        return Math.max(1000, musicBrain().optInt("request_timeout_ms", 15000));
    }

    public int getMusicBrainBatchSize() {
        return Math.max(4, musicBrain().optInt("batch_size", 12));
    }

    public Optional<StoredSettings> loadStoredSettings() {
        return loadSettings();
    }

    private Optional<StoredSettings> loadSettings() {
        return databaseSettingsService.loadSettings();
    }

    private String firstNonBlank(String... values) {
        return Arrays.stream(values)
                .filter(value -> value != null && !value.trim().isBlank())
                .map(String::trim)
                .findFirst()
                .orElse("");
    }

    public static String normalizeOllamaBaseUrl(String value) {
        return normalizeEndpointBaseUrl(value, List.of(
                "/api/chat",
                "/api/generate",
                "/v1/chat/completions",
                "/chat/completions",
                "/v1"
        ));
    }

    public static String normalizeOpenAiBaseUrl(String value) {
        return normalizeEndpointBaseUrl(value, List.of(
                "/v1/chat/completions",
                "/chat/completions",
                "/v1"
        ));
    }

    private static String normalizeEndpointBaseUrl(String value, List<String> suffixes) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            return "";
        }

        normalized = normalized.replaceAll("/+$", "");
        String lowerCase = normalized.toLowerCase(Locale.ROOT);
        boolean changed;
        do {
            changed = false;
            for (String suffix : suffixes) {
                if (!lowerCase.endsWith(suffix)) {
                    continue;
                }

                normalized = normalized.substring(0, normalized.length() - suffix.length()).replaceAll("/+$", "");
                lowerCase = normalized.toLowerCase(Locale.ROOT);
                changed = true;
                break;
            }
        } while (changed && !normalized.isBlank());

        return normalized;
    }

    private Set<String> parseUserIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }

        Set<String> ids = new LinkedHashSet<>();
        Arrays.stream(raw.split("[,;\\r\\n\\s]+"))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .forEach(ids::add);
        return ids;
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

    private void addEntries(Set<String> values, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }

        Arrays.stream(raw.split("[\\r\\n]+"))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .forEach(value -> addEntry(values, value));
    }

    private void addEntry(Set<String> values, String value) {
        if (values == null || value == null) {
            return;
        }

        String trimmed = value.trim();
        if (!trimmed.isBlank()) {
            values.add(trimmed);
        }
    }
}
