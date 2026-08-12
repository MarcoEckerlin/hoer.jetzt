package eckerlin.dev.services;

import eckerlin.dev.utils.Config;
import eckerlin.dev.utils.DB;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class DatabaseSettingsService {

    private final int botId = Config.config.optInt("bot_id", 1);

    public int getBotId() {
        return botId;
    }

    public Optional<StoredSettings> loadSettings() {
        if (!DB.isAvailable()) {
            return Optional.empty();
        }

        String sql = """
                SELECT id,
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
                       no_guild_invite_url,
                       discord_client_id,
                       discord_client_secret,
                       redirect_uri,
                       admin_user_ids,
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
                FROM settings
                WHERE id = ?
                LIMIT 1
                """;

        try (Connection connection = DB.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, botId);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }

                return Optional.of(new StoredSettings(
                        resultSet.getInt("id"),
                        text(resultSet, "token"),
                        text(resultSet, "activity"),
                        text(resultSet, "activity_rotation"),
                        text(resultSet, "status"),
                        text(resultSet, "brand_image_url"),
                        text(resultSet, "hero_image_url"),
                        bool(resultSet, "maintenance_enabled"),
                        text(resultSet, "maintenance_message"),
                        text(resultSet, "legal_owner_name"),
                        text(resultSet, "legal_email"),
                        text(resultSet, "legal_address"),
                        text(resultSet, "web_base_url"),
                        text(resultSet, "no_guild_invite_url"),
                        text(resultSet, "discord_client_id"),
                        text(resultSet, "discord_client_secret"),
                        text(resultSet, "redirect_uri"),
                        text(resultSet, "admin_user_ids"),
                        text(resultSet, "llm_provider"),
                        text(resultSet, "llm_ollama_url"),
                        text(resultSet, "llm_openai_base_url"),
                        text(resultSet, "llm_api_key"),
                        text(resultSet, "llm_model"),
                        text(resultSet, "llm_available_models"),
                        integer(resultSet, "llm_timeout_ms"),
                        decimal(resultSet, "llm_temperature"),
                        integer(resultSet, "llm_max_tokens"),
                        integer(resultSet, "llm_history_turns"),
                        text(resultSet, "llm_system_message")
                ));
            }
        } catch (SQLException exception) {
            return Optional.empty();
        }
    }

    public List<DeploymentSettings> loadDeployments() {
        if (!DB.isAvailable()) {
            return List.of();
        }

        String sql = """
                SELECT deployment_key,
                       display_name,
                       web_port,
                       base_url,
                       redirect_uri,
                       enabled,
                       sort_order
                FROM deployments
                WHERE bot_id = ?
                ORDER BY sort_order, deployment_key
                """;

        List<DeploymentSettings> deployments = new ArrayList<>();
        try (Connection connection = DB.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, botId);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    deployments.add(new DeploymentSettings(
                            text(resultSet, "deployment_key"),
                            text(resultSet, "display_name"),
                            integer(resultSet, "web_port"),
                            text(resultSet, "base_url"),
                            text(resultSet, "redirect_uri"),
                            resultSet.getBoolean("enabled"),
                            resultSet.getInt("sort_order")
                    ));
                }
            }
        } catch (SQLException exception) {
            return List.of();
        }

        return deployments;
    }

    public Optional<DeploymentSettings> loadDeployment(String deploymentKey) {
        if (!DB.isAvailable() || deploymentKey == null || deploymentKey.isBlank()) {
            return Optional.empty();
        }

        String sql = """
                SELECT deployment_key,
                       display_name,
                       web_port,
                       base_url,
                       redirect_uri,
                       enabled,
                       sort_order
                FROM deployments
                WHERE bot_id = ? AND deployment_key = ? AND enabled = 1
                ORDER BY sort_order, deployment_key
                LIMIT 1
                """;

        try (Connection connection = DB.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, botId);
            statement.setString(2, deploymentKey.trim());

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }

                return Optional.of(new DeploymentSettings(
                        text(resultSet, "deployment_key"),
                        text(resultSet, "display_name"),
                        integer(resultSet, "web_port"),
                        text(resultSet, "base_url"),
                        text(resultSet, "redirect_uri"),
                        resultSet.getBoolean("enabled"),
                        resultSet.getInt("sort_order")
                ));
            }
        } catch (SQLException exception) {
            return Optional.empty();
        }
    }

    public List<LavalinkNodeSettings> loadDeploymentNodes() {
        if (!DB.isAvailable()) {
            return List.of();
        }

        String sql = """
                SELECT id,
                       deployment_key,
                       node_name,
                       server_uri,
                       password,
                       http_timeout_ms,
                       resume_enabled,
                       resume_timeout_seconds,
                       enabled
                FROM deployment_lavalink_nodes
                WHERE bot_id = ?
                ORDER BY deployment_key, id
                """;

        // Je Deployment wird nur der erste Eintrag uebernommen. Aeltere
        // Installationen koennen noch mehrere Zeilen enthalten; die
        // Oberflaeche zeigt seit der Umstellung auf einen Node pro Deployment
        // nur noch einen davon.
        Map<String, LavalinkNodeSettings> nodesByDeployment = new LinkedHashMap<>();
        try (Connection connection = DB.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, botId);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String deploymentKey = text(resultSet, "deployment_key");
                    if (nodesByDeployment.containsKey(deploymentKey)) {
                        continue;
                    }

                    nodesByDeployment.put(deploymentKey, new LavalinkNodeSettings(
                            resultSet.getLong("id"),
                            deploymentKey,
                            text(resultSet, "node_name"),
                            text(resultSet, "server_uri"),
                            text(resultSet, "password"),
                            Math.max(1000, resultSet.getInt("http_timeout_ms")),
                            resultSet.getBoolean("resume_enabled"),
                            Math.max(10, resultSet.getLong("resume_timeout_seconds")),
                            resultSet.getBoolean("enabled")
                    ));
                }
            }
        } catch (SQLException exception) {
            return List.of();
        }

        return List.copyOf(nodesByDeployment.values());
    }

    /**
     * Laedt den aktiven Lavalink-Node eines Deployments.
     *
     * <p>Es wird bewusst nur ein einziger Datensatz gelesen. Altbestaende mit
     * mehreren Zeilen je Deployment bleiben in der Tabelle liegen und werden
     * ignoriert - so ist keine Migration noetig.
     */
    public Optional<LavalinkNodeSettings> loadDeploymentNode(String deploymentKey) {
        if (!DB.isAvailable() || deploymentKey == null || deploymentKey.isBlank()) {
            return Optional.empty();
        }

        String sql = """
                SELECT id,
                       deployment_key,
                       node_name,
                       server_uri,
                       password,
                       http_timeout_ms,
                       resume_enabled,
                       resume_timeout_seconds,
                       enabled
                FROM deployment_lavalink_nodes
                WHERE bot_id = ? AND deployment_key = ? AND enabled = 1
                ORDER BY id
                LIMIT 1
                """;

        try (Connection connection = DB.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, botId);
            statement.setString(2, deploymentKey.trim());

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }

                return Optional.of(new LavalinkNodeSettings(
                        resultSet.getLong("id"),
                        text(resultSet, "deployment_key"),
                        text(resultSet, "node_name"),
                        text(resultSet, "server_uri"),
                        text(resultSet, "password"),
                        Math.max(1000, resultSet.getInt("http_timeout_ms")),
                        resultSet.getBoolean("resume_enabled"),
                        Math.max(10, resultSet.getLong("resume_timeout_seconds")),
                        resultSet.getBoolean("enabled")
                ));
            }
        } catch (SQLException exception) {
            return Optional.empty();
        }
    }

    public void saveAll(
            StoredSettings settings,
            List<DeploymentSettings> deployments,
            List<LavalinkNodeSettings> nodes
    ) throws SQLException {
        if (!DB.isAvailable()) {
            throw new SQLException("Datenbank ist nicht verfuegbar.");
        }

        try (Connection connection = DB.connection()) {
            connection.setAutoCommit(false);
            try {
                saveSettings(connection, settings);
                replaceDeployments(connection, deployments == null ? List.of() : deployments);
                replaceNodes(connection, nodes == null ? List.of() : nodes);
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private void saveSettings(Connection connection, StoredSettings settings) throws SQLException {
        String updateSql = """
                UPDATE settings
                SET token = ?,
                    activity = ?,
                    activity_rotation = ?,
                    status = ?,
                    brand_image_url = ?,
                    hero_image_url = ?,
                    maintenance_enabled = ?,
                    maintenance_message = ?,
                    legal_owner_name = ?,
                    legal_email = ?,
                    legal_address = ?,
                    web_base_url = ?,
                    no_guild_invite_url = ?,
                    discord_client_id = ?,
                    discord_client_secret = ?,
                    redirect_uri = ?,
                    admin_user_ids = ?,
                    llm_provider = ?,
                    llm_ollama_url = ?,
                    llm_openai_base_url = ?,
                    llm_api_key = ?,
                    llm_model = ?,
                    llm_available_models = ?,
                    llm_timeout_ms = ?,
                    llm_temperature = ?,
                    llm_max_tokens = ?,
                    llm_history_turns = ?,
                    llm_system_message = ?
                WHERE id = ?
                """;

        try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
            bindSettings(statement, settings, false);
            int updated = statement.executeUpdate();
            if (updated > 0) {
                return;
            }
        }

        String insertSql = """
                INSERT INTO settings (
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
                    no_guild_invite_url,
                    discord_client_id,
                    discord_client_secret,
                    redirect_uri,
                    admin_user_ids,
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
                    llm_system_message,
                    id
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;

        try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
            bindSettings(statement, settings, true);
            statement.executeUpdate();
        }
    }

    private void replaceDeployments(Connection connection, List<DeploymentSettings> deployments) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM deployments WHERE bot_id = ?")) {
            delete.setInt(1, botId);
            delete.executeUpdate();
        }

        String sql = """
                INSERT INTO deployments (
                    bot_id,
                    deployment_key,
                    display_name,
                    web_port,
                    base_url,
                    redirect_uri,
                    enabled,
                    sort_order
                ) VALUES (?,?,?,?,?,?,?,?)
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (DeploymentSettings deployment : deployments) {
                if (deployment == null || deployment.deploymentKey() == null || deployment.deploymentKey().isBlank()) {
                    continue;
                }

                statement.setInt(1, botId);
                statement.setString(2, deployment.deploymentKey().trim());
                statement.setString(3, blankToEmpty(deployment.displayName()));
                if (deployment.webPort() == null || deployment.webPort() <= 0) {
                    statement.setNull(4, java.sql.Types.INTEGER);
                } else {
                    statement.setInt(4, deployment.webPort());
                }
                statement.setString(5, blankToEmpty(deployment.baseUrl()));
                statement.setString(6, blankToEmpty(deployment.redirectUri()));
                statement.setBoolean(7, deployment.enabled());
                statement.setInt(8, deployment.sortOrder());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void replaceNodes(Connection connection, List<LavalinkNodeSettings> nodes) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM deployment_lavalink_nodes WHERE bot_id = ?")) {
            delete.setInt(1, botId);
            delete.executeUpdate();
        }

        String sql = """
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
                ) VALUES (?,?,?,?,?,?,?,?,?,?)
                """;

        // Pro Deployment darf nur ein Node gespeichert werden. Kaeme ueber die
        // API doch ein zweiter Eintrag fuer denselben Key an, wuerde der Bot
        // sonst wieder in den alten Mehrfach-Zustand zurueckfallen.
        Set<String> writtenDeployments = new HashSet<>();

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (LavalinkNodeSettings node : nodes) {
                if (node == null || node.deploymentKey() == null || node.deploymentKey().isBlank()) {
                    continue;
                }
                if (node.serverUri() == null || node.serverUri().isBlank()) {
                    continue;
                }
                if (!writtenDeployments.add(node.deploymentKey().trim())) {
                    continue;
                }

                statement.setInt(1, botId);
                statement.setString(2, node.deploymentKey().trim());
                statement.setString(3, blankToEmpty(node.nodeName()));
                statement.setString(4, node.serverUri().trim());
                statement.setString(5, blankToEmpty(node.password()));
                statement.setInt(6, Math.max(1000, node.httpTimeoutMs()));
                statement.setBoolean(7, node.resumeEnabled());
                statement.setLong(8, Math.max(10, node.resumeTimeoutSeconds()));
                statement.setBoolean(9, node.enabled());
                // sort_order existiert nur noch aus Kompatibilitaetsgruenden.
                statement.setInt(10, 0);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void bindSettings(PreparedStatement statement, StoredSettings settings, boolean insertMode) throws SQLException {
        statement.setString(1, blankToEmpty(settings.token()));
        statement.setString(2, blankToEmpty(settings.activity()));
        statement.setString(3, blankToEmpty(settings.activityRotation()));
        statement.setString(4, blankToEmpty(settings.status()));
        statement.setString(5, blankToEmpty(settings.brandImageUrl()));
        statement.setString(6, blankToEmpty(settings.heroImageUrl()));
        if (settings.maintenanceEnabled() == null) {
            statement.setNull(7, java.sql.Types.BOOLEAN);
        } else {
            statement.setBoolean(7, settings.maintenanceEnabled());
        }
        statement.setString(8, blankToEmpty(settings.maintenanceMessage()));
        statement.setString(9, blankToEmpty(settings.legalOwnerName()));
        statement.setString(10, blankToEmpty(settings.legalEmail()));
        statement.setString(11, blankToEmpty(settings.legalAddress()));
        statement.setString(12, blankToEmpty(settings.webBaseUrl()));
        statement.setString(13, blankToEmpty(settings.noGuildInviteUrl()));
        statement.setString(14, blankToEmpty(settings.discordClientId()));
        statement.setString(15, blankToEmpty(settings.discordClientSecret()));
        statement.setString(16, blankToEmpty(settings.redirectUri()));
        statement.setString(17, blankToEmpty(settings.adminUserIds()));
        statement.setString(18, blankToEmpty(settings.llmProvider()));
        statement.setString(19, blankToEmpty(settings.llmOllamaUrl()));
        statement.setString(20, blankToEmpty(settings.llmOpenAiBaseUrl()));
        statement.setString(21, blankToEmpty(settings.llmApiKey()));
        statement.setString(22, blankToEmpty(settings.llmModel()));
        statement.setString(23, blankToEmpty(settings.llmAvailableModels()));
        if (settings.llmTimeoutMs() == null) {
            statement.setNull(24, java.sql.Types.INTEGER);
        } else {
            statement.setInt(24, settings.llmTimeoutMs());
        }
        if (settings.llmTemperature() == null) {
            statement.setNull(25, java.sql.Types.DOUBLE);
        } else {
            statement.setDouble(25, settings.llmTemperature());
        }
        if (settings.llmMaxTokens() == null) {
            statement.setNull(26, java.sql.Types.INTEGER);
        } else {
            statement.setInt(26, settings.llmMaxTokens());
        }
        if (settings.llmHistoryTurns() == null) {
            statement.setNull(27, java.sql.Types.INTEGER);
        } else {
            statement.setInt(27, settings.llmHistoryTurns());
        }
        statement.setString(28, blankToEmpty(settings.llmSystemMessage()));
        statement.setInt(29, botId);
    }

    private String text(ResultSet resultSet, String column) throws SQLException {
        String value = resultSet.getString(column);
        return value == null ? "" : value.trim();
    }

    private Integer integer(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private Double decimal(ResultSet resultSet, String column) throws SQLException {
        double value = resultSet.getDouble(column);
        return resultSet.wasNull() ? null : value;
    }

    private Boolean bool(ResultSet resultSet, String column) throws SQLException {
        boolean value = resultSet.getBoolean(column);
        return resultSet.wasNull() ? null : value;
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
