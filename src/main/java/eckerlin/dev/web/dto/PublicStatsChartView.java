package eckerlin.dev.web.dto;

import java.util.List;

public record PublicStatsChartView(
        String rangeKey,
        String rangeLabel,
        String listenedTimeLabel,
        long totalUniqueListeners,
        long peakListeners,
        List<PublicStatsChartPointView> points
) {
}
