package jetzt.hoer.updater.dienst;

import jetzt.hoer.updater.daten.VerwaltungDaten;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Der Verlauf und der Weg zurueck.
 *
 * <p>Diese Klasse schreibt und loescht Dateien, und die Version dafuer kommt
 * aus einem Formular. Beides zusammen ist der Grund, warum hier Tests stehen:
 * ein Pfad statt eines Namens, und die Oberflaeche loescht an einer Stelle,
 * die sie nichts angeht.</p>
 */
class ReleaseverwaltungTest {

    @TempDir
    Path ausliefern;

    private Releaseverwaltung verwaltung;

    /** Schreibt ins Protokoll, ohne eine Datenbank zu brauchen. */
    private static final VerwaltungDaten STILL = new VerwaltungDaten(null) {
        @Override
        public void merken(String wer, String handlung, String ziel,
                           String ergebnis, String quellIp) {
            // Das Protokoll gehoert nicht zu dem, was hier geprueft wird.
        }
    };

    @BeforeEach
    void aufbauen() {
        verwaltung = new Releaseverwaltung(ausliefern.toString(), STILL);
    }

    // ------------------------------------------------------------ Werkzeug

    private void ablegen(String datei, String inhalt) throws IOException {
        Path p = ausliefern.resolve("release").resolve(datei);
        Files.createDirectories(p.getParent());
        Files.writeString(p, inhalt);
    }

    private String manifest(String version, String core) {
        return "version=" + version + "\n"
                + "registry=repository.hoer.jetzt\n"
                + "core=" + core + "\n"
                + "core_digest=sha256:aaaa\n"
                + "web=1.0\n";
    }

    private String aktuell() throws IOException {
        return Files.readString(ausliefern.resolve("release").resolve("aktuell"));
    }

    // --------------------------------------------------------------- Lesen

    @Test
    @DisplayName("Ohne Veroeffentlichung gibt es nichts zu zeigen")
    void nochNichts() {
        // Die Seite wird auch vor dem ersten Release aufgerufen. Sie darf
        // dann nicht mit einer Ausnahme abbrechen, sondern muss sagen
        // koennen, dass noch nichts da ist.
        assertNull(verwaltung.laufend());
        assertEquals("", verwaltung.laufendeVersion());
        assertTrue(verwaltung.verlaufListe().isEmpty());
    }

    @Test
    @DisplayName("Der laufende Stand mit seinen Bestandteilen")
    void laufend() throws IOException {
        ablegen("aktuell", manifest("2026.08.21.01", "1.4.2"));

        Releaseverwaltung.Stand s = verwaltung.laufend();
        assertNotNull(s);
        assertEquals("2026.08.21.01", s.version());
        assertTrue(s.gilt());
        // version und registry sind Angaben ueber das Release, keine
        // Komponenten - sie wuerden die Liste der Bestandteile verwaessern.
        // Die Digest-Zeile gehoert zur Komponente daneben und stuende sonst
        // doppelt da.
        assertEquals(List.of("core", "web"), List.copyOf(s.teile().keySet()));
        assertEquals("1.4.2", s.teile().get("core"));
    }

    @Test
    @DisplayName("Der Platzhalter aus einrichten.sh gilt nicht als Release")
    void platzhalter() throws IOException {
        // einrichten.sh legt "noch nichts veroeffentlicht" ab, damit Caddy
        // nicht mit 404 antwortet. Ohne "version=" darin darf die Seite das
        // nicht fuer ein Release halten.
        ablegen("aktuell", "noch nichts veroeffentlicht\n");
        assertNull(verwaltung.laufend());
    }

    @Test
    @DisplayName("Der Verlauf steht nach Ablagezeit, nicht nach Namen")
    void verlaufSortiert() throws IOException {
        ablegen("verlauf/2026.08.21.02", manifest("2026.08.21.02", "1.4.3"));
        ablegen("verlauf/2026.08.21.01b", manifest("2026.08.21.01b", "1.4.2b"));
        ablegen("verlauf/2026.08.21.01", manifest("2026.08.21.01", "1.4.2"));

        // Nach Namen sortiert stuende "01b" hinter "02". Tatsaechlich ist es
        // die Nachlieferung und damit die juengste. Die Ablagezeit weiss das,
        // der Name nicht.
        Path b = ausliefern.resolve("release/verlauf/2026.08.21.01b");
        Files.setLastModifiedTime(b,
                FileTime.fromMillis(System.currentTimeMillis() + 60_000));

        List<Releaseverwaltung.Stand> liste = verwaltung.verlaufListe();
        assertEquals(3, liste.size());
        assertEquals("2026.08.21.01b", liste.get(0).version());
    }

    @Test
    @DisplayName("Im Verlauf ist markiert, was gerade gilt")
    void giltMarkiert() throws IOException {
        ablegen("aktuell", manifest("2026.08.21.01", "1.4.2"));
        ablegen("verlauf/2026.08.21.01", manifest("2026.08.21.01", "1.4.2"));
        ablegen("verlauf/2026.08.20.01", manifest("2026.08.20.01", "1.4.1"));

        assertEquals(1, verwaltung.verlaufListe().stream()
                .filter(Releaseverwaltung.Stand::gilt).count());
    }

    // ------------------------------------------------------- Zurueckrollen

    @Test
    @DisplayName("Zurueckrollen schaltet das Manifest um")
    void zurueck() throws IOException {
        ablegen("aktuell", manifest("2026.08.21.02", "1.4.3"));
        ablegen("verlauf/2026.08.20.01", manifest("2026.08.20.01", "1.4.1"));

        verwaltung.zurueckAuf("2026.08.20.01", "marco");

        assertEquals("2026.08.20.01", verwaltung.laufendeVersion());
        assertTrue(aktuell().contains("core=1.4.1"));
    }

    @Test
    @DisplayName("Der verlassene Stand landet im Verlauf")
    void wegZurueckBleibtOffen() throws IOException {
        // Sonst rollt man einmal zurueck und kommt nicht wieder vor: der
        // Stand, den man gerade verlassen hat, waere nirgends mehr notiert.
        ablegen("aktuell", manifest("2026.08.21.02", "1.4.3"));
        ablegen("verlauf/2026.08.20.01", manifest("2026.08.20.01", "1.4.1"));

        verwaltung.zurueckAuf("2026.08.20.01", "marco");

        assertTrue(Files.isRegularFile(
                        ausliefern.resolve("release/verlauf/2026.08.21.02")),
                "Der verlassene Stand muss im Verlauf stehen");

        // Und der Weg vor ist wieder gangbar.
        verwaltung.zurueckAuf("2026.08.21.02", "marco");
        assertEquals("2026.08.21.02", verwaltung.laufendeVersion());
    }

    @Test
    @DisplayName("Ein Stand, den es nicht gibt, aendert nichts")
    void unbekannterStand() throws IOException {
        ablegen("aktuell", manifest("2026.08.21.02", "1.4.3"));

        assertThrows(IllegalArgumentException.class,
                () -> verwaltung.zurueckAuf("2026.01.01.01", "marco"));
        assertEquals("2026.08.21.02", verwaltung.laufendeVersion());
    }

    // ------------------------------------------------------------ Loeschen

    @Test
    @DisplayName("Der laufende Stand laesst sich nicht entfernen")
    void laufendenNichtEntfernen() throws IOException {
        // Sonst stuende in "aktuell" eine Version, zu der kein Manifest mehr
        // gehoert - und niemand koennte mehr sagen, was eigentlich laeuft.
        ablegen("aktuell", manifest("2026.08.21.01", "1.4.2"));
        ablegen("verlauf/2026.08.21.01", manifest("2026.08.21.01", "1.4.2"));

        assertThrows(IllegalArgumentException.class,
                () -> verwaltung.entfernen("2026.08.21.01", "marco"));
        assertTrue(Files.isRegularFile(
                ausliefern.resolve("release/verlauf/2026.08.21.01")));
    }

    @Test
    @DisplayName("Entfernen nimmt genau eine Datei")
    void entfernen() throws IOException {
        ablegen("aktuell", manifest("2026.08.21.02", "1.4.3"));
        ablegen("verlauf/2026.08.20.01", manifest("2026.08.20.01", "1.4.1"));

        verwaltung.entfernen("2026.08.20.01", "marco");

        assertFalse(Files.exists(ausliefern.resolve("release/verlauf/2026.08.20.01")));
        assertEquals("2026.08.21.02", verwaltung.laufendeVersion());
    }

    // --------------------------------------------------------------- Pfade

    @Test
    @DisplayName("Die Version ist ein Dateiname, kein Pfad")
    void keinPfad() throws IOException {
        // Der Kern: die Version kommt aus einem Formular. Ohne Pruefung
        // liesse sich mit ".." eine beliebige Datei ins Manifest kopieren -
        // und eine beliebige loeschen.
        Path fremd = ausliefern.resolve("geheim");
        Files.writeString(fremd, "version=eingeschleust\n");
        ablegen("aktuell", manifest("2026.08.21.01", "1.4.2"));

        List<String> boese = List.of("../geheim", "../../etc/passwd",
                "release/verlauf/x", "/etc/passwd", "..", ".", "a/b",
                "verlauf\\x", "2026.08.21.01 ../geheim");

        for (String b : boese) {
            assertThrows(IllegalArgumentException.class,
                    () -> verwaltung.zurueckAuf(b, "marco"),
                    "zurueckAuf haette " + b + " ablehnen muessen");
            assertThrows(IllegalArgumentException.class,
                    () -> verwaltung.entfernen(b, "marco"),
                    "entfernen haette " + b + " ablehnen muessen");
        }

        assertTrue(Files.isRegularFile(fremd), "Die fremde Datei muss liegen bleiben");
        assertEquals("2026.08.21.01", verwaltung.laufendeVersion());
    }

    @Test
    @DisplayName("Leer und null sind keine Version")
    void leer() {
        assertThrows(IllegalArgumentException.class, () -> verwaltung.zurueckAuf(null, "m"));
        assertThrows(IllegalArgumentException.class, () -> verwaltung.zurueckAuf("", "m"));
        assertThrows(IllegalArgumentException.class, () -> verwaltung.zurueckAuf("   ", "m"));
    }

    @Test
    @DisplayName("Was veroeffentlichen.sh vergibt, geht durch")
    void echteVersionen() throws IOException {
        // Die Pruefung darf nicht so streng sein, dass sie die tatsaechlich
        // vergebenen Namen ablehnt.
        for (String gut : List.of("2026.08.21.01", "1.4.2", "v1.0.0-rc1", "test_1")) {
            ablegen("verlauf/" + gut, manifest(gut, "1.0"));
            verwaltung.zurueckAuf(gut, "marco");
            assertEquals(gut, verwaltung.laufendeVersion());
        }
    }
}
