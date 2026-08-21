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
            // Auf einem Controller laeuft kein Bot.
            //
            // Ein Controller ist Webseite, Datenbank und Steuerung der
            // uebrigen Knoten - er verwaltet Core- und Lavalink-Server,
            // spielt aber selbst keine Musik und bedient keinen Discord-Server.
            //
            // Das Programm ist dasselbe: die Steuerlogik (Paket "verbund")
            // liegt darin und braucht JDA nicht. Nur der Verbindungsaufbau
            // zu Discord entfaellt - und der ist genau eine Zeile weiter
            // unten. Zwei getrennte Programme zu bauen waere der Fehler, vor
            // dem Abschnitt 71 der Spezifikation warnt.
            //
            // Praktische Folge, wenn man es NICHT abschaltet: der Controller
            // meldet sich mit demselben Bot-Token bei Discord an wie die
            // Core-Knoten. Discord laesst das zu, verteilt die Ereignisse
            // dann aber auf beide - Befehle landen mal hier, mal dort, und
            // der Fehler sieht nach einem sporadischen Aussetzer aus.
            if (istController()) {
                Alert.send("INFO", "BOOT",
                        "Rolle Controller: Webseite, Datenbank und Knotensteuerung. "
                        + "Der Discord-Bot bleibt aus.");
            } else {
                discordBotService.start();
            }
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

    /**
     * Laeuft dieses Programm als Controller?
     *
     * <p>Gesetzt von {@code install-node.sh --modules controller} in der
     * {@code .env} des Hosts. Bewusst ueber die Umgebung und nicht ueber eine
     * Datenbankspalte: die Rolle muss feststehen, bevor irgendetwas startet -
     * auch dann, wenn die Datenbank gerade nicht erreichbar ist.</p>
     */
    private boolean istController() {
        String rolle = System.getenv("HJ_ROLLE");
        return rolle != null && rolle.trim().equalsIgnoreCase("controller");
    }
}
