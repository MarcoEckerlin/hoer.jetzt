package eckerlin.dev.web.dto;

import java.util.List;

/**
 * Zahlen zu <em>einem</em> Discord-Server, fuer die Bot-Verwaltung.
 *
 * <p>Alles bezieht sich auf die letzten 30 Tage - ausser {@code eigeneSender}
 * und {@code zuletztAktiv}, die einen Zustand beschreiben und keinen Zeitraum.
 * Der Zeitraum steht bewusst im Feldnamen: eine Zahl ohne Zeitraum laedt zum
 * Fehlschluss ein, und dieselbe Uebersicht zeigt daneben Werte, die seit dem
 * Serverbeitritt zaehlen.</p>
 *
 * <p>Es wird nichts geschaetzt und nichts hochgerechnet. Gibt die Datenbank
 * fuer einen Server nichts her - weil dort noch nie Musik lief -, stehen hier
 * Nullen, und die Oberflaeche sagt das auch so. Erfundene Zahlen in einer
 * Verwaltungsansicht sind schlimmer als gar keine: nach ihnen wird
 * entschieden.</p>
 */
public record AdminGuildStats(
        String guildId,
        long hoerzeitSekunden30d,
        String hoerzeit30d,
        long hoerer30d,
        long sitzungen30d,
        long titel30d,
        long radioSekunden30d,
        long musikSekunden30d,
        long aiRadioSekunden30d,
        List<AdminGuildStatsEintrag> meistgehoert,
        String zuletztAktiv,
        long eigeneSender
) {
}
