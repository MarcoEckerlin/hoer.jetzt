package eckerlin.dev.web.dto;

import java.util.List;
import java.util.Map;

/** Speichert die komplette Rechtematrix eines Servers; ersetzt den alten Stand. */
public record GuildPermissionMatrixRequest(
        Map<String, List<String>> matrix
) {
}
