package eckerlin.dev.utils;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class Config {

    public static JSONObject config;

    static {
        try {
            config = loadConfig();
        } catch (IOException e) {
            e.printStackTrace();
            config = new JSONObject();
        }
    }

    private static final String DEFAULT_CONFIG = """
            {
              "bot": {
                "token": "",
                "activity": "",
                "status": "IDLE"
              },
              "database": {
                "host": "127.0.0.1",
                "port": 3306,
                "name": "discordbot",
                "user": "discordbot",
                "password": ""
              },
              "bot_id": 1,
              "deployment": {
                "key": "local",
                "display_name": "Lokale Instanz"
              },
              "webinterface": {
                "port": 8080,
                "base_url": "http://localhost:8080",
                "discord_client_id": "",
                "discord_client_secret": "",
                "redirect_uri": "http://localhost:8080/auth/discord/callback"
              },
              "lavalink": {
                "name": "main-node",
                "uri": "http://127.0.0.1:2333",
                "password": "youshallnotpass",
                "http_timeout_ms": 10000,
                "resume_enabled": true,
                "resume_timeout_seconds": 60
              },
              "llm": {
                "provider": "ollama",
                "ollama_url": "http://127.0.0.1:11434",
                "openai_base_url": "http://127.0.0.1:1234",
                "api_key": "",
                "model": "phi-3.5-mini-instruct",
                "available_models": ["phi-3.5-mini-instruct"],
                "timeout_ms": 30000,
                "temperature": 0.7,
                "max_tokens": 220,
                "history_turns": 6,
                "system_message": "Du bist ein hilfreicher Discord-Assistent. Antworte kurz, freundlich und auf Deutsch."
              },
              "music_brain": {
                "base_url": "http://127.0.0.1:8091",
                "request_timeout_ms": 15000,
                "batch_size": 12
              }
            }
            """;

    public static int resolveServerPort() {
        int fallbackPort = config.optJSONObject("webinterface") == null
                ? 8080
                : config.optJSONObject("webinterface").optInt("port", 8080);

        JSONObject database = config.optJSONObject("database");
        JSONObject deployment = config.optJSONObject("deployment");
        if (database == null || deployment == null) {
            return fallbackPort;
        }

        String deploymentKey = deployment.optString("key", "").trim();
        String user = database.optString("user", "").trim();
        if (deploymentKey.isBlank() || user.isBlank()) {
            return fallbackPort;
        }

        String dbUrl = "jdbc:mariadb://%s:%s/%s".formatted(
                database.optString("host", "localhost"),
                database.optInt("port", 3306),
                database.optString("name", "discordBot")
        );

        String sql = """
                SELECT web_port
                FROM deployments
                WHERE bot_id = ? AND deployment_key = ? AND enabled = 1
                ORDER BY sort_order, updated_at DESC
                LIMIT 1
                """;

        try (Connection connection = DriverManager.getConnection(
                dbUrl,
                user,
                database.optString("password", "")
        );
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, config.optInt("bot_id", 1));
            statement.setString(2, deploymentKey);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    int resolvedPort = resultSet.getInt("web_port");
                    if (!resultSet.wasNull() && resolvedPort > 0) {
                        return resolvedPort;
                    }
                }
            }
        } catch (SQLException ignored) {
        }

        return fallbackPort;
    }

    private static JSONObject loadConfig() throws IOException {
        Path configPath = Paths.get("config", "config.json");
        String configString;

        if (!Files.exists(configPath)) {
            Files.createDirectories(configPath.getParent());
            Files.createFile(configPath);
            Files.writeString(configPath, DEFAULT_CONFIG);
            configString = DEFAULT_CONFIG;
        } else {
            configString = Files.readString(configPath);
            if (configString.isBlank()) {
                configString = DEFAULT_CONFIG;
            }
        }

        JSONObject loaded = new JSONObject(configString);
        mergeMissingKeys(loaded, new JSONObject(DEFAULT_CONFIG));
        Files.writeString(configPath, loaded.toString(2));
        return loaded;
    }

    private static void mergeMissingKeys(JSONObject target, JSONObject defaults) {
        for (String key : defaults.keySet()) {
            Object defaultValue = defaults.get(key);

            if (!target.has(key)) {
                target.put(key, defaultValue);
                continue;
            }

            Object targetValue = target.get(key);
            if (defaultValue instanceof JSONObject defaultObject && targetValue instanceof JSONObject targetObject) {
                mergeMissingKeys(targetObject, defaultObject);
            }
        }
    }
}
