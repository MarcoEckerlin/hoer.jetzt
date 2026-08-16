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
import java.time.Duration;

/**
 * Zeigt alles zum laufenden Titel - auf Abruf statt bei jedem Befehl.
 *
 * <p>Die Antwort ist absichtlich nur fuer den Fragenden sichtbar: wer die
 * Einzelheiten wissen will, will sie meist selbst wissen, und der Kanal soll
 * nicht wieder zulaufen.
 */
@Component
public class SongInfoCommand implements SlashCommand {

    private static final Color ACCENT = new Color(0x5865F2);

    private final AudioService audioService;

    public SongInfoCommand(AudioService audioService) {
        this.audioService = audioService;
    }

    @Override
    public SlashCommandData getCommandData() {
        return Commands.slash("songinfo", "Einzelheiten zum laufenden Titel");
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        if (event.getGuild() == null) {
            CommandHelper.replyError(event, "Songinfo", "Das geht nur auf einem Server.", true);
            return;
        }

        PlayerState zustand = audioService.getPlayerState(event.getGuild());
        TrackView titel = zustand == null ? null : zustand.currentTrack();
        if (titel == null) {
            CommandHelper.replyError(event, "Songinfo", "Es läuft gerade nichts.", true);
            return;
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setColor(ACCENT)
                .setTitle(titel.title() == null || titel.title().isBlank() ? "Unbekannter Titel" : titel.title());

        if (titel.uri() != null && !titel.uri().isBlank()) {
            embed.setUrl(titel.uri());
        }
        if (titel.artworkUrl() != null && !titel.artworkUrl().isBlank()) {
            embed.setThumbnail(titel.artworkUrl());
        }
        if (titel.author() != null && !titel.author().isBlank()) {
            embed.addField("Interpret", titel.author(), true);
        }

        embed.addField("Dauer", titel.stream() ? "Livestream" : dauer(titel.durationMs()), true);

        if (titel.sourceName() != null && !titel.sourceName().isBlank()) {
            embed.addField("Quelle", titel.sourceName(), true);
        }

        embed.addField("Warteschlange", (zustand.queue() == null ? 0 : zustand.queue().size()) + " Titel", true);
        embed.addField("Lautstärke", zustand.volume() + " %", true);

        // Auf welchem Knoten der Ton entsteht - im Zweifel die erste Frage bei
        // Aussetzern, und sonst nirgends im Chat zu sehen.
        String knoten = audioService.knotenVon(event.getGuild());
        if (!knoten.isBlank()) {
            embed.addField("Audio-Knoten", knoten + " (" + audioService.knotenStufeVon(event.getGuild()) + ")", true);
        }

        event.replyEmbeds(embed.build()).setEphemeral(true).queue(ignored -> {
        }, ignored -> {
        });
    }

    private String dauer(long millisekunden) {
        if (millisekunden <= 0) {
            return "unbekannt";
        }
        Duration d = Duration.ofMillis(millisekunden);
        long stunden = d.toHours();
        return stunden > 0
                ? String.format("%d:%02d:%02d", stunden, d.toMinutesPart(), d.toSecondsPart())
                : String.format("%d:%02d", d.toMinutes(), d.toSecondsPart());
    }
}
