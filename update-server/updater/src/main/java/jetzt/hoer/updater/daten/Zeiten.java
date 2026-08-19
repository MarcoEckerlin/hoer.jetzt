package jetzt.hoer.updater.daten;

import java.time.Instant;

/**
 * SQLite kennt keinen Zeitstempeltyp. Zeiten stehen als ISO-8601-Text in UTC.
 * Diese beiden Methoden sind die einzige Stelle, an der umgerechnet wird -
 * damit nicht an fuenf Orten je eine eigene Schreibweise entsteht.
 */
final class Zeiten {

    private Zeiten() {
    }

    static String text(Instant zeit) {
        return zeit == null ? null : zeit.toString();
    }

    static Instant zeit(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return Instant.parse(text);
        } catch (Exception e) {
            return null;
        }
    }
}
