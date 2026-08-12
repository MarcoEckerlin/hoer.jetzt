package eckerlin.dev.web.dto;

public record InviteJoinEventView(
        String memberDisplay,
        String inviteCode,
        String inviterDisplay,
        Integer uses,
        String joinedAt
) {
}
