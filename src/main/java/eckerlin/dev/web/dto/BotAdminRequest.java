package eckerlin.dev.web.dto;

/** Anlegen oder Hochstufen eines Bot-Admins. */
public record BotAdminRequest(
        String userId,
        String role,
        String displayName
) {
}
