package jetzt.hoer.updater.dienst;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wem die Adresse geglaubt wird.
 *
 * <p>Diese Proben halten den Unterschied fest zwischen "kommt durch den
 * Proxy" und "klopft direkt an". Solange der Port im LAN stand, war das egal;
 * seit er offen ist, haengt die gesamte IP-Freigabe daran.</p>
 */
class VorfeldTest {

    /** Wie im Betrieb: Docker-Netz und localhost gelten als Zwischenstelle. */
    private final Vorfeld vorfeld =
            new Vorfeld("127.0.0.1/32,::1/128,172.16.0.0/12,10.0.0.0/8,192.168.0.0/16");

    @Test
    @DisplayName("Hinter dem Proxy zaehlt der Kopf")
    void hinterProxy() {
        // Caddy und NPM liegen im Docker-Netz. Was sie weiterreichen, stimmt.
        assertEquals("203.0.113.9",
                vorfeld.adresse("172.18.0.5", "203.0.113.9", null));
        assertEquals("203.0.113.9",
                vorfeld.adresse("172.18.0.5", null, "203.0.113.9, 172.18.0.5"));
    }

    @Test
    @DisplayName("Wer direkt anklopft, kann sich keine Adresse aussuchen")
    void direktAufrufKannNichtLuegen() {
        // DAS ist der Angriff, den der offene Port erst moeglich macht:
        // ein Fremder schickt CF-Connecting-IP mit einer freigegebenen
        // Adresse und haengelt sich damit an der Freigabeliste vorbei.
        //
        // Frueher wurde der Kopf ungeprueft genommen - der Aufruf haette
        // 10.0.0.5 gegolten und die Freigabe waere wirkungslos gewesen.
        String angeblich = "10.0.0.5";
        String tatsaechlich = "203.0.113.66";

        assertEquals(tatsaechlich,
                vorfeld.adresse(tatsaechlich, angeblich, null),
                "Der erfundene Kopf darf die echte Adresse nicht verdraengen");

        assertEquals(tatsaechlich,
                vorfeld.adresse(tatsaechlich, null, angeblich + ", 1.1.1.1"));
    }

    @Test
    @DisplayName("Ohne Koepfe gilt die Verbindung")
    void ohneKoepfe() {
        assertEquals("203.0.113.66", vorfeld.adresse("203.0.113.66", null, null));
        assertEquals("172.18.0.5", vorfeld.adresse("172.18.0.5", null, null));
        assertEquals("172.18.0.5", vorfeld.adresse("172.18.0.5", "  ", ""));
    }

    @Test
    @DisplayName("IPv4 in IPv6-Schreibweise wird erkannt")
    void ipv4Gemappt() {
        // Tomcat liefert hinter Docker gern ::ffff:172.18.0.1. Ohne das
        // Abschneiden faende der Vergleich gegen 172.16.0.0/12 nichts - und
        // dann gaelte der eigene Proxy als Fremder, womit die Kopfzeilen
        // verworfen wuerden und JEDER Knoten als Docker-Adresse im Protokoll
        // stuende.
        assertEquals("203.0.113.9",
                vorfeld.adresse("::ffff:172.18.0.5", "203.0.113.9", null));
        assertTrue(vorfeld.istProxy("::ffff:127.0.0.1"));
    }

    @Test
    @DisplayName("Portangaben stoeren nicht")
    void mitPort() {
        assertEquals("203.0.113.9",
                vorfeld.adresse("172.18.0.5:54321", "203.0.113.9", null));
    }

    @Test
    @DisplayName("Leere Vertrauensliste heisst: niemandem glauben")
    void nichtsVertraut() {
        Vorfeld streng = new Vorfeld("");
        assertEquals("172.18.0.5",
                streng.adresse("172.18.0.5", "203.0.113.9", null),
                "Ohne vertraute Bereiche zaehlt immer die Verbindung");
    }

    @Test
    @DisplayName("Ein unbrauchbarer Eintrag legt den Dienst nicht lahm")
    void schrottUebergangen() {
        // Ein Tippfehler in der Umgebung darf nicht dazu fuehren, dass der
        // Updater gar nicht erst startet.
        Vorfeld mitSchrott = new Vorfeld("127.0.0.1/32, keine-adresse, 172.16.0.0/12");
        assertEquals("203.0.113.9",
                mitSchrott.adresse("172.18.0.5", "203.0.113.9", null));
        assertFalse(mitSchrott.istProxy("keine-adresse"));
    }
}
