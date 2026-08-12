package eckerlin.dev.web.dto;

public record TrackedInviteView(String code, Integer uses, String inviter, boolean temporary) {
}
