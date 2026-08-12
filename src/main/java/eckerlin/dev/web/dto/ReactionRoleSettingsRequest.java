package eckerlin.dev.web.dto;

import java.util.List;

public record ReactionRoleSettingsRequest(
        Boolean enabled,
        List<ReactionRolePanelRequest> panels
) {
}
