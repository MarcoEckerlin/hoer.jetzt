package jetzt.hoer.updater.modell;

import java.time.Instant;

/** Ein Eintrag im Zugriffsprotokoll - abgelehnte wie erlaubte. */
public record Zugriff(
        long id,
        Instant zeit,
        String ip,
        String pfad,
        boolean erlaubt,
        String grund) {
}
