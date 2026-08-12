package eckerlin.dev.security;

/**
 * Ein Eintrag der Bot-Verwaltung.
 *
 * @param applicationOwner true, wenn der Eintrag aus dem Discord-Application-Owner
 *                         stammt. Solche Eintraege lassen sich nicht entfernen.
 */
public record BotAdmin(
        String userId,
        String displayName,
        BotAdminRole role,
        String addedBy,
        String createdAt,
        boolean applicationOwner
) {
}
