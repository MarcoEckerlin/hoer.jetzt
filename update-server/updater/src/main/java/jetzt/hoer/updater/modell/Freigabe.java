package jetzt.hoer.updater.modell;

import java.time.Instant;

/**
 * Eine freigeschaltete Adresse oder ein Adressbereich.
 *
 * @param bereich  CIDR-Schreibweise. Eine einzelne Adresse wird beim Anlegen
 *                 zu /32 bzw. /128 ergaenzt, damit es nur eine Form gibt, die
 *                 geprueft werden muss.
 * @param laeuftAb Optionales Ablaufdatum. Gedacht fuer den haeufigen Fall:
 *                 eine Maschine wird aufgesetzt und die Freischaltung soll
 *                 nicht ueber Jahre stehen bleiben, weil sie niemand
 *                 zurueckgenommen hat.
 */
public record Freigabe(
        long id,
        String bereich,
        String name,
        String notiz,
        Instant angelegt,
        Instant laeuftAb,
        boolean aktiv) {

    public boolean gueltig(Instant jetzt) {
        return aktiv && (laeuftAb == null || laeuftAb.isAfter(jetzt));
    }
}
