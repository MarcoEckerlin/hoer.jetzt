package jetzt.hoer.updater.web;

import jetzt.hoer.updater.daten.FreigabeDaten;
import jetzt.hoer.updater.daten.KnotenDaten;
import jetzt.hoer.updater.daten.ZugriffDaten;
import jetzt.hoer.updater.dienst.Netzbereich;
import jetzt.hoer.updater.dienst.Torwaechter;
import jetzt.hoer.updater.modell.Knoten;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Die Oberflaeche. Haengt am Pult-Port, der im privaten Netz liegt.
 *
 * Jede Aenderung an den Freigaben verwirft den Zwischenspeicher des
 * Torwaechters - sonst wirkte eine Sperre erst nach dessen Haltbarkeit, und
 * dreissig Sekunden Verzoegerung sind genau dann laestig, wenn man es eilig
 * hat.
 */
@Controller
public class PultController {

    private final FreigabeDaten freigaben;
    private final KnotenDaten knoten;
    private final ZugriffDaten zugriffe;
    private final Torwaechter torwaechter;

    public PultController(FreigabeDaten freigaben, KnotenDaten knoten,
                          ZugriffDaten zugriffe, Torwaechter torwaechter) {
        this.freigaben = freigaben;
        this.knoten = knoten;
        this.zugriffe = zugriffe;
        this.torwaechter = torwaechter;
    }

    // ------------------------------------------------------------ Uebersicht

    @GetMapping("/")
    public String uebersicht(Model modell) {
        Instant jetzt = Instant.now();
        List<Knoten> liste = knoten.alle();

        modell.addAttribute("knoten", liste);
        modell.addAttribute("jetzt", jetzt);
        modell.addAttribute("stumme", liste.stream().filter(k -> k.stumm(jetzt)).count());
        // Wenn nicht ueberall dasselbe laeuft, ist das die erste Frage, die
        // man an diese Seite hat - deshalb steht sie oben und nicht in einer
        // Spalte, die man vergleichen muss.
        modell.addAttribute("versionen",
                liste.stream().map(Knoten::version).filter(v -> v != null && !v.isBlank())
                        .distinct().sorted().toList());
        modell.addAttribute("abgelehnt", zugriffe.letzteAbgelehnte(5));
        return "uebersicht";
    }

    @PostMapping("/knoten/{kennung}/name")
    public String umbenennen(@PathVariable String kennung, @RequestParam String name) {
        knoten.umbenennen(kennung, name.trim());
        return "redirect:/";
    }

    @PostMapping("/knoten/{kennung}/update")
    public String updateAnfordern(@PathVariable String kennung,
                                  @RequestParam(defaultValue = "true") boolean an,
                                  RedirectAttributes hinweis) {
        knoten.updateAnfordern(kennung, an);
        hinweis.addFlashAttribute("meldung", an
                ? "Vorgemerkt. Der Knoten holt es beim naechsten Herzschlag ab - "
                  + "es geht keine Verbindung von hier zu ihm."
                : "Vormerkung zurueckgenommen.");
        return "redirect:/";
    }

    @PostMapping("/knoten/{kennung}/loeschen")
    public String knotenLoeschen(@PathVariable String kennung) {
        knoten.loeschen(kennung);
        return "redirect:/";
    }

    // ------------------------------------------------------------- Freigaben

    @GetMapping("/freigaben")
    public String freigaben(Model modell) {
        modell.addAttribute("freigaben", freigaben.alle());
        modell.addAttribute("jetzt", Instant.now());
        modell.addAttribute("gemerkt", torwaechter.gemerkt());
        // Die haeufigste Freischaltung ist die Adresse, die es gerade
        // vergeblich versucht hat. Sie hier anzubieten spart das Abtippen -
        // und das Abtippen ist die Stelle, an der man sich vertut.
        modell.addAttribute("abgelehnt", zugriffe.letzteAbgelehnte(10));
        return "freigaben";
    }

    @PostMapping("/freigaben")
    public String anlegen(@RequestParam String bereich,
                          @RequestParam(defaultValue = "") String name,
                          @RequestParam(defaultValue = "") String notiz,
                          @RequestParam(defaultValue = "") String laeuftAb,
                          RedirectAttributes hinweis) {
        try {
            // Ueber Netzbereich, nicht roh: das ergaenzt eine einzelne Adresse
            // zu /32 bzw. /128 und nullt die Wirtsbits aus. Sonst stuende
            // "10.1.2.3/8" in der Liste und passte auf sich selbst nicht.
            String normal = Netzbereich.aus(bereich).toString();

            Instant ablauf = null;
            if (!laeuftAb.isBlank()) {
                ablauf = LocalDate.parse(laeuftAb.trim())
                        .plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            }

            freigaben.anlegen(normal, name.trim(), notiz.trim(), ablauf);
            torwaechter.verwerfen();
            hinweis.addFlashAttribute("meldung", normal + " ist freigeschaltet.");
        } catch (IllegalArgumentException e) {
            hinweis.addFlashAttribute("fehler", "Das ist keine gueltige Adresse: " + e.getMessage());
        } catch (java.time.format.DateTimeParseException e) {
            hinweis.addFlashAttribute("fehler", "Das Ablaufdatum ist unlesbar - erwartet wird JJJJ-MM-TT.");
        }
        return "redirect:/freigaben";
    }

    @PostMapping("/freigaben/{id}/sperren")
    public String sperren(@PathVariable long id) {
        freigaben.sperren(id);
        torwaechter.verwerfen();
        return "redirect:/freigaben";
    }

    @PostMapping("/freigaben/{id}/freigeben")
    public String wiederFreigeben(@PathVariable long id) {
        freigaben.freigeben(id);
        torwaechter.verwerfen();
        return "redirect:/freigaben";
    }

    @PostMapping("/freigaben/{id}/loeschen")
    public String freigabeLoeschen(@PathVariable long id) {
        freigaben.loeschen(id);
        torwaechter.verwerfen();
        return "redirect:/freigaben";
    }

    // ------------------------------------------------------------ Protokoll

    @GetMapping("/protokoll")
    public String protokoll(Model modell) {
        modell.addAttribute("zugriffe", zugriffe.letzte(200));
        modell.addAttribute("jetzt", Instant.now());
        return "protokoll";
    }

    // ------------------------------------------------------------- Anmelden

    @GetMapping("/anmelden")
    public String anmelden() {
        return "anmelden";
    }

    /**
     * "vor 3 Minuten" statt eines Zeitstempels. Bei der Frage, ob ein Knoten
     * noch da ist, will man eine Dauer wissen und keine Uhrzeit umrechnen.
     */
    @ModelAttribute("seit")
    public java.util.function.BiFunction<Instant, Instant, String> seit() {
        return (zeit, jetzt) -> {
            if (zeit == null) return "nie";
            long sekunden = Duration.between(zeit, jetzt).getSeconds();
            if (sekunden < 0) return "gerade eben";
            if (sekunden < 90) return "vor " + sekunden + " s";
            long minuten = sekunden / 60;
            if (minuten < 90) return "vor " + minuten + " min";
            long stunden = minuten / 60;
            if (stunden < 48) return "vor " + stunden + " h";
            return "vor " + (stunden / 24) + " Tagen";
        };
    }
}
