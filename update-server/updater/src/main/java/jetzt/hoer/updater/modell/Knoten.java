package jetzt.hoer.updater.modell;

import java.time.Instant;

/**
 * Was wir ueber einen Knoten wissen - und das ist ausschliesslich, was er
 * selbst erzaehlt oder was beim Zugriff anfaellt. Es geht keine Verbindung in
 * seine Richtung: die Knoten stehen bei Hetzner und zuhause hinter fremdem
 * NAT, eine Abfrage von hier aus wuerde nur manchmal ankommen.
 *
 * @param kennung  Beim Aufsetzen erzeugt, liegt in der .env des Knotens. Nicht
 *                 die IP - die wechselt, wenn eine Maschine neu aufgesetzt
 *                 wird, und dann waere es in der Uebersicht ein neuer Knoten.
 * @param zustand  Was der Knoten zuletzt ueber seine Container gemeldet hat.
 * @param vorher   Das Release, auf das --zurueck fuehren wuerde.
 */
public record Knoten(
        String kennung,
        String name,
        String profil,
        String version,
        String vorher,
        String zustand,
        String ergebnis,
        String letzteIp,
        Instant zuletztGemeldet,
        Instant zuletztGesehen,
        boolean updateAngefordert) {

    /**
     * Ein Knoten gilt als stumm, wenn er sich seit ueber einem Tag nicht
     * gemeldet hat. Der Zeitgeber laeuft naechtlich, ein einzelner
     * ausgefallener Lauf ist also normal und soll nicht sofort rot leuchten.
     */
    public boolean stumm(Instant jetzt) {
        return zuletztGesehen == null
            || zuletztGesehen.isBefore(jetzt.minusSeconds(26 * 3600));
    }
}
