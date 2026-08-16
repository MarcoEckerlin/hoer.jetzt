package eckerlin.dev.services;

import java.util.List;

public record MusicBrainRadioResponse(
        String summary,
        List<String> queries
) {
}
