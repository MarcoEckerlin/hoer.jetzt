package eckerlin.dev.web.dto;

/**
 * Freischaltung einer kostenpflichtigen Funktion fuer einen Server.
 *
 * @param dailyLimit 0 = unbegrenzt
 */
public record EntitlementRequest(
        String feature,
        boolean enabled,
        int dailyLimit,
        String note
) {
}
