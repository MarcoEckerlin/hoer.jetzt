package eckerlin.dev.web.dto;

import java.util.List;

public record AdminConfigurationView(
        int botId,
        String currentDeploymentKey,
        String currentDeploymentDisplayName,
        String applicationId,
        String applicationOwnerId,
        String applicationOwnerName,
        String token,
        String activity,
        String activityRotation,
        String status,
        String brandImageUrl,
        String heroImageUrl,
        boolean maintenanceEnabled,
        String maintenanceMessage,
        String legalOwnerName,
        String legalEmail,
        String legalAddress,
        String webBaseUrl,
        String noGuildInviteUrl,
        String supportUrl,
        String discordClientId,
        String discordClientSecret,
        String redirectUri,
        String llmProvider,
        String llmOllamaUrl,
        String llmOpenAiBaseUrl,
        String llmApiKey,
        String llmModel,
        List<String> llmAvailableModels,
        int llmTimeoutMs,
        double llmTemperature,
        int llmMaxTokens,
        int llmHistoryTurns,
        String llmSystemMessage,
        List<AdminDeploymentView> deployments,
        List<AdminLavalinkNodeView> lavalinkNodes
) {
}
