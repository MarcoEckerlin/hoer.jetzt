package eckerlin.dev.commands;

import eckerlin.dev.utils.Alert;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import eckerlin.dev.services.DiscordLoggingService;
import eckerlin.dev.services.GuildModuleSettingsService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class SlashCommandListener extends ListenerAdapter {

    private final Map<String, SlashCommand> commands;
    private final GuildModuleSettingsService settingsService;
    private final DiscordLoggingService discordLoggingService;
    /**
     * Eigener Thread fuer die Protokollierung. Sie darf weder den JDA-Thread
     * blockieren noch den gemeinsamen ForkJoinPool belegen, da sie auf
     * Datenbank und Discord-API wartet.
     */
    private final ExecutorService loggingExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "command-log");
        thread.setDaemon(true);
        return thread;
    });

    public SlashCommandListener(
            List<SlashCommand> commandList,
            GuildModuleSettingsService settingsService,
            DiscordLoggingService discordLoggingService
    ) {
        this.commands = new LinkedHashMap<>();
        this.settingsService = settingsService;
        this.discordLoggingService = discordLoggingService;
        for (SlashCommand command : commandList) {
            this.commands.put(command.getCommandData().getName(), command);
        }
    }

    @Override
    public void onReady(ReadyEvent event) {
        event.getJDA().updateCommands()
                .addCommands(commands.values().stream().map(SlashCommand::getCommandData).toList())
                .queue();
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        SlashCommand command = commands.get(event.getName());
        if (command == null) {
            CommandHelper.replyError(event, "Command", "Unbekannter Command.", true);
            return;
        }

        // Reihenfolge ist hier entscheidend. Vorher liefen zuerst
        // isCommandEnabled und logCommand - beides greift auf die Datenbank zu,
        // logCommand loest zusaetzlich einen Kanal auf und stellt eine
        // Discord-Nachricht in die Warteschlange. Das alles passierte auf dem
        // JDA-Thread, bevor die Interaktion bestaetigt wurde. War die Datenbank
        // auch nur kurz traege, lief Discords Drei-Sekunden-Fenster ab und der
        // Nutzer sah "Die Anwendung reagiert nicht".
        //
        // Jetzt wird zuerst quittiert, danach geprueft und protokolliert.
        if (command.requiresDeferredReply()) {
            event.deferReply(command.deferEphemeral()).queue(
                    hook -> continueAfterAcknowledge(command, event),
                    failure -> Alert.send(
                            "WARN",
                            "COMMAND",
                            "Interaktion fuer /" + event.getName() + " war bereits abgelaufen: " + failure.getMessage()
                    )
            );
            return;
        }

        continueAfterAcknowledge(command, event);
    }

    private void continueAfterAcknowledge(SlashCommand command, SlashCommandInteractionEvent event) {
        if (event.getGuild() != null && !settingsService.isCommandEnabled(event.getGuild().getId(), event.getName())) {
            CommandHelper.replyError(event, "Command", "Dieser Command wurde fuer diesen Server im Webinterface deaktiviert.", true);
            return;
        }

        // Protokollierung ist Nebensache und darf die Ausfuehrung nicht
        // aufhalten - sie schreibt in die Datenbank und sendet eine Nachricht.
        logAsync(event);

        runCommand(command, event);
    }

    private void logAsync(SlashCommandInteractionEvent event) {
        loggingExecutor.execute(() -> {
            try {
                discordLoggingService.logCommand(event);
            } catch (RuntimeException exception) {
                Alert.send("WARN", "COMMAND", "Command-Protokollierung fehlgeschlagen: " + exception.getMessage());
            }
        });
    }

    private void runCommand(SlashCommand command, SlashCommandInteractionEvent event) {
        try {
            command.execute(event);
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null || exception.getMessage().isBlank()
                    ? "Der Command konnte nicht abgeschlossen werden."
                    : exception.getMessage();
            Alert.send("WARN", "COMMAND", "Slash-Command /" + event.getName() + " fehlgeschlagen: " + message);
            if (event.isAcknowledged()) {
                CommandHelper.followupError(event, "Command Fehler", message);
            } else {
                CommandHelper.replyError(event, "Command Fehler", message, true);
            }
        }
    }

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        SlashCommand command = commands.get(event.getName());
        if (command == null) {
            event.replyChoices(List.of()).queue();
            return;
        }

        event.replyChoices(command.complete(event)).queue();
    }
}
