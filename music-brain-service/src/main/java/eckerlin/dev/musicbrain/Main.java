package eckerlin.dev.musicbrain;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

public class Main {

    public static void main(String[] args) throws Exception {
        String host = Config.config.optString("listen_host", "127.0.0.1");
        int port = Math.max(1, Config.config.optInt("port", 8091));
        MusicBrainEngine engine = new MusicBrainEngine();

        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/health", exchange -> writeJson(exchange, 200, new JSONObject().put("status", "ok")));
        server.createContext("/api/v1/guilds", exchange -> handleGuildRoute(exchange, engine));
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        System.out.println("discordbot-music-brain hoert auf http://" + host + ":" + port);
    }

    private static void handleGuildRoute(HttpExchange exchange, MusicBrainEngine engine) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, new JSONObject().put("error", "Nur POST ist erlaubt."));
                return;
            }

            String[] parts = exchange.getRequestURI().getPath().split("/");
            if (parts.length < 6 || !"api".equals(parts[1]) || !"v1".equals(parts[2]) || !"guilds".equals(parts[3]) || !"radio".equals(parts[5])) {
                writeJson(exchange, 404, new JSONObject().put("error", "Pfad nicht gefunden."));
                return;
            }

            String guildId = parts[4].trim();
            JSONObject body = readJson(exchange);
            int limit = body.optInt("limit", Config.config.optInt("batch_size", 12));

            writeJson(exchange, 200, engine.buildRadioPlan(guildId, limit));
        } catch (IllegalArgumentException exception) {
            writeJson(exchange, 400, new JSONObject().put("error", exception.getMessage()));
        } catch (Exception exception) {
            writeJson(exchange, 500, new JSONObject().put("error", exception.getMessage() == null ? "Unbekannter Fehler" : exception.getMessage()));
        }
    }

    private static JSONObject readJson(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        if (body.length == 0) {
            return new JSONObject();
        }
        return new JSONObject(new String(body, StandardCharsets.UTF_8));
    }

    private static void writeJson(HttpExchange exchange, int statusCode, JSONObject body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
