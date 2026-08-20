package jetzt.hoer.updater.modell;

import java.util.Locale;
import java.util.Optional;

/**
 * Was ein Knoten vom Update-Server holen darf.
 *
 * <p>Die Faehigkeiten ergeben sich aus den Modulen, die auf dem Knoten laufen
 * ({@link Modul#faehigkeiten()}). Getrennt gefuehrt werden sie trotzdem: beim
 * Entzug eines Moduls soll nachvollziehbar bleiben, was der Knoten vorher
 * durfte, und einzelne Faehigkeiten lassen sich so vorruebergehend sperren,
 * ohne das Modul zu entfernen.</p>
 *
 * <p>Der Zweck ist eine einzige Frage: <em>Warum sollte ein Audio-Knoten das
 * Bot-Token kennen?</em> Heute kennt er es, weil alle Knoten dasselbe Passwort
 * teilen und der Tresor nur zwei Profile hat. Wer eine dieser Maschinen
 * aufmacht, hat Bot-Token, Datenbank-Passwort und Client-Secret. Genau das
 * schneidet diese Aufzaehlung ab.</p>
 *
 * <p>Welcher URL-Pfad welche Faehigkeit verlangt, steht bewusst nicht hier,
 * sondern in {@code Pfadrechte}. Die Zuordnung haengt an der Bauform der
 * Registry-Adressen und gehoert damit zur Zugangspruefung, nicht zum
 * Begriff.</p>
 */
public enum Faehigkeit {

    CORE_UPDATE,
    CORE_CONFIG,
    CORE_SECRET,

    KI_CHAT_CONFIG,
    KI_CHAT_UPDATE,

    LAVALINK_UPDATE,
    LAVALINK_CONFIG,
    LAVALINK_SECRET,

    KI_RADIO_UPDATE,
    KI_RADIO_CONFIG,
    KI_RADIO_SECRET,

    CONTROLLER_CONFIG,
    CONTROLLER_SECRET,

    /**
     * Sicherungen ablegen. Nur der Controller - ein Audio-Knoten hat keine
     * Datenbank, und wer hier schreiben darf, kann den Speicherplatz des
     * Update-Servers fuellen.
     */
    SICHERUNG_SCHREIBEN,

    /** Herzschlag und Wartungsmeldung. Hat jeder Knoten, siehe {@link Modul}. */
    NODE_HEALTH;

    /**
     * Liest eine Faehigkeit aus der Datenbank oder einer Eingabe.
     *
     * <p>{@code AI_RADIO_*} wird auf {@code KI_RADIO_*} gezogen: der alte Name
     * steht noch in bestehenden Eintraegen, und ein Knoten soll bei der
     * Umbenennung nicht stillschweigend seine Rechte verlieren.</p>
     */
    public static Optional<Faehigkeit> aus(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String norm = text.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (norm.startsWith("AI_RADIO")) {
            norm = "KI_RADIO" + norm.substring("AI_RADIO".length());
        }
        for (Faehigkeit f : values()) {
            if (f.name().equals(norm)) {
                return Optional.of(f);
            }
        }
        return Optional.empty();
    }
}
