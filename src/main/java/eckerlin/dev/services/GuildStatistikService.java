package eckerlin.dev.services;

import eckerlin.dev.utils.Alert;
import eckerlin.dev.utils.DB;
import eckerlin.dev.web.dto.AdminGuildStats;
import eckerlin.dev.web.dto.AdminGuildStatsEintrag;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Zahlen zu einem einzelnen Discord-Server.
 *
 * <p>Getrennt vom {@link ListenerStatsService}: der zaehlt fuer die
 * oeffentliche Statistikseite ueber <em>alle</em> Server und haelt dafuer
 * laufende Sitzungen im Speicher. Hier wird nur gelesen, immer mit
 * {@code guild_id} in der Bedingung, und es gibt keinen Zustand. Beides in
 * einer Klasse haette bedeutet, dass jede Abfrage der Verwaltung an einem
 * Dienst haengt, der nebenbei Schreibarbeit verrichtet.</p>
 *
 * <h2>Warum nicht mit der Serverliste zusammen</h2>
 *
 * <p>Die Liste zeigt alle Server dieser Node. Diese Zahlen kosten je Server
 * vier Abfragen ueber das Netz zur Datenbank - bei zwanzig Servern also
 * achtzig, nur damit man eine davon aufklappt. Deshalb ein eigener Aufruf je
 * Server, der erst beim Aufklappen laeuft.</p>
 *
 * <p>Der Pfad enthaelt die Server-Kennung. Das ist keine Kosmetik: die
 * Weiterleitung zwischen den Knoten erkennt Anfragen daran, und ein Server
 * liegt immer nur auf einem der beiden Shards.</p>
 */
@Service
public class GuildStatistikService {

    /** Der Zeitraum, auf den sich alle 30-Tage-Werte beziehen. */
    private static final Duration ZEITRAUM = Duration.ofDays(30);

    /** Mehr als fuenf Zeilen liest in einer aufgeklappten Zeile niemand. */
    private static final int BESTENLISTE = 5;

    private final AppConfigService configService;

    public GuildStatistikService(AppConfigService configService) {
        this.configService = configService;
    }

    /**
     * Alle Zahlen eines Servers.
     *
     * <p>Faellt eine der Abfragen aus, bleibt der betroffene Teil auf Null und
     * der Rest wird trotzdem geliefert. Eine Verwaltungsansicht, die wegen
     * einer fehlenden Bestenliste ganz leer bleibt, hilft niemandem.</p>
     */
    public AdminGuildStats fuerServer(String guildId) {
        Timestamp seit = Timestamp.from(Instant.now().minus(ZEITRAUM));
        int botId = configService.getBotId();

        Summe summe = summeLesen(botId, guildId, seit);
        Arten arten = artenLesen(botId, guildId, seit);
        List<AdminGuildStatsEintrag> oben = bestenlisteLesen(botId, guildId, seit);

        return new AdminGuildStats(
                guildId,
                summe.sekunden(),
                dauer(summe.sekunden()),
                summe.hoerer(),
                summe.sitzungen(),
                titelZaehlen(botId, guildId, seit),
                arten.radio(),
                arten.musik(),
                arten.aiRadio(),
                oben,
                zuletztAktiv(botId, guildId),
                senderZaehlen(guildId)
        );
    }

    // ------------------------------------------------------------------
    // Einzelne Abfragen
    // ------------------------------------------------------------------

    private record Summe(long sekunden, long hoerer, long sitzungen) {
        static final Summe LEER = new Summe(0, 0, 0);
    }

    private Summe summeLesen(int botId, String guildId, Timestamp seit) {
        String sql = """
                SELECT COALESCE(SUM(listened_seconds), 0) AS sekunden,
                       COUNT(DISTINCT listener_hash)      AS hoerer,
                       COUNT(*)                           AS sitzungen
                FROM music_listener_events
                WHERE bot_id = ? AND guild_id = ? AND started_at >= ?
                """;
        try (Connection verbindung = DB.connection();
             PreparedStatement anweisung = verbindung.prepareStatement(sql)) {
            anweisung.setInt(1, botId);
            anweisung.setString(2, guildId);
            anweisung.setTimestamp(3, seit);
            try (ResultSet zeile = anweisung.executeQuery()) {
                if (zeile.next()) {
                    return new Summe(zeile.getLong("sekunden"), zeile.getLong("hoerer"), zeile.getLong("sitzungen"));
                }
            }
        } catch (SQLException fehler) {
            melden("Summe", guildId, fehler);
        }
        return Summe.LEER;
    }

    private record Arten(long radio, long musik, long aiRadio) {
        static final Arten LEER = new Arten(0, 0, 0);
    }

    /**
     * Hoerzeit nach Wiedergabeart.
     *
     * <p>Eine Abfrage mit GROUP BY statt dreier mit WHERE: die Werte sollen
     * aus demselben Lesevorgang stammen. Sonst koennen sie sich widersprechen,
     * wenn waehrenddessen etwas geschrieben wird - und ein Balken, dessen
     * Teile nicht die Summe ergeben, sieht nach einem Rechenfehler aus.</p>
     */
    private Arten artenLesen(int botId, String guildId, Timestamp seit) {
        String sql = """
                SELECT playback_kind, COALESCE(SUM(listened_seconds), 0) AS sekunden
                FROM music_listener_events
                WHERE bot_id = ? AND guild_id = ? AND started_at >= ?
                GROUP BY playback_kind
                """;
        long radio = 0;
        long musik = 0;
        long aiRadio = 0;
        try (Connection verbindung = DB.connection();
             PreparedStatement anweisung = verbindung.prepareStatement(sql)) {
            anweisung.setInt(1, botId);
            anweisung.setString(2, guildId);
            anweisung.setTimestamp(3, seit);
            try (ResultSet zeile = anweisung.executeQuery()) {
                while (zeile.next()) {
                    long sekunden = zeile.getLong("sekunden");
                    switch (zeile.getString("playback_kind") == null ? "" : zeile.getString("playback_kind")) {
                        case "radio" -> radio += sekunden;
                        case "ai_radio" -> aiRadio += sekunden;
                        default -> musik += sekunden;
                    }
                }
            }
            return new Arten(radio, musik, aiRadio);
        } catch (SQLException fehler) {
            melden("Wiedergabearten", guildId, fehler);
        }
        return Arten.LEER;
    }

    private long titelZaehlen(int botId, String guildId, Timestamp seit) {
        String sql = """
                SELECT COUNT(*) FROM music_track_events
                WHERE bot_id = ? AND guild_id = ? AND created_at >= ?
                """;
        try (Connection verbindung = DB.connection();
             PreparedStatement anweisung = verbindung.prepareStatement(sql)) {
            anweisung.setInt(1, botId);
            anweisung.setString(2, guildId);
            anweisung.setTimestamp(3, seit);
            try (ResultSet zeile = anweisung.executeQuery()) {
                return zeile.next() ? zeile.getLong(1) : 0;
            }
        } catch (SQLException fehler) {
            melden("Titelzahl", guildId, fehler);
            return 0;
        }
    }

    private List<AdminGuildStatsEintrag> bestenlisteLesen(int botId, String guildId, Timestamp seit) {
        String sql = """
                SELECT title,
                       COALESCE(MAX(author), '')          AS interpret,
                       COALESCE(SUM(listened_seconds), 0) AS sekunden,
                       COUNT(DISTINCT listener_hash)      AS hoerer
                FROM music_listener_events
                WHERE bot_id = ? AND guild_id = ? AND started_at >= ? AND title <> ''
                GROUP BY title
                ORDER BY sekunden DESC, hoerer DESC
                LIMIT ?
                """;
        List<AdminGuildStatsEintrag> liste = new ArrayList<>();
        try (Connection verbindung = DB.connection();
             PreparedStatement anweisung = verbindung.prepareStatement(sql)) {
            anweisung.setInt(1, botId);
            anweisung.setString(2, guildId);
            anweisung.setTimestamp(3, seit);
            anweisung.setInt(4, BESTENLISTE);
            try (ResultSet zeile = anweisung.executeQuery()) {
                while (zeile.next()) {
                    long sekunden = zeile.getLong("sekunden");
                    liste.add(new AdminGuildStatsEintrag(
                            zeile.getString("title"),
                            zeile.getString("interpret"),
                            sekunden,
                            dauer(sekunden),
                            zeile.getLong("hoerer")));
                }
            }
        } catch (SQLException fehler) {
            melden("Bestenliste", guildId, fehler);
        }
        return liste;
    }

    /**
     * Wann zuletzt etwas lief - ohne Zeitraumgrenze.
     *
     * <p>Gerade bei einem Server, auf dem seit Wochen nichts passiert, ist das
     * die eigentliche Auskunft. Eine Abfrage mit 30-Tage-Fenster haette dort
     * nichts geliefert, und "keine Daten" beantwortet die Frage nicht.</p>
     */
    private String zuletztAktiv(int botId, String guildId) {
        String sql = """
                SELECT MAX(started_at) FROM music_listener_events
                WHERE bot_id = ? AND guild_id = ?
                """;
        try (Connection verbindung = DB.connection();
             PreparedStatement anweisung = verbindung.prepareStatement(sql)) {
            anweisung.setInt(1, botId);
            anweisung.setString(2, guildId);
            try (ResultSet zeile = anweisung.executeQuery()) {
                if (zeile.next()) {
                    Timestamp wann = zeile.getTimestamp(1);
                    return wann == null ? "" : wann.toInstant().toString();
                }
            }
        } catch (SQLException fehler) {
            melden("Letzte Aktivitaet", guildId, fehler);
        }
        return "";
    }

    private long senderZaehlen(String guildId) {
        try (Connection verbindung = DB.connection();
             PreparedStatement anweisung = verbindung.prepareStatement(
                     "SELECT COUNT(*) FROM radio WHERE guild_id = ?")) {
            anweisung.setString(1, guildId);
            try (ResultSet zeile = anweisung.executeQuery()) {
                return zeile.next() ? zeile.getLong(1) : 0;
            }
        } catch (SQLException fehler) {
            melden("Senderzahl", guildId, fehler);
            return 0;
        }
    }

    // ------------------------------------------------------------------

    /**
     * Dieselbe Schreibweise wie auf der oeffentlichen Statistikseite.
     *
     * <p>Bewusst hier noch einmal und nicht aus dem {@link ListenerStatsService}
     * geholt: dort ist die Methode privat, und sie oeffentlich zu machen haette
     * eine Klasse mit Schreibarbeit und Sitzungsverwaltung zur
     * Formatierungshilfe gemacht. Vier Zeilen doppelt sind der kleinere
     * Preis - aendert sich die Schreibweise, faellt der Unterschied sofort
     * nebeneinander in der Oberflaeche auf.</p>
     */
    private static String dauer(long sekunden) {
        long stunden = sekunden / 3600;
        long minuten = (sekunden % 3600) / 60;
        if (stunden > 0) {
            return stunden + " h " + String.format(Locale.ROOT, "%02d", minuten) + " min";
        }
        // Unter einer Minute, aber nicht null: "0 min" sieht aus wie "nichts".
        return (sekunden > 0 && minuten == 0 ? 1 : Math.max(0L, minuten)) + " min";
    }

    private static void melden(String was, String guildId, SQLException fehler) {
        Alert.send("WARN", "STATISTIK",
                was + " fuer Server " + guildId + " nicht lesbar: " + fehler.getMessage());
    }
}
