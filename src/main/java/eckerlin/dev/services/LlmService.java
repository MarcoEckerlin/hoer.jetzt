package eckerlin.dev.services;

import eckerlin.dev.security.GuildEntitlementService;
import eckerlin.dev.security.GuildFeature;
import eckerlin.dev.security.GuildPermission;
import eckerlin.dev.security.GuildPermissionService;
import eckerlin.dev.services.tools.AudioToolService;
import eckerlin.dev.services.tools.BotTool;
import eckerlin.dev.services.tools.ToolContext;
import eckerlin.dev.services.tools.ToolResult;
import eckerlin.dev.utils.Alert;
import jakarta.annotation.PreDestroy;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class LlmService {

    /**
     * Discord bricht Nachrichten ueber 2000 Zeichen mit einem HTTP-400 ab. Bisher
     * wurde die Antwort nur auf {@code maxReplyChars} gekuerzt - stand dort ein
     * hoeherer Wert, ging die Antwort komplett verloren.
     */
    private static final int DISCORD_MESSAGE_LIMIT = 2000;
    private static final int MAX_MESSAGE_CHUNKS = 3;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_ATTEMPTS = 2;

    private final AppConfigService configService;
    private final GuildModuleSettingsService settingsService;
    private final AudioToolService audioToolService;
    private final HttpClient httpClient;
    private final ExecutorService llmExecutor;
    /**
     * Modelle ohne Function-Calling lehnen Anfragen mit {@code tools} ab.
     * Faellt ein Modell einmal so auf, wird es hier vermerkt und kuenftig
     * ohne Werkzeuge angesprochen, statt bei jeder Nachricht erneut in den
     * Fehler zu laufen.
     */
    private final Set<String> modelsWithoutToolSupport = ConcurrentHashMap.newKeySet();
    private final Map<Long, Deque<HistoryEntry>> historyByChannel = new ConcurrentHashMap<>();
    private final Map<Long, AtomicInteger> pendingByChannel = new ConcurrentHashMap<>();
    private final GuildEntitlementService entitlementService;
    private final GuildPermissionService permissionService;
    private volatile List<String> cachedOllamaModels = List.of();
    private volatile long cachedOllamaModelsUntil = 0L;

    public LlmService(
            AppConfigService configService,
            GuildModuleSettingsService settingsService,
            AudioToolService audioToolService,
            GuildEntitlementService entitlementService,
            GuildPermissionService permissionService
    ) {
        this.configService = configService;
        this.settingsService = settingsService;
        this.audioToolService = audioToolService;
        this.entitlementService = entitlementService;
        this.permissionService = permissionService;
        // Der Connect-Timeout war frueher an den gesamten LLM-Timeout gekoppelt.
        // Bei einem toten Endpoint blockierte der Aufruf dadurch minutenlang,
        // obwohl schon der TCP-Verbindungsaufbau scheitert.
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        // Eigener Pool: die alten Aufrufe liefen ueber den gemeinsamen
        // ForkJoinPool. Da HTTP-Requests dort blockieren, hat ein langsames
        // Modell den Pool ausgehungert - genau das Verhalten, bei dem
        // Anfragen scheinbar willkuerlich abbrechen.
        this.llmExecutor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "llm-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    public void shutdown() {
        llmExecutor.shutdownNow();
    }

    public void handleMessage(MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || event.getGuild() == null || event.getJDA().getSelfUser() == null) {
            return;
        }

        GuildModuleSettingsService.LlmState settings = settingsService.getLlmState(event.getGuild().getId());
        if (!settings.isEnabled() || !configService.isLlmConfigured()) {
            return;
        }

        if (!settings.getTextChannelId().isBlank() && !settings.getTextChannelId().equals(event.getChannel().getId())) {
            return;
        }

        // Neben der klassischen Erwaehnung zaehlt jetzt auch eine Antwort auf
        // eine Bot-Nachricht als Anfrage. Ohne das musste man in einem laufenden
        // Gespraech jedes Mal erneut mit @Bot beginnen.
        boolean mentioned = event.getMessage().getMentions().isMentioned(event.getJDA().getSelfUser());
        Message referenced = event.getMessage().getReferencedMessage();
        boolean replyToBot = referenced != null
                && referenced.getAuthor().getIdLong() == event.getJDA().getSelfUser().getIdLong();
        if (!mentioned && !replyToBot) {
            return;
        }

        String prompt = stripBotMention(event.getMessage().getContentRaw(), event.getJDA().getSelfUser().getIdLong());
        if (prompt.isBlank()) {
            return;
        }

        // Darf dieses Mitglied den Assistenten ueberhaupt ansprechen? Ohne
        // gepflegte Rechtematrix greift der Rueckfall in GuildPermissionService,
        // also die bisherige Discord-Regel - hier aendert sich fuer bestehende
        // Server nichts.
        if (!permissionService.has(event.getGuild(), event.getMember(), GuildPermission.AI_USE)) {
            return;
        }

        // Erst ab hier entstehen echte Kosten: Freischaltung pruefen und das
        // Tageskontingent fortschreiben. Absichtlich nicht frueher, sonst
        // wuerde jede beliebige Nachricht im Kanal auf das Limit zaehlen.
        GuildEntitlementService.Decision decision =
                entitlementService.tryConsume(event.getGuild().getId(), GuildFeature.LLM_CHAT);
        if (!decision.allowed()) {
            event.getMessage().reply(decision.reason()).queue(
                    ignored -> {
                    },
                    ignored -> {
                    }
            );
            return;
        }

        // Der Kontext traegt Server und Mitglied, damit ausgeloeste Werkzeuge
        // denselben Rechten unterliegen wie die Slash-Commands.
        respondAsync(
                event.getMessage(),
                prompt,
                settings,
                ToolContext.fromChat(event.getGuild(), event.getMember())
        );
    }

    private void respondAsync(
            Message message,
            String prompt,
            GuildModuleSettingsService.LlmState settings,
            ToolContext toolContext
    ) {
        long channelId = message.getChannel().getIdLong();
        AtomicInteger pending = pendingByChannel.computeIfAbsent(channelId, ignored -> new AtomicInteger());

        // Ohne Begrenzung stapelt sich bei mehreren schnellen Anfragen eine
        // Warteschlange auf, deren Antworten spaeter durcheinander eintreffen
        // oder in den Timeout laufen.
        if (pending.incrementAndGet() > 2) {
            pending.decrementAndGet();
            message.reply("Ich beantworte gerade noch eine andere Frage in diesem Kanal. Einen Moment bitte.")
                    .mentionRepliedUser(false)
                    .queue(success -> {
                    }, failure -> {
                    });
            return;
        }

        message.getChannel().sendTyping().queue(
                success -> {
                },
                failure -> {
                }
        );

        CompletableFuture.supplyAsync(() -> generateReply(channelId, prompt, settings, toolContext), llmExecutor)
                .whenComplete((reply, throwable) -> {
                    pending.decrementAndGet();

                    if (throwable != null) {
                        String reason = rootMessage(throwable);
                        Alert.send("WARN", "LLM", "LLM-Antwort fehlgeschlagen: " + reason);
                        sendReply(message, describeFailure(reason));
                        return;
                    }

                    if (reply == null || reply.isBlank()) {
                        // Reasoning-Modelle liefern haeufig nur einen leeren
                        // content-Block. Frueher endete das in der pauschalen
                        // Meldung "Der LLM antwortet gerade nicht", obwohl der
                        // Endpoint einwandfrei erreichbar war.
                        sendReply(message, "Darauf habe ich gerade keine Antwort bekommen. Versuch es bitte noch einmal "
                                + "oder formuliere die Frage etwas ausfuehrlicher.");
                        return;
                    }

                    sendReply(message, reply);
                    appendHistory(channelId, prompt, reply);
                });
    }

    /**
     * Sendet die Antwort und teilt sie bei Bedarf in mehrere Nachrichten auf,
     * damit Discords 2000-Zeichen-Limit nicht zum stillen Abbruch fuehrt.
     */
    private void sendReply(Message message, String reply) {
        List<String> chunks = splitForDiscord(reply);
        if (chunks.isEmpty()) {
            return;
        }

        message.reply(chunks.get(0))
                .mentionRepliedUser(false)
                .queue(
                        sent -> sendRemainingChunks(message, chunks, 1),
                        failure -> Alert.send("WARN", "LLM", "Antwort konnte nicht gesendet werden: " + failure.getMessage())
                );
    }

    private void sendRemainingChunks(Message message, List<String> chunks, int index) {
        if (index >= chunks.size()) {
            return;
        }

        message.getChannel().sendMessage(chunks.get(index)).queue(
                sent -> sendRemainingChunks(message, chunks, index + 1),
                failure -> {
                }
        );
    }

    private List<String> splitForDiscord(String reply) {
        String value = reply == null ? "" : reply.trim();
        if (value.isEmpty()) {
            return List.of();
        }
        if (value.length() <= DISCORD_MESSAGE_LIMIT) {
            return List.of(value);
        }

        List<String> chunks = new ArrayList<>();
        int cursor = 0;
        while (cursor < value.length() && chunks.size() < MAX_MESSAGE_CHUNKS) {
            int remaining = value.length() - cursor;
            if (remaining <= DISCORD_MESSAGE_LIMIT) {
                chunks.add(value.substring(cursor));
                break;
            }

            int limit = cursor + DISCORD_MESSAGE_LIMIT;
            // Bevorzugt an einem Absatz, sonst an einem Satzende, sonst an
            // einem Leerzeichen trennen - mitten im Wort zu schneiden liest
            // sich wie ein Fehler.
            int cut = value.lastIndexOf("\n\n", limit);
            if (cut <= cursor) {
                cut = value.lastIndexOf(". ", limit);
                if (cut > cursor) {
                    cut += 1;
                }
            }
            if (cut <= cursor) {
                cut = value.lastIndexOf(' ', limit);
            }
            if (cut <= cursor) {
                cut = limit;
            }

            chunks.add(value.substring(cursor, cut).trim());
            cursor = cut;
            while (cursor < value.length() && Character.isWhitespace(value.charAt(cursor))) {
                cursor++;
            }
        }

        return chunks;
    }

    /**
     * Uebersetzt technische Fehlermeldungen in etwas, das im Chat weiterhilft.
     */
    private String describeFailure(String reason) {
        String lowerCase = reason == null ? "" : reason.toLowerCase(Locale.ROOT);

        if (lowerCase.contains("timed out") || lowerCase.contains("timeout")) {
            return "Das Modell hat zu lange gebraucht. Bei kurzen Fragen hilft meist ein zweiter Versuch, "
                    + "sonst ist das Zeitlimit in den Bot-Einstellungen zu knapp gesetzt.";
        }
        if (lowerCase.contains("connection refused") || lowerCase.contains("connect")) {
            return "Der LLM-Dienst ist gerade nicht erreichbar. Laeuft Ollama beziehungsweise der konfigurierte Endpoint?";
        }
        if (lowerCase.contains("not found") && lowerCase.contains("model")) {
            return "Das eingestellte Modell ist auf dem Server nicht installiert.";
        }
        if (lowerCase.contains("401") || lowerCase.contains("403")) {
            return "Der LLM-Endpoint hat die Anfrage abgelehnt. Der API-Key stimmt vermutlich nicht.";
        }

        return "LLM-Fehler: " + clamp(reason, 220);
    }

    private String generateReply(
            long channelId,
            String prompt,
            GuildModuleSettingsService.LlmState settings,
            ToolContext toolContext
    ) {
        JSONArray messages = buildMessages(channelId, prompt, settings);
        String model = configService.resolveAllowedLlmModel(settings.getModel());
        IOException lastFailure = null;

        // Ein einzelner Netzwerk-Hickser oder ein gerade nachladendes Modell
        // hat frueher direkt zum Abbruch gefuehrt. Ein zweiter Versuch faengt
        // genau diese Faelle ab.
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                boolean withTools = toolsAvailable(model, toolContext);
                JSONObject responseMessage = switch (configService.getLlmProvider()) {
                    case "openai", "openai-compatible" -> callOpenAiCompatible(messages, model, withTools);
                    default -> callOllama(messages, model, withTools);
                };

                // Hat das Modell Werkzeuge angefordert, wird deren Ergebnis
                // direkt ausgegeben. Ein zweiter Modelldurchlauf zum
                // Umformulieren wuerde die Antwortzeit verdoppeln, und die
                // Meldungen des AudioService sind bereits fertige Saetze.
                List<ToolInvocation> toolCalls = parseToolCalls(responseMessage);
                if (!toolCalls.isEmpty()) {
                    return executeToolCalls(toolCalls, toolContext);
                }

                String normalized = normalizeReplyText(extractText(responseMessage));
                if (!normalized.isBlank()) {
                    return clamp(normalized, effectiveMaxReplyChars(settings));
                }

                if (attempt == MAX_ATTEMPTS) {
                    return "";
                }
            } catch (UnsupportedToolsException exception) {
                // Modell kann kein Function-Calling: merken und ohne
                // Werkzeuge weitermachen, ohne einen Versuch zu verbrauchen.
                modelsWithoutToolSupport.add(model);
                Alert.send("INFO", "LLM", "Modell " + model
                        + " unterstuetzt keine Werkzeuge - Chat-Steuerung ist fuer dieses Modell deaktiviert.");
                attempt--;
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw new CompletionException(interruptedException);
            } catch (IOException exception) {
                lastFailure = exception;
                if (attempt == MAX_ATTEMPTS || !isRetryable(exception)) {
                    throw new CompletionException(exception);
                }
            }

            try {
                Thread.sleep(600L);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw new CompletionException(interruptedException);
            }
        }

        if (lastFailure != null) {
            throw new CompletionException(lastFailure);
        }
        return "";
    }

    /**
     * Ein zweiter Versuch lohnt sich nur bei Transportfehlern und
     * serverseitigen Aussetzern - nicht bei Konfigurationsfehlern.
     */
    private boolean isRetryable(IOException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("401") || message.contains("403") || message.contains("400")) {
            return false;
        }
        return message.contains("timed out")
                || message.contains("timeout")
                || message.contains("reset")
                || message.contains("http 5")
                || message.contains("goaway")
                || message.contains("eof");
    }

    private int effectiveMaxReplyChars(GuildModuleSettingsService.LlmState settings) {
        int configured = settings.getMaxReplyChars();
        if (configured <= 0) {
            return DISCORD_MESSAGE_LIMIT * MAX_MESSAGE_CHUNKS;
        }
        return Math.min(configured, DISCORD_MESSAGE_LIMIT * MAX_MESSAGE_CHUNKS);
    }

    private JSONArray buildMessages(long channelId, String prompt, GuildModuleSettingsService.LlmState settings) {
        JSONArray messages = new JSONArray();
        messages.put(jsonMessage("system", configService.getLlmSystemMessage()));

        if (!settings.getSystemPrompt().isBlank()) {
            messages.put(jsonMessage("system", settings.getSystemPrompt()));
        }

        for (HistoryEntry entry : getHistory(channelId)) {
            messages.put(jsonMessage(entry.role(), entry.content()));
        }

        messages.put(jsonMessage("user", prompt));
        return messages;
    }

    private JSONObject jsonMessage(String role, String content) {
        return new JSONObject()
                .put("role", role)
                .put("content", content);
    }

    private JSONObject callOllama(JSONArray messages, String model, boolean withTools)
            throws IOException, InterruptedException {
        String effectiveModel = resolveInstalledOllamaModel(model, false);
        if (!effectiveModel.equals(model)) {
            Alert.send("INFO", "LLM", "Ollama Modell-Fallback aktiv: " + model + " -> " + effectiveModel);
        }

        HttpResponse<String> response = sendOllamaChat(messages, effectiveModel, withTools);
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return readOllamaMessage(response.body());
        }

        String errorMessage = buildOllamaErrorMessage(response);
        if (withTools && mentionsMissingToolSupport(errorMessage)) {
            throw new UnsupportedToolsException();
        }

        if (response.statusCode() == 404 && isMissingOllamaModel(errorMessage)) {
            String refreshedModel = resolveInstalledOllamaModel(model, true);
            if (!refreshedModel.isBlank() && !refreshedModel.equals(effectiveModel)) {
                Alert.send("INFO", "LLM", "Ollama Modell nach Tags-Refresh angepasst: " + model + " -> " + refreshedModel);
                response = sendOllamaChat(messages, refreshedModel, withTools);
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return readOllamaMessage(response.body());
                }
                errorMessage = buildOllamaErrorMessage(response);
            }
        }

        throw new IOException(errorMessage);
    }

    private HttpResponse<String> sendOllamaChat(JSONArray messages, String model, boolean withTools)
            throws IOException, InterruptedException {
        JSONObject payload = new JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("stream", false)
                .put("options", new JSONObject()
                        .put("temperature", configService.getLlmTemperature())
                        .put("num_predict", configService.getLlmMaxTokens()));

        if (withTools) {
            payload.put("tools", buildToolsArray());
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(configService.getLlmOllamaUrl().replaceAll("/+$", "") + "/api/chat"))
                .timeout(Duration.ofMillis(configService.getLlmTimeoutMs()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Liest den Antworttext aus einer Ollama-Response.
     *
     * <p>Die alte Version rief {@code optJSONObject("message").optString(...)}
     * auf. Fehlte das Feld - was bei Fehlerantworten und bei einigen Modellen
     * passiert - warf das eine NullPointerException, die als "LLM-Fehler:
     * NullPointerException" im Chat landete. Genau dieses Verhalten tritt bei
     * kurzen, einfachen Fragen besonders haeufig auf, weil Reasoning-Modelle
     * dort ihre Ausgabe komplett in {@code thinking} legen und
     * {@code content} leer lassen.
     */
    private JSONObject readOllamaMessage(String body) {
        if (body == null || body.isBlank()) {
            return new JSONObject();
        }

        JSONObject root;
        try {
            root = new JSONObject(body);
        } catch (RuntimeException exception) {
            return new JSONObject();
        }

        JSONObject messageObject = root.optJSONObject("message");
        if (messageObject != null) {
            return messageObject;
        }

        // /api/generate liefert den Text direkt unter "response".
        return new JSONObject().put("content", root.optString("response", ""));
    }

    /**
     * Holt den Antworttext aus einer Modellnachricht.
     *
     * <p>Reasoning-Modelle lassen {@code content} bei kurzen Fragen haeufig leer
     * und legen ihre Ausgabe stattdessen in {@code thinking} beziehungsweise
     * {@code reasoning_content} ab.
     */
    private String extractText(JSONObject messageObject) {
        if (messageObject == null) {
            return "";
        }

        String content = messageObject.optString("content", "").trim();
        if (!content.isBlank()) {
            return content;
        }

        String thinking = messageObject.optString("thinking", "").trim();
        if (!thinking.isBlank()) {
            return thinking;
        }

        return messageObject.optString("reasoning_content", "").trim();
    }

    private String buildOllamaErrorMessage(HttpResponse<String> response) {
        String body = response.body() == null ? "" : response.body().trim();
        if (!body.isBlank()) {
            try {
                String error = new JSONObject(body).optString("error", "").trim();
                if (!error.isBlank()) {
                    return "Ollama meldet: " + error + " (HTTP " + response.statusCode() + ")";
                }
            } catch (Exception ignored) {
            }
        }

        return "Ollama antwortet mit HTTP " + response.statusCode();
    }

    private boolean isMissingOllamaModel(String errorMessage) {
        String lowerCase = errorMessage == null ? "" : errorMessage.toLowerCase(Locale.ROOT);
        return lowerCase.contains("model") && lowerCase.contains("not found");
    }

    private String resolveInstalledOllamaModel(String requestedModel, boolean forceRefresh) throws IOException, InterruptedException {
        String requested = requestedModel == null ? "" : requestedModel.trim();
        if (requested.isBlank()) {
            return requested;
        }

        List<String> installedModels = loadInstalledOllamaModels(forceRefresh);
        if (installedModels.isEmpty()) {
            return requested;
        }

        for (String candidate : installedModels) {
            if (candidate.equalsIgnoreCase(requested)) {
                return candidate;
            }
        }

        String requestedBase = baseModelName(requested);
        for (String candidate : installedModels) {
            String candidateBase = baseModelName(candidate);
            if (candidateBase.equalsIgnoreCase(requested)
                    || candidateBase.equalsIgnoreCase(requestedBase)
                    || candidateBase.toLowerCase(Locale.ROOT).endsWith("/" + requested.toLowerCase(Locale.ROOT))
                    || candidateBase.toLowerCase(Locale.ROOT).endsWith("/" + requestedBase.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
        }

        String requestedToken = normalizeModelToken(requestedBase);
        if (!requestedToken.isBlank()) {
            for (String candidate : installedModels) {
                if (normalizeModelToken(candidate).contains(requestedToken)) {
                    return candidate;
                }
            }
        }

        return requested;
    }

    private List<String> loadInstalledOllamaModels(boolean forceRefresh) throws IOException, InterruptedException {
        long now = System.currentTimeMillis();
        if (!forceRefresh && now < cachedOllamaModelsUntil && !cachedOllamaModels.isEmpty()) {
            return cachedOllamaModels;
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(configService.getLlmOllamaUrl().replaceAll("/+$", "") + "/api/tags"))
                .timeout(Duration.ofMillis(configService.getLlmTimeoutMs()))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return cachedOllamaModels;
        }

        JSONArray models = new JSONObject(response.body()).optJSONArray("models");
        if (models == null || models.isEmpty()) {
            cachedOllamaModels = List.of();
            cachedOllamaModelsUntil = now + Duration.ofMinutes(5).toMillis();
            return cachedOllamaModels;
        }

        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        for (int index = 0; index < models.length(); index++) {
            JSONObject item = models.optJSONObject(index);
            if (item == null) {
                continue;
            }

            String model = item.optString("model", "").trim();
            String name = item.optString("name", "").trim();
            if (!model.isBlank()) {
                resolved.add(model);
            }
            if (!name.isBlank()) {
                resolved.add(name);
            }
        }

        cachedOllamaModels = List.copyOf(resolved);
        cachedOllamaModelsUntil = now + Duration.ofMinutes(5).toMillis();
        return cachedOllamaModels;
    }

    private String baseModelName(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isBlank()) {
            return "";
        }

        int colonIndex = trimmed.lastIndexOf(':');
        return colonIndex > 0 ? trimmed.substring(0, colonIndex) : trimmed;
    }

    private String normalizeModelToken(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private JSONObject callOpenAiCompatible(JSONArray messages, String model, boolean withTools)
            throws IOException, InterruptedException {
        JSONObject payload = new JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("stream", false)
                .put("temperature", configService.getLlmTemperature())
                .put("max_tokens", configService.getLlmMaxTokens());

        if (withTools) {
            payload.put("tools", buildToolsArray());
            payload.put("tool_choice", "auto");
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(configService.getLlmOpenAiBaseUrl().replaceAll("/+$", "") + "/v1/chat/completions"))
                .timeout(Duration.ofMillis(configService.getLlmTimeoutMs()))
                .header("Content-Type", "application/json");

        if (!configService.getLlmApiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + configService.getLlmApiKey());
        }

        HttpResponse<String> response = httpClient.send(
                builder.POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build(),
                HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String errorMessage = buildOpenAiErrorMessage(response);
            if (withTools && mentionsMissingToolSupport(errorMessage)) {
                throw new UnsupportedToolsException();
            }
            throw new IOException(errorMessage);
        }

        JSONObject root;
        try {
            root = new JSONObject(response.body() == null ? "" : response.body());
        } catch (RuntimeException exception) {
            throw new IOException("Der Endpoint hat keine gueltige JSON-Antwort geliefert.");
        }

        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            return new JSONObject();
        }

        JSONObject firstChoice = choices.optJSONObject(0);
        if (firstChoice == null) {
            return new JSONObject();
        }

        // Auch hier war der Zugriff auf "message" bisher ungeprueft und
        // konnte eine NullPointerException ausloesen.
        JSONObject messageObject = firstChoice.optJSONObject("message");
        if (messageObject != null) {
            return messageObject;
        }

        return new JSONObject().put("content", firstChoice.optString("text", ""));
    }

    // ------------------------------------------------------- Werkzeuge

    /**
     * Werkzeuge werden nur mitgeschickt, wenn der Aufruf aus einem Server
     * stammt und das Modell Function-Calling beherrscht.
     */
    private boolean toolsAvailable(String model, ToolContext toolContext) {
        return configService.isLlmToolsEnabled()
                && toolContext != null
                && toolContext.guild() != null
                && !modelsWithoutToolSupport.contains(model);
    }

    /**
     * Baut die Werkzeugliste im Format, das Ollama und OpenAI gemeinsam nutzen.
     * Der Server ergibt sich im Chat aus der Nachricht, deshalb ohne
     * guild-Parameter.
     */
    private JSONArray buildToolsArray() {
        JSONArray tools = new JSONArray();
        for (BotTool tool : audioToolService.tools(false)) {
            tools.put(new JSONObject()
                    .put("type", "function")
                    .put("function", new JSONObject()
                            .put("name", tool.name())
                            .put("description", tool.description())
                            .put("parameters", tool.inputSchema())));
        }
        return tools;
    }

    /**
     * Liest angeforderte Werkzeugaufrufe aus der Modellantwort.
     *
     * <p>Die beiden Anbieter unterscheiden sich in einem Detail: Ollama liefert
     * {@code arguments} als JSON-Objekt, OpenAI als JSON-Zeichenkette. Beides
     * wird hier abgefangen.
     */
    private List<ToolInvocation> parseToolCalls(JSONObject messageObject) {
        List<ToolInvocation> invocations = new ArrayList<>();
        if (messageObject == null) {
            return invocations;
        }

        JSONArray toolCalls = messageObject.optJSONArray("tool_calls");
        if (toolCalls == null) {
            return invocations;
        }

        for (int index = 0; index < toolCalls.length(); index++) {
            JSONObject call = toolCalls.optJSONObject(index);
            if (call == null) {
                continue;
            }

            JSONObject function = call.optJSONObject("function");
            if (function == null) {
                continue;
            }

            String name = function.optString("name", "").trim();
            if (name.isBlank()) {
                continue;
            }

            JSONObject arguments = new JSONObject();
            Object rawArguments = function.opt("arguments");
            if (rawArguments instanceof JSONObject objectArguments) {
                arguments = objectArguments;
            } else if (rawArguments instanceof String stringArguments && !stringArguments.isBlank()) {
                try {
                    arguments = new JSONObject(stringArguments);
                } catch (RuntimeException ignored) {
                    // Unbrauchbare Argumente: das Werkzeug meldet den
                    // fehlenden Parameter selbst.
                }
            }

            invocations.add(new ToolInvocation(name, arguments));
        }

        return invocations;
    }

    private String executeToolCalls(List<ToolInvocation> invocations, ToolContext toolContext) {
        List<String> messages = new ArrayList<>();

        // Mehr als drei Aufrufe pro Nachricht sind fast immer ein Fehlgriff
        // des Modells und wuerden den Player nur durcheinanderbringen.
        for (ToolInvocation invocation : invocations.stream().limit(3).toList()) {
            ToolResult result = audioToolService.execute(invocation.name(), invocation.arguments(), toolContext);
            Alert.send(
                    result.failed() ? "WARN" : "INFO",
                    "LLM",
                    "Chat-Steuerung: " + invocation.name() + " -> " + clamp(result.message(), 120)
            );
            messages.add(result.message());
        }

        return String.join("\n", messages);
    }

    private boolean mentionsMissingToolSupport(String message) {
        String lowerCase = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return lowerCase.contains("does not support tools")
                || lowerCase.contains("tools are not supported")
                || lowerCase.contains("tool use")
                || lowerCase.contains("function calling");
    }

    private record ToolInvocation(String name, JSONObject arguments) {
    }

    /**
     * Signalisiert, dass das Modell kein Function-Calling beherrscht.
     */
    private static class UnsupportedToolsException extends IOException {
    }

    private String buildOpenAiErrorMessage(HttpResponse<String> response) {
        String body = response.body() == null ? "" : response.body().trim();
        if (!body.isBlank()) {
            try {
                JSONObject error = new JSONObject(body).optJSONObject("error");
                if (error != null) {
                    String detail = error.optString("message", "").trim();
                    if (!detail.isBlank()) {
                        return "Endpoint meldet: " + detail + " (HTTP " + response.statusCode() + ")";
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }

        return "OpenAI-kompatibler Endpoint antwortet mit HTTP " + response.statusCode();
    }

    private List<HistoryEntry> getHistory(long channelId) {
        Deque<HistoryEntry> history = historyByChannel.computeIfAbsent(channelId, ignored -> new ArrayDeque<>());
        return new ArrayList<>(history);
    }

    private void appendHistory(long channelId, String prompt, String reply) {
        int maxTurns = configService.getLlmHistoryTurns();
        if (maxTurns <= 0) {
            return;
        }

        Deque<HistoryEntry> history = historyByChannel.computeIfAbsent(channelId, ignored -> new ArrayDeque<>());
        history.addLast(new HistoryEntry("user", prompt));
        history.addLast(new HistoryEntry("assistant", reply));

        while (history.size() > maxTurns * 2) {
            history.removeFirst();
        }
    }

    private String stripBotMention(String content, long botId) {
        if (content == null || content.isBlank()) {
            return "";
        }

        return content
                .replace("<@" + botId + ">", "")
                .replace("<@!" + botId + ">", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Raeumt die Antwort auf, ohne sie plattzuwalzen.
     *
     * <p>Vorher wurden alle Zeilenumbrueche durch Leerzeichen ersetzt. Damit
     * verlor jede Antwort ihre Struktur - Listen, Absaetze und vor allem
     * Codebloecke wurden unlesbar. Jetzt bleiben Absaetze erhalten, nur
     * ueberzaehlige Leerzeilen und Reasoning-Bloecke fliegen raus.
     */
    private String normalizeReplyText(String value) {
        if (value == null) {
            return "";
        }

        String text = value.replace("\r\n", "\n").replace("\r", "\n");
        // Manche Modelle liefern ihren Denkprozess inline in <think>-Tags.
        text = text.replaceAll("(?is)<think>.*?</think>", "");
        text = text.replaceAll("(?is)<thinking>.*?</thinking>", "");
        // Angefangene, nie geschlossene Reasoning-Bloecke ebenfalls entfernen.
        text = text.replaceAll("(?is)<think(ing)?>.*$", "");
        text = text.replaceAll("[ \\t]+", " ");
        text = text.replaceAll("\\n{3,}", "\n\n");
        return text.trim();
    }

    private String clamp(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return "";
        }

        if (value.length() <= maxChars) {
            return value;
        }

        return value.substring(0, Math.max(0, maxChars - 3)).trim() + "...";
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record HistoryEntry(String role, String content) {
    }
}
