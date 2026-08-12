package eckerlin.dev.web.dto;

public record PublicStatsChartPointView(
        String label,
        long listenedSeconds,
        long uniqueListeners
) {
}
