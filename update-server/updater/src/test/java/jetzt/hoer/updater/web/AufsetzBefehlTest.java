package jetzt.hoer.updater.web;

import jetzt.hoer.updater.modell.Modul;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Der Aufsetzbefehl.
 *
 * <p>Er ist das Ergebnis der ganzen Knotenseite - alles andere fuehrt nur
 * dorthin. Und er ist schon einmal still zerbrochen: als Ausdruck in der
 * Thymeleaf-Vorlage gebaut, scheiterte er am senkrechten Strich in
 * {@code | bash -s --}, den Thymeleaf als Literalersetzung deutet. Die Seite
 * antwortete daraufhin mit 500, und die Meldung stand nur im Log des
 * Containers.</p>
 *
 * <p>Deshalb wird er in Java gebaut und hier geprueft.</p>
 */
class AufsetzBefehlTest {

    /** Nur fuer den Befehlsbau - die Abhaengigkeiten braucht er nicht. */
    private final PultController pult = new PultController(
            null, null, null, null, null, null, null, null, null,
            "repository.hoer.jetzt");

    @Test
    @DisplayName("Ein Befehl, alles drin")
    void vollstaendig() {
        String b = pult.aufsetzBefehl("controller-1", "hj-ABCD-EFGH-JKLM-NPQR",
                "controller", List.of(Modul.CONTROLLER));

        assertEquals("curl -fsSL https://repository.hoer.jetzt/bootstrap"
                        + " | bash -s -- --rolle controller"
                        + " --kennung controller-1"
                        + " --token hj-ABCD-EFGH-JKLM-NPQR"
                        + " --modules CONTROLLER",
                b);
    }

    @Test
    @DisplayName("Mehrere Module werden mit Komma getrennt")
    void mehrereModule() {
        String b = pult.aufsetzBefehl("knoten-3", "hj-TOKEN", "node",
                List.of(Modul.CORE, Modul.LAVALINK));
        assertTrue(b.endsWith(" --modules CORE,LAVALINK"), b);
        // Kein Leerzeichen im Komma - sonst bricht die Argumentliste
        // auseinander und bash sieht "LAVALINK" als eigenen Befehl.
        assertFalse(b.contains(", "), "Kein Leerzeichen nach dem Komma: " + b);
    }

    @Test
    @DisplayName("Ohne Module faellt --modules weg")
    void ohneModule() {
        assertFalse(pult.aufsetzBefehl("k", "t", "node", List.of()).contains("--modules"));
        assertFalse(pult.aufsetzBefehl("k", "t", "node", null).contains("--modules"));
    }

    @Test
    @DisplayName("Der senkrechte Strich steht wirklich drin")
    void mitStrich() {
        // Genau daran ist die erste Fassung gescheitert. Der Befehl ist ohne
        // ihn kein Befehl mehr, sondern laedt eine Datei und tut nichts.
        String b = pult.aufsetzBefehl("k", "t", "node", List.of(Modul.CORE));
        assertTrue(b.contains("/bootstrap | bash -s --"), b);
    }

    @Test
    @DisplayName("Nur ein Geheimnis im Befehl")
    void einGeheimnis() {
        // Der Token oeffnet Download UND Anmeldung. Stuende hier zusaetzlich
        // das globale Aufsetz-Passwort, waere der Sinn des Einzeilers weg.
        String b = pult.aufsetzBefehl("k", "hj-GEHEIM", "node", List.of(Modul.CORE));
        assertEquals(1, b.split("hj-GEHEIM", -1).length - 1,
                "Der Token soll genau einmal vorkommen: " + b);
        assertFalse(b.contains("-u knoten"), "Kein zweites Geheimnis: " + b);
    }
}
