package jetzt.hoer.updater.web;

import jetzt.hoer.updater.dienst.Einstellungskatalog;
import jetzt.hoer.updater.dienst.Voreinstellungen;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Die Vorgabenseite wirklich rendern.
 *
 * <p>Sie hat viele Felder, und jedes davon wird aus dem Katalog erzeugt.
 * Ein Ausdrucksfehler in der Schleife faellt weder beim Uebersetzen noch
 * bei der Tag-Bilanz auf - die Seite endet dann mit 500.</p>
 */
@WebMvcTest(EinstellungController.class)
@AutoConfigureMockMvc(addFilters = false)
class VorgabenSeiteTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    Voreinstellungen vorgaben;

    @Test
    @DisplayName("Die Seite rendert mit gesetzten und ungesetzten Werten")
    void rendert() throws Exception {
        when(vorgaben.werte()).thenReturn(Map.of("LAVALINK_QUALITAET", "sparsam"));
        when(vorgaben.vorschau(anyString())).thenReturn("LAVALINK_QUALITAET=sparsam\n");

        mvc.perform(get("/voreinstellungen"))
                .andExpect(status().isOk())
                // Ein Auswahlfeld mit dem gesetzten Wert vorgewaehlt.
                .andExpect(content().string(containsString("LAVALINK_QUALITAET")))
                .andExpect(content().string(containsString("selected")))
                // Ein Textfeld mit der Vorgabe als Platzhalter - nicht als Wert.
                .andExpect(content().string(containsString("YOUTUBE_PLUGIN_VERSION")))
                .andExpect(content().string(containsString("Vorgabe: 1.18.2")))
                // Und die Erklaerung darunter.
                .andExpect(content().string(containsString("Player-Skript")));
    }

    @Test
    @DisplayName("Die Vorgabe steht als Platzhalter, nicht als Wert")
    void vorgabeNichtAlsWert() throws Exception {
        // Sonst saehe jedes Feld gesetzt aus, und beim ersten Speichern
        // stuenden alle Vorgaben als eigene Werte in der Datenbank. Danach
        // folgte keiner davon mehr einer geaenderten Vorgabe - eine Aenderung
        // an der Compose-Datei waere ab dann wirkungslos.
        when(vorgaben.werte()).thenReturn(Map.of());
        when(vorgaben.vorschau(anyString())).thenReturn("");

        String html = mvc.perform(get("/voreinstellungen"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertFalse(html.contains("value=\"1.18.2\""),
                "Die Vorgabe darf nicht als Feldwert dastehen");
        assertTrue(html.contains("placeholder=\"Vorgabe: 1.18.2\""));
    }

    @Test
    @DisplayName("Jede Gruppe des Katalogs kommt auf der Seite vor")
    void alleGruppen() throws Exception {
        when(vorgaben.werte()).thenReturn(Map.of());
        when(vorgaben.vorschau(anyString())).thenReturn("");

        String html = mvc.perform(get("/voreinstellungen"))
                .andReturn().getResponse().getContentAsString();

        for (String gruppe : Einstellungskatalog.nachGruppe().keySet()) {
            assertTrue(html.contains(gruppe), "Gruppe fehlt auf der Seite: " + gruppe);
        }
        for (var e : Einstellungskatalog.alle()) {
            assertTrue(html.contains(e.schluessel()),
                    "Schluessel fehlt auf der Seite: " + e.schluessel());
        }
    }

    @Test
    @DisplayName("Was ein Knoten bekaeme, steht mit auf der Seite")
    void vorschauSichtbar() throws Exception {
        when(vorgaben.werte()).thenReturn(Map.of());
        when(vorgaben.vorschau(anyString())).thenReturn("LAVALINK_QUALITAET=hoch\n");

        mvc.perform(get("/voreinstellungen"))
                .andExpect(content().string(containsString("voreinstellungen/lavalink.env")))
                .andExpect(content().string(containsString("LAVALINK_QUALITAET=hoch")));
    }

    @Test
    @DisplayName("Speichern meldet zurueck und bleibt auf der Seite")
    void speichern() throws Exception {
        when(vorgaben.uebernehmen(any(), anyString())).thenReturn(2);

        mvc.perform(post("/voreinstellungen").param("LAVALINK_QUALITAET", "hoch"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/voreinstellungen"));
    }

    @Test
    @DisplayName("Ein unbrauchbarer Wert wird gemeldet, nicht verschluckt")
    void fehlerMelden() throws Exception {
        when(vorgaben.uebernehmen(any(), anyString()))
                .thenThrow(new IllegalArgumentException("YOUTUBE_OAUTH: nur true oder false."));

        mvc.perform(post("/voreinstellungen").param("YOUTUBE_OAUTH", "ja"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/voreinstellungen"));
    }

}
