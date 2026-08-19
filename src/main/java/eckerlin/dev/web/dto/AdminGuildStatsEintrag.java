package eckerlin.dev.web.dto;

/** Eine Zeile der Bestenliste eines Servers. */
public record AdminGuildStatsEintrag(
        String titel,
        String interpret,
        long hoerzeitSekunden,
        String hoerzeit,
        long hoerer
) {
}
