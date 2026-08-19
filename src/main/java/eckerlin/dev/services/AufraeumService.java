package eckerlin.dev.services;

import eckerlin.dev.utils.Alert;
import eckerlin.dev.utils.Config;
import eckerlin.dev.utils.DB;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Loescht, was nicht mehr gebraucht wird.
 *
 * <h2>Warum es das geben muss</h2>
 *
 * <p>Bis hierhin wuchsen Protokolle, Ticket-Verlaeufe, Statistiken und das
 * Verwaltungsprotokoll unbegrenzt. Das ist nicht bloss unordentlich, sondern
 * steht dem Grundsatz der Speicherbegrenzung entgegen (Art. 5 Abs. 1 lit. e
 * DSGVO): personenbezogene Daten duerfen nur so lange liegen, wie sie fuer
 * ihren Zweck gebraucht werden. Ohne festgelegte Frist gibt es dieses "so
 * lange" nicht.</p>
 *
 * <h2>Warum die Fristen hier stehen und nicht in einer Tabelle</h2>
 *
 * <p>Sie stehen in der Umgebung, damit sie ohne Neubau aenderbar sind, und
 * haben Vorgaben, damit eine Installation nie ganz ohne Frist laeuft. Die
 * Datenschutzerklaerung liest dieselben Werte - so kann der Text nicht
 * behaupten, was die Technik nicht tut.</p>
 *
 * <h2>Was bewusst nicht geloescht wird</h2>
 *
 * <p>Konfigurationen, Freigaben, Bot-Admins und Radiosender: die beschreiben
 * einen gewollten Zustand und veralten nicht von selbst. Sie verschwinden,
 * wenn jemand sie loescht.</p>
 */
@Service
public class AufraeumService {

    /** Einmal am Tag genuegt - es geht um Monate, nicht um Minuten. */
    private static final long TAKT_STUNDEN = 24;

    /**
     * Die Fristen in Tagen.
     *
     * <p>Die Vorgaben sind bewusst zurueckhaltend gewaehlt: lang genug, dass
     * ein Server-Team nach einem Vorfall noch nachlesen kann, kurz genug, dass
     * nichts jahrelang liegt. Wer andere Zeitraeume braucht, setzt sie in der
     * Umgebung - und traegt sie damit zugleich in die Datenschutzerklaerung
     * ein, weil die denselben Wert anzeigt.</p>
     */
    public static final String[] BEREICHE = {
            "logs", "ticket_transcripts", "music_track_events",
            "music_listener_events", "admin_audit_log"
    };

    private final ScheduledExecutorService takt =
            Executors.newSingleThreadScheduledExecutor(auftrag -> {
                Thread faden = new Thread(auftrag, "aufraeumen");
                faden.setDaemon(true);
                return faden;
            });

    /** Frist in Tagen fuer einen Bereich; 0 schaltet das Loeschen dort ab. */
    public static int frist(String bereich) {
        return switch (bereich) {
            case "logs" -> zahl("HJ_FRIST_PROTOKOLL_TAGE", 90);
            case "ticket_transcripts" -> zahl("HJ_FRIST_TICKETS_TAGE", 365);
            case "music_track_events" -> zahl("HJ_FRIST_TITEL_TAGE", 365);
            case "music_listener_events" -> zahl("HJ_FRIST_HOERER_TAGE", 365);
            case "admin_audit_log" -> zahl("HJ_FRIST_AUDIT_TAGE", 365);
            default -> 0;
        };
    }

    /** Alle Fristen fuer die Anzeige in der Datenschutzerklaerung. */
    public Map<String, Integer> fristen() {
        Map<String, Integer> werte = new LinkedHashMap<>();
        for (String bereich : BEREICHE) {
            werte.put(bereich, frist(bereich));
        }
        return werte;
    }

    @PostConstruct
    public void starten() {
        // Erst nach fuenf Minuten: der Start hat Wichtigeres zu tun, und ein
        // Loeschlauf, der beim Hochfahren mitlaeuft, verzoegert ihn ohne Not.
        takt.scheduleWithFixedDelay(this::laufenLeise, 5, TAKT_STUNDEN * 60, TimeUnit.MINUTES);
    }

    @PreDestroy
    public void beenden() {
        takt.shutdownNow();
    }

    private void laufenLeise() {
        try {
            laufen();
        } catch (RuntimeException fehler) {
            // Ein Taktgeber, dessen Aufgabe eine Ausnahme durchlaesst, wird
            // stillschweigend abgeschaltet - dann laeuft nie wieder etwas.
            Alert.send("WARN", "AUFRAEUMEN", "Durchlauf gescheitert: " + fehler.getMessage());
        }
    }

    /**
     * Ein Durchlauf.
     *
     * @return wie viele Zeilen insgesamt entfernt wurden
     */
    public int laufen() {
        if (!DB.isAvailable()) {
            return 0;
        }

        int botId = Config.config.optInt("bot_id", 1);
        int gesamt = 0;
        StringBuilder bericht = new StringBuilder();

        try (Connection verbindung = DB.connection()) {
            for (String bereich : BEREICHE) {
                int tage = frist(bereich);
                if (tage <= 0) {
                    continue;
                }

                // Die Zeitspalte heisst nicht ueberall gleich, und logs hat gar
                // keine bot_id. Beides einzeln benannt statt geraten: eine
                // falsch geratene Spalte laesst den Bereich stillschweigend
                // ausfallen, waehrend die Datenschutzerklaerung eine Frist
                // nennt - genau die Luecke, die hier geschlossen werden soll.
                boolean mitBot = !"logs".equals(bereich);
                String zeitspalte = switch (bereich) {
                    case "logs" -> "\"timestamp\"";
                    case "music_listener_events" -> "ended_at";
                    default -> "created_at";
                };
                String sql = "DELETE FROM " + bereich
                        + " WHERE " + zeitspalte + " < now() - make_interval(days => ?)"
                        + (mitBot ? " AND bot_id = ?" : "");

                try (PreparedStatement anweisung = verbindung.prepareStatement(sql)) {
                    anweisung.setInt(1, tage);
                    if (mitBot) {
                        anweisung.setInt(2, botId);
                    }
                    int weg = anweisung.executeUpdate();
                    gesamt += weg;
                    if (weg > 0) {
                        bericht.append(bericht.isEmpty() ? "" : ", ").append(bereich).append(": ").append(weg);
                    }
                } catch (java.sql.SQLException einzelfehler) {
                    // Eine Tabelle, die es nicht gibt oder anders heisst, darf
                    // nicht den ganzen Lauf verhindern.
                    Alert.send("WARN", "AUFRAEUMEN",
                            "Bereich " + bereich + " uebersprungen: " + einzelfehler.getMessage());
                }
            }
        } catch (java.sql.SQLException fehler) {
            Alert.send("WARN", "AUFRAEUMEN", "Keine Verbindung: " + fehler.getMessage());
            return 0;
        }

        if (gesamt > 0) {
            Alert.send("INFO", "AUFRAEUMEN", "Abgelaufene Daten entfernt - " + bericht + ".");
        }
        return gesamt;
    }

    private static int zahl(String name, int vorgabe) {
        String wert = System.getenv(name);
        if (wert == null || wert.isBlank()) {
            return vorgabe;
        }
        try {
            return Integer.parseInt(wert.trim());
        } catch (NumberFormatException keineZahl) {
            return vorgabe;
        }
    }
}
