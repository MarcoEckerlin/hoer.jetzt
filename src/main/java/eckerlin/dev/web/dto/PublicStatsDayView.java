package eckerlin.dev.web.dto;

public record PublicStatsDayView(
        String dayLabel,
        String listenedTime,
        long uniqueListeners
) {
}
