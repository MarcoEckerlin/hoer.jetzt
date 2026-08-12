package eckerlin.dev.commands;

import eckerlin.dev.audio.AudioControlMessageBuilder;
import eckerlin.dev.audio.AudioService;
import eckerlin.dev.audio.PlayerState;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.springframework.stereotype.Component;

@Component
public class RepeatCommand implements SlashCommand {

    private final AudioService audioService;

    public RepeatCommand(AudioService audioService) {
        this.audioService = audioService;
    }

    @Override
    public SlashCommandData getCommandData() {
        return Commands.slash("repeat", "Schaltet den Dauer-Repeat fuer Musik ein oder aus")
                .addOption(OptionType.BOOLEAN, "aktiv", "Repeat aktivieren oder deaktivieren", false);
    }

    @Override
    public boolean requiresDeferredReply() {
        // Greift auf Lavalink beziehungsweise die Datenbank zu und kann daher
        // das Drei-Sekunden-Fenster von Discord reissen.
        return true;
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        PlayerState playerState = audioService.getPlayerState(event.getGuild());
        boolean enabled = event.getOption("aktiv") == null
                ? !playerState.repeatEnabled()
                : event.getOption("aktiv").getAsBoolean();

        audioService.setRepeatEnabled(event.getGuild(), enabled);

        String suffix = playerState.playingRadio()
                ? " Bei Webradio hat der Modus keine Wirkung."
                : "";
        AudioControlMessageBuilder.reply(
                event,
                "Audio",
                enabled ? "Dauer-Repeat wurde aktiviert." + suffix : "Dauer-Repeat wurde deaktiviert.",
                audioService.getPlayerState(event.getGuild()),
                false
        );
    }
}
