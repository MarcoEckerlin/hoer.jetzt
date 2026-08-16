package eckerlin.dev.commands;

import eckerlin.dev.audio.AudioService;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.springframework.stereotype.Component;

@Component
public class VolumeCommand implements SlashCommand {

    private final AudioService audioService;

    public VolumeCommand(AudioService audioService) {
        this.audioService = audioService;
    }

    @Override
    public SlashCommandData getCommandData() {
        return Commands.slash("volume", "Setzt die Lautstärke")
                .addOptions(new OptionData(OptionType.INTEGER, "wert", "0 bis 150 (über 100 kann verzerren)", true)
                        .setRequiredRange(0, 150));
    }

    @Override
    public boolean requiresDeferredReply() {
        // Greift auf Lavalink beziehungsweise die Datenbank zu und kann daher
        // das Drei-Sekunden-Fenster von Discord reissen.
        return true;
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        OptionMapping option = event.getOption("wert");
        if (option == null) {
            CommandHelper.replyError(event, "Audio", "Bitte gib einen Wert zwischen 0 und 150 an.", true);
            return;
        }

        int newVolume = audioService.setVolume(event.getGuild(), (int) Math.max(0L, Math.min(150L, option.getAsLong())));
        String hint = newVolume > AudioService.CLEAN_VOLUME_LIMIT
                ? " Über " + AudioService.CLEAN_VOLUME_LIMIT + "% wird das Signal digital verstärkt und kann hörbar verzerren."
                : "";
        CommandHelper.replySuccess(event, "Audio", "Lautstärke auf " + newVolume + "% gesetzt." + hint, false);
    }
}
