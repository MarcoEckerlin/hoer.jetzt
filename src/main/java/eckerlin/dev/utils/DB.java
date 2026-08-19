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
    private static final String dbhost = "jdbc:postgresql://%s:%s/%s".formatted(
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
    /**
     * Der Verbindungspool selbst.
     *
     * <p>Wird von der Sitzungsablage gebraucht: Spring Session will eine
     * {@link javax.sql.DataSource}, kein einzelnes {@link Connection}. Bewusst
     * derselbe Pool und kein zweiter - zwei Pools auf dieselbe Datenbank
     * verdoppeln die offenen Verbindungen, und die Obergrenze setzt
     * PostgreSQL, nicht wir.</p>
     */
    public static javax.sql.DataSource pool() {
        HikariDataSource vorhanden = dataSource;
        if (vorhanden == null) {
            synchronized (DB.class) {
                vorhanden = dataSource;
                if (vorhanden == null) {
                    vorhanden = createPool();
                    dataSource = vorhanden;
                }
            }
        }
        return vorhanden;
    }

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

            schemaAnwenden(statement);

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

    /**
     * Legt das Schema an - Anweisung fuer Anweisung aus einer Datei.
     *
     * <p>Frueher standen die sechzehn Tabellen als Textbloecke mitten in dieser
     * Methode, im MariaDB-Dialekt. Als eigene Datei laesst sich das Schema mit
     * gewoehnlichen Werkzeugen gegenlesen und pruefen, ohne Java zu uebersetzen -
     * und beim Wechsel auf PostgreSQL war genau das der Unterschied zwischen
     * "wir hoffen mal" und "der Parser von PostgreSQL hat es abgenommen".</p>
     *
     * <p>Jede Anweisung ist beliebig oft wiederholbar ({@code IF NOT EXISTS}).
     * Eine Version zu ueberspringen ist deshalb unproblematisch: es gibt keine
     * Kette, die in Reihenfolge laufen muesste.</p>
     */
    private static void schemaAnwenden(Statement statement) throws SQLException {
        String schema;
        try (var strom = DB.class.getResourceAsStream("/db/schema-postgres.sql")) {
            if (strom == null) {
                throw new SQLException("schema-postgres.sql fehlt im Abbild.");
            }
            schema = new String(strom.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException exception) {
            throw new SQLException("Schema konnte nicht gelesen werden: " + exception.getMessage(), exception);
        }

        // Der Zahlenraum je Node - siehe Kommentar in der Schema-Datei.
        String nodeNr = System.getenv("HJ_NODE_NR");
        schema = schema.replace("${HJ_NODE_NR}", nodeNr == null || nodeNr.isBlank() ? "1" : nodeNr.trim());

        int angewandt = 0;
        for (String anweisung : anweisungenTrennen(schema)) {
            String sauber = anweisung.strip();
            if (sauber.isEmpty() || sauber.lines().allMatch(z -> z.strip().isEmpty() || z.strip().startsWith("--"))) {
                continue;
            }
            statement.executeUpdate(sauber);
            angewandt++;
        }
        System.out.println("[DB] Schema geprueft: " + angewandt + " Anweisungen.");
    }

    /**
     * Zerlegt das Schema in einzelne Anweisungen.
     *
     * <p>Vorher stand hier {@code schema.split(";")}. Das reicht, solange jede
     * Anweisung einzeilig endet - aber nicht mehr, sobald eine davon einen
     * {@code DO $$ ... $$}-Block enthaelt: der hat innen Semikolons, und der
     * Block waere mitten im Rumpf zerschnitten worden. Gebraucht wird er fuer
     * bedingtes DDL, denn PostgreSQL kennt kein
     * "ALTER TABLE ... ADD PRIMARY KEY IF NOT EXISTS" - und ohne Bedingung
     * scheitert das Schema beim zweiten Start.
     *
     * <p>Semikolons in Zeichenketten werden ebenfalls uebergangen. Das ist
     * heute nicht noetig, kostet aber drei Zeilen und erspart die naechste
     * Suche nach einem Syntaxfehler, den es gar nicht gibt.</p>
     */
    private static java.util.List<String> anweisungenTrennen(String schema) {
        java.util.List<String> anweisungen = new java.util.ArrayList<>();
        StringBuilder aktuell = new StringBuilder();

        boolean inZeichenkette = false;
        boolean inDollarBlock = false;

        for (int i = 0; i < schema.length(); i++) {
            char zeichen = schema.charAt(i);

            if (!inZeichenkette && zeichen == '$' && i + 1 < schema.length() && schema.charAt(i + 1) == '$') {
                inDollarBlock = !inDollarBlock;
                aktuell.append("$$");
                i++;
                continue;
            }

            if (!inDollarBlock && zeichen == '\'') {
                inZeichenkette = !inZeichenkette;
            }

            if (zeichen == ';' && !inZeichenkette && !inDollarBlock) {
                anweisungen.add(aktuell.toString());
                aktuell.setLength(0);
                continue;
            }

            aktuell.append(zeichen);
        }

        anweisungen.add(aktuell.toString());
        return anweisungen;
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

    /**
     * Wie die Maschine heisst, auf der dieser Prozess laeuft.
     *
     * <p>Im Container ist der Rechnername die Container-Kennung und aendert
     * sich bei jedem Neustart - deshalb zuerst {@code HJ_NODE_NAME}, das
     * Compose setzt.</p>
     */
    private static String maschinenname() {
        String ausUmgebung = System.getenv("HJ_NODE_NAME");
        if (ausUmgebung != null && !ausUmgebung.isBlank()) {
            return ausUmgebung.trim();
        }
        try {
            String rechner = java.net.InetAddress.getLocalHost().getHostName();
            return rechner == null || rechner.isBlank() ? "unbenannt" : rechner.split("\\.")[0];
        } catch (Exception nichtErmittelbar) {
            return "unbenannt";
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
            // Kein erfundener Name: wenn keiner gepflegt ist, heisst die Instanz wie
            // die Maschine, auf der sie laeuft. "Lokale Instanz" stand auf jeder
            // Node gleich und sagte damit genau nichts - im Verbund war nicht zu
            // erkennen, welche gemeint war.
            String anzeige = deployment.optString("display_name", "").trim();
            statement.setString(3, anzeige.isEmpty() ? maschinenname() : anzeige);
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

    /**
     * Legt beim allerersten Start einen Audio-Knoten aus der Konfiguration an.
     *
     * <p>Gemeint ist der Fall "frische Einzelinstallation": ohne Knoten gaebe
     * es keine Musik, und der Grund waere aus der Meldung nicht zu erraten.</p>
     *
     * <p>Die Bedingung fragt bewusst nur nach {@code bot_id} und nicht mehr
     * zusaetzlich nach dem Deployment-Schluessel. Der Unterschied ist im
     * Verbund entscheidend: die Knoten melden sich selbst an, und die Adresse
     * aus der Konfiguration ist der Docker-interne Name
     * {@code http://lavalink-free-1:2333}. In einer geteilten Datenbank ist das
     * eine Adresse, die auf <em>jeder</em> Node auf deren eigenes Lavalink
     * zeigt - zwei Maschinen, ein Name, und der Lastverteiler haelt sie fuer
     * einen dritten Knoten. Mit dem Schluessel in der Bedingung kam dieser
     * Eintrag bei jedem Start zurueck, auch wenn man ihn geloescht hatte.</p>
     */
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
                    SELECT 1 FROM deployment_lavalink_nodes WHERE bot_id = ?
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
