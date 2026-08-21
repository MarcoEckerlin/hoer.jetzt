package jetzt.hoer.updater.dienst;

import jetzt.hoer.updater.daten.VerwaltungDaten;
import jetzt.hoer.updater.daten.VoreinstellungDaten;
import jetzt.hoer.updater.dienst.Einstellungskatalog.Eintrag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Vorgaben pflegen und an die Knoten ausliefern.
 *
 * <h2>Warum es das gibt</h2>
 *
 * <p>Bis hierher wurde jeder dieser Werte beim Aufsetzen eines Knotens von
 * Hand eingetippt, und beim naechsten Knoten wieder. Wer die Puffergroesse
 * von Lavalink aendern wollte, aenderte sie auf jeder Maschine einzeln -
 * oder eben auf dreien von vieren.</p>
 *
 * <h2>Wie sie ankommen</h2>
 *
 * <p>Der Updater schreibt je Profil eine Datei ins Auslieferungsverzeichnis,
 * Caddy liefert sie aus, {@code auto-update.sh} holt sie beim naechsten Lauf
 * und schreibt sie in den erzeugten Block der {@code .env}. Derselbe Weg wie
 * beim Release-Manifest - es geht keine Verbindung von hier zu den Knoten,
 * sie fragen selbst nach.</p>
 *
 * <h2>Was nicht drinsteht</h2>
 *
 * <p>Die Datei ist unverschluesselt. Sie enthaelt deshalb nichts, was
 * geheim ist - das liegt im Tresor - und nichts, was je Knoten verschieden
 * sein muss. Welche Schluessel das sind, entscheidet
 * {@link Einstellungskatalog}, und zwar als Auswahl und nicht als
 * Vertrauenssache.</p>
 */
@Service
public class Voreinstellungen {

    private static final Logger log = LoggerFactory.getLogger(Voreinstellungen.class);

    private static final DateTimeFormatter STEMPEL =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Europe/Berlin"));

    private final Path ausliefern;
    private final VoreinstellungDaten daten;
    private final VerwaltungDaten protokoll;

    public Voreinstellungen(@Value("${hj.ausliefern:/srv/ausliefern}") String ausliefern,
                            VoreinstellungDaten daten,
                            VerwaltungDaten protokoll) {
        this.ausliefern = Path.of(ausliefern);
        this.daten = daten;
        this.protokoll = protokoll;
        Einstellungskatalog.pruefen();
    }

    private Path verzeichnis() {
        return ausliefern.resolve("voreinstellungen");
    }

    /** Was gesetzt ist. Nicht gesetzte Schluessel fehlen. */
    public Map<String, String> werte() {
        return daten.alle();
    }

    /**
     * Der Wert, der fuer einen Eintrag gilt - gesetzt oder Vorgabe.
     *
     * <p>Fuer die Anzeige. Ausgeliefert wird nur, was gesetzt ist.</p>
     */
    public String geltend(Eintrag e, Map<String, String> gesetzt) {
        String w = gesetzt.get(e.schluessel());
        return w == null || w.isBlank() ? e.vorgabe() : w;
    }

    /**
     * Ein Formular uebernehmen.
     *
     * <p>Alles oder nichts: erst wird jeder Wert geprueft, dann wird
     * geschrieben. Sonst stuende nach einem Tippfehler im letzten Feld die
     * Haelfte der Aenderungen in der Datenbank und die andere nicht - und
     * ausgeliefert waere ein Zwischenstand, den so niemand wollte.</p>
     *
     * @return wie viele Werte sich tatsaechlich geaendert haben
     */
    public int uebernehmen(Map<String, String> formular, String wer) {
        Map<String, String> geprueft = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : formular.entrySet()) {
            Eintrag eintrag = Einstellungskatalog.finden(e.getKey()).orElse(null);
            if (eintrag == null) {
                // Ein Schluessel, den der Katalog nicht kennt. Kommt aus einem
                // veralteten Formular oder von Hand - beides kein Grund, ihn
                // zu uebernehmen.
                log.warn("Unbekannter Schluessel im Formular, uebergangen: {}", e.getKey());
                continue;
            }
            geprueft.put(e.getKey(), Einstellungskatalog.pruefeWert(eintrag, e.getValue()));
        }

        Map<String, String> vorher = daten.alle();
        int geaendert = 0;
        for (Map.Entry<String, String> e : geprueft.entrySet()) {
            String alt = vorher.getOrDefault(e.getKey(), "");
            if (!alt.equals(e.getValue())) {
                daten.setzen(e.getKey(), e.getValue(), wer);
                geaendert++;
            }
        }

        if (geaendert > 0) {
            ausliefernSchreiben();
            protokoll.merken(wer, "Voreinstellungen geaendert", "",
                    geaendert + " Werte", "");
        }
        return geaendert;
    }

    /**
     * Die Dateien neu schreiben.
     *
     * <p>Oeffentlich, weil der Start sie ebenfalls aufruft: nach einem
     * Neuaufsetzen des Auslieferungsvolumens waeren sie sonst weg, waehrend
     * die Werte in der Datenbank noch stehen - die Knoten faenden dann
     * nichts vor und niemand wuesste warum.</p>
     */
    public void ausliefernSchreiben() {
        Map<String, String> gesetzt = daten.alle();
        for (String profil : Einstellungskatalog.PROFILE) {
            schreiben(profil, gesetzt);
        }
    }

    private void schreiben(String profil, Map<String, String> gesetzt) {
        StringBuilder inhalt = new StringBuilder();
        inhalt.append("# hoer.jetzt - zentrale Vorgaben fuer Profil '").append(profil)
                .append("'.\n")
                .append("# Geschrieben vom Update-Server am ").append(STEMPEL.format(Instant.now()))
                .append(".\n")
                .append("#\n")
                .append("# Gelesen von auto-update.sh. Was hier steht, gewinnt gegen die\n")
                .append("# Vorgaben der Compose-Datei. Geheimnisse stehen im Tresor,\n")
                .append("# Knotenspezifisches in der .env des Knotens - hier nichts davon.\n");

        int wieViele = 0;
        for (Eintrag e : Einstellungskatalog.fuer(profil)) {
            String wert = gesetzt.get(e.schluessel());
            if (wert == null || wert.isBlank()) {
                // Nicht gesetzt heisst nicht ausgeliefert. Eine leere Zeile
                // wuerde die Vorgabe der Compose-Datei mit nichts ueberschreiben.
                continue;
            }
            inhalt.append(e.schluessel()).append('=').append(wert).append('\n');
            wieViele++;
        }
        if (wieViele == 0) {
            inhalt.append("# (nichts gesetzt - es gilt ueberall die Vorgabe)\n");
        }

        Path ziel = verzeichnis().resolve(profil + ".env");
        try {
            Files.createDirectories(verzeichnis());
            // Erst daneben, dann umbenennen - dieselbe Ueberlegung wie beim
            // Manifest: ein Knoten, der genau jetzt fragt, bekommt entweder
            // den alten Stand oder den neuen, nie eine halbe Datei.
            Path neben = ziel.resolveSibling(profil + ".env.neu");
            Files.writeString(neben, inhalt.toString(), StandardCharsets.UTF_8);
            Files.move(neben, ziel, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException fehler) {
            throw new IllegalStateException(
                    "Vorgaben fuer " + profil + " liessen sich nicht ablegen: "
                    + fehler.getMessage(), fehler);
        }
    }

    /** Was ein Knoten mit diesem Profil bekaeme - fuer die Einsicht. */
    public String vorschau(String profil) {
        Path p = verzeichnis().resolve(profil + ".env");
        try {
            return Files.isRegularFile(p) ? Files.readString(p, StandardCharsets.UTF_8) : "";
        } catch (IOException nichtLesbar) {
            return "";
        }
    }
}
