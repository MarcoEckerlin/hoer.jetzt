package jetzt.hoer.updater.web;

import jetzt.hoer.updater.dienst.Sicherungen;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Die Sicherungsseite.
 *
 * <p>Der Schwerpunkt liegt auf dem Herunterladen: dort wandert ein Name aus
 * der Adresszeile in einen Dateipfad. Ohne Pruefung liesse sich damit jede
 * Datei im Container holen - auch {@code updater.db} mit den Geheimnissen
 * aller Knoten.</p>
 */
@WebMvcTest(SicherungController.class)
@AutoConfigureMockMvc(addFilters = false)
class SicherungSeiteTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    Sicherungen sicherungen;

    @TempDir
    Path ablage;

    private Path angelegt;

    @BeforeEach
    void aufbauen() throws IOException {
        angelegt = ablage.resolve("controller-1-20260822T000700Z.sql.gz");
        Files.write(angelegt, new byte[]{0x1f, (byte) 0x8b, 0, 1, 2, 3});
    }

    @Test
    @DisplayName("Die Seite gruppiert nach Knoten")
    void rendert() throws Exception {
        when(sicherungen.alle()).thenReturn(List.of(
                new Sicherungen.Eintrag("controller-1-20260822T000700Z.sql.gz",
                        5_242_880L, Instant.now()),
                new Sicherungen.Eintrag("controller-1-20260821T210700Z.sql.gz",
                        5_100_000L, Instant.now().minusSeconds(10_800)),
                new Sicherungen.Eintrag("controller-2-20260822T000700Z.sql.gz",
                        4_900_000L, Instant.now()),
                new Sicherungen.Eintrag("updater-20260822T001200Z.db",
                        270_000L, Instant.now())));

        mvc.perform(get("/sicherungen"))
                .andExpect(status().isOk())
                // Die Kennung mit Bindestrich darf nicht am Bindestrich
                // zerfallen - sonst stuende hier "controller" statt
                // "controller-1", und beide Knoten laegen in einem Topf.
                .andExpect(content().string(containsString("controller-1")))
                .andExpect(content().string(containsString("controller-2")))
                .andExpect(content().string(containsString("updater")))
                .andExpect(content().string(containsString("herunterladen")));
    }

    @Test
    @DisplayName("Ohne Sicherungen steht da, was zu tun ist")
    void leer() throws Exception {
        // Der haeufigste Fall am Anfang - und der, bei dem eine leere Tabelle
        // wie ein Fehler der Seite aussieht statt wie ein fehlender Timer.
        when(sicherungen.alle()).thenReturn(List.of());

        mvc.perform(get("/sicherungen"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Noch keine Sicherung")))
                .andExpect(content().string(containsString("hj-sicherung.timer")));
    }

    @Test
    @DisplayName("Herunterladen liefert die Datei zum Speichern")
    void herunterladen() throws Exception {
        when(sicherungen.datei(anyString())).thenReturn(angelegt);

        mvc.perform(get("/sicherungen/controller-1-20260822T000700Z.sql.gz"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        containsString("attachment")))
                .andExpect(header().string("Content-Disposition",
                        containsString("controller-1-20260822T000700Z.sql.gz")));
    }

    @Test
    @DisplayName("Ein Name mit Pfadanteil kommt nicht durch")
    void keinPfadausbruch() throws Exception {
        // Sicherungen.datei wirft bei einem Namen, der aus der Ablage
        // herausfuehrt. Der Controller muss das als 400 beantworten und darf
        // es nicht zu einem 500 werden lassen - eine Ausnahme im Log ist
        // keine Antwort.
        when(sicherungen.datei(anyString()))
                .thenThrow(new IllegalArgumentException("Ungueltiger Name."));

        mvc.perform(get("/sicherungen/updater.db"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Was es nicht gibt, ist 404 und nicht 500")
    void fehlt() throws Exception {
        when(sicherungen.datei(anyString())).thenReturn(ablage.resolve("gibtesnicht.sql.gz"));

        mvc.perform(get("/sicherungen/gibtesnicht.sql.gz"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Entfernen kehrt auf die Seite zurueck")
    void entfernen() throws Exception {
        when(sicherungen.loeschen(anyString(), anyString())).thenReturn(true);

        mvc.perform(post("/sicherungen/controller-1-20260822T000700Z.sql.gz/entfernen"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/sicherungen"));
    }

    // ------------------------------------------------------------- Zerlegen

    @Test
    @DisplayName("Die Kennung wird korrekt aus dem Dateinamen gelesen")
    void knotenAus() {
        // Der Name entsteht als <kennung>-<zeitstempel>. Kennungen haben
        // selbst Bindestriche, also zaehlt der LETZTE.
        assertEquals("controller-1",
                SicherungController.knotenAus("controller-1-20260822T000700Z.sql.gz"));
        assertEquals("lavalink-free-2",
                SicherungController.knotenAus("lavalink-free-2-20260822T000700Z.sql.gz"));
        assertEquals("updater",
                SicherungController.knotenAus("updater-20260822T001200Z.db"));
        // Etwas ohne Bindestrich faellt nicht auseinander.
        assertTrue(SicherungController.knotenAus("komisch.sql.gz").length() > 0);
    }
}
