package jetzt.hoer.updater.dienst;

import jetzt.hoer.updater.daten.VerwaltungDaten;
import jetzt.hoer.updater.daten.VoreinstellungDaten;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Was tatsaechlich beim Knoten ankommt.
 *
 * <p>Die Datei ist das Ergebnis, auf das es ankommt - nicht die Zeile in
 * der Datenbank. Ein Wert, der gesetzt ist und nicht ausgeliefert wird,
 * sieht in der Oberflaeche richtig aus und wirkt trotzdem nirgends.</p>
 */
class VoreinstellungenTest {

    @TempDir
    Path ausliefern;

    private Voreinstellungen vorgaben;
    private Map<String, String> gespeichert;

    private static final VerwaltungDaten STILL = new VerwaltungDaten(null) {
        @Override
        public void merken(String wer, String handlung, String ziel,
                           String ergebnis, String quellIp) {
        }
    };

    @BeforeEach
    void aufbauen() {
        gespeichert = new LinkedHashMap<>();
        // Statt einer Datenbank eine Karte. Geprueft wird hier, was aus den
        // Werten wird - nicht, ob SQLite schreiben kann.
        VoreinstellungDaten daten = new VoreinstellungDaten(null) {
            @Override
            public Map<String, String> alle() {
                return new LinkedHashMap<>(gespeichert);
            }

            @Override
            public void setzen(String schluessel, String wert, String wer) {
                if (wert == null || wert.isBlank()) {
                    gespeichert.remove(schluessel);
                } else {
                    gespeichert.put(schluessel, wert);
                }
            }
        };
        vorgaben = new Voreinstellungen(ausliefern.toString(), daten, STILL);
    }

    private String datei(String profil) throws IOException {
        Path p = ausliefern.resolve("voreinstellungen").resolve(profil + ".env");
        return Files.isRegularFile(p) ? Files.readString(p) : "";
    }

    /** Nur die Wertzeilen - ohne Kopfkommentar. */
    private static Map<String, String> zeilen(String inhalt) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String z : inhalt.split("\n")) {
            if (z.isBlank() || z.startsWith("#")) {
                continue;
            }
            m.put(z.substring(0, z.indexOf('=')), z.substring(z.indexOf('=') + 1));
        }
        return m;
    }

    @Test
    @DisplayName("Ein gesetzter Wert landet nur beim richtigen Profil")
    void nurBeimRichtigenProfil() throws IOException {
        vorgaben.uebernehmen(Map.of("LAVALINK_QUALITAET", "sparsam"), "marco");

        assertEquals("sparsam", zeilen(datei("lavalink")).get("LAVALINK_QUALITAET"));
        // Ein Controller faehrt kein Lavalink - der Wert hat bei ihm nichts
        // zu suchen und wuerde die Datei nur laenger machen.
        assertFalse(zeilen(datei("controller")).containsKey("LAVALINK_QUALITAET"));
        assertFalse(zeilen(datei("core")).containsKey("LAVALINK_QUALITAET"));
    }

    @Test
    @DisplayName("Nicht gesetzt heisst nicht ausgeliefert")
    void nichtGesetztFehlt() throws IOException {
        vorgaben.uebernehmen(Map.of("LAVALINK_QUALITAET", "sparsam"), "marco");

        // YOUTUBE_PLUGIN_VERSION hat eine Vorgabe (1.18.2), ist aber nicht
        // gesetzt. Stuende sie in der Datei, waere die Vorgabe der
        // Compose-Datei ab jetzt eingefroren - eine spaetere Aenderung dort
        // wuerde nie mehr wirken.
        assertFalse(zeilen(datei("lavalink")).containsKey("YOUTUBE_PLUGIN_VERSION"));
    }

    @Test
    @DisplayName("Ein geleertes Feld nimmt den Wert zurueck")
    void leerenNimmtZurueck() throws IOException {
        vorgaben.uebernehmen(Map.of("LAVALINK_QUALITAET", "sparsam"), "marco");
        assertTrue(zeilen(datei("lavalink")).containsKey("LAVALINK_QUALITAET"));

        vorgaben.uebernehmen(Map.of("LAVALINK_QUALITAET", ""), "marco");

        assertFalse(zeilen(datei("lavalink")).containsKey("LAVALINK_QUALITAET"),
                "Leeren muss die Vorgabe zurueckholen, nicht einen leeren Wert setzen");
        assertFalse(gespeichert.containsKey("LAVALINK_QUALITAET"));
    }

    @Test
    @DisplayName("Alles oder nichts")
    void allesOderNichts() throws IOException {
        vorgaben.uebernehmen(Map.of("LAVALINK_QUALITAET", "sparsam"), "marco");

        // Zwei Werte, der zweite unbrauchbar. Ohne die Vorabpruefung stuende
        // der erste in der Datenbank und der zweite nicht - ausgeliefert
        // waere ein Zwischenstand, den so niemand wollte.
        Map<String, String> formular = new LinkedHashMap<>();
        formular.put("LAVALINK_QUALITAET", "hoch");
        formular.put("YOUTUBE_OAUTH", "vielleicht");

        assertThrows(IllegalArgumentException.class,
                () -> vorgaben.uebernehmen(formular, "marco"));

        assertEquals("sparsam", gespeichert.get("LAVALINK_QUALITAET"),
                "Der erste Wert darf nicht schon uebernommen sein");
        assertEquals("sparsam", zeilen(datei("lavalink")).get("LAVALINK_QUALITAET"));
    }

    @Test
    @DisplayName("Unbekannte Schluessel werden uebergangen, nicht uebernommen")
    void unbekanntesUebergehen() throws IOException {
        // Kommt aus einem veralteten Formular oder von Hand. Uebernaehme man
        // es, liesse sich ueber diese Seite jeder beliebige Wert in die .env
        // der Knoten schreiben - auch ein Token.
        Map<String, String> formular = new HashMap<>();
        formular.put("HJ_BOT_TOKEN", "erschlichen");
        formular.put("LAVALINK_QUALITAET", "hoch");

        vorgaben.uebernehmen(formular, "marco");

        assertFalse(gespeichert.containsKey("HJ_BOT_TOKEN"));
        for (String profil : Einstellungskatalog.PROFILE) {
            assertFalse(datei(profil).contains("HJ_BOT_TOKEN"),
                    "Ein Geheimnis darf nicht in einer unverschluesselten Datei landen");
        }
        assertEquals("hoch", gespeichert.get("LAVALINK_QUALITAET"));
    }

    @Test
    @DisplayName("Jedes Profil bekommt eine Datei, auch eine leere")
    void alleProfileGeschrieben() throws IOException {
        vorgaben.uebernehmen(Map.of("LAVALINK_QUALITAET", "hoch"), "marco");

        for (String profil : Einstellungskatalog.PROFILE) {
            assertFalse(datei(profil).isEmpty(),
                    "Fuer " + profil + " fehlt die Datei - der Agent bekaeme 404");
        }
        // Ein Profil ohne gesetzte Werte sagt das ausdruecklich, statt leer
        // dazustehen: eine leere Datei sieht aus wie ein Fehler.
        assertTrue(datei("ai-radio").contains("nichts gesetzt"));
    }

    @Test
    @DisplayName("Nichts geaendert schreibt auch nichts")
    void nichtsGeaendert() {
        assertEquals(1, vorgaben.uebernehmen(Map.of("LAVALINK_QUALITAET", "hoch"), "marco"));
        assertEquals(0, vorgaben.uebernehmen(Map.of("LAVALINK_QUALITAET", "hoch"), "marco"));
    }

    @Test
    @DisplayName("Die Datei nennt Herkunft und Zweck")
    void kopfzeile() throws IOException {
        vorgaben.uebernehmen(Map.of("LAVALINK_QUALITAET", "hoch"), "marco");
        String inhalt = datei("lavalink");

        // Wer sie auf einem Knoten findet, soll wissen, woher sie kommt und
        // dass Handaenderungen daran beim naechsten Lauf verschwinden.
        assertTrue(inhalt.startsWith("#"));
        assertTrue(inhalt.contains("lavalink"));
        assertTrue(inhalt.contains("auto-update.sh"));
    }

    @Test
    @DisplayName("Der geltende Wert ist der gesetzte, sonst die Vorgabe")
    void geltend() {
        var e = Einstellungskatalog.finden("LAVALINK_QUALITAET").orElseThrow();
        assertEquals("hoch", vorgaben.geltend(e, Map.of()));
        assertEquals("sparsam", vorgaben.geltend(e, Map.of("LAVALINK_QUALITAET", "sparsam")));
    }
}
