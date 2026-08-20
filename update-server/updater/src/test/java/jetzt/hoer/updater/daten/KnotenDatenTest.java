package jetzt.hoer.updater.daten;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Die Nummerierung neuer Knoten.
 *
 * <p>Sieht harmlos aus und hat genau eine Falle - siehe die zweite Probe.</p>
 */
class KnotenDatenTest {

    @Test
    @DisplayName("Auf lavalink-9 folgt lavalink-10")
    void zaehltHoch() {
        assertEquals("lavalink-10", KnotenDaten.naechste("lavalink", List.of("lavalink-9")));
    }

    @Test
    @DisplayName("Gezaehlt wird nach der Zahl, nicht nach dem Text")
    void nichtAlphabetisch() {
        // Die Falle: alphabetisch sortiert steht "lavalink-9" HINTER
        // "lavalink-10". Wer den letzten Eintrag einer sortierten Liste nimmt,
        // landet bei lavalink-10 - und schlaegt damit einen Namen vor, den es
        // schon gibt. Das Anlegen scheiterte dann mit "Kennung gibt es
        // bereits", und zwar nur ab dem zehnten Knoten.
        assertEquals("lavalink-11",
                KnotenDaten.naechste("lavalink", List.of("lavalink-9", "lavalink-10")));
        assertEquals("knoten-100",
                KnotenDaten.naechste("knoten", List.of("knoten-98", "knoten-99")));
    }

    @Test
    @DisplayName("Ohne Bestand faengt es bei eins an")
    void erstesMal() {
        assertEquals("lavalink-1", KnotenDaten.naechste("lavalink", List.of()));
    }

    @Test
    @DisplayName("Luecken werden nicht wieder gefuellt")
    void lueckeBleibt() {
        // Eine Kennung taucht in Protokollen, Sicherungsdateinamen und
        // Freigaben auf. Sie ein zweites Mal zu vergeben macht diese Spuren
        // mehrdeutig - dann gehoert "lavalink-3" zu zwei Maschinen.
        assertEquals("lavalink-6",
                KnotenDaten.naechste("lavalink", List.of("lavalink-1", "lavalink-5")));
    }

    @Test
    @DisplayName("Fremde Namen stoeren die Zaehlung nicht")
    void nurEigenePraefixe() {
        assertEquals("lavalink-3",
                KnotenDaten.naechste("lavalink", List.of("core-7", "lavalink-2")));
        assertEquals("core-2",
                KnotenDaten.naechste("core", List.of("ki-radio-4", "core-1")));
    }

    @Test
    @DisplayName("Was keine reine Zahl ist, zaehlt nicht mit")
    void nurZahlen() {
        // "lavalink-premium" ist ein Name, keine Nummer - und "lavalink-2b"
        // ist keine 2.
        assertEquals("lavalink-4",
                KnotenDaten.naechste("lavalink", List.of("lavalink-premium", "lavalink-3")));
        assertEquals("lavalink-1",
                KnotenDaten.naechste("lavalink", List.of("lavalink-2b")));
    }

    @Test
    @DisplayName("Unsinn bringt die Zaehlung nicht zum Absturz")
    void haeltAus() {
        // Eine Zahl jenseits von int ist ein Versehen, kein Grund zum
        // Abbrechen: sonst liesse sich das Anlegen neuer Knoten mit einem
        // einzigen kaputten Eintrag dauerhaft blockieren.
        assertEquals("lavalink-3",
                KnotenDaten.naechste("lavalink",
                        java.util.Arrays.asList("lavalink-99999999999999999999",
                                                null, "lavalink-2", "")));
    }
}
