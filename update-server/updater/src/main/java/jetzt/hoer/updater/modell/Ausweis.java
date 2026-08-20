package jetzt.hoer.updater.modell;

import java.util.Set;

/**
 * Wer da gerade anklopft - und was er darf.
 *
 * <p>Das Ergebnis der Anmeldung, bevor die Adresse geprueft wird. Bewusst ein
 * eigener Typ statt eines {@code boolean}: mit einem Wahrheitswert liesse sich
 * die Frage "darf dieser Knoten <em>diesen</em> Pfad" gar nicht stellen, und
 * genau das ist der Punkt des Umbaus.</p>
 *
 * @param kennung    der Knoten, oder {@code null} beim gemeinsamen Passwort
 * @param gemeinsam  true, solange der Knoten noch das alte Passwort benutzt
 * @param faehigkeiten was er holen darf; beim gemeinsamen Passwort alles
 */
public record Ausweis(String kennung, boolean gemeinsam, Set<Faehigkeit> faehigkeiten) {

    /**
     * Der Uebergangsfall: ein Knoten, der noch nicht umgestellt ist, meldet
     * sich mit dem gemeinsamen Passwort.
     *
     * <p>Er bekommt alles - wie bisher. Das ist keine Nachlaessigkeit, sondern
     * die Bedingung dafuer, dass die Umstellung ohne Ausfall laufen kann: die
     * bestehenden Knoten duerfen nicht in dem Moment stehenbleiben, in dem
     * dieser Server neu startet. Wann das gemeinsame Passwort abgeschaltet
     * wird, entscheidet {@code hj.token.gemeinsam-erlauben}.</p>
     */
    public static Ausweis mitGemeinsamemPasswort() {
        return new Ausweis(null, true, Set.of(Faehigkeit.values()));
    }

    public static Ausweis fuer(String kennung, Set<Faehigkeit> faehigkeiten) {
        return new Ausweis(kennung, false, Set.copyOf(faehigkeiten));
    }

    public boolean darf(Faehigkeit faehigkeit) {
        return faehigkeiten.contains(faehigkeit);
    }

    /**
     * Die Kennung, sofern bekannt.
     *
     * <p>Beim gemeinsamen Passwort ist sie es nicht - und das ist genau der
     * Grund fuer den Umbau: bis hierher liess sich nicht sagen, welche
     * Maschine gerade zieht. Alles, was einen Knoten benennen will
     * (Herzschlag, Protokoll, Sperre), muss diesen Fall aushalten.</p>
     */
    public java.util.Optional<String> kennungOptional() {
        return kennung == null || kennung.isBlank()
                ? java.util.Optional.empty()
                : java.util.Optional.of(kennung);
    }

    // Ob ein bestimmter Pfad offensteht, beantwortet Pfadrechte - dort und
    // nur dort. Die Zuordnung ist unangenehmer als sie aussieht (die Registry
    // schiebt den Besitzernamen zwischen Praefix und Modul), und zwei Stellen,
    // die dieselbe Frage verschieden beantworten, waeren genau die Art Fehler,
    // die man erst bemerkt, wenn ein Knoten zu viel darf.
}
