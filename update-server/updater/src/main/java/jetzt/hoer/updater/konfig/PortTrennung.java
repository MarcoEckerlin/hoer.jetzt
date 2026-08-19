package jetzt.hoer.updater.konfig;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Haelt die beiden Anschluesse auseinander.
 *
 * Tomcat bedient mit zwei Connectoren denselben Anwendungskontext: ohne diesen
 * Filter waere die Oberflaeche auch am Torwaechter-Port erreichbar und
 * umgekehrt. Das faellt im Betrieb nicht auf, weil alles funktioniert - nur
 * eben an einer Stelle mehr als gedacht.
 *
 * Die Regel ist absichtlich als Zugehoerigkeit formuliert und nicht als
 * Sperrliste: /intern/ gehoert ans Tor, alles andere ans Pult. Was spaeter
 * dazukommt, landet damit von selbst auf der richtigen Seite. Eine Sperrliste
 * haette man bei jedem neuen Pfad nachziehen muessen - und genau das vergisst
 * man.
 *
 * Antwort ist 404, nicht 403: am falschen Port existiert der Pfad nicht, und
 * wer ihn durchprobiert, soll nicht erfahren, dass es ihn woanders gibt.
 */
@Component
public class PortTrennung extends OncePerRequestFilter {

    /** Alles unterhalb dieses Pfades ist die Maschinen-Schnittstelle. */
    public static final String INTERN = "/intern/";

    private final int torPort;

    public PortTrennung(@Value(PortKonfiguration.TOR_PORT_EIGENSCHAFT) int torPort) {
        this.torPort = torPort;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest anfrage,
                                    HttpServletResponse antwort,
                                    FilterChain kette) throws ServletException, IOException {

        boolean amTor = anfrage.getLocalPort() == torPort;
        boolean internerPfad = anfrage.getRequestURI().startsWith(INTERN);

        if (amTor != internerPfad) {
            antwort.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        kette.doFilter(anfrage, antwort);
    }
}
