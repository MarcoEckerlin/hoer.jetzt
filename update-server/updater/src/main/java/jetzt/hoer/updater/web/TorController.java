package jetzt.hoer.updater.web;

import jetzt.hoer.updater.daten.KnotenDaten;
import jetzt.hoer.updater.daten.SchluesselDaten;
import jetzt.hoer.updater.dienst.Knotenverwaltung;
import jetzt.hoer.updater.dienst.Pfadrechte;
import jetzt.hoer.updater.dienst.Sicherungen;
import jetzt.hoer.updater.dienst.Torwaechter;
import jetzt.hoer.updater.dienst.Tresorausgabe;
import jetzt.hoer.updater.dienst.Umschlag;
import jetzt.hoer.updater.dienst.Vorfeld;
import jetzt.hoer.updater.dienst.Zugang;
import jetzt.hoer.updater.modell.Ausweis;
import jetzt.hoer.updater.modell.Knoten;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;

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

    /**
     * Traegt die Kennung des angemeldeten Knotens von der Pruefung zur
     * weitergereichten Anfrage. Caddy kopiert ihn per {@code copy_headers};
     * ein vom Aufrufer selbst gesetzter wird davor entfernt - siehe
     * {@code request_header -X-Knoten} im Caddyfile. Beide Zeilen gehoeren
     * zusammen, eine allein genuegt nicht.
     */
    private static final String KNOTEN_KOPF = "X-Knoten";

    private final Torwaechter torwaechter;
    private final Zugang zugang;
    private final KnotenDaten knoten;
    private final Knotenverwaltung verwaltung;
    private final Tresorausgabe tresorausgabe;
    private final SchluesselDaten schluesselDaten;
    private final Sicherungen sicherungen;
    private final Vorfeld vorfeld;

    public TorController(Torwaechter torwaechter, Zugang zugang, KnotenDaten knoten,
                         Knotenverwaltung verwaltung, Tresorausgabe tresorausgabe,
                         SchluesselDaten schluesselDaten, Sicherungen sicherungen,
                         Vorfeld vorfeld) {
        this.vorfeld = vorfeld;
        this.sicherungen = sicherungen;
        this.torwaechter = torwaechter;
        this.zugang = zugang;
        this.knoten = knoten;
        this.verwaltung = verwaltung;
        this.tresorausgabe = tresorausgabe;
        this.schluesselDaten = schluesselDaten;
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
            HttpServletRequest anfrage,
            @RequestHeader(value = "X-Forwarded-Uri", required = false) String pfad) {

        Optional<Ausweis> ausweis = zugang.anmelden(anmeldung);
        if (ausweis.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.WWW_AUTHENTICATE, FORDERE_ANMELDUNG)
                    .body("Passwort fehlt oder stimmt nicht.\n");
        }

        String adresse = vorfeld.adresse(anfrage.getRemoteAddr(), cloudflare, weitergereicht);
        if (!torwaechter.darf(adresse, pfad)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Diese Adresse (" + adresse + ") ist nicht freigeschaltet.\n");
        }

        // Dritte Huerde, neu: darf dieser Knoten *diesen* Pfad?
        //
        // Adresse und Passwort sagen nur, dass ein berechtigter Knoten
        // anklopft - nicht, dass er das Richtige holt. Ohne diese Pruefung
        // kaeme ein Audio-Knoten weiterhin an den Core-Tresor, also an
        // Bot-Token, Datenbank-Passwort und Client-Secret.
        if (!Pfadrechte.darf(ausweis.get(), pfad)) {
            String wer = ausweis.get().kennung();
            log.info("Knoten {} abgewiesen an {} - fehlende Berechtigung.", wer, pfad);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Dieser Knoten ist fuer " + pfad + " nicht berechtigt.\n");
        }

        // Wer etwas holt, war offensichtlich da. Nur bei benannten Knoten -
        // beim gemeinsamen Passwort weiss niemand, wer es tatsaechlich war.
        ausweis.get().kennungOptional().ifPresent(knoten::gesehen);

        // Die Kennung geht als X-Knoten zurueck; Caddy kopiert sie per
        // copy_headers auf die weitergereichte Anfrage. Damit weiss /melden,
        // wer schreibt, ohne es dem Koerper zu glauben.
        return ResponseEntity.noContent()
                .header(KNOTEN_KOPF, ausweis.get().kennungOptional().orElse(""))
                .build();
    }

    /**
     * Caddys forward_auth fuer /knoten/. Nur das kurze Passwort, keine
     * Adresspruefung - ein frischer Rechner ist noch nicht freigeschaltet.
     */
    @RequestMapping(value = "/pruefen-knoten",
                    method = {RequestMethod.GET, RequestMethod.HEAD})
    public ResponseEntity<String> pruefenKnoten(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String anmeldung) {

        // Zwei Wege herein, und der zweite ist der bessere.
        //
        //   knoten:<Aufsetz-Passwort>   global, gilt bis jemand es tauscht
        //   <kennung>:<Aufsetz-Token>   zwei Stunden, ein Knoten, widerrufbar
        //
        // Der Token kam dazu, damit ein Aufsetz-Einzeiler mit EINEM Geheimnis
        // auskommt. Vorher brauchte er zwei - eines zum Holen, eines zum
        // Anmelden -, und auf der Knotenseite steht nur der Token.
        if (zugang.aufsetzPasswort(anmeldung)) {
            return ResponseEntity.noContent().build();
        }

        Zugang.Anmeldedaten daten = zugang.anmeldedaten(anmeldung);
        if (daten != null && verwaltung.aufsetzTokenGueltig(daten.benutzer(), daten.passwort())) {
            return ResponseEntity.noContent().build();
        }

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, FORDERE_ANMELDUNG)
                .body("Passwort fehlt oder stimmt nicht.\n");
    }


    /** Was ein Knoten bei seiner Erstanmeldung schickt. */
    public record Anmeldewunsch(
            String kennung,
            String token,
            String rechnername,
            String privatIp,
            String ipv4,
            String ipv6,
            String agentVersion) {
    }

    /**
     * Die Erstanmeldung: Bootstrap-Token gegen dauerhaftes Geheimnis.
     *
     * <p><strong>Ohne Adresspruefung</strong>, und das muss so sein - ein
     * frisch bestellter Server ist noch nicht freigeschaltet. Dieselbe Lage
     * wie bei {@code /knoten/}; geschuetzt ist der Weg durch den Token selbst,
     * der knotenspezifisch ist, zwei Stunden lebt und genau einmal wirkt.</p>
     *
     * <p>Die Antwort traegt das Geheimnis im Klartext. Das ist der einzige
     * Zeitpunkt, an dem es ueber die Leitung geht - danach kennt der Server
     * nur noch dessen Hash. Deshalb liegt vor diesem Endpunkt TLS, und
     * deshalb steht er nicht im Zugriffsprotokoll mit Koerper.</p>
     */
    @PostMapping("/anmelden")
    public ResponseEntity<Map<String, Object>> anmelden(
            @RequestBody Anmeldewunsch wunsch,
            @RequestHeader(value = "CF-Connecting-IP", required = false) String cloudflare,
            @RequestHeader(value = "X-Forwarded-For", required = false) String weitergereicht,
            HttpServletRequest anfrage) {

        if (wunsch == null || wunsch.kennung() == null || wunsch.kennung().isBlank()
                || wunsch.token() == null || wunsch.token().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("fehler", "kennung und token noetig"));
        }

        String adresse = vorfeld.adresse(anfrage.getRemoteAddr(), cloudflare, weitergereicht);
        Optional<String> geheimnis = verwaltung.anmelden(
                wunsch.kennung().trim().toLowerCase(java.util.Locale.ROOT),
                wunsch.token().trim(),
                new Knotenverwaltung.Selbstauskunft(
                        wunsch.rechnername(), wunsch.privatIp(),
                        wunsch.ipv4(), wunsch.ipv6(), wunsch.agentVersion()),
                adresse);

        if (geheimnis.isEmpty()) {
            // Eine einzige Meldung fuer alle Fehlerfaelle. Ob die Kennung
            // unbekannt, der Token falsch oder schon verbraucht ist, waere
            // eine Auskunft an jemanden, der sie sich gerade erst zu
            // erschleichen versucht.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("fehler", "Anmeldung nicht moeglich"));
        }

        return ResponseEntity.ok(Map.of(
                "kennung", wunsch.kennung().trim().toLowerCase(java.util.Locale.ROOT),
                "geheimnis", geheimnis.get()));
    }

    // ------------------------------------------------------------- Tresor

    /**
     * Der Tresor - verschluesselt an den anfragenden Knoten.
     *
     * <p>Frueher gab Caddy hier eine Datei aus dem Auslieferungsverzeichnis
     * heraus, fuer alle Knoten dieselbe und im Klartext. Jetzt laeuft der
     * Abruf durch den Updater, weil erst hier bekannt ist, <em>wer</em>
     * fragt - und nur so laesst sich die Antwort an ihn richten.</p>
     */
    @GetMapping("/tresor/{profil}")
    public ResponseEntity<String> tresor(@PathVariable String profil,
                                         @RequestHeader(value = KNOTEN_KOPF, required = false)
                                         String angemeldetAls) {
        if (angemeldetAls == null || angemeldetAls.isBlank()) {
            // Das gemeinsame Passwort reicht hier nicht mehr. Ohne Kennung
            // gibt es keinen Schluessel, an den sich etwas richten liesse -
            // und Klartext auszugeben waere genau der Zustand, den dieser
            // Umbau beendet.
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Der Tresor wird nur noch an benannte Knoten ausgegeben.\n"
                          + "Diesen Host im Updater anlegen und aufsetzen.sh mit Token laufen lassen.\n");
        }

        Tresorausgabe.Ergebnis ergebnis = tresorausgabe.holen(angemeldetAls, profil);
        if (ergebnis.gut()) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
                    // Ein Umschlag ist an einen Knoten und einen Zeitpunkt
                    // gebunden. Zwischengespeichert waere er beim naechsten
                    // Schluesseltausch stillschweigend der falsche.
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(ergebnis.umschlag());
        }

        return switch (ergebnis.fehlschlag()) {
            case UNBEKANNTES_PROFIL -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Unbekanntes Tresorprofil: " + profil + "\n");
            case NICHT_BERECHTIGT -> ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Dieser Knoten hat kein Modul, das " + profil + " braucht.\n");
            case KEIN_SCHLUESSEL -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("Fuer diesen Knoten ist kein oeffentlicher Schluessel hinterlegt.\n"
                          + "Auf dem Host: bash aufsetzen.sh --schluessel-neu\n");
            case NICHT_BEFUELLT -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Das Profil " + profil + " ist auf dem Server nicht befuellt.\n"
                          + "Dort: bash tresor.sh fuellen " + profil + "\n");
        };
    }

    /** Womit ein Knoten seinen oeffentlichen Schluessel hinterlegt. */
    public record Schluesselwunsch(String zweck, String oeffentlich) {
    }

    /**
     * Nimmt den oeffentlichen Schluessel eines Knotens entgegen.
     *
     * <p>Der private Teil wird auf dem Knoten erzeugt und kommt hier nie an -
     * das ist der Punkt des Verfahrens. Ein Schluesselwechsel loest den
     * bisherigen ab; alte Umschlaege bleiben damit nachvollziehbar, aber
     * neue gehen an den neuen Schluessel.</p>
     */
    @PostMapping("/schluessel")
    public ResponseEntity<Map<String, Object>> schluessel(
            @RequestBody Schluesselwunsch wunsch,
            @RequestHeader(value = KNOTEN_KOPF, required = false) String angemeldetAls) {

        if (angemeldetAls == null || angemeldetAls.isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("fehler", "nur fuer benannte Knoten"));
        }
        if (wunsch == null || wunsch.oeffentlich() == null || wunsch.oeffentlich().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("fehler", "oeffentlich fehlt"));
        }

        SchluesselDaten.Zweck zweck;
        try {
            zweck = wunsch.zweck() == null || wunsch.zweck().isBlank()
                    ? SchluesselDaten.Zweck.TRESOR
                    : SchluesselDaten.Zweck.valueOf(wunsch.zweck().trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unbekannt) {
            return ResponseEntity.badRequest().body(Map.of("fehler", "unbekannter Zweck"));
        }

        String fingerabdruck;
        try {
            // Vor dem Speichern einmal einlesen. Ein unlesbarer Schluessel
            // faellt sonst erst beim naechsten Tresorabruf auf - also dann,
            // wenn der Knoten ihn braucht und niemand danebensteht.
            fingerabdruck = Umschlag.fingerabdruck(wunsch.oeffentlich());
            Umschlag.ausPem(wunsch.oeffentlich());
        } catch (IllegalArgumentException unlesbar) {
            return ResponseEntity.badRequest()
                    .body(Map.of("fehler", "Schluessel unlesbar: " + unlesbar.getMessage()));
        }

        schluesselDaten.hinterlegen(angemeldetAls, zweck, wunsch.oeffentlich().trim());
        log.info("Knoten {} hat Schluessel {} hinterlegt ({}).",
                angemeldetAls, zweck, fingerabdruck);

        return ResponseEntity.ok(Map.of("zweck", zweck.name(), "fingerabdruck", fingerabdruck));
    }

    /**
     * Nimmt die Sicherung eines Controllers entgegen.
     *
     * <p>Der Name im Pfad ist ein Vorschlag und wird verworfen - der
     * tatsaechliche Name entsteht aus Kennung und Zeitstempel. Ein Dateiname
     * aus der Anfrage, der in einen Pfad wandert, ist der klassische Weg
     * nach {@code ../../}.</p>
     */
    @PostMapping("/sicherung/{name}")
    public ResponseEntity<Map<String, Object>> sicherungAblegen(
            @PathVariable String name,
            @RequestBody byte[] inhalt,
            @RequestHeader(value = KNOTEN_KOPF, required = false) String angemeldetAls) {

        if (angemeldetAls == null || angemeldetAls.isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("fehler", "nur fuer benannte Knoten"));
        }
        try {
            String abgelegt = sicherungen.annehmen(angemeldetAls, inhalt);
            return ResponseEntity.ok(Map.of("abgelegt", abgelegt, "vorschlag", name));
        } catch (IllegalArgumentException falsch) {
            return ResponseEntity.badRequest().body(Map.of("fehler", falsch.getMessage()));
        } catch (java.io.IOException schreibfehler) {
            log.warn("Sicherung von {} nicht ablegbar: {}", angemeldetAls, schreibfehler.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("fehler", "nicht schreibbar"));
        }
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
            @RequestHeader(value = KNOTEN_KOPF, required = false) String angemeldetAls,
            @RequestHeader(value = "CF-Connecting-IP", required = false) String cloudflare,
            @RequestHeader(value = "X-Forwarded-For", required = false) String weitergereicht,
            HttpServletRequest anfrage) {

        if (meldung == null || meldung.kennung() == null || meldung.kennung().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("fehler", "kennung fehlt"));
        }

        // Wer sich angemeldet hat, gewinnt gegen das, was im Koerper steht.
        //
        // Vorher wurde die Kennung ungeprueft aus der Meldung uebernommen -
        // was richtig war, solange alle Knoten dasselbe Passwort hatten und
        // eine Kennung ohnehin nichts bewies. Jetzt beweist sie etwas, und
        // dann darf ein Knoten sie nicht mehr frei waehlen: sonst schriebe
        // ein Audio-Knoten den Eintrag des Controllers um.
        if (angemeldetAls != null && !angemeldetAls.isBlank()
                && !angemeldetAls.equals(meldung.kennung().trim())) {
            log.warn("Knoten {} meldete sich als {} - abgewiesen.",
                    angemeldetAls, meldung.kennung());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("fehler", "Kennung passt nicht zur Anmeldung"));
        }

        String adresse = vorfeld.adresse(anfrage.getRemoteAddr(), cloudflare, weitergereicht);

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
