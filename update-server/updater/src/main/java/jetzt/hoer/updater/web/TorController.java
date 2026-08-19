package jetzt.hoer.updater.web;

import jetzt.hoer.updater.daten.KnotenDaten;
import jetzt.hoer.updater.dienst.Torwaechter;
import jetzt.hoer.updater.modell.Knoten;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final Torwaechter torwaechter;
    private final KnotenDaten knoten;

    public TorController(Torwaechter torwaechter, KnotenDaten knoten) {
        this.torwaechter = torwaechter;
        this.knoten = knoten;
    }

    /**
     * Caddys forward_auth landet hier - einmal je Anfrage an /v2/, /release/
     * und /tresor/.
     *
     * 204 heisst durchlassen, 403 heisst abweisen. Was hier zurueckkommt,
     * sieht der Knoten; deshalb ein kurzer Klartext statt einer leeren Seite.
     *
     * Bewusst GET und HEAD und POST auf demselben Pfad: forward_auth
     * spiegelt die Methode der urspruenglichen Anfrage nicht, aber ein
     * Docker-Pull macht auch HEAD-Anfragen, und Caddy reicht die Pruefung
     * dafuer genauso durch.
     */
    @RequestMapping(value = "/pruefen",
                    method = {RequestMethod.GET, RequestMethod.HEAD, RequestMethod.POST})
    public ResponseEntity<String> pruefen(
            @RequestHeader(value = "X-Echte-Ip", required = false) String echteIp,
            @RequestHeader(value = "X-Forwarded-For", required = false) String weitergereicht,
            @RequestHeader(value = "X-Forwarded-Uri", required = false) String pfad) {

        String adresse = adresseBestimmen(echteIp, weitergereicht);

        if (torwaechter.darf(adresse, pfad)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body("Diese Adresse (" + adresse + ") ist nicht freigeschaltet.\n");
    }

    /**
     * X-Echte-Ip setzt Caddy selbst und ueberschreibt dabei, was der Aufrufer
     * geschickt hat - die Angabe ist damit nicht faelschbar.
     *
     * Der Rueckfall auf X-Forwarded-For nimmt den *letzten* Eintrag, nicht
     * den ersten. Ein Aufrufer kann diesen Kopf selbst mitschicken; Caddy
     * haengt die tatsaechlich gesehene Adresse hinten an. Der erste Eintrag
     * ist also der, den sich der Aufrufer ausgesucht hat - wer den nimmt,
     * laesst sich die Zugangskontrolle vom Aufrufer diktieren.
     */
    private static String adresseBestimmen(String echteIp, String weitergereicht) {
        if (echteIp != null && !echteIp.isBlank()) {
            return echteIp.trim();
        }
        if (weitergereicht != null && !weitergereicht.isBlank()) {
            String[] teile = weitergereicht.split(",");
            return teile[teile.length - 1].trim();
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
     * Der Herzschlag. Kommt durch Caddy und damit nur mit gueltigem Ausweis
     * und von einer freigeschalteten Adresse - die Pruefung steht davor,
     * nicht hier.
     *
     * Die Antwort traegt den Merker "sofort aktualisieren" zurueck. Das ist
     * der einzige Weg in Richtung Knoten: es geht keine Verbindung von hier
     * aus dorthin, die Knoten stehen hinter fremdem NAT.
     */
    @PostMapping("/melden")
    public ResponseEntity<Map<String, Object>> melden(
            @RequestBody Meldung meldung,
            @RequestHeader(value = "X-Echte-Ip", required = false) String echteIp,
            @RequestHeader(value = "X-Forwarded-For", required = false) String weitergereicht) {

        if (meldung == null || meldung.kennung() == null || meldung.kennung().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("fehler", "kennung fehlt"));
        }

        String adresse = adresseBestimmen(echteIp, weitergereicht);

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
