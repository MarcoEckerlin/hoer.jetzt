package eckerlin.dev.verbund;

import eckerlin.dev.services.AdminAccessService;
import eckerlin.dev.web.DashboardController;
import eckerlin.dev.web.dto.ActionResponse;
import eckerlin.dev.web.dto.DashboardSession;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Die Schnittstelle des Verbunds.
 *
 * <p>Zwei Sorten Zugang, bewusst getrennt:</p>
 *
 * <ul>
 *   <li>Der <b>Agent</b> meldet sich mit einem Token. Er hat kein
 *       Discord-Konto und kann sich nicht anmelden - eine Maschine, die alle
 *       Minute anklopft, soll das auch nicht.</li>
 *   <li>Der <b>Adminbereich</b> geht ueber die uebliche Sitzung. Wer das Ziel
 *       des ganzen Verbunds setzen darf, muss ein Mensch mit Rechten sein.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/verbund")
public class VerbundApiController {

    private final VerbundService verbundService;
    private final AdminAccessService adminAccessService;

    public VerbundApiController(VerbundService verbundService, AdminAccessService adminAccessService) {
        this.verbundService = verbundService;
        this.adminAccessService = adminAccessService;
    }

    @PostMapping("/anmelden")
    public VerbundService.NodeAntwort anmelden(
            @RequestHeader(value = "Authorization", required = false) String kopfzeile,
            @RequestBody VerbundService.NodeMeldung meldung
    ) {
        tokenPruefen(kopfzeile);
        try {
            return verbundService.anmelden(meldung);
        } catch (IllegalArgumentException fehler) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fehler.getMessage());
        } catch (IllegalStateException fehler) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, fehler.getMessage());
        }
    }

    @GetMapping("/nodes")
    public List<VerbundService.NodeUebersicht> nodes(HttpSession sitzung) {
        adminAccessService.requireAdmin(sitzungPruefen(sitzung));
        return verbundService.uebersicht();
    }

    @GetMapping("/ziel")
    public VerbundService.Ziel ziel(HttpSession sitzung) {
        adminAccessService.requireAdmin(sitzungPruefen(sitzung));
        return verbundService.ziel();
    }

    @PostMapping("/ziel")
    public ActionResponse ziel(@RequestBody ZielRequest anfrage, HttpSession sitzung) {
        DashboardSession angemeldet = sitzungPruefen(sitzung);
        adminAccessService.requireAdmin(angemeldet);
        verbundService.zielSetzen(anfrage.releaseVersion(), anfrage.shardsGesamt(), angemeldet.userId());
        return new ActionResponse(true,
                "Ziel gesetzt. Die Nodes übernehmen es beim nächsten Lauf des Agenten, spätestens in einer Minute.");
    }

    /**
     * Prueft das Agenten-Token.
     *
     * <p>Der Vergleich laeuft ueber {@link MessageDigest#isEqual} statt ueber
     * {@code equals}. Ein gewoehnlicher Zeichenkettenvergleich bricht beim
     * ersten Unterschied ab - wer die Antwortzeit misst, kann daraus Zeichen
     * fuer Zeichen das Token erraten. Der Unterschied ist winzig und der
     * Aufwand, ihn zu vermeiden, auch.</p>
     */
    private void tokenPruefen(String kopfzeile) {
        String erwartet = System.getenv("HJ_CONTROLLER_TOKEN");
        if (erwartet == null || erwartet.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Der Verbund ist nicht eingerichtet (HJ_CONTROLLER_TOKEN fehlt).");
        }
        String gegeben = kopfzeile == null ? "" : kopfzeile.replaceFirst("(?i)^Bearer\\s+", "").trim();
        boolean gleich = MessageDigest.isEqual(
                gegeben.getBytes(StandardCharsets.UTF_8),
                erwartet.trim().getBytes(StandardCharsets.UTF_8));
        if (!gleich) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token stimmt nicht.");
        }
    }

    private DashboardSession sitzungPruefen(HttpSession sitzung) {
        Object nutzer = sitzung.getAttribute(DashboardController.SESSION_USER);
        if (nutzer instanceof DashboardSession angemeldet) {
            return angemeldet;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Bitte zuerst über Discord anmelden.");
    }

    public record ZielRequest(String releaseVersion, Integer shardsGesamt) {
    }
}
