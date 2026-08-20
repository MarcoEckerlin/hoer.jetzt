package jetzt.hoer.updater.dienst;

import jetzt.hoer.updater.daten.AusweisDaten;
import jetzt.hoer.updater.daten.SchluesselDaten;
import jetzt.hoer.updater.daten.VerwaltungDaten;
import jetzt.hoer.updater.modell.Faehigkeit;
import jetzt.hoer.updater.modell.Modul;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Gibt den Tresor heraus - an genau einen Knoten gerichtet.
 *
 * <h2>Was sich aendert</h2>
 *
 * Bisher lag der Tresor als Datei im Auslieferungsverzeichnis und Caddy gab
 * ihn per {@code file_server} heraus. Jeder Knoten mit dem gemeinsamen
 * Passwort bekam denselben Klartext.
 *
 * Jetzt laeuft der Abruf durch diesen Dienst: er sucht den oeffentlichen
 * Schluessel des anfragenden Knotens und verschliesst den Inhalt daran. Zwei
 * Knoten, die dasselbe Profil holen, bekommen zwei verschiedene Antworten -
 * und keiner kann die des anderen oeffnen.
 *
 * <h2>Was der Knoten bekommt, haengt an seinen Modulen</h2>
 *
 * Ein Lavalink-Knoten bekommt das Lavalink-Profil und sonst nichts. Die
 * Pruefung liegt doppelt: {@code Pfadrechte} weist den Pfad ab, und hier wird
 * die Faehigkeit noch einmal geprueft. Das ist Absicht - die erste Pruefung
 * haengt an einer Zeile im Caddyfile, und eine Zeile in einer
 * Konfigurationsdatei ist kein Ort, an dem die letzte Verteidigung stehen
 * sollte.
 */
@Service
public class Tresorausgabe {

    private static final Logger log = LoggerFactory.getLogger(Tresorausgabe.class);

    private final Path ausliefern;
    private final SchluesselDaten schluessel;
    private final AusweisDaten ausweise;
    private final VerwaltungDaten protokoll;

    public Tresorausgabe(@Value("${hj.ausliefern:/srv/ausliefern}") String ausliefern,
                         SchluesselDaten schluessel, AusweisDaten ausweise,
                         VerwaltungDaten protokoll) {
        this.ausliefern = Path.of(ausliefern);
        this.schluessel = schluessel;
        this.ausweise = ausweise;
        this.protokoll = protokoll;
    }

    /** Warum ein Abruf nicht geklappt hat - fuer eine brauchbare Meldung. */
    public enum Fehlschlag {
        UNBEKANNTES_PROFIL,
        NICHT_BERECHTIGT,
        KEIN_SCHLUESSEL,
        NICHT_BEFUELLT
    }

    public record Ergebnis(String umschlag, Fehlschlag fehlschlag) {
        public boolean gut() {
            return umschlag != null;
        }

        static Ergebnis gut(String umschlag) {
            return new Ergebnis(umschlag, null);
        }

        static Ergebnis schlecht(Fehlschlag warum) {
            return new Ergebnis(null, warum);
        }
    }

    /**
     * Holt ein Tresorprofil und richtet es an den Knoten.
     *
     * @param kennung der angemeldete Knoten
     * @param profil  {@code core}, {@code lavalink}, {@code ki-radio}, {@code controller}
     */
    public Ergebnis holen(String kennung, String profil) {
        Optional<Faehigkeit> noetig = faehigkeitFuer(profil);
        if (noetig.isEmpty()) {
            return Ergebnis.schlecht(Fehlschlag.UNBEKANNTES_PROFIL);
        }

        Set<Faehigkeit> hat = ausweise.faehigkeiten(kennung);
        if (!hat.contains(noetig.get())) {
            log.warn("Knoten {} wollte Tresor {} - hat die Faehigkeit {} nicht.",
                    kennung, profil, noetig.get());
            protokoll.merken("(Knoten)", "Tresor abgewiesen", kennung,
                    profil + " - keine Berechtigung", "");
            return Ergebnis.schlecht(Fehlschlag.NICHT_BERECHTIGT);
        }

        Optional<String> pem = schluessel.aktueller(kennung, SchluesselDaten.Zweck.TRESOR);
        if (pem.isEmpty()) {
            // Bewusst kein Rueckfall auf Klartext. Ein Knoten ohne Schluessel
            // ist ein Knoten, bei dem die Einrichtung nicht durch ist - ihm
            // die Zugangsdaten trotzdem offen zu schicken hiesse, dass dieser
            // Umbau nichts bewirkt, sobald etwas schiefgeht.
            log.warn("Knoten {} hat keinen Tresor-Schluessel hinterlegt.", kennung);
            return Ergebnis.schlecht(Fehlschlag.KEIN_SCHLUESSEL);
        }

        byte[] klartext = lesen(profil);
        if (klartext == null) {
            return Ergebnis.schlecht(Fehlschlag.NICHT_BEFUELLT);
        }

        String umschlag = Umschlag.verschliessen(klartext, pem.get());
        // Vermerkt wird, DASS ein Tresor geholt wurde, nie sein Inhalt.
        protokoll.merken("(Knoten)", "Tresor geholt", kennung, profil, "");
        return Ergebnis.gut(umschlag);
    }

    /**
     * Liest die Klartextdatei aus dem Auslieferungsverzeichnis.
     *
     * <p>Der Dateiname wird aus einer festen Liste gebildet und nie aus der
     * Anfrage zusammengesetzt. Ein {@code profil} von aussen, das direkt in
     * einen Pfad wandert, ist der klassische Weg zu {@code ../../etc/shadow} -
     * und dieser Dienst liest mit den Rechten des Containers.</p>
     */
    private byte[] lesen(String profil) {
        String datei = switch (normal(profil)) {
            case "core", "voll" -> "voll.env";
            case "lavalink" -> "lavalink.env";
            case "ki-radio" -> "ki-radio.env";
            case "controller" -> "controller.env";
            default -> null;
        };
        if (datei == null) {
            return null;
        }
        Path pfad = ausliefern.resolve("tresor").resolve(datei);
        try {
            if (!Files.isReadable(pfad)) {
                log.warn("Tresor {} ist nicht befuellt ({}).", profil, pfad);
                return null;
            }
            return Files.readAllBytes(pfad);
        } catch (java.io.IOException lesefehler) {
            log.warn("Tresor {} nicht lesbar: {}", profil, lesefehler.getMessage());
            return null;
        }
    }

    /**
     * Welche Faehigkeit ein Profil verlangt.
     *
     * <p>Dieselbe Zuordnung wie in {@link Pfadrechte}, hier aber auf den
     * Profilnamen statt auf den Pfad. Sie steht bewusst zweimal: die eine
     * entscheidet ueber den Zugang zum Pfad, die andere ueber die Ausgabe des
     * Inhalts. Faellt eine aus, greift die andere.</p>
     */
    private static Optional<Faehigkeit> faehigkeitFuer(String profil) {
        return switch (normal(profil)) {
            case "core", "voll" -> Optional.of(Faehigkeit.CORE_SECRET);
            case "lavalink" -> Optional.of(Faehigkeit.LAVALINK_SECRET);
            case "ki-radio" -> Optional.of(Faehigkeit.KI_RADIO_SECRET);
            case "controller" -> Optional.of(Faehigkeit.CONTROLLER_SECRET);
            default -> Optional.empty();
        };
    }

    private static String normal(String text) {
        if (text == null) {
            return "";
        }
        String t = text.trim().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        if (t.endsWith(".env")) {
            t = t.substring(0, t.length() - 4);
        }
        return t.equals("ai-radio") ? "ki-radio" : t;
    }

    /** Welche Profile ein Knoten mit diesen Modulen ueberhaupt bekommen kann. */
    public static Set<String> profileFuer(Set<Modul> module) {
        Set<Faehigkeit> alle = EnumSet.noneOf(Faehigkeit.class);
        module.forEach(m -> alle.addAll(m.faehigkeiten()));
        Set<String> profile = new java.util.LinkedHashSet<>();
        if (alle.contains(Faehigkeit.CORE_SECRET)) profile.add("core");
        if (alle.contains(Faehigkeit.LAVALINK_SECRET)) profile.add("lavalink");
        if (alle.contains(Faehigkeit.KI_RADIO_SECRET)) profile.add("ki-radio");
        if (alle.contains(Faehigkeit.CONTROLLER_SECRET)) profile.add("controller");
        return profile;
    }

    /** Nur fuer Proben. */
    static String alsText(byte[] daten) {
        return new String(daten, StandardCharsets.UTF_8);
    }
}
