package eckerlin.dev.web.dto;

public record PublicStatsLiveItemView(
        String modeLabel,
        String title,
        String subtitle,
        long listenerCount
) {
}
