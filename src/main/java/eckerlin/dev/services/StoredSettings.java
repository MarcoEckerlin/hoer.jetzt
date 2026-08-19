package eckerlin.dev.services;

public record StoredSettings(
        int botId,
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
        // Bewusst am Ende: dieses Record ist positionsgebunden, und die
        // SQL-Parameter im DatabaseSettingsService sind es ebenfalls. Ein Feld
        // in der Mitte einzuschieben verschoebe stillschweigend jeden Index
        // dahinter - auffallen wuerde das erst beim Speichern.
        String supportUrl
) {
}
