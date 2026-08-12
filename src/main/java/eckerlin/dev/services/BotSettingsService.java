package eckerlin.dev.services;

import org.springframework.stereotype.Service;

@Service
public class BotSettingsService {

    private final AppConfigService configService;

    public BotSettingsService(AppConfigService configService) {
        this.configService = configService;
    }

    public BotSettings load() {
        String configuredToken = configService.getConfiguredBotToken();
        if (!configuredToken.isBlank()) {
            return new BotSettings(
                    configuredToken,
                    configService.getBotActivity(),
                    configService.getBotStatus()
            );
        }

        throw new IllegalStateException("Kein Discord-Bot-Token gefunden. Bitte config/config.json oder die Datenbank-Einstellung befuellen.");
    }
}
