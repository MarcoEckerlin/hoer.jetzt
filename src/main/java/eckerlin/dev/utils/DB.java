package eckerlin.dev.utils;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

public class DB {

    private static final JSONObject config = Config.config.optJSONObject("database") == null
            ? new JSONObject()
            : Config.config.optJSONObject("database");
    private static final String dbhost = "jdbc:mariadb://%s:%s/%s".formatted(
            config.optString("host", "localhost"),
            config.optInt("port", 3306),
            config.optString("name", "discordBot")
    );
    private static final String dbuser = config.optString("user", "");
    private static final String dbpw = config.optString("password", "");
    private static volatile Boolean available;
    private static volatile HikariDataSource dataSource;

    /**
     * Liefert eine Verbindung aus dem Pool.
     *
     * <p>Vorher stand hier {@code DriverManager.getConnection(...)} - also ein
     * kompletter TCP-Verbindungsaufbau samt MariaDB-Authentifizierung bei
     * <em>jeder einzelnen</em> Abfrage. Da die Datenbank auf einem anderen Host
     * liegt, kostete das gemessen 13 ms im Schnitt und bis zu 64 ms im
     * Spitzenfall. Bei rund zwei Dutzend Aufrufstellen summiert sich das
     * spuerbar: Slash-Commands rissen dadurch Discords Drei-Sekunden-Fenster
     * ("Die Anwendung reagiert nicht") und das Dashboard wirkte traege.
     *
     * <p>Alle Aufrufer nutzen try-with-resources. Deren {@code close()} gibt die
     * Verbindung jetzt an den Pool zurueck, statt sie abzubauen - die Umstellung
     * brauchte daher keine Aenderung an den Aufrufstellen.
     */
    public static Connection connection() throws SQLException {
        HikariDataSource pool = dataSource;
        if (pool == null) {
            synchronized (DB.class) {
                pool = dataSource;
                if (pool == null) {
                    pool = createPool();
                    dataSource = pool;
                }
            }
        }
        return pool.getConnection();
    }

    private static HikariDataSource createPool() {
        HikariConfig poolConfig = new HikariConfig();
        poolConfig.setJdbcUrl(dbhost);
        poolConfig.setUsername(dbuser);
        poolConfig.setPassword(dbpw);
        poolConfig.setPoolName("discordbot-db");

        // Der Bot stellt viele kleine, kurze Abfragen. Ein knappes Dutzend
        // Verbindungen reicht dafuer reichlich aus.
        poolConfig.setMaximumPoolSize(12);
        poolConfig.setMinimumIdle(2);

        // Lieber schnell scheitern als einen Discord-Command haengen lassen.
        poolConfig.setConnectionTimeout(5_000L);
        poolConfig.setValidationTimeout(2_000L);

        // Verbindungen zu einem entfernten Host werden von Firewalls und vom
        // MariaDB-eigenen wait_timeout stillschweigend gekappt. Regelmaessiges
        // Recyceln verhindert, dass tote Verbindungen aus dem Pool kommen.
        poolConfig.setIdleTimeout(120_000L);
        poolConfig.setMaxLifetime(600_000L);
        poolConfig.setKeepaliveTime(60_000L);

        return new HikariDataSource(poolConfig);
    }

    /**
     * Direkte Verbindung am Pool vorbei - nur fuer den Startvorgang, bevor der
     * Pool existiert.
     */
    private static Connection directConnection() throws SQLException {
        return DriverManager.getConnection(dbhost, dbuser, dbpw);
    }

    public static void shutdown() {
        HikariDataSource pool = dataSource;
        if (pool != null) {
            pool.close();
            dataSource = null;
        }
    }

    public static boolean init() {
        if (dbuser.isBlank()) {
            available = false;
            return false;
        }

        // Beim Start bewusst ohne Pool: schlaegt die Verbindung fehl, soll das
        // sofort auffallen und nicht erst einen Pool aufbauen, der ohnehin
        // nichts verbinden kann.
        try (Connection connection = directConnection();
             Statement statement = connection.createStatement()) {

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS logs (
                        type text DEFAULT NULL,
                        module text DEFAULT NULL,
                        value text DEFAULT NULL,
                        timestamp timestamp NULL DEFAULT NULL
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """);

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS settings (
                        id int(11) DEFAULT NULL,
                        token text DEFAULT NULL,
                        activity text DEFAULT NULL,
                        activity_rotation longtext DEFAULT NULL,
                        status text DEFAULT 'IDLE',
                        brand_image_url text DEFAULT NULL,
                        hero_image_url text DEFAULT NULL,
                        maintenance_enabled tinyint(1) DEFAULT NULL,
                        maintenance_message text DEFAULT NULL,
                        legal_owner_name text DEFAULT NULL,
                        legal_email text DEFAULT NULL,
                        legal_address longtext DEFAULT NULL,
                        web_base_url text DEFAULT NULL,
                        no_guild_invite_url text DEFAULT NULL,
                        discord_client_id text DEFAULT NULL,
                        discord_client_secret text DEFAULT NULL,
                        redirect_uri text DEFAULT NULL,
                        admin_user_ids longtext DEFAULT NULL,
                        llm_provider text DEFAULT NULL,
                        llm_ollama_url text DEFAULT NULL,
                        llm_openai_base_url text DEFAULT NULL,
                        llm_api_key text DEFAULT NULL,
                        llm_model text DEFAULT NULL,
                        llm_available_models longtext DEFAULT NULL,
                        llm_timeout_ms int(11) DEFAULT NULL,
                        llm_temperature double DEFAULT NULL,
                        llm_max_tokens int(11) DEFAULT NULL,
                        llm_history_turns int(11) DEFAULT NULL,
                        llm_system_message longtext DEFAULT NULL,
                        created_at timestamp NULL DEFAULT current_timestamp(),
                        updated_at timestamp NULL DEFAULT current_timestamp() ON UPDATE current_timestamp()
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
                    """);

            statement.executeUpdate("ALTER TABLE settings ADD COLUMN IF NOT EXISTS activity_rotation longtext DEFAULT NULL");
            statement.executeUpdate("ALTER TABLE settings ADD COLUMN IF NOT EXISTS brand_image_url text DEFAULT NULL");
            statement.executeUpdate("ALTER TABLE settings ADD COLUMN IF NOT EXISTS hero_image_url text DEFAULT NULL");
            statement.executeUpdate("ALTER TABLE settings ADD COLUMN IF NOT EXISTS maintenance_enabled tinyint(1) DEFAULT NULL");
            statement.executeUpdate("ALTER TABLE settings ADD COLUMN IF NOT EXISTS maintenance_message text DEFAULT NULL");
            statement.executeUpdate("ALTER TABLE settings ADD COLUMN IF NOT EXISTS legal_owner_name text DEFAULT NULL");
            statement.executeUpdate("ALTER TABLE settings ADD COLUMN IF NOT EXISTS legal_email text DEFAULT NULL");
            statement.executeUpdate("ALTER TABLE settings ADD COLUMN IF NOT EXISTS legal_address longtext DEFAULT NULL");
            statement.executeUpdate("ALTER TABLE settings ADD COLUMN IF NOT EXISTS web_base_url text DEFAULT NULL");
            statement.executeUpdate("ALTER TABLE settings ADD COLUMN IF NOT EXISTS no_guild_invite_url text DEFAULT NULL");
            statement.executeUpdate("ALTER TABLE settings ADD COLUMN IF NOT EXISTS discord_client_id text DEFAULT NULL");
            statement.executeUpdate("ALTER TABLE settings ADD COLUMN IF NOT EXISTS discord_client_secret text DEFAULT NULL");
            statement.executeUpdate("ALTER TABLE settings ADD COLUMN IF NOT EXISTS redirect_uri text DEFAULT NULL");
            statement.executeUpdate("ALTER TABLE settings ADD COLUMN IF NOT EXISTS admin_user_ids longtext DEFAULT NULL");
            statement.executeUpdate("ALTER TABLE settings ADD COLUMN IF NOT EXISTS llm_provider text DEFAULT NULL");
            statement.executeUpdate("ALTER TABLE settings ADD COLUMN IF NOT EXISTS llm_ollama_url text DEFAULT NULL");
            statement.executeUpdate("ALTER TABLE settings ADD COLUMN IF NOT EXISTS llm_openai_base_url text DEFAULT NULL");
            statement.executeUpdate("ALTER TABLE settings ADD COLUMN IF NOT EXISTS llm_api_key text DEFAULT NULL");
            statement.executeUpdate("ALTER TABLE settings ADD COLUMN IF NOT EXISTS llm_model text DEFAULT NULL");
            statement.executeUpdate("ALTER TABLE settings ADD COLUMN IF NOT EXISTS llm_available_models longtext DEFAULT NULL");
            statement.executeUpdate("ALTER TABLE settings ADD COLUMN IF NOT EXISTS llm_timeout_ms int(11) DEFAULT NULL");
            statement.executeUpdate("ALTER TABLE settings ADD COLUMN IF NOT EXISTS llm_temperature double DEFAULT NULL");
            statement.executeUpdate("ALTER TABLE settings ADD COLUMN IF NOT EXISTS llm_max_tokens int(11) DEFAULT NULL");
            statement.executeUpdate("ALTER TABLE settings ADD COLUMN IF NOT EXISTS llm_history_turns int(11) DEFAULT NULL");
            statement.executeUpdate("ALTER TABLE settings ADD COLUMN IF NOT EXISTS llm_system_message longtext DEFAULT NULL");
            statement.executeUpdate("ALTER TABLE settings ADD COLUMN IF NOT EXISTS created_at timestamp NULL DEFAULT current_timestamp()");
            statement.executeUpdate("ALTER TABLE settings ADD COLUMN IF NOT EXISTS updated_at timestamp NULL DEFAULT current_timestamp() ON UPDATE current_timestamp()");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_settings_id ON settings (id)");

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS guild_module_settings (
                        bot_id int(11) NOT NULL,
                        guild_id varchar(32) NOT NULL,
                        settings_json longtext NOT NULL,
                        updated_at timestamp NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
                        PRIMARY KEY (bot_id, guild_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
                    """);

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS deployments (
                        id bigint(20) NOT NULL AUTO_INCREMENT,
                        bot_id int(11) NOT NULL,
                        deployment_key varchar(120) NOT NULL,
                        display_name varchar(160) DEFAULT NULL,
                        web_port int(11) DEFAULT NULL,
                        base_url text DEFAULT NULL,
                        redirect_uri text DEFAULT NULL,
                        enabled tinyint(1) NOT NULL DEFAULT 1,
                        sort_order int(11) NOT NULL DEFAULT 0,
                        created_at timestamp NULL DEFAULT current_timestamp(),
                        updated_at timestamp NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
                        PRIMARY KEY (id),
                        UNIQUE KEY uq_bot_deployment (bot_id, deployment_key)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
                    """);

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS deployment_lavalink_nodes (
                        id bigint(20) NOT NULL AUTO_INCREMENT,
                        bot_id int(11) NOT NULL,
                        deployment_key varchar(120) NOT NULL,
                        node_name varchar(160) DEFAULT NULL,
                        server_uri text NOT NULL,
                        password text DEFAULT NULL,
                        http_timeout_ms int(11) NOT NULL DEFAULT 10000,
                        resume_enabled tinyint(1) NOT NULL DEFAULT 1,
                        resume_timeout_seconds bigint(20) NOT NULL DEFAULT 60,
                        enabled tinyint(1) NOT NULL DEFAULT 1,
                        sort_order int(11) NOT NULL DEFAULT 0,
                        created_at timestamp NULL DEFAULT current_timestamp(),
                        updated_at timestamp NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
                        PRIMARY KEY (id),
                        KEY idx_deployment_nodes (bot_id, deployment_key, enabled, sort_order)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
                    """);

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS ticket_transcripts (
                        id bigint(20) NOT NULL AUTO_INCREMENT,
                        bot_id int(11) NOT NULL,
                        guild_id varchar(32) NOT NULL,
                        channel_id varchar(32) DEFAULT NULL,
                        opener_user_id varchar(32) DEFAULT NULL,
                        opener_display varchar(160) DEFAULT NULL,
                        ticket_subject varchar(190) DEFAULT NULL,
                        transcript_text longtext NOT NULL,
                        created_at timestamp NULL DEFAULT current_timestamp(),
                        PRIMARY KEY (id),
                        KEY idx_ticket_transcripts_guild (bot_id, guild_id, created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
                    """);

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS music_track_events (
                        id bigint(20) NOT NULL AUTO_INCREMENT,
                        bot_id int(11) NOT NULL,
                        guild_id varchar(32) NOT NULL,
                        title varchar(255) NOT NULL,
                        author varchar(255) DEFAULT NULL,
                        uri longtext DEFAULT NULL,
                        identifier varchar(255) DEFAULT NULL,
                        source_name varchar(80) DEFAULT NULL,
                        duration_ms bigint(20) DEFAULT NULL,
                        is_stream tinyint(1) NOT NULL DEFAULT 0,
                        created_at timestamp NULL DEFAULT current_timestamp(),
                        PRIMARY KEY (id),
                        KEY idx_music_track_events_guild (bot_id, guild_id, created_at),
                        KEY idx_music_track_events_lookup (bot_id, guild_id, is_stream, created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
                    """);

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS uploaded_assets (
                        asset_id varchar(64) NOT NULL,
                        bot_id int(11) NOT NULL,
                        created_by_user_id varchar(32) DEFAULT NULL,
                        original_name varchar(255) DEFAULT NULL,
                        content_type varchar(120) NOT NULL,
                        base64_data longtext NOT NULL,
                        size_bytes int(11) NOT NULL DEFAULT 0,
                        created_at timestamp NULL DEFAULT current_timestamp(),
                        PRIMARY KEY (asset_id),
                        KEY idx_uploaded_assets_bot (bot_id, created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
                    """);

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS music_listener_events (
                        id bigint(20) NOT NULL AUTO_INCREMENT,
                        bot_id int(11) NOT NULL,
                        guild_id varchar(32) NOT NULL,
                        listener_hash varchar(64) NOT NULL,
                        playback_kind varchar(32) NOT NULL,
                        title varchar(255) NOT NULL,
                        author varchar(255) DEFAULT NULL,
                        identifier varchar(255) DEFAULT NULL,
                        source_label varchar(190) DEFAULT NULL,
                        listened_seconds int(11) NOT NULL DEFAULT 0,
                        is_stream tinyint(1) NOT NULL DEFAULT 0,
                        started_at timestamp NULL DEFAULT current_timestamp(),
                        ended_at timestamp NULL DEFAULT current_timestamp(),
                        PRIMARY KEY (id),
                        KEY idx_listener_events_time (bot_id, started_at),
                        KEY idx_listener_events_lookup (bot_id, guild_id, playback_kind, started_at),
                        KEY idx_listener_events_listener (bot_id, listener_hash, started_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
                    """);

            // ----------------------------------------------------------------
            // Rechte, Bot-Verwaltung und Freischaltungen
            //
            // Bewusst eigene Tabellen statt weiterer Spalten in "settings":
            // es handelt sich um n:m-Beziehungen (Rolle zu Recht, Server zu
            // Funktion), die sich in einer Freitextspalte nicht sauber
            // abfragen liessen. Die alte Spalte settings.admin_user_ids bleibt
            // bestehen und wird beim ersten Start nach bot_admins uebernommen.
            // ----------------------------------------------------------------

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS bot_admins (
                        bot_id int(11) NOT NULL,
                        user_id varchar(32) NOT NULL,
                        role varchar(16) NOT NULL DEFAULT 'ADMIN',
                        display_name varchar(160) DEFAULT NULL,
                        added_by varchar(32) DEFAULT NULL,
                        application_owner tinyint(1) NOT NULL DEFAULT 0,
                        created_at timestamp NULL DEFAULT current_timestamp(),
                        updated_at timestamp NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
                        PRIMARY KEY (bot_id, user_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
                    """);
            statement.executeUpdate("ALTER TABLE bot_admins ADD COLUMN IF NOT EXISTS application_owner tinyint(1) NOT NULL DEFAULT 0");

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS guild_role_permissions (
                        bot_id int(11) NOT NULL,
                        guild_id varchar(32) NOT NULL,
                        role_id varchar(32) NOT NULL,
                        permission varchar(40) NOT NULL,
                        created_at timestamp NULL DEFAULT current_timestamp(),
                        PRIMARY KEY (bot_id, guild_id, role_id, permission),
                        KEY idx_guild_role_permissions (bot_id, guild_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
                    """);

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS guild_entitlements (
                        bot_id int(11) NOT NULL,
                        guild_id varchar(32) NOT NULL,
                        feature varchar(40) NOT NULL,
                        enabled tinyint(1) NOT NULL DEFAULT 0,
                        daily_limit int(11) NOT NULL DEFAULT 0,
                        note varchar(255) DEFAULT NULL,
                        granted_by varchar(32) DEFAULT NULL,
                        created_at timestamp NULL DEFAULT current_timestamp(),
                        updated_at timestamp NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
                        PRIMARY KEY (bot_id, guild_id, feature)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
                    """);

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS guild_feature_usage (
                        bot_id int(11) NOT NULL,
                        guild_id varchar(32) NOT NULL,
                        feature varchar(40) NOT NULL,
                        usage_day date NOT NULL,
                        used_count int(11) NOT NULL DEFAULT 0,
                        PRIMARY KEY (bot_id, guild_id, feature, usage_day),
                        KEY idx_guild_feature_usage_day (bot_id, usage_day)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
                    """);

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS admin_audit_log (
                        id bigint(20) NOT NULL AUTO_INCREMENT,
                        bot_id int(11) NOT NULL,
                        actor_user_id varchar(32) DEFAULT NULL,
                        actor_name varchar(160) DEFAULT NULL,
                        action varchar(80) NOT NULL,
                        target_type varchar(40) DEFAULT NULL,
                        target_id varchar(64) DEFAULT NULL,
                        details text DEFAULT NULL,
                        created_at timestamp NULL DEFAULT current_timestamp(),
                        PRIMARY KEY (id),
                        KEY idx_admin_audit_log_time (bot_id, created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
                    """);

            seedSettingsIfMissing(connection);
            seedDeploymentIfMissing(connection);
            seedLavalinkNodeIfMissing(connection);

            available = true;
            return true;
        } catch (SQLException exception) {
            available = false;
            return false;
        }
    }

    public static boolean isAvailable() {
        if (available != null) {
            return available;
        }

        try (Connection ignored = connection()) {
            available = true;
            return true;
        } catch (SQLException exception) {
            available = false;
            return false;
        }
    }

    public static void logs(String type, String module, String value) {
        String logSql = "INSERT INTO logs (type, module, value, timestamp) VALUES (?,?,?,?)";
        LocalDateTime time = LocalDateTime.now();

        if (!isAvailable()) {
            System.out.println(Alert.formatLogLine(type, module, value, -1, -1));
            return;
        }

        try (Connection conn = connection();
             PreparedStatement logs = conn.prepareStatement(logSql)) {
            logs.setString(1, type);
            logs.setString(2, module);
            logs.setString(3, value);
            logs.setTimestamp(4, Timestamp.valueOf(time));

            long t0 = System.nanoTime();
            int rows = logs.executeUpdate();
            long ms = (System.nanoTime() - t0) / 1_000_000;

            System.out.println(Alert.formatLogLine(type, module, value, rows, ms));
        } catch (SQLException exception) {
            System.out.println(Alert.formatLogLine(type, module, value, -1, -1));
        }
    }

    private static void seedSettingsIfMissing(Connection connection) throws SQLException {
        JSONObject root = Config.config;
        JSONObject bot = root.optJSONObject("bot") == null ? new JSONObject() : root.optJSONObject("bot");
        JSONObject web = root.optJSONObject("webinterface") == null ? new JSONObject() : root.optJSONObject("webinterface");
        JSONObject llm = root.optJSONObject("llm") == null ? new JSONObject() : root.optJSONObject("llm");
        int botId = root.optInt("bot_id", 1);

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO settings (
                    id,
                    token,
                    activity,
                    activity_rotation,
                    status,
                    brand_image_url,
                    hero_image_url,
                    maintenance_enabled,
                    maintenance_message,
                    legal_owner_name,
                    legal_email,
                    legal_address,
                    web_base_url,
                    discord_client_id,
                    discord_client_secret,
                    redirect_uri,
                    llm_provider,
                    llm_ollama_url,
                    llm_openai_base_url,
                    llm_api_key,
                    llm_model,
                    llm_available_models,
                    llm_timeout_ms,
                    llm_temperature,
                    llm_max_tokens,
                    llm_history_turns,
                    llm_system_message
                )
                SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?
                WHERE NOT EXISTS (SELECT 1 FROM settings WHERE id = ?)
                """)) {
            String availableModels = defaultLlmAvailableModels(llm);
            statement.setInt(1, botId);
            statement.setString(2, bot.optString("token", ""));
            statement.setString(3, bot.optString("activity", ""));
            statement.setString(4, "");
            statement.setString(5, bot.optString("status", "IDLE"));
            statement.setString(6, "");
            statement.setString(7, "");
            statement.setNull(8, java.sql.Types.BOOLEAN);
            statement.setString(9, "");
            statement.setString(10, "");
            statement.setString(11, "");
            statement.setString(12, "");
            statement.setString(13, web.optString("base_url", ""));
            statement.setString(14, web.optString("discord_client_id", ""));
            statement.setString(15, web.optString("discord_client_secret", ""));
            statement.setString(16, web.optString("redirect_uri", ""));
            statement.setString(17, llm.optString("provider", "ollama"));
            statement.setString(18, llm.optString("ollama_url", "http://127.0.0.1:11434"));
            statement.setString(19, llm.optString("openai_base_url", "http://127.0.0.1:1234"));
            statement.setString(20, llm.optString("api_key", ""));
            statement.setString(21, llm.optString("model", "phi-3.5-mini-instruct"));
            statement.setString(22, availableModels);
            statement.setInt(23, llm.optInt("timeout_ms", 30000));
            statement.setDouble(24, llm.optDouble("temperature", 0.7d));
            statement.setInt(25, llm.optInt("max_tokens", 220));
            statement.setInt(26, llm.optInt("history_turns", 6));
            statement.setString(27, llm.optString("system_message", ""));
            statement.setInt(28, botId);
            statement.executeUpdate();
        }
    }

    private static void seedDeploymentIfMissing(Connection connection) throws SQLException {
        JSONObject root = Config.config;
        JSONObject deployment = root.optJSONObject("deployment") == null ? new JSONObject() : root.optJSONObject("deployment");
        JSONObject web = root.optJSONObject("webinterface") == null ? new JSONObject() : root.optJSONObject("webinterface");
        int botId = root.optInt("bot_id", 1);
        String deploymentKey = deployment.optString("key", "local").trim();

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO deployments (
                    bot_id,
                    deployment_key,
                    display_name,
                    web_port,
                    base_url,
                    redirect_uri,
                    enabled,
                    sort_order
                )
                SELECT ?,?,?,?,?,?,?,?
                WHERE NOT EXISTS (
                    SELECT 1 FROM deployments WHERE bot_id = ? AND deployment_key = ?
                )
                """)) {
            statement.setInt(1, botId);
            statement.setString(2, deploymentKey.isBlank() ? "local" : deploymentKey);
            statement.setString(3, deployment.optString("display_name", "Lokale Instanz"));
            statement.setInt(4, web.optInt("port", 8080));
            statement.setString(5, web.optString("base_url", ""));
            statement.setString(6, web.optString("redirect_uri", ""));
            statement.setBoolean(7, true);
            statement.setInt(8, 0);
            statement.setInt(9, botId);
            statement.setString(10, deploymentKey.isBlank() ? "local" : deploymentKey);
            statement.executeUpdate();
        }
    }

    private static void seedLavalinkNodeIfMissing(Connection connection) throws SQLException {
        JSONObject root = Config.config;
        JSONObject deployment = root.optJSONObject("deployment") == null ? new JSONObject() : root.optJSONObject("deployment");
        JSONObject lavalink = root.optJSONObject("lavalink") == null ? new JSONObject() : root.optJSONObject("lavalink");
        int botId = root.optInt("bot_id", 1);
        String deploymentKey = deployment.optString("key", "local").trim();

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO deployment_lavalink_nodes (
                    bot_id,
                    deployment_key,
                    node_name,
                    server_uri,
                    password,
                    http_timeout_ms,
                    resume_enabled,
                    resume_timeout_seconds,
                    enabled,
                    sort_order
                )
                SELECT ?,?,?,?,?,?,?,?,?,?
                WHERE NOT EXISTS (
                    SELECT 1 FROM deployment_lavalink_nodes WHERE bot_id = ? AND deployment_key = ?
                )
                """)) {
            statement.setInt(1, botId);
            statement.setString(2, deploymentKey.isBlank() ? "local" : deploymentKey);
            statement.setString(3, lavalink.optString("name", "main-node"));
            statement.setString(4, lavalink.optString("uri", "http://127.0.0.1:2333"));
            statement.setString(5, lavalink.optString("password", "youshallnotpass"));
            statement.setInt(6, lavalink.optInt("http_timeout_ms", 10000));
            statement.setBoolean(7, lavalink.optBoolean("resume_enabled", true));
            statement.setLong(8, lavalink.optLong("resume_timeout_seconds", 60L));
            statement.setBoolean(9, true);
            statement.setInt(10, 0);
            statement.setInt(11, botId);
            statement.setString(12, deploymentKey.isBlank() ? "local" : deploymentKey);
            statement.executeUpdate();
        }
    }

    private static String defaultLlmAvailableModels(JSONObject llm) {
        Set<String> models = new LinkedHashSet<>();
        JSONArray availableModels = llm.optJSONArray("available_models");
        if (availableModels != null) {
            availableModels.forEach(value -> addModel(models, String.valueOf(value)));
        }
        addModels(models, llm.optString("available_models", ""));
        addModel(models, llm.optString("model", "phi-3.5-mini-instruct"));
        return String.join("\n", models);
    }

    private static void addModels(Set<String> models, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }

        Arrays.stream(raw.split("[,;\\r\\n]+"))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .forEach(value -> addModel(models, value));
    }

    private static void addModel(Set<String> models, String model) {
        if (models == null || model == null) {
            return;
        }

        String trimmed = model.trim();
        if (!trimmed.isBlank()) {
            models.add(trimmed);
        }
    }
}
