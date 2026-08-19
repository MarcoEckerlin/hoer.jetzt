package jetzt.hoer.updater.dienst;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Diese Klasse entscheidet, wer an die Abbilder und den Tresor kommt. Sie hat
 * ausser der Standardbibliothek keine Abhaengigkeiten - es gibt also keinen
 * Grund, sie nicht zu pruefen.
 *
 * Die Faelle sind nicht willkuerlich gewaehlt: jeder steht fuer einen Fehler,
 * den man an dieser Stelle tatsaechlich macht.
 */
class NetzbereichTest {

    @ParameterizedTest
    @CsvSource({
            // Eine nackte Adresse muss zur vollen Laenge werden, sonst gaebe
            // es zwei Schreibweisen fuer dieselbe Sache.
            "192.168.1.5,        192.168.1.5/32",
            "10.0.0.0/8,         10.0.0.0/8",
            // Wirtsbits ausnullen. Ohne das passte "10.1.2.3/8" nicht einmal
            // auf sich selbst - und in der Liste stuende etwas anderes als
            // das, was gemeint war.
            "10.1.2.3/8,         10.0.0.0/8",
            "172.16.5.5/12,      172.16.0.0/12",
            "::1,                0:0:0:0:0:0:0:1/128"
    })
    @DisplayName("wird auf eine einzige Schreibweise gebracht")
    void normalisiert(String eingabe, String erwartet) {
        assertEquals(erwartet, Netzbereich.aus(eingabe).toString());
    }

    @ParameterizedTest
    @CsvSource({
            "10.0.0.0/8,      10.1.2.3,          true",
            "10.0.0.0/8,      11.1.2.3,          false",
            "192.168.1.5,     192.168.1.5,       true",
            "192.168.1.5,     192.168.1.6,       false",
            // Die Raender von /12 - hier vertut man sich beim Rechnen.
            "172.16.0.0/12,   172.16.0.0,        true",
            "172.16.0.0/12,   172.31.255.255,    true",
            "172.16.0.0/12,   172.15.255.255,    false",
            "172.16.0.0/12,   172.32.0.0,        false",
            "127.0.0.0/8,     127.0.0.1,         true",
            "0.0.0.0/0,       203.0.113.9,       true",
            "2001:db8::/32,   2001:db8::5,       true",
            "2001:db8::/32,   2001:db9::5,       false"
    })
    @DisplayName("trifft genau den angegebenen Bereich")
    void trifft(String bereich, String adresse, boolean erwartet) {
        assertEquals(erwartet, Netzbereich.aus(bereich).enthaelt(adresse));
    }

    @Test
    @DisplayName("mischt IPv4 und IPv6 nicht")
    void keineVermischung() {
        assertFalse(Netzbereich.aus("10.0.0.0/8").enthaelt("::1"));
        assertFalse(Netzbereich.aus("::1/128").enthaelt("10.1.2.3"));
    }

    @Test
    @DisplayName("erkennt IPv4 in IPv6-Schreibweise")
    void abgebildet() {
        // Kommt eine IPv4-Verbindung ueber einen IPv6-Anschluss herein, meldet
        // sie sich so. Wuerde das nicht erkannt, waere der Knoten ausgesperrt.
        assertTrue(Netzbereich.aus("10.0.0.0/8").enthaelt("::ffff:10.1.2.3"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "kaputt", "10.0.0.0/99", "10.0.0.0/-1", "10.0.0.0/abc"})
    @DisplayName("weist Unlesbares beim Anlegen ab, statt es still zu schlucken")
    void weistAb(String eingabe) {
        // Eine Freigabe, die nicht verstanden wird, darf nicht als "passt auf
        // nichts" in der Liste landen - dann stuende dort ein Eintrag, auf den
        // sich jemand verlaesst und der nie greift.
        assertThrows(IllegalArgumentException.class, () -> Netzbereich.aus(eingabe));
    }

    @ParameterizedTest
    @ValueSource(strings = {"example.com", "localhost", "knoten.local"})
    @DisplayName("schlaegt keine Namen im DNS nach")
    void keinDns(String name) {
        // Eine Zugangsliste, die von der Namensaufloesung abhaengt, ist keine:
        // wer den DNS-Eintrag stellt, stellt dann auch den Zugang.
        assertThrows(IllegalArgumentException.class, () -> Netzbereich.aus(name));
    }

    @Test
    @DisplayName("beantwortet eine unsinnige Anfrage mit nein, nicht mit einem Fehler")
    void unsinnAlsAnfrage() {
        Netzbereich bereich = Netzbereich.aus("10.0.0.0/8");
        assertFalse(bereich.enthaelt("kaputt"));
        assertFalse(bereich.enthaelt((String) null));
        assertFalse(bereich.enthaelt("example.com"));
    }
}
