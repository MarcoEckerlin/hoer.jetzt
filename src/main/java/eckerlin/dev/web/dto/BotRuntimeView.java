package eckerlin.dev.web.dto;

public record BotRuntimeView(
        String displayName,
        String avatarUrl,
        String brandImageUrl,
        String heroImageUrl,
        boolean online,
        String status,
        String activity
) {
}
