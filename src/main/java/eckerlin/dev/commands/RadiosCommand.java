package eckerlin.dev.commands;

import eckerlin.dev.audio.AudioService;
import eckerlin.dev.audio.RadioStation;
import eckerlin.dev.services.RadioStationService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.util.List;

@Component
public class RadiosCommand implements SlashCommand {

    private static final int PAGE_SIZE = 20;

    private final AudioService audioService;

    public RadiosCommand(AudioService audioService) {
        this.audioService = audioService;
    }

    @Override
    public SlashCommandData getCommandData() {
        return Commands.slash("radios", "Listet die verfügbaren Radiosender")
                .addOption(OptionType.INTEGER, "seite", "Seite der Senderliste", false);
    }

    @Override
    public boolean requiresDeferredReply() {
        // Greift auf Lavalink beziehungsweise die Datenbank zu und kann daher
        // das Drei-Sekunden-Fenster von Discord reissen.
        return true;
    }

    @Override
    public boolean deferEphemeral() {
        return true;
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        // Senderliste aus Sicht dieses Servers: ohne Freigabe kein AI-Radio.
        List<RadioStation> stations = audioService.getStations(
                event.getGuild() == null ? null : event.getGuild().getId());
        if (stations.isEmpty()) {
            CommandHelper.replyError(event, "Radio", "Aktuell sind keine Radiosender verfügbar.", true);
            return;
        }

        int totalPages = Math.max(1, (int) Math.ceil(stations.size() / (double) PAGE_SIZE));
        int requestedPage = event.getOption("seite") == null ? 1 : event.getOption("seite").getAsInt();
        int page = Math.max(1, Math.min(totalPages, requestedPage));
        int fromIndex = (page - 1) * PAGE_SIZE;
        int toIndex = Math.min(stations.size(), fromIndex + PAGE_SIZE);

        String description = stations.subList(fromIndex, toIndex).stream()
                .map(station -> station.id() == RadioStationService.SMART_RADIO_ID
                        ? "🤖 `AI` " + station.name()
                        : "📻 `#" + station.id() + "` " + station.name())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("Keine Einträge");

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("📻 Radiosender")
                .setDescription(description)
                .setColor(new Color(0x4C83FF))
                .setFooter("Seite " + page + " von " + totalPages + " - Starte einen Sender mit /radio");

        event.replyEmbeds(embed.build()).setEphemeral(true).queue();
    }
}
