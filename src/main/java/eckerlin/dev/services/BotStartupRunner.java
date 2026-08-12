package eckerlin.dev.services;

import eckerlin.dev.utils.Alert;
import eckerlin.dev.utils.DB;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class BotStartupRunner implements ApplicationRunner {

    private final DiscordBotService discordBotService;
    private final GuildModuleSettingsService guildModuleSettingsService;

    public BotStartupRunner(DiscordBotService discordBotService, GuildModuleSettingsService guildModuleSettingsService) {
        this.discordBotService = discordBotService;
        this.guildModuleSettingsService = guildModuleSettingsService;
    }

    @Override
    public void run(ApplicationArguments args) {
        Alert.send("INFO", "BOOT", "Systemstart");

        boolean dbReady = DB.init();
        if (dbReady) {
            guildModuleSettingsService.initializeStorage();
            Alert.send("SUCCESS", "BOOT", "Datenbankverbindung steht.");
        } else {
            Alert.send("WARN", "BOOT", "Datenbank nicht erreichbar. Konfig-/In-Memory-Fallback wird verwendet.");
        }

        try {
            discordBotService.start();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            Alert.send("ERROR", "DISCORD", "Bot-Start wurde unterbrochen. Webinterface bleibt online.");
        } catch (Exception exception) {
            String message = exception.getMessage();
            if (message == null || message.isBlank()) {
                message = exception.getClass().getSimpleName();
            }

            Alert.send("ERROR", "DISCORD", "Bot-Start fehlgeschlagen: " + message + ". Webinterface bleibt online.");
        }
    }
}
