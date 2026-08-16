package eckerlin.dev.utils;

import io.netty.resolver.DefaultAddressResolverGroup;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;

/**
 * Namensaufloesung fuer alle ausgehenden HTTP-Aufrufe von Spring.
 *
 * <h2>Das Problem</h2>
 *
 * <p>Im Container standen sich zwei Aufloeser gegenueber. JDA loest ueber den
 * JVM-Resolver auf, also ueber das Betriebssystem. Spring WebClient laeuft
 * ueber Reactor Netty, und das bringt einen <em>eigenen</em> asynchronen
 * DNS-Resolver mit, der die {@code /etc/resolv.conf} selbst liest und
 * auswertet.</p>
 *
 * <p>Im Log sah das so aus - beide Zeilen in derselben Sekunde:</p>
 *
 * <pre>
 * 19:39:19.487 WARN  LOGIN  ... WebClientRequestException: Failed to resolve 'discord.com' [A(1)]
 * 19:39:19.902 INFO  JDA    ... Login Successful!
 * </pre>
 *
 * <p>Derselbe Container, dieselbe Domain, dieselbe Sekunde - einmal geht es,
 * einmal nicht. Wer an dieser Stelle die Netzwerkkonfiguration untersucht,
 * sucht stundenlang an einem Netz, das in Ordnung ist. Fuer den Nutzer kam das
 * als {@code ?error=oauth_auth_failed} an: die Anmeldung ging nicht mehr, der
 * Bot lief weiter.</p>
 *
 * <h2>Die Loesung</h2>
 *
 * <p>Reactor Netty bekommt den Aufloeser des Betriebssystems - denselben, den
 * JDA benutzt und der nachweislich funktioniert. Damit gibt es im Prozess nur
 * noch einen Weg, wie ein Name zu einer Adresse wird.</p>
 *
 * <p>Der Preis ist bekannt und hier ohne Bedeutung: der JVM-Resolver blockiert
 * den aufrufenden Faden. Bei einer Handvoll Aufrufen an Discord pro Anmeldung
 * faellt das nicht ins Gewicht - eine Anmeldung, die zuverlaessig eine
 * Millisekunde laenger dauert, ist einer vorzuziehen, die schnell scheitert.</p>
 */
@Configuration
public class NetzAufloesung {

    /**
     * Ersetzt die Vorgabe von Spring Boot.
     *
     * <p>Boot legt diese Bohne nur an, wenn es keine gibt - eine eigene gewinnt
     * also, ohne dass etwas abgeschaltet werden muss. Sie gilt fuer jeden
     * {@code WebClient}, der aus dem {@code WebClient.Builder} entsteht: den
     * OAuth-Tausch, die Abfrage des Anwendungs-Eigentuemers und alles, was
     * spaeter dazukommt.</p>
     */
    @Bean
    public ClientHttpConnector clientHttpConnector() {
        return new ReactorClientHttpConnector(
                HttpClient.create().resolver(DefaultAddressResolverGroup.INSTANCE));
    }
}
