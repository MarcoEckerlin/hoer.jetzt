package eckerlin.dev.services;

import eckerlin.dev.audio.RadioStation;
import eckerlin.dev.utils.DB;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class RadioStationService {

    public static final int SMART_RADIO_ID = 900001;
    private static final RadioStation SMART_RADIO_STATION = new RadioStation(
            SMART_RADIO_ID,
            "AI Radio Clean Shuffle",
            "smart://taste-radio"
    );

    public RadioStationService() {
    }

    public Optional<RadioStation> findByIdForConfiguredBot(int radioId) {
        if (radioId == SMART_RADIO_ID) {
            return Optional.of(SMART_RADIO_STATION);
        }

        if (!DB.isAvailable()) {
            return Optional.empty();
        }

        String sql = """
                SELECT id, name, url
                FROM radio
                WHERE id = ?
                LIMIT 1
                """;

        try (Connection connection = DB.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, radioId);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }

                return Optional.of(new RadioStation(
                        resultSet.getInt("id"),
                        resultSet.getString("name"),
                        resultSet.getString("url")
                ));
            }
        } catch (SQLException exception) {
            return Optional.empty();
        }
    }

    public List<RadioStation> findAllForConfiguredBot() {
        List<RadioStation> stations = new ArrayList<>();
        stations.add(SMART_RADIO_STATION);

        if (!DB.isAvailable()) {
            return List.copyOf(stations);
        }

        String sql = """
                SELECT id, name, url
                FROM radio
                ORDER BY id
                """;

        try (Connection connection = DB.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    stations.add(new RadioStation(
                            resultSet.getInt("id"),
                            resultSet.getString("name"),
                            resultSet.getString("url")
                    ));
                }
            }
        } catch (SQLException exception) {
            return List.copyOf(stations);
        }

        return List.copyOf(stations);
    }

    public boolean isSmartRadioStation(int radioId) {
        return radioId == SMART_RADIO_ID;
    }
}
