package jetzt.hoer.updater.modell;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Was auf einem Knoten laufen kann.
 *
 * <p>Bewusst eine Aufzaehlung und keine freie Textspalte. Ein Modul entscheidet
 * darueber, welche Geheimnisse ein Knoten bekommt - und eine Zeichenkette, die
 * sich vertippen laesst, waere an genau dieser Stelle die falsche Freiheit:
 * {@code lavalnk} als Modul liefe nicht in einen Fehler, sondern in einen
 * Knoten ohne Rechte, und der Fehler zeigte sich erst beim naechsten Update.</p>
 *
 * <p>KI-Chat steht hier <em>nicht</em>. Es ist kein eigener Knotentyp, sondern
 * fester Bestandteil von {@link #CORE} - siehe Abschnitt 13 der Spezifikation.
 * Seine Faehigkeiten haengen deshalb an CORE.</p>
 */
public enum Modul {

    CORE(Faehigkeit.CORE_UPDATE,
         Faehigkeit.CORE_CONFIG,
         Faehigkeit.CORE_SECRET,
         Faehigkeit.KI_CHAT_CONFIG,
         Faehigkeit.KI_CHAT_UPDATE),

    LAVALINK(Faehigkeit.LAVALINK_UPDATE,
             Faehigkeit.LAVALINK_CONFIG,
             Faehigkeit.LAVALINK_SECRET),

    KI_RADIO(Faehigkeit.KI_RADIO_UPDATE,
             Faehigkeit.KI_RADIO_CONFIG,
             Faehigkeit.KI_RADIO_SECRET),

    /**
     * Die Steuer-Rolle. Kein eigener Dienst, sondern der Core-Stack mit
     * gesetztem Controller-Token - siehe Kollision K2 in
     * UMBAU-KNOTENVERWALTUNG.md. Steht hier trotzdem als Modul, weil ein
     * Controller andere Geheimnisse braucht als ein reiner Core-Knoten.
     */
    CONTROLLER(Faehigkeit.CORE_UPDATE,
               Faehigkeit.CORE_CONFIG,
               Faehigkeit.CORE_SECRET,
               Faehigkeit.CONTROLLER_CONFIG,
               Faehigkeit.CONTROLLER_SECRET,
               Faehigkeit.SICHERUNG_SCHREIBEN);

    private final Set<Faehigkeit> faehigkeiten;

    Modul(Faehigkeit... faehigkeiten) {
        Set<Faehigkeit> alle = new LinkedHashSet<>(Arrays.asList(faehigkeiten));
        // Zustandsmeldungen darf jeder Knoten - unabhaengig davon, was auf ihm
        // laeuft. Ein Knoten, der sich nicht melden darf, ist ein Knoten, den
        // niemand vermisst, wenn er ausfaellt.
        alle.add(Faehigkeit.NODE_HEALTH);
        this.faehigkeiten = Set.copyOf(alle);
    }

    public Set<Faehigkeit> faehigkeiten() {
        return faehigkeiten;
    }

    /**
     * Liest ein Modul aus der Datenbank oder einer Eingabe. Grosszuegig bei
     * Schreibweise und Trennzeichen ({@code ki-radio}, {@code KI_RADIO},
     * {@code ki radio}), streng beim Ergebnis: was nicht passt, gibt leer
     * zurueck statt zu raten.
     */
    public static Optional<Modul> aus(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String norm = text.trim()
                .toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        // Der alte Name aus der Zeit vor der Umbenennung. Bestehende Knoten
        // melden ihn noch, bis sie das naechste Release ziehen.
        if (norm.equals("AI_RADIO")) {
            norm = "KI_RADIO";
        }
        for (Modul m : values()) {
            if (m.name().equals(norm)) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }

    /** Fuer die Oberflaeche: "KI-Radio" statt "KI_RADIO". */
    public String anzeige() {
        return switch (this) {
            case CORE -> "Core";
            case LAVALINK -> "Lavalink";
            case KI_RADIO -> "KI-Radio";
            case CONTROLLER -> "Controller";
        };
    }
}
