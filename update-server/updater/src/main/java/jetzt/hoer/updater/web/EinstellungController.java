package jetzt.hoer.updater.web;

import jetzt.hoer.updater.dienst.Einstellungskatalog;
import jetzt.hoer.updater.dienst.Voreinstellungen;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Die Seite fuer die zentralen Vorgaben.
 *
 * <p>Bis hierher wurde jeder dieser Werte beim Aufsetzen eines Knotens von
 * Hand eingetippt - und beim naechsten Knoten wieder. Wer die Puffergroesse
 * von Lavalink aendern wollte, aenderte sie auf jeder Maschine einzeln.</p>
 *
 * <p>Was hier steht, holt sich jeder Knoten beim naechsten Lauf des Agenten.
 * Es geht keine Verbindung von hier zu ihm; das steht auch auf der Seite,
 * damit "Speichern" nicht nach einem sofortigen Vorgang aussieht.</p>
 */
@Controller
public class EinstellungController {

    private final Voreinstellungen vorgaben;

    public EinstellungController(Voreinstellungen vorgaben) {
        this.vorgaben = vorgaben;
    }

    private static String name(Principal wer) {
        return wer == null ? "unbekannt" : wer.getName();
    }

    @GetMapping("/voreinstellungen")
    public String seite(Model modell) {
        Map<String, String> gesetzt = vorgaben.werte();

        // Was im Formular stehen soll: der gesetzte Wert, sonst leer. Nicht
        // die Vorgabe - sonst saehe jedes Feld gesetzt aus, und beim ersten
        // Speichern stuenden alle Vorgaben als eigene Werte in der Datenbank.
        // Danach folgte keiner davon mehr einer geaenderten Vorgabe.
        modell.addAttribute("gesetzt", gesetzt);
        modell.addAttribute("gruppen", Einstellungskatalog.nachGruppe());
        modell.addAttribute("profile", Einstellungskatalog.PROFILE);

        Map<String, String> vorschau = new LinkedHashMap<>();
        for (String profil : Einstellungskatalog.PROFILE) {
            vorschau.put(profil, vorgaben.vorschau(profil));
        }
        modell.addAttribute("vorschau", vorschau);
        return "voreinstellungen";
    }

    /**
     * Das Formular uebernehmen.
     *
     * <p>Alles oder nichts - siehe {@link Voreinstellungen#uebernehmen}. Ein
     * Tippfehler im letzten Feld darf nicht die Haelfte der Aenderungen
     * stehen lassen.</p>
     */
    @PostMapping("/voreinstellungen")
    public String speichern(@RequestParam Map<String, String> formular,
                            Principal wer,
                            RedirectAttributes hinweis) {
        // Spring legt hier auch das CSRF-Feld hinein. Der Katalog kennt es
        // nicht und Voreinstellungen uebergeht es - trotzdem raus, damit im
        // Protokoll keine Warnung ueber einen "unbekannten Schluessel" steht,
        // die nichts bedeutet.
        Map<String, String> sauber = new LinkedHashMap<>(formular);
        sauber.remove("_csrf");

        try {
            int wieViele = vorgaben.uebernehmen(sauber, name(wer));
            hinweis.addFlashAttribute("meldung", wieViele == 0
                    ? "Nichts geaendert."
                    : wieViele + " Werte uebernommen. Die Knoten holen sie beim naechsten "
                      + "Lauf des Agenten - es geht keine Verbindung von hier zu ihnen.");
        } catch (IllegalArgumentException | IllegalStateException falsch) {
            hinweis.addFlashAttribute("fehler", falsch.getMessage());
        }
        return "redirect:/voreinstellungen";
    }
}
