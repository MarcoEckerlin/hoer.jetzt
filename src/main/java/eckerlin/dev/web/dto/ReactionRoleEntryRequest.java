package eckerlin.dev.web.dto;

import java.util.List;

public record ReactionRoleEntryRequest(
        String id,
        String emoji,
        List<String> roleIds,
        List<String> removedRoleIds,
        String label,
        String description
) {
}
