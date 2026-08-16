package eckerlin.dev.security;

/** Eine Zeile im Audit-Log der Bot-Verwaltung. */
public record AuditEntry(
        long id,
        String actorUserId,
        String actorName,
        String action,
        String targetType,
        String targetId,
        String details,
        String createdAt
) {
}
