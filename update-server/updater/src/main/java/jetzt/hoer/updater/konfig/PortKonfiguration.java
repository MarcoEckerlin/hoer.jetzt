package jetzt.hoer.updater.konfig;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Zwei Anschluesse an einem Dienst.
 *
 *   TOR    (Vorgabe 8080)  Caddy fragt hier nach. Wird im Compose-Verbund
 *                          nicht auf den Host gelegt - erreichbar ist er nur
 *                          ueber das interne Docker-Netz. Kein Login: wer
 *                          hier ankommt, ist Caddy.
 *
 *   PULT   (Vorgabe 8081)  Die Oberflaeche. Wird auf 127.0.0.1 bzw. die
 *                          Tailscale-Adresse gelegt und liegt damit im
 *                          privaten Netz.
 *
 * Warum ueberhaupt zwei Ports und nicht zwei Pfade mit unterschiedlicher
 * Anmeldung: eine Pfadregel ist eine Zeile, die man falsch schreiben kann,
 * und der Fehler faellt niemandem auf, weil beide Wege funktionieren - nur
 * eben einer zu viel. Ein Port, der nicht auf dem Host liegt, ist von aussen
 * nicht erreichbar, egal was in der Anwendung steht. Die Trennung haelt
 * also auch dann, wenn die Regel darueber kaputt ist.
 *
 * Beide Anschlusse bedienen denselben Anwendungskontext - Tomcat kennt keine
 * getrennten Kontexte je Connector. Die Zuordnung, welcher Pfad an welchem
 * Port ueberhaupt existiert, erzwingt deshalb {@link PortTrennung}.
 */
@Configuration
public class PortKonfiguration {

    /** Port fuer den Torwaechter. Entspricht server.port. */
    public static final String TOR_PORT_EIGENSCHAFT = "${server.port:8080}";

    /** Port fuer die Oberflaeche. */
    public static final String PULT_PORT_EIGENSCHAFT = "${hj.pult-port:8081}";

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> pultAnschluss(
            @Value(PULT_PORT_EIGENSCHAFT) int pultPort) {

        return factory -> {
            Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
            connector.setPort(pultPort);
            factory.addAdditionalTomcatConnectors(connector);
        };
    }
}
