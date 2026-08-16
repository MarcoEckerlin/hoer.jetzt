package eckerlin.dev.services;

public record DiscordApplicationOwner(
        String applicationId,
        String ownerId,
        String ownerName
) {
}
