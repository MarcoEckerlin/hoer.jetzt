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
public class BassCommand implements SlashCommand {

    private final AudioService audioService;

    public BassCommand(AudioService audioService) {
        this.audioService = audioService;
    }

    @Override
    public SlashCommandData getCommandData() {
        return Commands.slash("bass", "Schaltet den Bass-Boost für Musik und Radio ein oder aus")
                .addOption(OptionType.BOOLEAN, "aktiv", "Bass-Boost aktivieren oder deaktivieren", false);
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
                ? !playerState.bassBoostEnabled()
                : event.getOption("aktiv").getAsBoolean();

        audioService.setBassBoostEnabled(event.getGuild(), enabled);

        String suffix = playerState.currentTrack() == null
                ? " Gilt ab dem nächsten Titel."
                : "";

        AudioControlMessageBuilder.reply(
                event,
                "Audio",
                enabled ? "Bass-Boost wurde aktiviert." + suffix : "Bass-Boost wurde deaktiviert.",
                audioService.getPlayerState(event.getGuild()),
                false
        );
    }
}
