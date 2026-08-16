package eckerlin.dev.utils;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Legt Umgebungsvariablen ueber die geladene {@code config.json}.
 *
 * <p>Ohne das ist der Container unbrauchbar: man muesste eine Datei
 * hineinreichen, nur um den Bot-Token zu setzen. Mit Overlay bleibt die Datei
 * der dokumentierte Normalfall, und im Betrieb gewinnt die Umgebung.</p>
 *
 * <p>Bewusst eine feste Zuordnung statt einer generischen Ableitung aus dem
 * Schluesselnamen: eine Variable, die stillschweigend etwas anderes setzt als
 * gedacht, ist im Betrieb kaum zu finden. Hier steht jede Zuordnung schwarz auf
 * weiss.</p>
 *
 * <p>Leere Variablen zaehlen als nicht gesetzt. Sonst wuerde ein leeres
 * {@code HJ_LLM_OLLAMA_URL} aus einer Beispieldatei die funktionierende
 * Einstellung aus der Datei ueberschreiben.</p>
 */
public final class ConfigEnvOverlay {

    /** Umgebungsvariable -> Pfad in der Konfiguration, mit Punkt getrennt. */
    private static final Map<String, String> ZUORDNUNG = new LinkedHashMap<>();

    static {
        ZUORDNUNG.put("HJ_BOT_TOKEN", "bot.token");
        ZUORDNUNG.put("HJ_BOT_ACTIVITY", "bot.activity");
        ZUORDNUNG.put("HJ_BOT_STATUS", "bot.status");

        ZUORDNUNG.put("HJ_BOT_ID", "bot_id");

        ZUORDNUNG.put("HJ_DB_HOST", "database.host");
        ZUORDNUNG.put("HJ_DB_PORT", "database.port");
        ZUORDNUNG.put("HJ_DB_NAME", "database.name");
        ZUORDNUNG.put("HJ_DB_USER", "database.user");
        ZUORDNUNG.put("HJ_DB_PASSWORD", "database.password");

        ZUORDNUNG.put("HJ_DEPLOYMENT_KEY", "deployment.key");
        ZUORDNUNG.put("HJ_DEPLOYMENT_NAME", "deployment.display_name");

        ZUORDNUNG.put("HJ_WEB_PORT", "webinterface.port");
        ZUORDNUNG.put("HJ_WEB_BASE_URL", "webinterface.base_url");
        ZUORDNUNG.put("HJ_WEB_REDIRECT_URI", "webinterface.redirect_uri");
        ZUORDNUNG.put("HJ_DISCORD_CLIENT_ID", "webinterface.discord_client_id");
        ZUORDNUNG.put("HJ_DISCORD_CLIENT_SECRET", "webinterface.discord_client_secret");

        ZUORDNUNG.put("HJ_LAVALINK_NAME", "lavalink.name");
        ZUORDNUNG.put("HJ_LAVALINK_URI", "lavalink.uri");
        ZUORDNUNG.put("HJ_LAVALINK_PASSWORD", "lavalink.password");
        // Sekunden zwischen zwei Abgleichen der Knotentabelle. 0 = aus.
        ZUORDNUNG.put("HJ_LAVALINK_WATCH_SECONDS", "lavalink.watch_seconds");
        // Duerfen Standard-Server auf Premium ausweichen, wenn Standard voll ist?
        ZUORDNUNG.put("HJ_LAVALINK_FREE_OVERFLOW", "lavalink.free_overflow");
        ZUORDNUNG.put("HJ_LAVALINK_OVERFLOW_CPU", "lavalink.overflow_cpu");
        ZUORDNUNG.put("HJ_LAVALINK_PREMIUM_RESERVE", "lavalink.premium_reserve");

        ZUORDNUNG.put("HJ_LLM_PROVIDER", "llm.provider");
        ZUORDNUNG.put("HJ_LLM_OLLAMA_URL", "llm.ollama_url");
        ZUORDNUNG.put("HJ_LLM_API_KEY", "llm.api_key");
        ZUORDNUNG.put("HJ_LLM_MODEL", "llm.model");

        ZUORDNUNG.put("HJ_MUSIC_BRAIN_BASE_URL", "music_brain.base_url");

        // Redis: leerer Host = Einzelbetrieb, alles laeuft wie bisher.
        ZUORDNUNG.put("HJ_REDIS_HOST", "redis.host");
        ZUORDNUNG.put("HJ_REDIS_PORT", "redis.port");
        ZUORDNUNG.put("HJ_REDIS_PASSWORD", "redis.password");

        ZUORDNUNG.put("HJ_MCP_ENABLED", "mcp.enabled");
        ZUORDNUNG.put("HJ_MCP_TOKEN", "mcp.token");
    }

    private ConfigEnvOverlay() {
    }

    /**
     * Traegt gesetzte Umgebungsvariablen in die Konfiguration ein.
     *
     * @return Anzahl der uebernommenen Werte, fuer die Startmeldung
     */
    public static int apply(JSONObject config) {
        return apply(config, System.getenv());
    }

    /** Getrennt testbar: die Umgebung wird hereingereicht statt gelesen. */
    static int apply(JSONObject config, Map<String, String> umgebung) {
        if (config == null) {
            return 0;
        }
        int uebernommen = 0;
        for (Map.Entry<String, String> eintrag : ZUORDNUNG.entrySet()) {
            String wert = umgebung.get(eintrag.getKey());
            if (wert == null || wert.isBlank()) {
                continue;
            }
            setzen(config, eintrag.getValue(), wert.trim());
            uebernommen++;
        }
        return uebernommen;
    }

    /** Namen der gesetzten Variablen - ohne Werte, damit nichts ins Log gerät. */
    public static java.util.List<String> gesetzteVariablen() {
        java.util.List<String> namen = new java.util.ArrayList<>();
        for (String name : ZUORDNUNG.keySet()) {
            String wert = System.getenv(name);
            if (wert != null && !wert.isBlank()) {
                namen.add(name);
            }
        }
        return namen;
    }

    private static void setzen(JSONObject config, String pfad, String wert) {
        String[] teile = pfad.split("\\.");
        JSONObject ebene = config;
        for (int i = 0; i < teile.length - 1; i++) {
            JSONObject naechste = ebene.optJSONObject(teile[i]);
            if (naechste == null) {
                naechste = new JSONObject();
                ebene.put(teile[i], naechste);
            }
            ebene = naechste;
        }
        ebene.put(teile[teile.length - 1], typisieren(pfad, wert));
    }

    /**
     * Zahlen und Wahrheitswerte als solche ablegen - aber nur dort, wo sie
     * hingehoeren.
     *
     * <p>{@code webinterface.port} als Zeichenkette liesse Spring beim Start
     * mit einer Typumwandlung scheitern, und zwar spaet und mit einer Meldung,
     * die nicht auf die Umgebungsvariable zeigt.
     *
     * <p>Umgekehrt darf eine Discord-ID <em>nicht</em> zur Zahl werden: sie ist
     * eine Kennung, keine Menge. Als Zahl abgelegt braeche sie bei jedem
     * Zugriff, der eine Zeichenkette erwartet - deshalb die ausdrueckliche
     * Liste statt "sieht aus wie eine Zahl, also ist es eine".
     */
    private static final Set<String> ZAHLEN = Set.of(
            "webinterface.port",
            "database.port",
            "bot_id",
            "lavalink.watch_seconds",
            "lavalink.premium_reserve",
            "redis.port"
    );

    private static final Set<String> KOMMAZAHLEN = Set.of(
            "lavalink.overflow_cpu"
    );

    private static final Set<String> WAHRHEITSWERTE = Set.of(
            "mcp.enabled",
            "lavalink.free_overflow"
    );

    private static Object typisieren(String pfad, String wert) {
        if (WAHRHEITSWERTE.contains(pfad)) {
            return Boolean.parseBoolean(wert);
        }
        if (KOMMAZAHLEN.contains(pfad)) {
            try {
                // Auch "0,85" annehmen: das Komma ist auf einer deutschen
                // Tastatur der naheliegende Trenner und der Fehler waere sonst
                // stumm - die Zeichenkette landete als Vorgabe im Nichts.
                return Double.parseDouble(wert.replace(',', '.'));
            } catch (NumberFormatException ignored) {
                return wert;
            }
        }
        if (ZAHLEN.contains(pfad)) {
            try {
                return Integer.parseInt(wert);
            } catch (NumberFormatException ignored) {
                // Unbrauchbare Zahl: lieber die Zeichenkette weiterreichen und
                // die Beschwerde dort entstehen lassen, wo der Wert gebraucht
                // wird - dann steht der Zusammenhang wenigstens im Log.
                return wert;
            }
        }
        return wert;
    }
}
