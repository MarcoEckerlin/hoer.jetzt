package jetzt.hoer.updater.web;

import jetzt.hoer.updater.dienst.Sicherungen;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Die Sicherungen ansehen, holen und wegwerfen.
 *
 * <h2>Warum es die Seite bis jetzt nicht gab</h2>
 *
 * <p>{@link Sicherungen} konnte von Anfang an alles: annehmen, auflisten,
 * ausliefern, loeschen. Nur die Haelfte davon war je erreichbar - es gab
 * genau einen Endpunkt, und der war zum Hochladen. Die Sicherungen lagen
 * also im Container und liessen sich weder ansehen noch herunterladen.</p>
 *
 * <p>Eine Sicherung, an die man nicht herankommt, ist keine. Man merkt es
 * an dem Tag, an dem man sie braucht - und das ist der schlechteste Tag
 * dafuer.</p>
 *
 * <h2>Was hier bewusst fehlt</h2>
 *
 * <p>Kein Einspielen. Der Update-Server kennt die Datenbank eines
 * Controllers nicht und soll sie nicht anfassen - er kuemmert sich um
 * Integritaet und Versionierung. Eingespielt wird auf dem Knoten, mit
 * {@code deploy/uebernehmen.sh}; der haelt vorher die Dienste an, sichert
 * den jetzigen Stand und laeuft in einer Transaktion. Ein Knopf von hier
 * aus koennte nichts davon.</p>
 */
@Controller
public class SicherungController {

    private final Sicherungen sicherungen;

    public SicherungController(Sicherungen sicherungen) {
        this.sicherungen = sicherungen;
    }

    private static String name(Principal wer) {
        return wer == null ? "unbekannt" : wer.getName();
    }

    /**
     * Zu welchem Knoten eine Sicherung gehoert.
     *
     * <p>Der Name entsteht in {@code Sicherungen.annehmen} als
     * {@code <kennung>-<zeitstempel>.sql.gz}. Der Zeitstempel ist fest
     * geformt, also ist alles davor die Kennung - auch wenn sie selbst
     * Bindestriche hat ({@code controller-1}).</p>
     */
    static String knotenAus(String datei) {
        int trenner = datei.lastIndexOf('-');
        return trenner > 0 ? datei.substring(0, trenner) : datei;
    }

    @GetMapping("/sicherungen")
    public String seite(Model modell) {
        List<Sicherungen.Eintrag> alle = sicherungen.alle();

        // Nach Knoten gruppiert. Die Frage an diese Seite ist fast immer
        // "hat Controller-1 eine aktuelle?" und nicht "was liegt hier".
        Map<String, List<Sicherungen.Eintrag>> nachKnoten = new LinkedHashMap<>();
        for (Sicherungen.Eintrag e : alle) {
            nachKnoten.computeIfAbsent(knotenAus(e.name()), k -> new java.util.ArrayList<>()).add(e);
        }

        modell.addAttribute("nachKnoten", nachKnoten);
        modell.addAttribute("gesamt", alle.size());
        modell.addAttribute("platz", alle.stream().mapToLong(Sicherungen.Eintrag::groesse).sum());
        modell.addAttribute("jetzt", java.time.Instant.now());
        return "sicherungen";
    }

    /**
     * Eine Sicherung herunterladen.
     *
     * <p>{@code Sicherungen.datei} schneidet den Namen auf den reinen
     * Dateinamen zurueck und prueft, dass der Pfad in der Ablage bleibt -
     * sonst liesse sich ueber {@code ../} jede Datei im Container holen,
     * auch die Datenbank mit den Knoten-Geheimnissen.</p>
     */
    @GetMapping("/sicherungen/{datei}")
    @ResponseBody
    public ResponseEntity<Resource> holen(@PathVariable String datei) {
        Path pfad;
        try {
            pfad = sicherungen.datei(datei);
        } catch (IllegalArgumentException falsch) {
            return ResponseEntity.badRequest().build();
        }
        if (!Files.isRegularFile(pfad)) {
            return ResponseEntity.notFound().build();
        }

        long groesse;
        try {
            groesse = Files.size(pfad);
        } catch (IOException nichtLesbar) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + pfad.getFileName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(groesse)
                .body(new FileSystemResource(pfad));
    }

    @PostMapping("/sicherungen/{datei}/entfernen")
    public String entfernen(@PathVariable String datei, Principal wer,
                            RedirectAttributes hinweis) {
        try {
            if (sicherungen.loeschen(datei, name(wer))) {
                hinweis.addFlashAttribute("meldung", datei + " ist weg.");
            } else {
                hinweis.addFlashAttribute("fehler", "Gibt es nicht: " + datei);
            }
        } catch (IllegalArgumentException falsch) {
            hinweis.addFlashAttribute("fehler", falsch.getMessage());
        }
        return "redirect:/sicherungen";
    }
}
