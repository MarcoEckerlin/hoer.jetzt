package eckerlin.dev.web.dto;

import java.util.List;

public record PublicStatsView(
        PublicStatsSummaryView summary,
        List<PublicStatsLiveItemView> liveItems,
        List<PublicStatsRankedItemView> topTracks,
        List<PublicStatsRankedItemView> topArtists,
        List<PublicStatsRankedItemView> topSources
) {
}
