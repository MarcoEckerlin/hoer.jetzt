package eckerlin.dev.services;

import eckerlin.dev.audio.RadioStation;
import eckerlin.dev.utils.DB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Die Senderliste.
 *
 * <h2>Global und je Server</h2>
 *
 * <p>Ein Sender ohne {@code guild_id} ist global und steht ueberall; einer mit
 * gehoert genau diesem Server. Beides liegt in derselben Tabelle, weil die
 * Wiedergabe einen Sender ueber seine ID nachschlaegt und dabei nicht wissen
 * kann, aus welcher Ecke er stammt.</p>
 *
 * <h2>Warum hier ueberall der Server mitgegeben wird</h2>
 *
 * <p>Die frueheren Methoden hiessen {@code ...ForConfiguredBot} und kannten
 * keinen Server. Solange es nur globale Sender gab, war das richtig. Jetzt
 * waere es eine Luecke: wer die ID eines fremden Senders erraet, wuerde ihn
 * abspielen. Deshalb nimmt jede Abfrage den Server entgegen und liefert nur,
 * was dort erlaubt ist.</p>
 */
@Service
public class RadioStationService {

    private static final Logger LOG = LoggerFactory.getLogger(RadioStationService.class);

    /**
     * Das KI-Radio ist kein Tabelleneintrag, sondern eine Betriebsart - es
     * braucht trotzdem eine ID, um in derselben Liste stehen zu koennen.
     *
     * <p>Die Zahl ist negativ, und das ist kein Zufall: die IDs kommen aus
     * {@code hj_id_seq}, einer Sequenz, die sich alle Tabellen teilen. Der
     * frueher benutzte Wert 900001 lag in ihrem Zahlenraum - ein echter Sender
     * haette ihn irgendwann bekommen und das KI-Radio damit verdeckt. Eine
     * Sequenz vergibt keine negativen Nummern.</p>
     */
    public static final int SMART_RADIO_ID = -1;

    private static final RadioStation SMART_RADIO_STATION = new RadioStation(
            SMART_RADIO_ID,
            "KI-Radio Clean Shuffle",
            "smart://taste-radio"
    );

    /** Mehr Sender braucht kein Server - die Grenze haelt die Auswahlliste bedienbar. */
    public static final int MAX_JE_SERVER = 50;

    private static final String SPALTEN = "id, guild_id, name, url, logo_url";

    /**
     * Ein Sender, den dieser Server benutzen darf.
     *
     * @param guildId {@code null} nur fuer Aufrufer ohne Serverbezug; dann sind
     *                ausschliesslich globale Sender sichtbar.
     */
    public Optional<RadioStation> findById(int radioId, String guildId) {
        if (radioId == SMART_RADIO_ID) {
            return Optional.of(SMART_RADIO_STATION);
        }
        if (!DB.isAvailable()) {
            return Optional.empty();
        }

        String sql = "SELECT " + SPALTEN + " FROM radio WHERE id = ? AND (guild_id IS NULL OR guild_id = ?) LIMIT 1";

        try (Connection connection = DB.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, radioId);
            statement.setString(2, guildId == null ? "" : guildId);

            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(lesen(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            LOG.warn("Radiosender {} konnte nicht gelesen werden: {}", radioId, exception.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Senderliste aus Sicht eines Servers: globale zuerst, dann die eigenen.
     *
     * @param mitSmartRadio {@code false} laesst das KI-Radio weg. Es ist je
     *                      Server freizuschalten und hat in einer Liste, die
     *                      ein nicht freigeschalteter Server sieht, nichts
     *                      verloren - sonst steht dort ein Sender, der beim
     *                      Anklicken nur eine Absage liefert.
     */
    public List<RadioStation> findAll(String guildId, boolean mitSmartRadio) {
        List<RadioStation> sender = new ArrayList<>();
        if (mitSmartRadio) {
            sender.add(SMART_RADIO_STATION);
        }
        if (!DB.isAvailable()) {
            return List.copyOf(sender);
        }

        // Globale zuerst, damit die gewohnten Sender oben stehen und die
        // eigenen als Block darunter - eine gemischte Liste liest sich nicht.
        String sql = "SELECT " + SPALTEN + " FROM radio"
                + " WHERE guild_id IS NULL OR guild_id = ?"
                + " ORDER BY (guild_id IS NOT NULL), sortierung, name";

        try (Connection connection = DB.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, guildId == null ? "" : guildId);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    sender.add(lesen(resultSet));
                }
            }
        } catch (SQLException exception) {
            LOG.warn("Senderliste konnte nicht gelesen werden: {}", exception.getMessage());
        }

        return List.copyOf(sender);
    }

    /** Nur die eigenen Sender eines Servers - fuer dessen Verwaltungsseite. */
    public List<RadioStation> findEigene(String guildId) throws SQLException {
        return abfragen("SELECT " + SPALTEN + " FROM radio WHERE guild_id = ? ORDER BY sortierung, name", guildId);
    }

    /** Nur die globalen Sender - fuer den Betriebsbereich. */
    public List<RadioStation> findGlobale() throws SQLException {
        return abfragen("SELECT " + SPALTEN + " FROM radio WHERE guild_id IS NULL ORDER BY sortierung, name", null);
    }

    /**
     * Legt einen Sender an oder aendert ihn.
     *
     * @param guildId {@code null} meint einen globalen Sender. Das darf nur der
     *                Betriebsbereich; die Rechtepruefung sitzt im Controller.
     * @return die ID des Senders
     */
    public int speichern(Integer id, String guildId, String name, String url, String logoUrl, String angelegtVon)
            throws SQLException {
        String sauberName = pflicht(name, 160, "Der Sender braucht einen Namen.");
        String sauberUrl = pruefeStream(url);
        String sauberLogo = logoUrl == null ? "" : logoUrl.trim();

        try (Connection connection = DB.connection()) {
            if (id == null) {
                if (guildId != null && anzahl(connection, guildId) >= MAX_JE_SERVER) {
                    throw new IllegalArgumentException(
                            "Mehr als " + MAX_JE_SERVER + " eigene Sender je Server sind nicht vorgesehen.");
                }

                String sql = "INSERT INTO radio (guild_id, name, url, logo_url, sortierung, angelegt_von)"
                        + " VALUES (?, ?, ?, ?, ?, ?) RETURNING id";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    setzeGuild(statement, 1, guildId);
                    statement.setString(2, sauberName);
                    statement.setString(3, sauberUrl);
                    statement.setString(4, sauberLogo);
                    statement.setInt(5, naechsteSortierung(connection, guildId));
                    statement.setString(6, angelegtVon == null ? "" : angelegtVon);

                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (!resultSet.next()) {
                            throw new SQLException("Der Sender wurde nicht angelegt.");
                        }
                        return resultSet.getInt(1);
                    }
                }
            }

            // Die guild_id steht bewusst in der WHERE-Klausel und nicht im SET:
            // so kann ein Serveradmin einen fremden oder globalen Sender nicht
            // aendern, auch wenn er dessen ID kennt.
            String sql = "UPDATE radio SET name = ?, url = ?, logo_url = ? WHERE id = ?"
                    + (guildId == null ? " AND guild_id IS NULL" : " AND guild_id = ?");
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, sauberName);
                statement.setString(2, sauberUrl);
                statement.setString(3, sauberLogo);
                statement.setInt(4, id);
                if (guildId != null) {
                    statement.setString(5, guildId);
                }
                if (statement.executeUpdate() == 0) {
                    throw new IllegalArgumentException(
                            "Diesen Sender gibt es nicht - oder er gehoert nicht zu diesem Server.");
                }
            }
            return id;
        }
    }

    /** Loescht einen Sender. {@code guildId == null} meint einen globalen. */
    public void loeschen(int id, String guildId) throws SQLException {
        String sql = "DELETE FROM radio WHERE id = ?"
                + (guildId == null ? " AND guild_id IS NULL" : " AND guild_id = ?");
        try (Connection connection = DB.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, id);
            if (guildId != null) {
                statement.setString(2, guildId);
            }
            if (statement.executeUpdate() == 0) {
                throw new IllegalArgumentException(
                        "Diesen Sender gibt es nicht - oder er gehoert nicht zu diesem Server.");
            }
        }
    }

    public boolean isSmartRadioStation(int radioId) {
        return radioId == SMART_RADIO_ID;
    }

    // ------------------------------------------------------------------

    private List<RadioStation> abfragen(String sql, String guildId) throws SQLException {
        List<RadioStation> sender = new ArrayList<>();
        try (Connection connection = DB.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (guildId != null) {
                statement.setString(1, guildId);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    sender.add(lesen(resultSet));
                }
            }
        }
        return sender;
    }

    private RadioStation lesen(ResultSet resultSet) throws SQLException {
        String guildId = resultSet.getString("guild_id");
        String logo = resultSet.getString("logo_url");
        return new RadioStation(
                resultSet.getInt("id"),
                resultSet.getString("name"),
                resultSet.getString("url"),
                logo == null ? "" : logo,
                guildId == null || guildId.isBlank() ? null : guildId
        );
    }

    private int anzahl(Connection connection, String guildId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT count(*) FROM radio WHERE guild_id = ?")) {
            statement.setString(1, guildId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        }
    }

    private int naechsteSortierung(Connection connection, String guildId) throws SQLException {
        String sql = "SELECT coalesce(max(sortierung), -1) + 1 FROM radio WHERE "
                + (guildId == null ? "guild_id IS NULL" : "guild_id = ?");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (guildId != null) {
                statement.setString(1, guildId);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        }
    }

    private void setzeGuild(PreparedStatement statement, int index, String guildId) throws SQLException {
        if (guildId == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, guildId);
        }
    }

    private String pflicht(String wert, int maximum, String meldung) {
        String sauber = wert == null ? "" : wert.trim();
        if (sauber.isEmpty()) {
            throw new IllegalArgumentException(meldung);
        }
        return sauber.length() <= maximum ? sauber : sauber.substring(0, maximum);
    }

    /**
     * Die Stream-Adresse.
     *
     * <p>Nur http und https: alles andere gaebe der Wiedergabe eine Adresse in
     * die Hand, die sie nicht ueber das Netz aufloest - {@code file://} etwa
     * zeigt auf das Dateisystem des Servers. Und {@code smart://} ist die
     * interne Kennung des KI-Radios; wer sie von Hand eintraegt, bekaeme einen
     * Sender, der sich als etwas anderes ausgibt.</p>
     */
    private String pruefeStream(String url) {
        String sauber = pflicht(url, 2000, "Der Sender braucht eine Stream-Adresse.");
        String klein = sauber.toLowerCase(Locale.ROOT);
        if (!klein.startsWith("http://") && !klein.startsWith("https://")) {
            throw new IllegalArgumentException("Die Stream-Adresse muss mit http:// oder https:// beginnen.");
        }
        return sauber;
    }
}
