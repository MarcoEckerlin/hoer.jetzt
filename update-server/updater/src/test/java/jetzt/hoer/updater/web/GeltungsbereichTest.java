package jetzt.hoer.updater.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Der Geltungsbereich einer Token-Anfrage.
 *
 * <p>Hier haengt die gesamte Rechtepruefung der Registry dran. Forgejo gibt
 * einen Bearer-Token aus, und was damit geholt werden darf, entscheidet sich
 * an dieser einen Zeichenkette:</p>
 *
 * <pre>
 *   /v2/token?service=container_registry&amp;scope=repository:hoerjetzt/core:pull
 * </pre>
 *
 * <p>Wird sie falsch gelesen, bekommt ein Audio-Knoten einen Token fuer
 * {@code core} - und damit Zugriff auf ein Abbild, das ihn nichts angeht.
 * Wird sie zu streng gelesen, bekommt niemand mehr etwas.</p>
 */
class GeltungsbereichTest {

    @Test
    @DisplayName("Der uebliche Fall")
    void ziehen() {
        assertEquals("/v2/hoerjetzt/core/",
                TorController.geltungsbereich(
                        "/v2/token?service=container_registry&scope=repository:hoerjetzt/core:pull"));
    }

    @Test
    @DisplayName("Mehrere Rechte im Bereich")
    void ziehenUndSchieben() {
        // Beim Veroeffentlichen fragt Docker "pull,push" an. Fuer den Pfad
        // macht das keinen Unterschied - welches Recht gilt, entscheidet
        // Forgejo anhand des Kontos.
        assertEquals("/v2/hoerjetzt/core/",
                TorController.geltungsbereich(
                        "/v2/token?scope=repository:hoerjetzt/core:pull,push"));
    }

    @Test
    @DisplayName("Reihenfolge der Parameter ist egal")
    void reihenfolge() {
        assertEquals("/v2/hoerjetzt/lavalink/",
                TorController.geltungsbereich(
                        "/v2/token?scope=repository:hoerjetzt/lavalink:pull&service=container_registry"));
    }

    @Test
    @DisplayName("Maskierte Doppelpunkte werden entschluesselt")
    void maskiert() {
        // Docker maskiert den Bereich je nach Fassung unterschiedlich. Ohne
        // Entschluesselung stuende hier "repository%3Ahoerjetzt..." und die
        // Zerlegung am Doppelpunkt ginge ins Leere - der Bereich waere
        // unlesbar und die Anfrage kaeme ungeprueft durch.
        assertEquals("/v2/hoerjetzt/core/",
                TorController.geltungsbereich(
                        "/v2/token?scope=repository%3Ahoerjetzt%2Fcore%3Apull"));
    }

    @Test
    @DisplayName("Ohne Bereich: die blosse Anmeldung")
    void ohneBereich() {
        // "docker login" fragt zuerst ohne Bereich an - es will nur wissen,
        // ob es hereindarf. Holen laesst sich damit nichts; der naechste
        // Schritt nennt den Bereich und wird dann geprueft.
        assertNull(TorController.geltungsbereich("/v2/token?service=container_registry"));
        assertNull(TorController.geltungsbereich("/v2/token"));
        assertNull(TorController.geltungsbereich(null));
    }

    @Test
    @DisplayName("Unbrauchbare Angaben gelten als 'kein Bereich'")
    void unbrauchbar() {
        // Wichtig fuer die Sicherheit: eine Anfrage, deren Bereich sich nicht
        // lesen laesst, darf nicht versehentlich als "alles erlaubt"
        // durchgehen. Sie liefert null - und der Aufrufer behandelt null wie
        // die blosse Anmeldung, die nichts herausgibt.
        assertNull(TorController.geltungsbereich("/v2/token?scope=repository"));
        assertNull(TorController.geltungsbereich("/v2/token?scope=repository:"));
        assertNull(TorController.geltungsbereich("/v2/token?scope=quatsch:hoerjetzt/core:pull"));
    }

    @Test
    @DisplayName("Ein anderes Repository ergibt einen anderen Pfad")
    void verschieden() {
        // Damit sichtbar bleibt, dass die Trennung wirklich am Namen haengt:
        // zwei Module, zwei Pfade, und Pfadrechte entscheidet darueber.
        assertNotEquals(
                TorController.geltungsbereich("/v2/token?scope=repository:hoerjetzt/core:pull"),
                TorController.geltungsbereich("/v2/token?scope=repository:hoerjetzt/lavalink:pull"));
    }
}
