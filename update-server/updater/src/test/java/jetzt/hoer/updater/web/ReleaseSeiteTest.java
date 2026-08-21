package jetzt.hoer.updater.web;

import jetzt.hoer.updater.daten.AnmeldungDaten;
import jetzt.hoer.updater.daten.AusweisDaten;
import jetzt.hoer.updater.daten.FreigabeDaten;
import jetzt.hoer.updater.daten.KnotenDaten;
import jetzt.hoer.updater.daten.VerwaltungDaten;
import jetzt.hoer.updater.daten.ZugriffDaten;
import jetzt.hoer.updater.dienst.Knotenverwaltung;
import jetzt.hoer.updater.dienst.Releaseverwaltung;
import jetzt.hoer.updater.dienst.Torwaechter;
import jetzt.hoer.updater.dienst.Zugang;
import jetzt.hoer.updater.modell.Knoten;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rendert die Release-Seite wirklich.
 *
 * <h2>Warum das hier steht</h2>
 *
 * <p>Eine Vorlage kann syntaktisch tadellos sein und trotzdem beim Aufruf
 * mit 500 enden - Thymeleaf wertet die Ausdruecke erst zur Laufzeit aus.
 * Genau das ist schon passiert: ein {@code |} im Text wurde als
 * Literal-Ersetzung gelesen, und die Seite war tot. Weder der Uebersetzer
 * noch eine Pruefung der Tag-Bilanz sieht so etwas.</p>
 *
 * <p>Also einmal durch den echten Vorlagen-Motor schicken. Kommt HTML mit
 * den erwarteten Stuecken heraus, sind die Ausdruecke auswertbar - und
 * damit auch die Zeitformatierung eines {@link Instant}, die ohne Zone
 * gern mit einer Ausnahme endet.</p>
 */
@WebMvcTest(PultController.class)
@AutoConfigureMockMvc(addFilters = false)
class ReleaseSeiteTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean FreigabeDaten freigaben;
    @MockitoBean KnotenDaten knotenDaten;
    @MockitoBean ZugriffDaten zugriffe;
    @MockitoBean Torwaechter torwaechter;
    @MockitoBean AusweisDaten ausweise;
    @MockitoBean AnmeldungDaten anmeldungen;
    @MockitoBean VerwaltungDaten protokoll;
    @MockitoBean Knotenverwaltung verwaltung;
    @MockitoBean Zugang zugang;
    @MockitoBean Releaseverwaltung releases;

    private static Map<String, String> teile() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("core", "2026.08.21.01");
        m.put("web", "2026.08.21.01");
        return m;
    }

    private Knoten knoten(String kennung, String version, Instant gesehen) {
        // Ueber den kanonischen Konstruktor, damit der Test bricht, wenn dem
        // Record ein Feld zuwaechst - und nicht still etwas anderes prueft.
        return new Knoten(kennung, kennung, "controller", version,
                "", "", "", "", gesehen, gesehen,
                false, true, false, "", null, "", "",
                "host", "10.0.0.1", "1.0");
    }

    @Test
    @DisplayName("Die Seite rendert mit Verlauf")
    void mitVerlauf() throws Exception {
        Instant jetzt = Instant.now();
        when(releases.laufendeVersion()).thenReturn("2026.08.21.01");
        when(releases.laufend()).thenReturn(
                new Releaseverwaltung.Stand("2026.08.21.01", jetzt, teile(), true));
        when(releases.verlaufListe()).thenReturn(List.of(
                new Releaseverwaltung.Stand("2026.08.21.01", jetzt, teile(), true),
                new Releaseverwaltung.Stand("2026.08.20.01",
                        jetzt.minusSeconds(90_000), teile(), false)));
        when(knotenDaten.alle()).thenReturn(List.of(
                knoten("controller-1", "2026.08.21.01", jetzt),
                knoten("core-1", "2026.08.20.01", jetzt.minusSeconds(600)),
                // Einer, der sich nie gemeldet hat: null bei Version und
                // Zeitpunkt ist der Fall, an dem eine Vorlage abstuerzt.
                knoten("lavalink-1", null, null)));

        mvc.perform(get("/releases"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("2026.08.21.01")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("2026.08.20.01")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("zurueckrollen")))
                // Der Zeitpunkt muss formatiert dastehen. Ein Instant ohne
                // Zone laesst sich nicht auf Tag und Stunde abbilden - wenn
                // das schiefgeht, endet die Seite mit 500 statt mit einem
                // schiefen Datum.
                .andExpect(content().string(org.hamcrest.Matchers.matchesPattern(
                        "(?s).*\\d{2}\\.\\d{2}\\.\\d{4} \\d{2}:\\d{2}.*")))
                // Und der nie gesehene Knoten steht mit Gedankenstrich da,
                // nicht mit einer Ausnahme.
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("noch nichts gemeldet")));
    }

    @Test
    @DisplayName("Die Seite rendert auch vor dem ersten Release")
    void ohneRelease() throws Exception {
        // Der haeufigste Aufruf direkt nach der Einrichtung. Hier ist
        // "laufend" null und der Verlauf leer - beides muss die Vorlage
        // aushalten, sonst ist die Seite kaputt, bevor sie je nuetzt.
        when(releases.laufendeVersion()).thenReturn("");
        when(releases.laufend()).thenReturn(null);
        when(releases.verlaufListe()).thenReturn(List.of());
        when(knotenDaten.alle()).thenReturn(List.of());

        mvc.perform(get("/releases"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Es ist noch nichts veroeffentlicht")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Noch kein Verlauf")));
    }

    @Test
    @DisplayName("Zurueckrollen leitet auf die Seite zurueck")
    void zurueckLeitetZurueck() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/releases/2026.08.20.01/zurueck")
                        .param("vormerken", "false"))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .redirectedUrl("/releases"));
    }

    @Test
    @DisplayName("Vormerken von der Release-Seite kehrt dorthin zurueck")
    void vormerkenKehrtZurueck() throws Exception {
        // Ohne "zurueck" landete man auf der Uebersicht und muesste sich
        // zurueckklicken.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/knoten/core-1/update")
                        .param("an", "true")
                        .param("zurueck", "/releases"))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .redirectedUrl("/releases"));
    }

    @Test
    @DisplayName("Ein fremdes Ziel fuehrt nicht aus dem Haus")
    void keineOffeneWeiterleitung() throws Exception {
        // Das Feld kommt aus einem Formular. Ungeprueft in ein Redirect
        // gegeben waere es eine offene Weiterleitung.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/knoten/core-1/update")
                        .param("an", "true")
                        .param("zurueck", "https://beispiel.example/phish"))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .redirectedUrl("/"));
    }

    @Test
    @DisplayName("Die Navigation kennt die Seite")
    void navigation() throws Exception {
        when(releases.laufendeVersion()).thenReturn("");
        when(releases.laufend()).thenReturn(null);
        when(releases.verlaufListe()).thenReturn(List.of());
        when(knotenDaten.alle()).thenReturn(List.of());

        mvc.perform(get("/releases"))
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("href=\"/releases\"")));
    }
}
