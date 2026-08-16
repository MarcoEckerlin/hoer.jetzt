package eckerlin.dev.web.dto;

import java.io.Serializable;
import java.util.List;

public record DashboardSession(
        String userId,
        String username,
        String avatarUrl,
        String accessToken,
        List<DiscordGuildAccess> guilds
) implements Serializable {
}
