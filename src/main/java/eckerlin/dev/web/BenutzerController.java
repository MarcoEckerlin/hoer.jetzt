package eckerlin.dev.web;

import eckerlin.dev.services.AdminAccessService;
import eckerlin.dev.web.dto.DashboardSession;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Wer gerade angemeldet ist.
 *
 * <p>Die Angaben liegen laengst in der Sitzung - die alte Oberflaeche hat sie
 * beim Rendern in die Vorlage geschrieben. Die neue ist eine getrennte
 * Anwendung und kann das nicht; sie muss fragen. Ohne diesen Endpunkt gab es
 * im Dashboard weder Name noch Bild noch einen Abmeldeknopf, der wusste, wen er
 * abmeldet.</p>
 *
 * <p>Bewusst ohne {@code accessToken}: der liegt zwar in derselben Sitzung, geht
 * die Oberflaeche aber nichts an. Was nicht hinausgeht, kann auch nicht aus
 * einem Browser-Protokoll oder einer Fehlermeldung herausfallen.</p>
 */
@RestController
public class BenutzerController {

    private final AdminAccessService adminAccessService;

    public BenutzerController(AdminAccessService adminAccessService) {
        this.adminAccessService = adminAccessService;
    }

    @GetMapping("/api/dashboard/me")
    public BenutzerAnsicht me(HttpSession sitzung) {
        Object nutzer = sitzung.getAttribute(DashboardController.SESSION_USER);
        if (!(nutzer instanceof DashboardSession angemeldet)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Nicht angemeldet.");
        }
        return new BenutzerAnsicht(
                angemeldet.userId(),
                angemeldet.username(),
                angemeldet.avatarUrl(),
                adminAccessService.isAdmin(angemeldet)
        );
    }

    /**
     * @param botAdmin ob die Person den Betriebsbereich sehen darf. Die
     *                 Oberflaeche blendet den Verweis danach ein - die
     *                 Entscheidung faellt trotzdem noch einmal beim Zugriff.
     *                 Ein ausgeblendeter Knopf ist Bequemlichkeit, kein Schutz.
     */
    public record BenutzerAnsicht(
            String userId,
            String username,
            String avatarUrl,
            boolean botAdmin
    ) {
    }
}
