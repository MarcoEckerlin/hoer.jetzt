package eckerlin.dev.web;

import eckerlin.dev.services.AppConfigService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.SelfUser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Name und Bild dieser Instanz - ohne Anmeldung abrufbar.
 *
 * <p>Die Startseite laeuft als getrennte React-Anwendung und kann deshalb
 * nichts aus einer Vorlage mitbekommen; sie muss fragen. Ohne diesen Endpunkt
 * stand dort fest "hoer.jetzt", auch auf einer Instanz, die anders heisst - und
 * der Aufruf lief still ins Leere, weil die Oberflaeche den Fehler abfaengt und
 * auf den Vorgabenamen zurueckfaellt.</p>
 *
 * <p>Bewusst nur Name und Bild. Alles andere aus der Konfiguration ist entweder
 * uninteressant oder gehoert hinter die Anmeldung, und ein oeffentlicher
 * Endpunkt, der "die Konfiguration" liefert, waechst mit der Zeit von selbst
 * ins Bedenkliche.</p>
 */
@RestController
public class PublicBrandController {

    private final AppConfigService configService;

    /**
     * JDA als {@link ObjectProvider}, nicht direkt: die Weboberflaeche steht,
     * bevor der Bot bei Discord angemeldet ist, und sie soll auch dann
     * antworten. Ein fest verdrahtetes JDA machte den Start der einen vom Start
     * der anderen abhaengig.
     */
    private final ObjectProvider<JDA> jda;

    public PublicBrandController(AppConfigService configService, ObjectProvider<JDA> jda) {
        this.configService = configService;
        this.jda = jda;
    }

    @GetMapping("/api/public/brand")
    public BrandView brand() {
        String name = configService.getCurrentDeploymentDisplayName();
        String bild = null;

        JDA verbindung = jda.getIfAvailable();
        if (verbindung != null) {
            try {
                SelfUser selbst = verbindung.getSelfUser();
                bild = selbst.getEffectiveAvatarUrl();
                if (name == null || name.isBlank()) {
                    name = selbst.getName();
                }
            } catch (RuntimeException ignoriert) {
                // Noch nicht verbunden. Kein Grund, die Startseite scheitern zu
                // lassen - sie kommt ohne Bild aus.
            }
        }

        return new BrandView(name == null || name.isBlank() ? "hoer.jetzt" : name, bild);
    }

    public record BrandView(String displayName, String avatarUrl) {
    }
}
