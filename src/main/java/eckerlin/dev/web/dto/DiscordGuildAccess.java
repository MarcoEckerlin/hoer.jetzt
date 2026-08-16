package eckerlin.dev.web.dto;

import java.io.Serializable;

public record DiscordGuildAccess(
        String id,
        String name,
        String icon,
        boolean owner,
        long permissions
) implements Serializable {

    public String iconUrl() {
        if (icon == null || icon.isBlank()) {
            return null;
        }
        return "https://cdn.discordapp.com/icons/" + id + "/" + icon + ".png?size=256";
    }
}
