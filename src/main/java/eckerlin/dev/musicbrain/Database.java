package eckerlin.dev.musicbrain;

import org.json.JSONObject;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public final class Database {

    private Database() {
    }

    public static Connection connection() throws SQLException {
        JSONObject database = Config.config.optJSONObject("database");
        if (database == null) {
            throw new SQLException("Database-Config fehlt.");
        }

        try {
            Class.forName("org.mariadb.jdbc.Driver");
        } catch (ClassNotFoundException exception) {
            throw new SQLException("MariaDB-Treiber konnte nicht geladen werden.", exception);
        }

        String url = "jdbc:mariadb://%s:%s/%s".formatted(
                database.optString("host", "localhost"),
                database.optInt("port", 3306),
                database.optString("name", "discordBot")
        );

        return DriverManager.getConnection(
                url,
                database.optString("user", ""),
                database.optString("password", "")
        );
    }
}
