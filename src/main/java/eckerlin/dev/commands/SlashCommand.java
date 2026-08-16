package eckerlin.dev.commands;

import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

import java.util.List;

public interface SlashCommand {
    SlashCommandData getCommandData();
    void execute(SlashCommandInteractionEvent event);

    /**
     * Signalisiert, dass der Command vor der eigentlichen Arbeit bestaetigt
     * werden soll.
     *
     * <p>Discord verwirft eine Interaktion, wenn sie nicht innerhalb von drei
     * Sekunden beantwortet wird - danach schlaegt jede Antwort mit dem Fehler
     * "Unknown interaction" (10062) fehl. Alle Commands, die Lavalink
     * ansprechen oder die Datenbank befragen, koennen dieses Fenster reissen.
     * Der {@link SlashCommandListener} ruft deshalb fuer solche Commands
     * zuerst {@code deferReply} auf und startet die Ausfuehrung erst danach.
     */
    default boolean requiresDeferredReply() {
        return false;
    }

    /**
     * Ob die verzoegerte Antwort nur fuer den Aufrufer sichtbar sein soll.
     */
    default boolean deferEphemeral() {
        return false;
    }

    default List<Command.Choice> complete(CommandAutoCompleteInteractionEvent event) {
        return List.of();
    }
}
