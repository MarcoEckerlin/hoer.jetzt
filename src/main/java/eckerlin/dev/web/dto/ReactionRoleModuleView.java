package eckerlin.dev.web.dto;

import java.util.List;

public record ReactionRoleModuleView(
        boolean enabled,
        String notice,
        List<ReactionRolePanelView> panels
) {
}
