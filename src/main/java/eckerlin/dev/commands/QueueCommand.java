package eckerlin.dev.commands;

import eckerlin.dev.audio.AudioService;
import eckerlin.dev.audio.PlayerState;
import eckerlin.dev.audio.TrackView;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

@Component
public class QueueCommand implements SlashCommand {

    private final AudioService audioService;

    public QueueCommand(AudioService audioService) {
        this.audioService = audioService;
    }

    @Override
    public SlashCommandData getCommandData() {
        return Commands.slash("queue", "Zeigt den aktuellen Player-Status und die Queue");
    }

    @Override
    public boolean requiresDeferredReply() {
        // Greift auf Lavalink beziehungsweise die Datenbank zu und kann daher
        // das Drei-Sekunden-Fenster von Discord reissen.
        return true;
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        PlayerState state = audioService.getPlayerState(event.getGuild());
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("📚 Player-Status")
                .setColor(new Color(0x2F3136))
                .addField("🔌 Verbunden", state.connected() ? "Ja" : "Nein", true)
                .addField("🔊 Kanal", state.voiceChannelName() == null ? "-" : state.voiceChannelName(), true)
                .addField("🔁 Repeat", state.repeatEnabled() ? "An" : "Aus", true)
                .addField("🔉 Lautstärke", state.volume() + "%", true);

        if (state.currentTrack() != null) {
            embed.addField(
                    "🎵 Aktuell",
                    state.currentTrack().title() + " • " + CommandHelper.formatDuration(state.currentTrack().durationMs(), state.currentTrack().stream()),
                    false
            );
        } else {
            embed.addField("🎵 Aktuell", "Nichts", false);
        }

        if (!state.queue().isEmpty()) {
            List<String> lines = new ArrayList<>();
            for (int index = 0; index < Math.min(10, state.queue().size()); index++) {
                TrackView track = state.queue().get(index);
                lines.add("`" + (index + 1) + ".` " + track.title() + " • " + CommandHelper.formatDuration(track.durationMs(), track.stream()));
            }
            embed.addField("📚 Queue", String.join("\n", lines), false);
        } else {
            embed.addField("📚 Queue", "Leer", false);
        }

        event.replyEmbeds(embed.build()).queue();
    }
}
