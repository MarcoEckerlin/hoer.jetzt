package jetzt.hoer.updater.modell;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Die ueblichen Modulkombinationen, unter einem Namen.
 *
 * <h2>Warum eine Auswahl statt einzelner Kaestchen</h2>
 *
 * Weil die Kaestchen die falsche Frage stellen. Sie lauten "welche Module",
 * gemeint ist aber "was fuer ein Knoten soll das werden" - und darauf gibt es
 * eine Handvoll sinnvoller Antworten, nicht acht frei kombinierbare.
 *
 * <p>Ein praktischer Grund kommt dazu: aus der Kombination ergibt sich der
 * Namensvorschlag. Wer "Lavalink" waehlt, bekommt {@code lavalink-10}
 * vorgeschlagen, wenn {@code lavalink-9} der hoechste ist. Bei frei
 * angekreuzten Modulen liesse sich nicht sagen, wie so ein Knoten heissen
 * sollte.</p>
 *
 * <p>Die einzelnen Module bleiben trotzdem erreichbar: in der Knotenliste
 * lassen sie sich je Knoten einzeln zu- und abschalten. Die Auswahl hier
 * legt nur den Anfang fest.</p>
 */
public enum Knotenvorlage {

    LAVALINK("Lavalink", "lavalink",
            "Reiner Audio-Knoten. Bekommt nur das Lavalink-Passwort.",
            List.of(Modul.LAVALINK)),

    CORE("Core", "core",
            "Bot und Weboberflaeche, KI-Chat inbegriffen.",
            List.of(Modul.CORE)),

    CORE_LAVALINK("Core + Lavalink", "knoten",
            "Beides auf einer Maschine - der uebliche Zuschnitt.",
            List.of(Modul.CORE, Modul.LAVALINK)),

    CORE_LAVALINK_RADIO("Core + Lavalink + KI-Radio", "knoten",
            "Alles auf einer Maschine.",
            List.of(Modul.CORE, Modul.LAVALINK, Modul.KI_RADIO)),

    KI_RADIO("KI-Radio", "ki-radio",
            "Nur das KI-Radio.",
            List.of(Modul.KI_RADIO)),

    CONTROLLER("Controller", "controller",
            "Steuer-Node: Core-Stapel mit Datenbank und Sicherungen.",
            List.of(Modul.CONTROLLER, Modul.LAVALINK));

    private final String anzeige;
    private final String praefix;
    private final String erklaerung;
    private final List<Modul> module;

    Knotenvorlage(String anzeige, String praefix, String erklaerung, List<Modul> module) {
        this.anzeige = anzeige;
        this.praefix = praefix;
        this.erklaerung = erklaerung;
        this.module = List.copyOf(module);
    }

    public String anzeige() {
        return anzeige;
    }

    /** Woraus der Namensvorschlag gebildet wird - {@code lavalink} wird zu {@code lavalink-10}. */
    public String praefix() {
        return praefix;
    }

    public String erklaerung() {
        return erklaerung;
    }

    public List<Modul> module() {
        return module;
    }

    /** Die Modulnamen, wie das Formular sie schickt. */
    public List<String> modulnamen() {
        return module.stream().map(Enum::name).toList();
    }

    public static Optional<Knotenvorlage> aus(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String norm = text.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (Knotenvorlage v : values()) {
            if (v.name().equals(norm)) {
                return Optional.of(v);
            }
        }
        return Optional.empty();
    }
}
