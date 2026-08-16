package eckerlin.dev.web.dto;

import eckerlin.dev.security.GuildEntitlement;

import java.util.List;

/** Ein Discord-Server aus Sicht der Bot-Verwaltung. */
public record AdminGuildView(
        String id,
        String name,
        String iconUrl,
        int memberCount,
        String ownerId,
        String ownerName,
        String joinedAt,
        boolean permissionsConfigured,
        List<GuildEntitlement> entitlements
) {
}
