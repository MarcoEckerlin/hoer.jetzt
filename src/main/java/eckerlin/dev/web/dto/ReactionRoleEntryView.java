package eckerlin.dev.web.dto;

import java.util.List;

public record ReactionRoleEntryView(
        String id,
        String emoji,
        List<String> roleIds,
        String label,
        String description
) {
}
