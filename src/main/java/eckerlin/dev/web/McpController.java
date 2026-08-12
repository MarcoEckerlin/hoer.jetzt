package eckerlin.dev.web;

import eckerlin.dev.services.AppConfigService;
import eckerlin.dev.services.tools.AudioToolService;
import eckerlin.dev.services.tools.BotTool;
import eckerlin.dev.services.tools.ToolContext;
import eckerlin.dev.services.tools.ToolResult;
import eckerlin.dev.utils.Alert;
import jakarta.servlet.http.HttpServletRequest;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * MCP-Endpunkt des Bots (Streamable-HTTP-Transport).
 *
 * <p>Erlaubt externen Clients - etwa Claude Desktop oder Cowork - den Bot zu
 * steuern. Die Werkzeuge stammen aus {@link AudioToolService} und sind damit
 * identisch mit denen, die das Sprachmodell im Discord-Chat nutzt.
 *
 * <p>Bewusst als Teil der bestehenden Spring-Anwendung umgesetzt statt als
 * eigener Dienst: die Audio-Funktionen liegen in derselben JVM. Ein separater
 * Prozess muesste ueber das Netz zurueck in genau diese Anwendung rufen.
 *
 * <p>Umgesetzt sind die Methoden {@code initialize}, {@code ping},
 * {@code tools/list} und {@code tools/call} sowie die Notifikation
 * {@code notifications/initialized}. Server-initiierte Nachrichten und damit
 * ein SSE-Stream werden nicht gebraucht - GET beantwortet der Endpunkt
 * spezifikationskonform mit 405.
 */
@RestController
@RequestMapping("/mcp")
public class McpController {

    private static final String LATEST_PROTOCOL_VERSION = "2025-06-18";
    private static final List<String> SUPPORTED_PROTOCOL_VERSIONS =
            List.of("2025-06-18", "2025-03-26", "2024-11-05");

    private static final int PARSE_ERROR = -32700;
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final int INTERNAL_ERROR = -32603;

    private final AppConfigService configService;
    private final AudioToolService audioToolService;

    public McpController(AppConfigService configService, AudioToolService audioToolService) {
        this.configService = configService;
        this.audioToolService = audioToolService;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> handle(@RequestBody(required = false) String body, HttpServletRequest request) {
        if (!configService.isMcpEnabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (!isAuthorized(request)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(errorResponse(null, INVALID_REQUEST, "Nicht autorisiert.").toString());
        }
        // Schutz vor DNS-Rebinding: ein Browser sendet Origin, ein regulaerer
        // MCP-Client nicht. Fremde Origins werden abgewiesen.
        if (!isOriginAllowed(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(errorResponse(null, INVALID_REQUEST, "Origin nicht erlaubt.").toString());
        }

        JSONObject message;
        try {
            message = new JSONObject(body == null ? "" : body);
        } catch (RuntimeException exception) {
            return ResponseEntity.badRequest()
                    .body(errorResponse(null, PARSE_ERROR, "Ungueltiges JSON.").toString());
        }

        String method = message.optString("method", "");
        boolean isNotification = message.isNull("id");

        if (method.isBlank()) {
            // Antworten des Clients interessieren uns nicht, werden aber
            // laut Spezifikation mit 202 quittiert.
            return isNotification
                    ? ResponseEntity.accepted().build()
                    : ResponseEntity.badRequest()
                    .body(errorResponse(message.opt("id"), INVALID_REQUEST, "Feld \"method\" fehlt.").toString());
        }

        if (isNotification) {
            return ResponseEntity.accepted().build();
        }

        Object id = message.opt("id");
        JSONObject params = message.optJSONObject("params") == null
                ? new JSONObject()
                : message.optJSONObject("params");

        try {
            JSONObject result = switch (method) {
                case "initialize" -> initialize(params);
                case "ping" -> new JSONObject();
                case "tools/list" -> listTools();
                case "tools/call" -> callTool(params);
                default -> null;
            };

            if (result == null) {
                return ResponseEntity.ok(
                        errorResponse(id, METHOD_NOT_FOUND, "Unbekannte Methode: " + method).toString());
            }

            return ResponseEntity.ok(new JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", id)
                    .put("result", result)
                    .toString());
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.ok(
                    errorResponse(id, INVALID_PARAMS, exception.getMessage()).toString());
        } catch (RuntimeException exception) {
            Alert.send("WARN", "MCP", "Aufruf von " + method + " fehlgeschlagen: " + exception);
            return ResponseEntity.ok(
                    errorResponse(id, INTERNAL_ERROR, "Interner Fehler im Bot.").toString());
        }
    }

    /**
     * Der Endpunkt sendet von sich aus keine Nachrichten. Die Spezifikation
     * sieht dafuer ausdruecklich 405 vor.
     */
    @GetMapping
    public ResponseEntity<Void> noServerStream() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    /**
     * Sitzungen werden nicht verwaltet, es gibt also auch nichts zu beenden.
     */
    @DeleteMapping
    public ResponseEntity<Void> noSessionTermination() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    // --------------------------------------------------------- Methoden

    private JSONObject initialize(JSONObject params) {
        String requested = params.optString("protocolVersion", LATEST_PROTOCOL_VERSION);
        String negotiated = SUPPORTED_PROTOCOL_VERSIONS.contains(requested)
                ? requested
                : LATEST_PROTOCOL_VERSION;

        return new JSONObject()
                .put("protocolVersion", negotiated)
                .put("capabilities", new JSONObject()
                        .put("tools", new JSONObject().put("listChanged", false)))
                .put("serverInfo", new JSONObject()
                        .put("name", "discord-bot-audio")
                        .put("title", "Discord Bot Audiosteuerung")
                        .put("version", "1.0.0"))
                .put("instructions",
                        "Steuert die Musik- und Radiowiedergabe eines Discord-Bots. "
                                + "Fast alle Werkzeuge brauchen den Parameter \"guild\" - die verfuegbaren Server "
                                + "liefert list_servers. Wiedergabe kann nur gestartet werden, wenn der Bot bereits "
                                + "in einem Sprachkanal ist.");
    }

    private JSONObject listTools() {
        JSONArray tools = new JSONArray();

        // Externe Clients kennen keinen Server-Kontext, deshalb hier mit
        // guild-Parameter und zusaetzlichem Werkzeug zum Auflisten.
        tools.put(describe(audioToolService.listGuildsTool()));
        audioToolService.tools(true).forEach(tool -> tools.put(describe(tool)));

        return new JSONObject().put("tools", tools);
    }

    private JSONObject describe(BotTool tool) {
        return new JSONObject()
                .put("name", tool.name())
                .put("description", tool.description())
                .put("inputSchema", tool.inputSchema())
                .put("annotations", new JSONObject()
                        .put("readOnlyHint", tool.readOnly())
                        .put("destructiveHint", false));
    }

    private JSONObject callTool(JSONObject params) {
        String name = params.optString("name", "").trim();
        if (name.isBlank()) {
            throw new IllegalArgumentException("Feld \"name\" fehlt.");
        }

        JSONObject arguments = params.optJSONObject("arguments") == null
                ? new JSONObject()
                : params.optJSONObject("arguments");

        ToolResult result = audioToolService.execute(
                name,
                arguments,
                ToolContext.fromMcp(arguments.optString("guild", ""))
        );

        return new JSONObject()
                .put("content", new JSONArray().put(new JSONObject()
                        .put("type", "text")
                        .put("text", result.message())))
                .put("isError", result.failed());
    }

    // ---------------------------------------------------------- Sicherheit

    private boolean isAuthorized(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || header.isBlank()) {
            return false;
        }

        String presented = header.regionMatches(true, 0, "Bearer ", 0, 7)
                ? header.substring(7).trim()
                : header.trim();

        // Zeitkonstanter Vergleich, damit sich das Token nicht ueber
        // Antwortzeiten erraten laesst.
        return constantTimeEquals(presented, configService.getMcpToken());
    }

    private boolean constantTimeEquals(String left, String right) {
        byte[] a = left == null ? new byte[0] : left.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] b = right == null ? new byte[0] : right.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(a, b);
    }

    private boolean isOriginAllowed(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            return true;
        }

        try {
            String host = URI.create(origin).getHost();
            if (host == null) {
                return false;
            }

            String normalized = host.toLowerCase(Locale.ROOT);
            if (normalized.equals("localhost") || normalized.equals("127.0.0.1") || normalized.equals("::1")) {
                return true;
            }

            String configured = configService.getWebBaseUrl();
            if (configured != null && !configured.isBlank()) {
                String configuredHost = URI.create(configured).getHost();
                return configuredHost != null && configuredHost.equalsIgnoreCase(normalized);
            }
            return false;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private JSONObject errorResponse(Object id, int code, String message) {
        return new JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id == null ? JSONObject.NULL : id)
                .put("error", new JSONObject()
                        .put("code", code)
                        .put("message", message == null ? "Fehler" : message));
    }
}
