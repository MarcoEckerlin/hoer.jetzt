package eckerlin.dev.web.dto;

public record PublicStatsSummaryView(
        long liveListeners,
        long liveStreams,
        long uniqueListeners30d,
        long trackedSessions30d,
        long trackedGuilds30d,
        String listenedTime30d,
        String generatedAtLabel
) {
}
