package eckerlin.dev.web;

import eckerlin.dev.audio.AudioService;
import eckerlin.dev.audio.KnotenRegistrierungService;
import eckerlin.dev.utils.Alert;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.Map;

/**
 * Wo sich Audio-Knoten selbst anmelden.
 *
 * <p>Dieser Endpunkt ist der einzige im Bot, der <em>ohne</em> Discord-Anmeldung
 * erreichbar ist. Er muss es sein: ein frisch aufgesetzter Knoten hat keine
 * Sitzung und keinen Menschen davor. Geschuetzt ist er ueber ein gemeinsames
 * Geheimnis in {@code HJ_NODE_TOKEN}, das derselbe {@code install.sh} auf dem
 * Knoten hinterlegt.</p>
 *
 * <p><strong>Ohne gesetztes Token ist der Endpunkt geschlossen</strong> - nicht
 * offen. Der umgekehrte Standardwert waere bequemer und genau der Fehler, bei
 * dem ein vergessener Eintrag in der {@code .env} jedem im Netz erlaubt, dem
 * Bot einen eigenen Audio-Knoten unterzuschieben. Ueber einen solchen Knoten
 * liefe der gesamte Ton der Server, die auf ihm landen.</p>
 */
@RestController
@RequestMapping("/api/nodes")
public class KnotenAnmeldeController {

    private final KnotenRegistrierungService registrierung;
    private final AudioService audioService;

    public KnotenAnmeldeController(KnotenRegistrierungService registrierung, AudioService audioService) {
        this.registrierung = registrierung;
        this.audioService = audioService;
    }

    @PostMapping("/register")
    public Map<String, Object> anmelden(HttpServletRequest anfrage, @RequestBody Map<String, Object> koerper) {
        tokenPruefen(anfrage);

        KnotenRegistrierungService.Anmeldung anmeldung = new KnotenRegistrierungService.Anmeldung(
                text(koerper, "name"),
                text(koerper, "adresse"),
                text(koerper, "passwort"),
                text(koerper, "stufe"),
                text(koerper, "agentUrl"),
                zahlOderNull(koerper, "hetznerId"),
                Boolean.TRUE.equals(koerper.get("autoscaling"))
        );

        try {
            KnotenRegistrierungService.Ergebnis ergebnis = registrierung.anmelden(anmeldung);

            // Ohne diesen Aufruf laege der Knoten zwar in der Tabelle, waere aber
            // bis zum naechsten Takt der Knotenwache unbenutzt - bis zu 30
            // Sekunden, in denen die Installation "fertig" aussieht und trotzdem
            // nichts tut.
            audioService.knotenNeuEinlesen();

            return Map.of(
                    "status", ergebnis == KnotenRegistrierungService.Ergebnis.NEU ? "neu" : "aktualisiert",
                    "name", anmeldung.name()
            );
        } catch (IllegalArgumentException fehler) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fehler.getMessage());
        } catch (SQLException fehler) {
            Alert.send("WARN", "AUDIO", "Knotenanmeldung fehlgeschlagen: " + fehler.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Knoten konnte nicht eingetragen werden.");
        }
    }

    @PostMapping("/deregister")
    public Map<String, Object> abmelden(HttpServletRequest anfrage, @RequestBody Map<String, Object> koerper) {
        tokenPruefen(anfrage);
        try {
            boolean geaendert = registrierung.abmelden(text(koerper, "name"));
            audioService.knotenNeuEinlesen();
            return Map.of("status", geaendert ? "abgemeldet" : "unbekannt");
        } catch (SQLException fehler) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Abmeldung fehlgeschlagen.");
        }
    }

    /** Lebenszeichen. Der Agent schickt es im Takt, damit Karteileichen auffallen. */
    @PostMapping("/heartbeat")
    public Map<String, Object> lebenszeichen(HttpServletRequest anfrage, @RequestBody Map<String, Object> koerper) {
        tokenPruefen(anfrage);
        registrierung.gesehen(text(koerper, "name"));
        return Map.of("status", "ok");
    }

    // ------------------------------------------------------------------ intern

    private void tokenPruefen(HttpServletRequest anfrage) {
        String erwartet = System.getenv("HJ_NODE_TOKEN");
        if (erwartet == null || erwartet.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Die Selbstanmeldung von Knoten ist nicht eingeschaltet.");
        }

        String kopf = anfrage.getHeader("Authorization");
        String mitgeschickt = kopf != null && kopf.regionMatches(true, 0, "Bearer ", 0, 7)
                ? kopf.substring(7).trim()
                : "";

        // Zeitkonstanter Vergleich: bei String.equals verraet die Laufzeit, wie
        // viele Zeichen gestimmt haben. Bei einem Geheimnis, das ueber das Netz
        // geraten werden kann, ist das kein theoretischer Einwand.
        if (!MessageDigest.isEqual(
                mitgeschickt.getBytes(StandardCharsets.UTF_8),
                erwartet.trim().getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Falsches oder fehlendes Knoten-Token.");
        }
    }

    private String text(Map<String, Object> koerper, String schluessel) {
        Object wert = koerper == null ? null : koerper.get(schluessel);
        return wert == null ? "" : String.valueOf(wert).trim();
    }

    private Long zahlOderNull(Map<String, Object> koerper, String schluessel) {
        Object wert = koerper == null ? null : koerper.get(schluessel);
        if (wert == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(wert).trim());
        } catch (NumberFormatException fehler) {
            return null;
        }
    }
}
