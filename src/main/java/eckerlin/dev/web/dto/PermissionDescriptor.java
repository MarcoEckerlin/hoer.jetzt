package eckerlin.dev.web.dto;

/** Ein Recht so, wie die Oberflaeche es anzeigt. */
public record PermissionDescriptor(
        String key,
        String label,
        String description
) {
}
