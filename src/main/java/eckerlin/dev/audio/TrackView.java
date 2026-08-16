package eckerlin.dev.audio;

import dev.arbjerg.lavalink.client.player.Track;
import dev.arbjerg.lavalink.protocol.v4.TrackInfo;

public record TrackView(
        String title,
        String author,
        String uri,
        long durationMs,
        boolean stream,
        String identifier,
        String sourceName,
        String artworkUrl
) {

    public static TrackView from(Track track) {
        TrackInfo info = track.getInfo();
        return new TrackView(
                info.getTitle(),
                info.getAuthor(),
                info.getUri(),
                info.getLength(),
                info.isStream(),
                info.getIdentifier(),
                info.getSourceName(),
                resolveArtworkUrl(info)
        );
    }

    private static String resolveArtworkUrl(TrackInfo info) {
        if (info.getArtworkUrl() != null && !info.getArtworkUrl().isBlank()) {
            return info.getArtworkUrl();
        }

        if (looksLikeYouTube(info)) {
            String identifier = info.getIdentifier() == null ? "" : info.getIdentifier().trim();
            if (!identifier.isBlank()) {
                return "https://i.ytimg.com/vi/" + identifier + "/hqdefault.jpg";
            }
        }

        return "";
    }

    private static boolean looksLikeYouTube(TrackInfo info) {
        String source = info.getSourceName() == null ? "" : info.getSourceName().toLowerCase();
        String uri = info.getUri() == null ? "" : info.getUri().toLowerCase();
        return source.contains("youtube") || uri.contains("youtube.com") || uri.contains("youtu.be");
    }
}
