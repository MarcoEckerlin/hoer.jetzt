package jetzt.hoer.updater.dienst;

import jetzt.hoer.updater.daten.FreigabeDaten;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Der Update-Server gibt seine eigene Adresse frei.
 *
 * <h2>Warum das noetig ist</h2>
 *
 * Beim Veroeffentlichen schiebt dieser Server Abbilder in seine eigene
 * Registry - scheinbar ueber {@code 127.0.0.1}. Tatsaechlich nicht: Forgejo
 * antwortet auf die erste Anfrage mit
 *
 * <pre>
 *   WWW-Authenticate: Bearer realm="https://repository.hoer.jetzt/v2/token"
 * </pre>
 *
 * und Docker folgt diesem Namen. Die Token-Anfrage geht also hinaus - ueber
 * Cloudflare und den Proxy - und kommt von aussen wieder herein. Fuer den
 * Torwaechter stammt sie damit von der <em>oeffentlichen</em> Adresse dieses
 * Servers, nicht von localhost.
 *
 * <p>Ohne Freigabe endet das Veroeffentlichen in:</p>
 *
 * <pre>
 *   denied: Diese Adresse (45.14.124.54) ist nicht freigeschaltet.
 * </pre>
 *
 * <p>Das ist verwirrend, weil man auf demselben Rechner sitzt und nichts
 * "von aussen" tut. Deshalb traegt der Server die Adresse selbst ein, statt
 * darauf zu warten, dass jemand den Zusammenhang durchschaut.</p>
 *
 * <h2>Warum bei jedem Start und nicht nur beim ersten</h2>
 *
 * {@link Erstbelegung} legt die Grundfreigaben an, solange die Liste leer
 * ist - danach nie wieder, damit eine bewusste Sperre nicht zurueckkommt.
 *
 * Die eigene Adresse ist ein anderer Fall: sie aendert sich, wenn der Server
 * umzieht oder eine neue IP bekommt. Danach koennte er nicht mehr
 * veroeffentlichen, und die Meldung zeigte auf eine Adresse, die niemand
 * eingetragen hat. Also bei jedem Start nachsehen.
 *
 * <p>Was er <em>nicht</em> tut: eine gesperrte Freigabe wieder aufmachen.
 * Wer die eigene Adresse absichtlich sperrt, hat einen Grund - und bekommt
 * eine Warnung statt einer stillen Korrektur.</p>
 */
@Component
public class EigeneFreigabe implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EigeneFreigabe.class);

    private final FreigabeDaten freigaben;
    private final String eigene;

    public EigeneFreigabe(FreigabeDaten freigaben,
                          @Value("${hj.eigene-ip:}") String eigene) {
        this.freigaben = freigaben;
        this.eigene = eigene == null ? "" : eigene.trim();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (eigene.isEmpty()) {
            // Kein Wert gesetzt - aeltere Installationen. Kein Grund zur
            // Aufregung, aber ein Hinweis: ohne ihn scheitert das
            // Veroeffentlichen an einer Meldung, die woanders hinzeigt.
            log.info("hj.eigene-ip ist nicht gesetzt. Sollte das Veroeffentlichen "
                     + "mit 'Adresse nicht freigeschaltet' scheitern, traegt "
                     + "einrichten.sh den Wert nach.");
            return;
        }

        String bereich;
        try {
            bereich = Netzbereich.aus(eigene).toString();
        } catch (IllegalArgumentException unlesbar) {
            log.warn("hj.eigene-ip ist unlesbar und wird uebergangen: {} ({})",
                    eigene, unlesbar.getMessage());
            return;
        }

        var vorhanden = freigaben.alle().stream()
                .filter(f -> f.bereich().equals(bereich))
                .findFirst();

        if (vorhanden.isPresent()) {
            if (!vorhanden.get().aktiv()) {
                // Absichtlich gesperrt - nicht stillschweigend aufmachen.
                log.warn("Die eigene Adresse {} ist gesperrt. Solange das so bleibt, "
                         + "kann dieser Server nicht veroeffentlichen.", bereich);
            }
            return;
        }

        freigaben.anlegen(bereich, "Update-Server selbst",
                "Der Token-Umweg beim Veroeffentlichen kommt von aussen zurueck", null);
        log.info("Eigene Adresse {} freigeschaltet - ohne sie schluege das "
                 + "Veroeffentlichen fehl.", bereich);
    }
}
