package eckerlin.dev.web.dto;

import eckerlin.dev.security.BotAdmin;

import java.util.List;

/**
 * Uebersicht fuer die Seite „Admins".
 *
 * @param assignableRoles Stufen, die vergeben werden duerfen — Owner ist nicht
 *                        dabei, der kommt aus der Discord-Anwendung.
 */
public record BotAdminOverview(
        String currentUserId,
        String currentRole,
        boolean canManageAdmins,
        List<BotAdmin> admins,
        List<PermissionDescriptor> assignableRoles
) {
}
