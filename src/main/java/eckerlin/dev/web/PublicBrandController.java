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
        // Der Bereitstellungsschluessel ist kein Markenname.
        //
        // getCurrentDeploymentDisplayName() faellt auf den Schluessel zurueck,
        // wenn kein Anzeigename hinterlegt ist. Fuer die Verwaltung ist das
        // richtig - dort will man wissen, welche Bereitstellung man vor sich
        // hat. Oeffentlich ist es falsch: so stand "local" als Name der Seite
        // und "LOCAL" in der Player-Karte, fuer jeden Besucher sichtbar.
        //
        // Also nur uebernehmen, wenn wirklich ein Name gesetzt ist. Sonst
        // zaehlt der Discord-Name des Bots und danach der Rueckfall unten.
        String schluessel = configService.getCurrentDeploymentKey();
        String name = configService.getCurrentDeploymentDisplayName();
        if (name != null && name.equals(schluessel)) {
            name = "";
        }
        // Zuerst das im Adminbereich hinterlegte Markenbild.
        //
        // Das wurde hier nie gelesen: die Methode fragte ausschliesslich JDA
        // nach dem Bot-Avatar. Wer unter Instanz ein Bild hochlud, sah es
        // nirgends - der Wert landete zwar in der Datenbank, aber nur die alte
        // Thymeleaf-Startseite las ihn ueber BotPresentationService. Die
        // React-Oberflaeche holt ihre Marke von hier.
        String bild = sauber(configService.getBrandImageUrl());

        JDA verbindung = jda.getIfAvailable();
        if (verbindung != null) {
            try {
                SelfUser selbst = verbindung.getSelfUser();
                // Nur einspringen, wenn nichts hinterlegt ist - eine bewusste
                // Wahl im Adminbereich schlaegt den Discord-Avatar.
                if (bild.isBlank()) {
                    bild = selbst.getEffectiveAvatarUrl();
                }
                if (name == null || name.isBlank()) {
                    name = selbst.getName();
                }
            } catch (RuntimeException ignoriert) {
                // Noch nicht verbunden. Kein Grund, die Startseite scheitern zu
                // lassen - sie kommt ohne Bild aus.
            }
        }

        // Der Einladungslink gehoert hierher und nicht hinter die Anmeldung:
        // wer den Bot hinzufuegen will, hat auf dieser Seite noch kein Konto
        // bei uns. Es ist ausserdem nichts Vertrauliches - derselbe Link steht
        // in jeder Antwort des Bots an einen Server, auf dem er fehlt.
        //
        // Ist keiner hinterlegt, baut AppConfigService ihn aus der Client-ID.
        // Geht auch das nicht, kommt ein leerer String zurueck, und die
        // Startseite laesst den Knopf weg - ein Knopf, der ins Leere fuehrt,
        // ist schlimmer als keiner.
        return new BrandView(
                name == null || name.isBlank() ? "hoer.jetzt" : name,
                bild == null || bild.isBlank() ? null : bild,
                configService.getNoGuildInviteUrl(),
                configService.getSupportUrl());
    }

    private static String sauber(String wert) {
        return wert == null ? "" : wert.trim();
    }

    public record BrandView(String displayName, String avatarUrl, String inviteUrl, String supportUrl) {
    }
}
