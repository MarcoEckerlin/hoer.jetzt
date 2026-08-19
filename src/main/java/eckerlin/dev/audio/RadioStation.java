package eckerlin.dev.audio;

/**
 * Ein Webradio-Sender.
 *
 * @param guildId Server, dem dieser Sender gehoert - {@code null} bei einem
 *                globalen Sender, den der Betreiber pflegt. Die Oberflaeche
 *                braucht die Unterscheidung, weil ein Serveradmin nur seine
 *                eigenen Sender aendern darf.
 * @param logoUrl Bild fuer die Senderkachel. Darf leer sein; dann zeigt die
 *                Oberflaeche die Anfangsbuchstaben.
 */
public record RadioStation(
        int id,
        String name,
        String url,
        String logoUrl,
        String guildId
) {
    /** Kurzform fuer Aufrufer, die weder Bild noch Serverzuordnung kennen. */
    public RadioStation(int id, String name, String url) {
        this(id, name, url, "", null);
    }

    public boolean global() {
        return guildId == null || guildId.isBlank();
    }
}
