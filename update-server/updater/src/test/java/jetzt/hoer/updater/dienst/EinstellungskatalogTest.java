package jetzt.hoer.updater.dienst;

import jetzt.hoer.updater.dienst.Einstellungskatalog.Eintrag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Der Katalog entscheidet, was zentral gesetzt werden darf.
 *
 * <p>Ein Fehler hier trifft nicht einen Knoten, sondern alle gleichzeitig -
 * und zwar lautlos. Steht {@code HJ_SHARD_VON} zentral, bekommen alle
 * dieselbe Scherbe des Shardings; der Bot ist dann auf einem Teil der
 * Discord-Server stumm, und es sieht aus wie ein Ausfall einzelner Server
 * statt wie eine Rechnung, die nicht aufgeht.</p>
 */
class EinstellungskatalogTest {

    @Test
    @DisplayName("Der Katalog ist in sich stimmig")
    void stimmig() {
        // Laeuft auch beim Start des Dienstes - hier, damit ein Fehler beim
        // Bauen auffaellt und nicht erst beim Hochfahren.
        assertDoesNotThrow(Einstellungskatalog::pruefen);
    }

    @Test
    @DisplayName("Kein Schluessel ist knotenspezifisch oder geheim")
    void nichtsVerbotenes() {
        for (Eintrag e : Einstellungskatalog.alle()) {
            assertFalse(Einstellungskatalog.VERBOTEN.contains(e.schluessel()),
                    e.schluessel() + " darf nicht zentral gesetzt werden");
        }
    }

    @Test
    @DisplayName("Die Shard-Grenzen stehen ausdruecklich nicht zur Auswahl")
    void keineShardGrenzen() {
        // Namentlich, nicht nur ueber die Verbotsliste: wer sie spaeter aus
        // der Liste nimmt, soll hier stolpern und nicht im Betrieb.
        assertTrue(Einstellungskatalog.finden("HJ_SHARD_VON").isEmpty());
        assertTrue(Einstellungskatalog.finden("HJ_SHARD_BIS").isEmpty());
        assertTrue(Einstellungskatalog.finden("HJ_NODE_NR").isEmpty());
        // Die Gesamtzahl dagegen ist eine Zahl fuer den ganzen Verbund und
        // gehoert sehr wohl hierher.
        assertTrue(Einstellungskatalog.finden("HJ_SHARDS_GESAMT").isPresent());
    }

    @Test
    @DisplayName("Kein Geheimnis steht im Katalog")
    void keineGeheimnisse() {
        // Die Datei wird unverschluesselt ausgeliefert - jeder Knoten holt
        // sie ueber HTTPS, aber ohne Umschlag. Was geheim ist, gehoert in
        // den Tresor.
        for (String geheim : List.of("HJ_BOT_TOKEN", "HJ_DB_PASSWORD",
                "HJ_LAVALINK_PASSWORD", "HJ_DISCORD_CLIENT_SECRET",
                "YT_CIPHER_PASSWORD", "YOUTUBE_REFRESH_TOKEN", "HJ_HETZNER_TOKEN")) {
            assertTrue(Einstellungskatalog.finden(geheim).isEmpty(),
                    geheim + " gehoert in den Tresor, nicht in die Vorgaben");
        }
    }

    @Test
    @DisplayName("Jedes Profil bekommt etwas, und nicht alles")
    void profileTrennen() {
        // Ein Audio-Knoten hat mit Autoscale nichts zu tun, ein Controller
        // nichts mit der Lavalink-Qualitaet. Die Trennung haelt die Datei
        // kurz genug, um sie im Zweifel von Hand zu lesen.
        assertTrue(Einstellungskatalog.fuer("lavalink").stream()
                .anyMatch(e -> e.schluessel().equals("LAVALINK_QUALITAET")));
        assertTrue(Einstellungskatalog.fuer("lavalink").stream()
                .noneMatch(e -> e.schluessel().startsWith("HJ_AUTOSCALE")));
        assertTrue(Einstellungskatalog.fuer("controller").stream()
                .anyMatch(e -> e.schluessel().equals("HJ_AUTOSCALE")));
        assertTrue(Einstellungskatalog.fuer("controller").stream()
                .noneMatch(e -> e.schluessel().equals("LAVALINK_QUALITAET")));
        // Autoscale nur beim Controller: er ist der Verwalter. Ein Core-Knoten,
        // der selbst Maschinen anlegt, waere die Doppelzustaendigkeit, die man
        // nachts sucht.
        assertTrue(Einstellungskatalog.fuer("core").stream()
                .noneMatch(e -> e.schluessel().equals("HJ_AUTOSCALE")));

        for (String profil : Einstellungskatalog.PROFILE) {
            assertFalse(Einstellungskatalog.fuer(profil).isEmpty(),
                    "Profil " + profil + " bekaeme gar nichts");
        }
    }

    // ------------------------------------------------------------ Pruefung

    private static Eintrag e(String schluessel) {
        return Einstellungskatalog.finden(schluessel).orElseThrow();
    }

    @Test
    @DisplayName("Leer heisst Vorgabe und ist immer erlaubt")
    void leerErlaubt() {
        // Der Unterschied zwischen "nicht gesetzt" und "auf leer gesetzt":
        // eine leere Zeile in der .env ueberschreibt die Vorgabe der
        // Compose-Datei mit nichts. Deshalb wird leer nicht ausgeliefert.
        assertEquals("", Einstellungskatalog.pruefeWert(e("LAVALINK_QUALITAET"), ""));
        assertEquals("", Einstellungskatalog.pruefeWert(e("LAVALINK_QUALITAET"), "   "));
        assertEquals("", Einstellungskatalog.pruefeWert(e("LAVALINK_QUALITAET"), null));
    }

    @Test
    @DisplayName("Eine Auswahl nimmt nur, was in ihr steht")
    void auswahl() {
        assertEquals("sparsam",
                Einstellungskatalog.pruefeWert(e("LAVALINK_QUALITAET"), " sparsam "));
        assertThrows(IllegalArgumentException.class,
                () -> Einstellungskatalog.pruefeWert(e("LAVALINK_QUALITAET"), "hoechste"));
    }

    @Test
    @DisplayName("Ein Schalter kennt nur true und false")
    void schalter() {
        assertEquals("true", Einstellungskatalog.pruefeWert(e("YOUTUBE_OAUTH"), "true"));
        // "ja", "1", "on" waeren fuer Compose kein wahrer Wert - der Knoten
        // liefe dann anders als gedacht und meldete nichts.
        assertThrows(IllegalArgumentException.class,
                () -> Einstellungskatalog.pruefeWert(e("YOUTUBE_OAUTH"), "ja"));
        assertThrows(IllegalArgumentException.class,
                () -> Einstellungskatalog.pruefeWert(e("YOUTUBE_OAUTH"), "1"));
    }

    @Test
    @DisplayName("Eine Zahl ist eine Zahl")
    void zahl() {
        assertEquals("45",
                Einstellungskatalog.pruefeWert(e("HJ_LAVALINK_WATCH_SECONDS"), "45"));
        assertThrows(IllegalArgumentException.class,
                () -> Einstellungskatalog.pruefeWert(e("HJ_LAVALINK_WATCH_SECONDS"), "45s"));
        assertThrows(IllegalArgumentException.class,
                () -> Einstellungskatalog.pruefeWert(e("HJ_LAVALINK_WATCH_SECONDS"), "-5"));
    }

    @Test
    @DisplayName("Ein Zeilenumbruch zerlegt die .env")
    void keinUmbruch() {
        // Was nach dem Umbruch stuende, waere fuer den Knoten ein eigener
        // Schluessel. Damit liesse sich ueber ein harmloses Textfeld jeder
        // beliebige Wert in die .env schreiben - auch ein Token.
        assertThrows(IllegalArgumentException.class,
                () -> Einstellungskatalog.pruefeWert(e("HJ_BOT_ADMIN_IDS"),
                        "123\nHJ_BOT_TOKEN=erschlichen"));
        assertThrows(IllegalArgumentException.class,
                () -> Einstellungskatalog.pruefeWert(e("HJ_BOT_ADMIN_IDS"),
                        "123\r\nHJ_BOT_TOKEN=erschlichen"));
    }

    @Test
    @DisplayName("Jeder Eintrag hat eine Erklaerung")
    void erklaerungen() {
        // Die Seite zeigt sie unter dem Feld. Ohne sie ist ein Schluessel
        // wie LAVALINK_QUALITAET eine Vermutung.
        for (Eintrag e : Einstellungskatalog.alle()) {
            assertFalse(e.erklaerung().isBlank(),
                    e.schluessel() + " hat keine Erklaerung");
            assertTrue(e.erklaerung().length() > 20,
                    e.schluessel() + ": die Erklaerung ist zu duenn");
        }
    }

    @Test
    @DisplayName("Eine Auswahl hat auch Auswahlmoeglichkeiten")
    void auswahlGefuellt() {
        for (Eintrag e : Einstellungskatalog.alle()) {
            if (e.art() == Einstellungskatalog.Art.AUSWAHL
                    || e.art() == Einstellungskatalog.Art.SCHALTER) {
                assertFalse(e.auswahl().isEmpty(),
                        e.schluessel() + " ist eine Auswahl ohne Auswahl");
            }
            // Und die Vorgabe muss selbst zulaessig sein - sonst steht im
            // Formular ein Wert, den das Formular nicht annimmt.
            if (!e.vorgabe().isEmpty() && !e.auswahl().isEmpty()) {
                assertTrue(e.auswahl().contains(e.vorgabe()),
                        e.schluessel() + ": die Vorgabe steht nicht in der Auswahl");
            }
        }
    }
}
