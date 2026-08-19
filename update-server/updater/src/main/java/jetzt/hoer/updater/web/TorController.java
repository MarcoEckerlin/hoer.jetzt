package jetzt.hoer.updater.web;

import jetzt.hoer.updater.daten.KnotenDaten;
import jetzt.hoer.updater.dienst.Torwaechter;
import jetzt.hoer.updater.dienst.Zugang;
import jetzt.hoer.updater.modell.Knoten;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/**
 * Die Maschinen-Schnittstelle. Haengt am Tor-Port, der im Compose-Verbund
 * nicht auf den Host gelegt wird - erreichbar ist sie nur aus dem internen
 * Docker-Netz, also durch Caddy.
 */
@RestController
@RequestMapping("/intern")
public class TorController {

    private static final Logger log = LoggerFactory.getLogger(TorController.class);

    /**
     * Ohne diesen Kopf fragt "docker login" nicht nach Zugangsdaten, sondern
     * gibt auf. Der Wert muss "Basic" sein - bei "Bearer" versucht Docker den
     * Token-Ablauf der Registry-Spezifikation und landet im Nichts.
     */
    private static final String FORDERE_ANMELDUNG = "Basic realm=\"hoer.jetzt\"";

    private final Torwaechter torwaechter;
    private final Zugang zugang;
    private final KnotenDaten knoten;

    public TorController(Torwaechter torwaechter, Zugang zugang, KnotenDaten knoten) {
        this.torwaechter = torwaechter;
        this.zugang = zugang;
        this.knoten = knoten;
    }

    /**
     * Caddys forward_auth fuer alles ausser /knoten/.
     *
     * Zwei Huerden, und die Reihenfolge ist bewusst so: erst das Passwort,
     * dann die Adresse. Wer das Passwort nicht hat, soll nicht erfahren, ob
     * seine Adresse freigeschaltet waere - das waere eine Auskunft ueber die
     * Freigabeliste an jeden, der anklopft.
     */
    @RequestMapping(value = "/pruefen",
                    method = {RequestMethod.GET, RequestMethod.HEAD, RequestMethod.POST})
    public ResponseEntity<String> pruefen(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String anmeldung,
            @RequestHeader(value = "CF-Connecting-IP", required = false) String cloudflare,
            @RequestHeader(value = "X-Forwarded-For", required = false) String weitergereicht,
            @RequestHeader(value = "X-Forwarded-Uri", required = false) String pfad) {

        if (!zugang.knotenPasswort(anmeldung)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.WWW_AUTHENTICATE, FORDERE_ANMELDUNG)
                    .body("Passwort fehlt oder stimmt nicht.\n");
        }

        String adresse = adresseBestimmen(cloudflare, weitergereicht);
        if (torwaechter.darf(adresse, pfad)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body("Diese Adresse (" + adresse + ") ist nicht freigeschaltet.\n");
    }

    /**
     * Caddys forward_auth fuer /knoten/. Nur das kurze Passwort, keine
     * Adresspruefung - ein frischer Rechner ist noch nicht freigeschaltet.
     */
    @RequestMapping(value = "/pruefen-knoten",
                    method = {RequestMethod.GET, RequestMethod.HEAD})
    public ResponseEntity<String> pruefenKnoten(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String anmeldung) {

        if (zugang.aufsetzPasswort(anmeldung)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, FORDERE_ANMELDUNG)
                .body("Passwort fehlt oder stimmt nicht.\n");
    }

    /**
     * Welche Adresse zaehlt.
     *
     * Die Kette ist Cloudflare -> Nginx Proxy Manager -> Caddy -> hier. Die
     * Gegenstelle der Verbindung ist damit der NPM und nie der Knoten; die
     * echte Adresse steht nur in den Koepfen.
     *
     * CF-Connecting-IP setzt Cloudflare selbst und ueberschreibt dabei, was
     * der Aufrufer geschickt hat - solange der Verkehr wirklich durch
     * Cloudflare laeuft, ist die Angabe nicht faelschbar.
     *
     * Der Rueckfall auf X-Forwarded-For nimmt den *ersten* Eintrag. Das ist
     * hier richtig und anderswo falsch: jede Zwischenstelle haengt hinten an,
     * vorne steht also der urspruengliche Aufrufer. Bei nur einer
     * Zwischenstelle waere der letzte Eintrag der richtige - deshalb muss
     * diese Entscheidung zur tatsaechlichen Kette passen und nicht zu einer
     * Faustregel.
     *
     * Das heisst zugleich: wer den Update-Server unter Umgehung von
     * Cloudflare und NPM direkt erreichen kann, kann sich eine Adresse
     * aussuchen. Der Port darf deshalb nicht offen im Netz stehen - er
     * gehoert ins LAN, und davor der NPM.
     */
    private static String adresseBestimmen(String cloudflare, String weitergereicht) {
        if (cloudflare != null && !cloudflare.isBlank()) {
            return cloudflare.trim();
        }
        if (weitergereicht != null && !weitergereicht.isBlank()) {
            return weitergereicht.split(",")[0].trim();
        }
        return "";
    }

    /** Was ein Knoten nach einem Update-Lauf von sich meldet. */
    public record Meldung(
            String kennung,
            String name,
            String profil,
            String version,
            String vorher,
            String zustand,
            String ergebnis) {
    }

    /**
     * Der Herzschlag. Kommt durch Caddy und ist damit schon geprueft - die
     * Pruefung steht davor, nicht hier.
     *
     * Die Antwort traegt den Merker "sofort aktualisieren" zurueck. Das ist
     * der einzige Weg in Richtung Knoten: es geht keine Verbindung von hier
     * aus dorthin, die Knoten stehen hinter fremdem NAT.
     */
    @PostMapping("/melden")
    public ResponseEntity<Map<String, Object>> melden(
            @RequestBody Meldung meldung,
            @RequestHeader(value = "CF-Connecting-IP", required = false) String cloudflare,
            @RequestHeader(value = "X-Forwarded-For", required = false) String weitergereicht) {

        if (meldung == null || meldung.kennung() == null || meldung.kennung().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("fehler", "kennung fehlt"));
        }

        String adresse = adresseBestimmen(cloudflare, weitergereicht);

        // Der Merker wird vor dem Speichern gelesen: melden() setzt ihn
        // zurueck, und der Knoten soll ihn in genau dieser Antwort noch
        // bekommen. Andersherum ginge er verloren.
        boolean angefordert = knoten.einer(meldung.kennung())
                .map(Knoten::updateAngefordert)
                .orElse(false);

        knoten.melden(
                meldung.kennung().trim(),
                Optional.ofNullable(meldung.name()).orElse(""),
                Optional.ofNullable(meldung.profil()).orElse(""),
                meldung.version(),
                meldung.vorher(),
                meldung.zustand(),
                meldung.ergebnis(),
                adresse);

        log.info("Herzschlag von {} ({}): {} - {}",
                meldung.kennung(), adresse, meldung.version(), meldung.ergebnis());

        return ResponseEntity.ok(Map.of("update_angefordert", angefordert));
    }
}
