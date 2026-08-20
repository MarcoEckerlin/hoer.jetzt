package jetzt.hoer.updater.dienst;

import jetzt.hoer.updater.modell.Ausweis;
import jetzt.hoer.updater.modell.Modul;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hier entscheidet sich, ob die Trennung nach Modulen tatsaechlich traegt.
 *
 * <p>Wie {@code NetzbereichTest}: keine Abhaengigkeiten ausser der
 * Standardbibliothek, also kein Grund, es nicht zu pruefen. Und wie dort sind
 * die Faelle nicht willkuerlich - jeder steht fuer einen Fehler, den man an
 * dieser Stelle tatsaechlich macht.</p>
 */
class PfadrechteTest {

    private static final Ausweis AUDIO =
            Ausweis.fuer("lavalink-07", Modul.LAVALINK.faehigkeiten());
    private static final Ausweis CORE =
            Ausweis.fuer("core-1", Modul.CORE.faehigkeiten());

    // ------------------------------------------------------------------------
    // Der eigentliche Zweck des Umbaus
    // ------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "/tresor/voll",
            "/tresor/core",
            "/tresor/controller",
            "/config/core",
            "/v2/hoerjetzt/core/blobs/sha256:abc",
            "/v2/hoerjetzt/web/manifests/latest"
    })
    @DisplayName("Ein Audio-Knoten kommt nicht an die Core-Geheimnisse")
    void audioKommtNichtAnCore(String pfad) {
        assertFalse(Pfadrechte.darf(AUDIO, pfad),
                "Ein Lavalink-Knoten darf " + pfad + " nicht oeffnen - genau dort "
                + "liegen Bot-Token, Datenbank-Passwort und Client-Secret.");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/v2/hoerjetzt/lavalink/blobs/sha256:abc",
            "/v2/hoerjetzt/lavalink/manifests/latest",
            "/tresor/lavalink",
            "/config/lavalink"
    })
    @DisplayName("Sein eigenes Modul bekommt er weiterhin")
    void audioBekommtSeines(String pfad) {
        assertTrue(Pfadrechte.darf(AUDIO, pfad));
    }

    @Test
    @DisplayName("Ein Core-Knoten bekommt kein Lavalink-Passwort")
    void coreKommtNichtAnLavalink() {
        assertTrue(Pfadrechte.darf(CORE, "/tresor/voll"));
        assertFalse(Pfadrechte.darf(CORE, "/tresor/lavalink"));
    }

    // ------------------------------------------------------------------------
    // Was jeder Knoten braucht
    // ------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"/v2/", "/v2", "/release/aktuell", "/melden"})
    @DisplayName("Versionspruefung, Manifest und Herzschlag stehen jedem offen")
    void grundpfade(String pfad) {
        assertTrue(Pfadrechte.darf(AUDIO, pfad),
                pfad + " muss jeder angemeldete Knoten erreichen - sonst weiss er "
                + "nicht einmal, ob es fuer ihn etwas zu tun gibt.");
    }

    // ------------------------------------------------------------------------
    // Der Uebergang
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Das gemeinsame Passwort oeffnet alles - wenn es denn zugelassen ist")
    void gemeinsamesPasswortOeffnetAlles() {
        Ausweis alt = Ausweis.mitGemeinsamemPasswort();
        assertTrue(Pfadrechte.darf(alt, "/tresor/voll"));
        assertTrue(Pfadrechte.darf(alt, "/v2/hoerjetzt/core/blobs/x"));

        // Wichtig zum Verstaendnis dieser Probe: sie prueft, was ein solcher
        // Ausweis darf - nicht, ob es ihn ueberhaupt geben kann.
        //
        // Ob einer ausgestellt wird, entscheidet Zugang anhand von
        // hj.token.gemeinsam-erlauben, und das steht ab Werk auf FALSE. Im
        // Normalbetrieb kommt dieser Fall also gar nicht vor. Er bleibt
        // hier, weil der Schalter fuer den Notfall existiert - und wenn
        // jemand ihn umlegt, soll nachlesbar sein, was er damit oeffnet.
    }

    @ParameterizedTest
    @CsvSource({
            // Der alte Name steht noch in Compose-Dateien und Abbildnamen.
            // Faellt er hier durch, verlieren laufende Knoten mitten in der
            // Umbenennung den Zugriff auf ihr eigenes Abbild.
            "/v2/hoerjetzt/ai-radio/blobs/x, true",
            "/v2/hoerjetzt/ki-radio/blobs/x, true",
            "/v2/hoerjetzt/lavalink/blobs/x, false"
    })
    @DisplayName("Der alte Name ai-radio zeigt weiter auf dieselbe Faehigkeit")
    void alterNameTraegtNoch(String pfad, boolean erwartet) {
        Ausweis radio = Ausweis.fuer("radio-1", Modul.KI_RADIO.faehigkeiten());
        assertEquals(erwartet, Pfadrechte.darf(radio, pfad));
    }

    // ------------------------------------------------------------------------
    // Was man an dieser Stelle falsch macht
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Ein Abfrageteil aendert die Entscheidung nicht")
    void abfrageteilAendertNichts() {
        // Sonst entschiede /tresor/voll?x=1 anders als /tresor/voll - ein
        // Unterschied, den niemand erwartet und den ein Angreifer zuerst
        // ausprobiert.
        assertFalse(Pfadrechte.darf(AUDIO, "/tresor/voll?x=1"));
        assertTrue(Pfadrechte.darf(AUDIO, "/tresor/lavalink?x=1"));
    }

    @Test
    @DisplayName("Der Registry-Katalog bleibt zu")
    void katalogBleibtZu() {
        // /v2/_catalog listet die gesamte Registry auf. Nicht zuzuordnen
        // heisst gesperrt - und hier ist das ausdruecklich erwuenscht.
        assertFalse(Pfadrechte.darf(CORE, "/v2/_catalog"));
    }

    @Test
    @DisplayName("Ein mehrteiliger Besitzername verschiebt den Modulnamen nicht")
    void mehrteiligerBesitzer() {
        // Ein fester Abschnittszaehler ginge hier schief: das Modul ist der
        // Abschnitt vor blobs/manifests, nicht der dritte.
        assertTrue(Pfadrechte.darf(CORE, "/v2/hoer/jetzt/core/blobs/sha256:x"));
        assertFalse(Pfadrechte.darf(CORE, "/v2/hoer/jetzt/lavalink/blobs/sha256:x"));
    }

    @Test
    @DisplayName("Der Upload-Pfad endet auf zwei Schluesselwoerter und muss trotzdem passen")
    void uploadPfad() {
        // Beim Veroeffentlichen laeuft der Upload ueber
        // /v2/<name>/blobs/uploads/<uuid>. Von hinten gesucht gewinnt
        // "uploads", und als Modul kaeme "blobs" heraus - der Knoten waere
        // ausgerechnet beim Hochladen ausgesperrt. Genau so war es zuerst
        // gebaut, und genau dieser Fall hat es gezeigt.
        assertTrue(Pfadrechte.darf(CORE, "/v2/hoerjetzt/core/blobs/uploads/"));
        assertTrue(Pfadrechte.darf(CORE, "/v2/hoerjetzt/core/blobs/uploads/abc-123"));
        assertFalse(Pfadrechte.darf(CORE, "/v2/hoerjetzt/lavalink/blobs/uploads/abc"));
    }

    @Test
    @DisplayName("Seinen eigenen Schluessel darf jeder hinterlegen")
    void schluesselFuerAlle() {
        // Sonst waere es ein Zirkel: ohne Schluessel kein Tresor, ohne
        // Tresor-Faehigkeit kein Schluessel.
        assertTrue(Pfadrechte.darf(AUDIO, "/schluessel"));
        assertTrue(Pfadrechte.darf(CORE, "/schluessel"));
    }

    @Test
    @DisplayName("Sicherungen darf nur der Controller ablegen")
    void sicherungNurController() {
        Ausweis controller = Ausweis.fuer("first", Modul.CONTROLLER.faehigkeiten());
        assertTrue(Pfadrechte.darf(controller, "/sicherung/first-2026.sql.gz"));
        // Ein Audio-Knoten hat keine Datenbank - und wer hier schreiben darf,
        // kann den Speicherplatz des Update-Servers fuellen.
        assertFalse(Pfadrechte.darf(AUDIO, "/sicherung/beliebig.sql.gz"));
        assertFalse(Pfadrechte.darf(CORE, "/sicherung/beliebig.sql.gz"));
    }

    @Test
    @DisplayName("Der Controller kommt an sein eigenes Profil, der Core nicht")
    void controllerProfil() {
        Ausweis controller = Ausweis.fuer("first", Modul.CONTROLLER.faehigkeiten());
        assertTrue(Pfadrechte.darf(controller, "/tresor/controller"));
        assertTrue(Pfadrechte.darf(controller, "/tresor/voll"));
        assertFalse(Pfadrechte.darf(CORE, "/tresor/controller"));
    }

    @Test
    @DisplayName("Unbekannt heisst gesperrt, nicht offen")
    void unbekanntIstGesperrt() {
        assertFalse(Pfadrechte.darf(CORE, "/etwas/neues"));
        assertFalse(Pfadrechte.darf(CORE, ""));
        assertFalse(Pfadrechte.darf(CORE, null));
        assertFalse(Pfadrechte.darf(null, "/tresor/voll"));
    }
}
