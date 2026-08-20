package eckerlin.dev.services;

import eckerlin.dev.security.Geheimtext;
import eckerlin.dev.utils.Alert;
import eckerlin.dev.utils.DB;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Welchen KI-Endpunkt ein Discord-Server benutzt.
 *
 * <h2>Warum je Server und nicht je Instanz</h2>
 *
 * Bisher galt eine Einstellung fuer alle. Damit hing der KI-Chat jedes Servers
 * an einem Modell - wer ein eigenes betreibt, konnte es nicht benutzen, und
 * wer keines hat, bekam gar nichts. Zwei Betriebsarten loesen beides:
 *
 * <ul>
 *   <li>{@code SELFHOST} - der Betreiber hinterlegt Endpunkt, Modell und
 *       Token. Die Rechenzeit ist seine.</li>
 *   <li>{@code HOER_HOSTED} - er waehlt aus dem, was hoer.jetzt anbietet.</li>
 * </ul>
 *
 * <h2>Die Grenze, die nicht verhandelbar ist</h2>
 *
 * Bei {@code HOER_HOSTED} bekommt der Betreiber Endpunkt und Token
 * <strong>nie</strong> zu sehen - weder in der Oberflaeche noch ueber die
 * API. Sie stehen nicht in seiner Zeile, sondern in {@code ai_modell}, und
 * {@link Zugang} wird nur serverseitig ausgewertet. Ein Server-Betreiber ist
 * ein beliebiger Discord-Nutzer; ihm die Zugangsdaten unserer Infrastruktur
 * zu geben hiesse, sie zu veroeffentlichen.
 */
@Service
public class KiProviderService {

    /** Betriebsart. */
    public enum Modus {
        SELFHOST,
        HOER_HOSTED;

        static Modus aus(String text) {
            if (text == null) {
                return HOER_HOSTED;
            }
            try {
                return valueOf(text.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException unbekannt) {
                return HOER_HOSTED;
            }
        }
    }

    /**
     * Was der Bot braucht, um eine Anfrage zu stellen.
     *
     * <p>Dieser Typ verlaesst den Server nie. Was in die Oberflaeche geht,
     * ist {@link Einstellung} - dort fehlt der Token.</p>
     */
    public record Zugang(Modus modus, String endpunkt, String model,
                         String apiToken, int tokenLimit) {
    }

    /**
     * Was der Server-Betreiber sieht und aendern darf.
     *
     * <p>{@code tokenHinterlegt} statt des Tokens: ob einer da ist, muss er
     * wissen, welcher nicht - er hat ihn selbst eingetragen.</p>
     */
    public record Einstellung(Modus modus, String endpunkt, String model,
                              boolean tokenHinterlegt, int tokenLimit, boolean aktiv) {
    }

    /** Ein Modell, das hoer.jetzt selbst bereitstellt. */
    public record Angebot(String kennung, String anzeige, int tokenLimit) {
    }

    private final AppConfigService configService;

    public KiProviderService(AppConfigService configService) {
        this.configService = configService;
    }

    private int botId() {
        return configService.getBotId();
    }

    // ------------------------------------------------------------- Aufloesen

    /**
     * Der Zugang fuer einen Server - oder leer, wenn der KI-Chat dort nicht
     * eingerichtet ist.
     *
     * <p>Die Reihenfolge ist wichtig: erst die Einstellung des Servers, dann
     * bei {@code HOER_HOSTED} das gewaehlte Angebot, und erst wenn beides
     * fehlt, die Instanzkonfiguration. Andersherum haette die Instanz die
     * Einstellung des Servers ueberschrieben.</p>
     */
    public Optional<Zugang> zugangFuer(String guildId) {
        Optional<Einstellung> eigene = einstellung(guildId);
        if (eigene.isEmpty() || !eigene.get().aktiv()) {
            return Optional.empty();
        }
        Einstellung e = eigene.get();

        if (e.modus() == Modus.SELFHOST) {
            if (e.endpunkt().isBlank() || e.model().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new Zugang(Modus.SELFHOST, e.endpunkt(), e.model(),
                    tokenLesen(guildId), e.tokenLimit()));
        }

        // HOER_HOSTED: Endpunkt und Token kommen aus dem Angebot, nie aus der
        // Zeile des Servers.
        return angebot(e.model()).map(a -> new Zugang(
                Modus.HOER_HOSTED,
                a.endpunktIntern(),
                a.kennung(),
                a.tokenIntern(),
                Math.min(e.tokenLimit(), a.tokenLimit())));
    }

    // ------------------------------------------------------------ Einstellung

    public Optional<Einstellung> einstellung(String guildId) {
        if (!DB.isAvailable() || guildId == null || guildId.isBlank()) {
            return Optional.empty();
        }
        String sql = "SELECT modus, endpunkt, model, api_token, token_limit, aktiv"
                + " FROM guild_ai_provider WHERE bot_id = ? AND guild_id = ?";
        try (Connection v = DB.connection();
             PreparedStatement a = v.prepareStatement(sql)) {
            a.setInt(1, botId());
            a.setString(2, guildId);
            try (ResultSet r = a.executeQuery()) {
                if (!r.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Einstellung(
                        Modus.aus(r.getString("modus")),
                        text(r.getString("endpunkt")),
                        text(r.getString("model")),
                        !text(r.getString("api_token")).isEmpty(),
                        r.getInt("token_limit"),
                        r.getBoolean("aktiv")));
            }
        } catch (SQLException fehler) {
            Alert.send("WARN", "KI", "Provider nicht lesbar: " + fehler.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Speichert die Einstellung eines Servers.
     *
     * @param apiToken neuer Token, oder {@code null} um den vorhandenen zu
     *                 behalten. Das ist noetig, weil die Oberflaeche den
     *                 hinterlegten Token nie anzeigt - sie kann ihn also
     *                 auch nicht unveraendert zuruecksenden, und ein leeres
     *                 Feld wuerde ihn sonst bei jedem Speichern loeschen.
     */
    public boolean speichern(String guildId, Modus modus, String endpunkt, String model,
                             String apiToken, int tokenLimit, boolean aktiv, String wer) {
        if (!DB.isAvailable() || guildId == null || guildId.isBlank()) {
            return false;
        }
        if (modus == Modus.SELFHOST && !endpunktTauglich(endpunkt)) {
            throw new IllegalArgumentException(
                    "Der Endpunkt muss mit http:// oder https:// beginnen.");
        }
        if (modus == Modus.HOER_HOSTED && angebot(model).isEmpty()) {
            throw new IllegalArgumentException("Dieses Modell wird nicht angeboten.");
        }

        // Ein Token gehoert nur zu SELFHOST. Bei HOER_HOSTED wird ein
        // mitgeschickter verworfen statt gespeichert - sonst laege ein Wert
        // in der Zeile, den niemand benutzt und den trotzdem jemand
        // verwahren muesste.
        String zuSpeichern;
        if (modus == Modus.HOER_HOSTED) {
            zuSpeichern = "";
        } else if (apiToken == null) {
            zuSpeichern = rohToken(guildId);
        } else {
            zuSpeichern = Geheimtext.verschluesseln(apiToken.trim());
        }

        String sql = "INSERT INTO guild_ai_provider"
                + " (bot_id, guild_id, modus, endpunkt, model, api_token, token_limit,"
                + "  aktiv, geaendert_von, geaendert_am)"
                + " VALUES (?,?,?,?,?,?,?,?,?, current_timestamp)"
                + " ON CONFLICT (bot_id, guild_id) DO UPDATE SET"
                + "   modus = EXCLUDED.modus, endpunkt = EXCLUDED.endpunkt,"
                + "   model = EXCLUDED.model, api_token = EXCLUDED.api_token,"
                + "   token_limit = EXCLUDED.token_limit, aktiv = EXCLUDED.aktiv,"
                + "   geaendert_von = EXCLUDED.geaendert_von,"
                + "   geaendert_am = current_timestamp";
        try (Connection v = DB.connection();
             PreparedStatement a = v.prepareStatement(sql)) {
            a.setInt(1, botId());
            a.setString(2, guildId);
            a.setString(3, modus.name());
            a.setString(4, modus == Modus.HOER_HOSTED ? "" : text(endpunkt));
            a.setString(5, text(model));
            a.setString(6, zuSpeichern);
            // Nach oben begrenzt: ein Server, der 200000 einträgt, wuerde
            // sonst bei HOER_HOSTED unser Kontingent verbrauchen.
            a.setInt(7, Math.max(256, Math.min(tokenLimit, 32768)));
            a.setBoolean(8, aktiv);
            a.setString(9, wer == null ? "" : wer);
            a.executeUpdate();
            return true;
        } catch (SQLException fehler) {
            Alert.send("WARN", "KI", "Provider nicht speicherbar: " + fehler.getMessage());
            return false;
        }
    }

    // --------------------------------------------------------------- Angebote

    /** Ein Angebot samt der Zugangsdaten - bleibt serverseitig. */
    private record InternesAngebot(String kennung, String anzeige, String endpunktIntern,
                                   String tokenIntern, int tokenLimit) {
    }

    /** Was der Server-Betreiber zur Auswahl bekommt - ohne Zugangsdaten. */
    public List<Angebot> angebote() {
        List<Angebot> liste = new ArrayList<>();
        if (!DB.isAvailable()) {
            return liste;
        }
        String sql = "SELECT kennung, anzeige, token_limit FROM ai_modell"
                + " WHERE bot_id = ? AND waehlbar = true ORDER BY sortierung, kennung";
        try (Connection v = DB.connection();
             PreparedStatement a = v.prepareStatement(sql)) {
            a.setInt(1, botId());
            try (ResultSet r = a.executeQuery()) {
                while (r.next()) {
                    String kennung = r.getString("kennung");
                    String anzeige = text(r.getString("anzeige"));
                    liste.add(new Angebot(kennung,
                            anzeige.isEmpty() ? kennung : anzeige,
                            r.getInt("token_limit")));
                }
            }
        } catch (SQLException fehler) {
            Alert.send("WARN", "KI", "Angebote nicht lesbar: " + fehler.getMessage());
        }
        return liste;
    }

    private Optional<InternesAngebot> angebot(String kennung) {
        if (!DB.isAvailable() || kennung == null || kennung.isBlank()) {
            return Optional.empty();
        }
        String sql = "SELECT kennung, anzeige, endpunkt, api_token, token_limit"
                + " FROM ai_modell WHERE bot_id = ? AND kennung = ? AND waehlbar = true";
        try (Connection v = DB.connection();
             PreparedStatement a = v.prepareStatement(sql)) {
            a.setInt(1, botId());
            a.setString(2, kennung);
            try (ResultSet r = a.executeQuery()) {
                if (!r.next()) {
                    return Optional.empty();
                }
                // Faellt der Endpunkt leer aus, gilt der der Instanz - so
                // laesst sich ein Angebot eintragen, ohne die Adresse zu
                // wiederholen.
                String endpunkt = text(r.getString("endpunkt"));
                String token = Geheimtext.entschluesseln(text(r.getString("api_token")));
                return Optional.of(new InternesAngebot(
                        r.getString("kennung"),
                        text(r.getString("anzeige")),
                        endpunkt.isEmpty() ? instanzEndpunkt() : endpunkt,
                        token.isEmpty() ? configService.getLlmApiKey() : token,
                        r.getInt("token_limit")));
            }
        } catch (SQLException fehler) {
            Alert.send("WARN", "KI", "Angebot nicht lesbar: " + fehler.getMessage());
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------- Werkzeug

    /**
     * Die Adresse, die fuer diese Instanz gilt.
     *
     * <p>Es gibt zwei davon, und welche zaehlt, haengt am Provider. Frueher
     * war das gleichgueltig, weil nur ein Weg benutzt wurde; sobald ein
     * Angebot ohne eigenen Endpunkt eingetragen wird, entscheidet es
     * darueber, wohin die Anfrage geht.</p>
     */
    private String instanzEndpunkt() {
        return switch (configService.getLlmProvider()) {
            case "openai", "openai-compatible" -> configService.getLlmOpenAiBaseUrl();
            default -> configService.getLlmOllamaUrl();
        };
    }

    private String tokenLesen(String guildId) {
        return Geheimtext.entschluesseln(rohToken(guildId));
    }

    private String rohToken(String guildId) {
        if (!DB.isAvailable()) {
            return "";
        }
        try (Connection v = DB.connection();
             PreparedStatement a = v.prepareStatement(
                     "SELECT api_token FROM guild_ai_provider WHERE bot_id = ? AND guild_id = ?")) {
            a.setInt(1, botId());
            a.setString(2, guildId);
            try (ResultSet r = a.executeQuery()) {
                return r.next() ? text(r.getString("api_token")) : "";
            }
        } catch (SQLException fehler) {
            return "";
        }
    }

    /**
     * Der Endpunkt muss eine http- oder https-Adresse sein.
     *
     * <p>Ohne diese Pruefung liesse sich {@code file:///etc/passwd} oder eine
     * Adresse im privaten Netz eintragen, und der Bot fragte sie ab - ein
     * Server-Betreiber koennte den Bot damit als Sonde ins interne Netz
     * benutzen. Die Pruefung ist keine vollstaendige Abwehr davon, aber sie
     * schneidet den einfachsten Weg ab.</p>
     */
    static boolean endpunktTauglich(String endpunkt) {
        if (endpunkt == null || endpunkt.isBlank()) {
            return false;
        }
        String e = endpunkt.trim().toLowerCase(Locale.ROOT);
        return e.startsWith("http://") || e.startsWith("https://");
    }

    private static String text(String wert) {
        return wert == null ? "" : wert.trim();
    }
}
