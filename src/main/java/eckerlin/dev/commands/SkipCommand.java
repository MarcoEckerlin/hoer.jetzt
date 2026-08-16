package eckerlin.dev.commands;

import eckerlin.dev.audio.AudioControlMessageBuilder;
import eckerlin.dev.audio.AudioService;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.springframework.stereotype.Component;

@Component
public class SkipCommand implements SlashCommand {

    private final AudioService audioService;

    public SkipCommand(AudioService audioService) {
        this.audioService = audioService;
    }

    @Override
    public SlashCommandData getCommandData() {
        return Commands.slash("skip", "Überspringt den aktuellen Track");
    }

    @Override
    public boolean requiresDeferredReply() {
        // Greift auf Lavalink beziehungsweise die Datenbank zu und kann daher
        // das Drei-Sekunden-Fenster von Discord reissen.
        return true;
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        AudioControlMessageBuilder.reply(
                event,
                "Audio",
                audioService.skip(event.getGuild()),
                audioService.getPlayerState(event.getGuild()),
                false
        );
    }
}
