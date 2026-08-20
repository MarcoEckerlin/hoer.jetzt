package jetzt.hoer.updater.daten;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Zeitstempel als Text - in fester Breite.
 *
 * <h2>Warum auf Sekunden gekuerzt wird</h2>
 *
 * Die Datenbank vergleicht diese Werte als <em>Text</em>
 * ({@code laeuft_ab > ?}). Bei ISO-8601 stimmt die Textreihenfolge mit der
 * Zeitreihenfolge ueberein - aber nur, solange alle Werte gleich lang sind.
 *
 * <p>{@link Instant#toString()} laesst Nachkommastellen weg, wenn sie null
 * sind. Damit entstehen zwei Formate nebeneinander:</p>
 *
 * <pre>
 *   2026-08-20T21:19:40Z        20 Zeichen
 *   2026-08-20T21:19:40.123Z    24 Zeichen
 * </pre>
 *
 * <p>Verglichen wird zeichenweise. An Stelle 20 steht einmal {@code Z} und
 * einmal ein Punkt - und {@code '.'} liegt im Zeichensatz vor {@code 'Z'}.
 * Also gilt als Text {@code ...40Z > ...40.123Z}, obwohl der zweite Wert
 * spaeter liegt. Gemessen und bestaetigt: Textvergleich sagt "spaeter",
 * Zeitvergleich sagt "frueher".</p>
 *
 * <p>Der Fehler betraegt weniger als eine Sekunde und ist bei einem Token,
 * der zwei Stunden gilt, folgenlos. Er steckt aber in <em>jedem</em>
 * Zeitvergleich dieser Anwendung - und bei einer Freigabe, die auf die
 * Sekunde ablaeuft, waere er es nicht mehr.</p>
 *
 * <p>Deshalb: auf Sekunden kuerzen. Danach sind alle Werte 20 Zeichen lang,
 * und die Textreihenfolge ist die Zeitreihenfolge. Bestehende Werte ohne
 * Nachkommastellen haben dieses Format bereits.</p>
 *
 * <h2>Und die Zeitzone?</h2>
 *
 * Es gibt keine. {@link Instant} ist ein Zeitpunkt, kein Datum mit Uhrzeit -
 * {@code toString()} liefert immer UTC mit {@code Z} am Ende. Zwei Stunden
 * Gueltigkeit sind zwei Stunden, ob in Berlin, im Sommer oder im Winter.
 *
 * <p>Zeitzonen kommen erst dort ins Spiel, wo ein Mensch ein <em>Datum</em>
 * eintippt - siehe die Freigaben im PultController.</p>
 */
final class Zeiten {

    private Zeiten() {
    }

    static String text(Instant zeit) {
        return zeit == null ? null : zeit.truncatedTo(ChronoUnit.SECONDS).toString();
    }

    static Instant zeit(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (Exception unlesbar) {
            return null;
        }
    }
}
