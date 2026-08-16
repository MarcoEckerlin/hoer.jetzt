package eckerlin.dev.audio;

import eckerlin.dev.utils.Alert;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Spricht mit dem Agenten auf einem Knoten-Host.
 *
 * <p>Der Agent kann drei Dinge, die vom Bot aus bisher nicht gingen: den
 * Lavalink-Container wirklich neu starten, den Knoten aktualisieren und
 * berichten, wie es ihm geht. "Neu verbinden" im Adminbereich kappt nur die
 * Verbindung von dieser Seite - ein haengender Container blieb haengen.</p>
 *
 * <h2>Zwei Geheimnisse, nicht eins</h2>
 *
 * <p>{@code HJ_NODE_TOKEN} ist das gemeinsame Geheimnis, mit dem sich Knoten
 * beim Bot anmelden. Fuer den Weg zurueck gilt {@code HJ_AGENT_TOKEN}. Sie
 * getrennt zu halten kostet eine Zeile in der {@code .env} und bedeutet, dass
 * ein abgegriffenes Anmeldetoken - das jeder Knoten kennt - nicht ausreicht,
 * um auf allen Hosts Container neu zu starten.</p>
 *
 * <p>Alle Aufrufe haben ein knappes Zeitlimit. Ein Knoten-Host, der nicht mehr
 * antwortet, ist der haeufigste Grund, ueberhaupt hier zu landen - dann darf
 * die Weboberflaeche nicht mit ihm zusammen stehenbleiben.</p>
 */
@Service
public class KnotenAgentService {

    private static final Duration VERBINDEN = Duration.ofSeconds(4);
    private static final Duration ANTWORT = Duration.ofSeconds(10);

    private final KnotenRegistrierungService registrierung;
    private final HttpClient klient = HttpClient.newBuilder()
            .connectTimeout(VERBINDEN)
            // Ein Umzug auf eine andere Adresse waere hier keine Hilfe, sondern
            // ein Weg, versehentlich mit dem falschen Host zu reden.
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public KnotenAgentService(KnotenRegistrierungService registrierung) {
        this.registrierung = registrierung;
    }

    public record Antwort(boolean ok, String meldung, JSONObject inhalt) {
    }

    public boolean hatAgent(String knotenName) {
        return registrierung.agentUrl(knotenName).isPresent();
    }

    /** Zustand des Knoten-Hosts. Leer, wenn kein Agent hinterlegt ist oder er schweigt. */
    public Optional<JSONObject> zustand(String knotenName) {
        Antwort antwort = rufe(knotenName, "GET", "/zustand", null);
        return antwort.ok() ? Optional.ofNullable(antwort.inhalt()) : Optional.empty();
    }

    public Antwort neustarten(String knotenName) {
        Antwort antwort = rufe(knotenName, "POST", "/neustart", "{}");
        Alert.send(antwort.ok() ? "INFO" : "WARN", "AUDIO",
                "Neustart von %s: %s".formatted(knotenName, antwort.meldung()));
        return antwort;
    }

    public Antwort aktualisieren(String knotenName) {
        // Bewusst laenger: der Agent antwortet zwar sofort, aber er startet
        // vorher noch systemd-run - auf einem gerade beschaeftigten Host kann
        // das ein paar Sekunden dauern.
        Antwort antwort = rufe(knotenName, "POST", "/aktualisieren", "{}");
        Alert.send(antwort.ok() ? "INFO" : "WARN", "AUDIO",
                "Aktualisierung von %s: %s".formatted(knotenName, antwort.meldung()));
        return antwort;
    }

    public Optional<String> protokoll(String knotenName) {
        Antwort antwort = rufe(knotenName, "GET", "/protokoll", null);
        return antwort.ok() ? Optional.of(antwort.meldung()) : Optional.empty();
    }

    // ------------------------------------------------------------------ intern

    private Antwort rufe(String knotenName, String verfahren, String pfad, String koerper) {
        String token = System.getenv("HJ_AGENT_TOKEN");
        if (token == null || token.isBlank()) {
            return new Antwort(false, "HJ_AGENT_TOKEN ist nicht gesetzt - der Bot kann keinen Agenten ansprechen.", null);
        }

        Optional<String> basis = registrierung.agentUrl(knotenName);
        if (basis.isEmpty()) {
            return new Antwort(false, "Für diesen Knoten ist kein Agent hinterlegt. "
                    + "Das betrifft Knoten, die von Hand eingetragen wurden.", null);
        }

        URI ziel;
        try {
            ziel = URI.create(basis.get().replaceAll("/+$", "") + pfad);
        } catch (IllegalArgumentException fehler) {
            return new Antwort(false, "Die hinterlegte Agent-Adresse ist unbrauchbar: " + basis.get(), null);
        }

        HttpRequest.Builder bau = HttpRequest.newBuilder(ziel)
                .timeout(ANTWORT)
                .header("Authorization", "Bearer " + token.trim());

        if ("POST".equals(verfahren)) {
            bau.header("Content-Type", "application/json")
               .POST(HttpRequest.BodyPublishers.ofString(koerper == null ? "{}" : koerper));
        } else {
            bau.GET();
        }

        try {
            HttpResponse<String> antwort = klient.send(bau.build(), HttpResponse.BodyHandlers.ofString());
            String text = antwort.body() == null ? "" : antwort.body();

            if (antwort.statusCode() == 401) {
                return new Antwort(false, "Der Agent weist das Token ab. "
                        + "HJ_AGENT_TOKEN muss auf beiden Seiten gleich sein.", null);
            }
            if (antwort.statusCode() >= 400) {
                return new Antwort(false, "Der Agent antwortet mit " + antwort.statusCode() + ": " + kurz(text), null);
            }

            JSONObject inhalt = null;
            String meldung = kurz(text);
            if (text.startsWith("{")) {
                try {
                    inhalt = new JSONObject(text);
                    if (inhalt.has("meldung")) {
                        meldung = inhalt.optString("meldung");
                    }
                } catch (RuntimeException fehler) {
                    // Kein JSON - dann eben der Rohtext. /protokoll liefert
                    // absichtlich Klartext.
                }
            }
            return new Antwort(true, meldung, inhalt);
        } catch (java.io.IOException fehler) {
            return new Antwort(false, "Der Knoten-Host antwortet nicht: " + fehler.getMessage(), null);
        } catch (InterruptedException fehler) {
            Thread.currentThread().interrupt();
            return new Antwort(false, "Abgebrochen.", null);
        }
    }

    private String kurz(String text) {
        String sauber = text == null ? "" : text.strip();
        return sauber.length() > 600 ? sauber.substring(0, 600) + "…" : sauber;
    }

    /** Für die Weboberfläche: knappe Zusammenfassung statt des rohen JSON. */
    public Map<String, Object> uebersicht(String knotenName) {
        return zustand(knotenName)
                .map(inhalt -> Map.<String, Object>of(
                        "erreichbar", true,
                        "containerStatus", inhalt.optJSONObject("docker") == null
                                ? "?" : inhalt.optJSONObject("docker").optString("status", "?"),
                        "lavalink", inhalt.optString("lavalink", ""),
                        "agentVersion", inhalt.optString("agentVersion", ""),
                        "aktualisierungLaeuft", inhalt.optBoolean("aktualisierungLaeuft", false)
                ))
                .orElse(Map.of("erreichbar", false));
    }
}
