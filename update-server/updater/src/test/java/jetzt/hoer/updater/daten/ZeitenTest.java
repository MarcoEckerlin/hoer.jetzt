package jetzt.hoer.updater.daten;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Zeitstempel - Breite, Reihenfolge, Zeitzone.
 *
 * <p>Die Datenbank vergleicht diese Werte als Text. Damit das stimmt, muessen
 * sie alle gleich lang sein.</p>
 */
class ZeitenTest {

    @Test
    @DisplayName("Immer dieselbe Breite, egal ob Nachkommastellen anfallen")
    void festeBreite() {
        assertEquals(20, Zeiten.text(Instant.parse("2026-08-20T21:19:40Z")).length());
        assertEquals(20, Zeiten.text(Instant.parse("2026-08-20T21:19:40.123Z")).length());
        assertEquals(20, Zeiten.text(Instant.parse("2026-08-20T21:19:40.999999Z")).length());
    }

    @Test
    @DisplayName("Textreihenfolge ist Zeitreihenfolge")
    void textVergleichStimmt() {
        // Der Fehler, den das behebt: Instant.toString() laesst Nachkommastellen
        // weg, wenn sie null sind. Dann steht an Stelle 20 einmal 'Z' und einmal
        // '.', und '.' liegt im Zeichensatz VOR 'Z'. Als Text galt damit
        //   "...40Z" > "...40.123Z"
        // obwohl der zweite Wert spaeter liegt.
        Instant frueher = Instant.parse("2026-08-20T21:19:40Z");
        Instant spaeter = Instant.parse("2026-08-20T21:19:40.500Z");

        // Ohne Kuerzung waere das genau andersherum:
        assertTrue(frueher.toString().compareTo(spaeter.toString()) > 0,
                "Der alte Fehler soll nachweisbar bleiben");

        // Mit Kuerzung fallen beide auf dieselbe Sekunde - und was
        // unterscheidbar bleibt, ist richtig sortiert.
        assertEquals(Zeiten.text(frueher), Zeiten.text(spaeter));
        assertTrue(Zeiten.text(Instant.parse("2026-08-20T21:19:39Z"))
                        .compareTo(Zeiten.text(spaeter)) < 0);
        assertTrue(Zeiten.text(Instant.parse("2026-08-20T21:19:41Z"))
                        .compareTo(Zeiten.text(spaeter)) > 0);
    }

    @Test
    @DisplayName("Hin und zurueck")
    void rundgang() {
        Instant i = Instant.parse("2026-08-20T21:19:40Z");
        assertEquals(i, Zeiten.zeit(Zeiten.text(i)));
        assertNull(Zeiten.text(null));
        assertNull(Zeiten.zeit(null));
        assertNull(Zeiten.zeit(""));
        assertNull(Zeiten.zeit("kein Zeitstempel"));
    }

    @Test
    @DisplayName("Zwei Stunden sind zwei Stunden - auch in der Sommerzeit")
    void tokenIstZeitzonenfrei() {
        // Der Aufsetz-Token gilt zwei Stunden. Instant ist ein Zeitpunkt und
        // kein Datum mit Uhrzeit - eine Zeitzone kommt darin nicht vor. Das
        // gilt auch ueber die Zeitumstellung hinweg.
        Instant vorUmstellung = Instant.parse("2026-10-25T00:30:00Z"); // Nacht der Rueckstellung
        Instant ablauf = vorUmstellung.plusSeconds(2 * 3600);

        assertEquals(2, Duration.between(vorUmstellung, ablauf).toHours());
        assertEquals(20, Zeiten.text(ablauf).length());
        assertTrue(Zeiten.text(ablauf).compareTo(Zeiten.text(vorUmstellung)) > 0);
    }

    @Test
    @DisplayName("Ein Ablaufdatum meint den deutschen Tag, nicht den in Greenwich")
    void freigabeAblaufInDeutscherZeit() {
        // Was der PultController rechnet. Wer "25.08." eintippt, meint: am 26.
        // um Mitternacht ist Schluss - Berliner Zeit.
        LocalDate gewaehlt = LocalDate.parse("2026-08-25");
        ZoneId berlin = ZoneId.of("Europe/Berlin");

        Instant richtig = gewaehlt.plusDays(1).atStartOfDay(berlin).toInstant();
        Instant frueher = gewaehlt.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        assertEquals(0, richtig.atZone(berlin).getHour(),
                "Mitternacht deutscher Zeit");
        // Im Sommer galt die Freigabe zwei Stunden zu lang.
        assertEquals(2, Duration.between(richtig, frueher).toHours());
    }

    @Test
    @DisplayName("Im Winter ist es eine Stunde, nicht zwei")
    void winterzeit() {
        // Damit klar ist, dass es an der Zone haengt und nicht an einer
        // festen Zahl: im Winter gilt MEZ, also UTC+1.
        LocalDate imWinter = LocalDate.parse("2026-01-15");
        ZoneId berlin = ZoneId.of("Europe/Berlin");

        Instant richtig = imWinter.plusDays(1).atStartOfDay(berlin).toInstant();
        Instant frueher = imWinter.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        assertEquals(1, Duration.between(richtig, frueher).toHours());
    }
}
