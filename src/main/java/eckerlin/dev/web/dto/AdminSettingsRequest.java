package eckerlin.dev.web.dto;

import java.util.List;

public record AdminSettingsRequest(
        String token,
        String activity,
        String activityRotation,
        String status,
        String brandImageUrl,
        String heroImageUrl,
        Boolean maintenanceEnabled,
        String maintenanceMessage,
        String legalOwnerName,
        String legalEmail,
        String legalAddress,
        String webBaseUrl,
        String noGuildInviteUrl,
        String discordClientId,
        String discordClientSecret,
        String redirectUri,
        String adminUserIds,
        String llmProvider,
        String llmOllamaUrl,
        String llmOpenAiBaseUrl,
        String llmApiKey,
        String llmModel,
        String llmAvailableModels,
        Integer llmTimeoutMs,
        Double llmTemperature,
        Integer llmMaxTokens,
        Integer llmHistoryTurns,
        String llmSystemMessage,
        List<AdminDeploymentRequest> deployments,
        List<AdminLavalinkNodeRequest> lavalinkNodes
) {
}
