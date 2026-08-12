package eckerlin.dev.musicbrain;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Config {

    public static final JSONObject config;

    private static final String DEFAULT_CONFIG = """
            {
              "database": {
                "host": "127.0.0.1",
                "port": 3306,
                "name": "discordbot",
                "user": "discordbot",
                "password": ""
              },
              "bot_id": 1,
              "listen_host": "127.0.0.1",
              "port": 8091,
              "history_days": 45,
              "batch_size": 12,
              "ollama": {
                "enabled": true,
                "url": "http://127.0.0.1:11434",
                "model": "krith/phi-3.5-mini-instruct:IQ3_M",
                "timeout_ms": 30000
              }
            }
            """;

    static {
        try {
            config = loadConfig();
        } catch (IOException exception) {
            throw new RuntimeException("Config konnte nicht geladen werden.", exception);
        }
    }

    private static JSONObject loadConfig() throws IOException {
        Path configPath = Paths.get("config", "config.json");
        String configString;

        if (!Files.exists(configPath)) {
            Files.createDirectories(configPath.getParent());
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
