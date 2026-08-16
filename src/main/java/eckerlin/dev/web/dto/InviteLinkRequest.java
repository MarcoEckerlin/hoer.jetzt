package eckerlin.dev.web.dto;

public record InviteLinkRequest(
        Boolean enabled,
        String slug,
        String targetUrl
) {
}
