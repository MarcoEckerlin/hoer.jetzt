package eckerlin.dev.security;

/**
 * Freischaltung einer kostenpflichtigen Funktion fuer genau einen Discord-Server.
 *
 * @param dailyLimit 0 bedeutet unbegrenzt. Alles darueber ist die Anzahl
 *                   Aufrufe pro Kalendertag, danach antwortet die Funktion mit
 *                   einem Hinweis statt zu arbeiten.
 */
public record GuildEntitlement(
        String guildId,
        String feature,
        String featureLabel,
        boolean enabled,
        int dailyLimit,
        int usedToday,
        String note,
        String grantedBy,
        String updatedAt
) {
}
