package eckerlin.dev.web.dto;

import java.util.List;
import java.util.Map;

/**
 * Alles, was die Seite „Rollenrechte" braucht.
 *
 * @param configured false, solange fuer den Server noch nichts gepflegt ist. In
 *                   dem Fall gilt weiterhin die alte Regel (Discord-Administrator
 *                   darf alles) und die Oberflaeche weist darauf hin.
 * @param matrix     Rollen-ID -&gt; Liste von Rechte-Schluesseln
 */
public record GuildPermissionMatrixView(
        boolean configured,
        List<PermissionDescriptor> permissions,
        List<GuildRoleView> roles,
        Map<String, List<String>> matrix,
        List<String> ownPermissions,
        boolean botAdmin
) {
}
