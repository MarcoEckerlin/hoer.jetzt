package eckerlin.dev.web.dto;

import java.util.List;

public record LlmModuleView(
        boolean enabled,
        String textChannelId,
        boolean configured,
        String provider,
        String model,
        List<String> availableModels,
        String notice
) {
}
