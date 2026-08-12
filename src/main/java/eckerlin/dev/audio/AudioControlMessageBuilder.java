package eckerlin.dev.audio;

import eckerlin.dev.commands.CommandHelper;
import eckerlin.dev.utils.Alert;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;

import java.awt.Color;
import java.time.Instant;
import java.util.List;

public final class AudioControlMessageBuilder {

    private static final Color ACCENT = new Color(0x7CA9FF);
    private static final String BUTTON_PREFIX = "audio:";

    private AudioControlMessageBuilder() {
    }

    public static void reply(
            SlashCommandInteractionEvent event,
            String title,
            String description,
            PlayerState playerState,
            boolean ephemeral
    ) {
        MessageEmbed embed = buildEmbed(title, description, playerState);
        List<ActionRow> controls = buildControls(playerState);

        if (event.isAcknowledged()) {
            // Der Listener bestaetigt Audio-Commands inzwischen vorab. Die
            // fertige Antwort ersetzt daher die Platzhalter-Nachricht, statt
            // eine zusaetzliche zu erzeugen.
            editDeferred(event.getHook(), title, description, playerState);
            return;
        }

        event.replyEmbeds(embed)
                .addComponents(controls)
                .setEphemeral(ephemeral)
                .queue(
                        success -> {
                        },
                        failure -> {
                            Alert.send("WARN", "AUDIO", "Audio-Reply mit Buttons konnte nicht gesendet werden: " + failure.getMessage());
                            event.replyEmbeds(new EmbedBuilder()
                                            .setTitle(title == null || title.isBlank() ? "🎵 Audio" : title)
                                            .setDescription(description == null || description.isBlank() ? "-" : description)
                                            .setColor(ACCENT)
                                            .setTimestamp(Instant.now())
                                            .build())
                                    .setEphemeral(ephemeral)
                                    .queue(
                                            ignored -> {
                                            },
                                            ignored -> Alert.send("WARN", "AUDIO", "Auch der Audio-Fallback ohne Buttons konnte nicht gesendet werden.")
                                    );
                        }
                );
    }

    public static void editDeferred(
            InteractionHook hook,
            String title,
            String description,
            PlayerState playerState
    ) {
        MessageEmbed embed = buildEmbed(title, description, playerState);
        List<ActionRow> controls = buildControls(playerState);

        hook.editOriginalEmbeds(embed)
                .setComponents(controls)
                .queue(
                        success -> {
                        },
                        failure -> {
                            Alert.send("WARN", "AUDIO", "Audio-Embed mit Buttons konnte nicht gesendet werden: " + failure.getMessage());
                            hook.editOriginalEmbeds(new EmbedBuilder()
                                            .setTitle(title == null || title.isBlank() ? "🎵 Audio" : title)
                                            .setDescription(description == null || description.isBlank() ? "-" : description)
                                            .setColor(ACCENT)
                                            .setTimestamp(Instant.now())
                                            .build())
                                    .setComponents(List.of())
                                    .queue(
                                            ignored -> {
                                            },
                                            ignored -> Alert.send("WARN", "AUDIO", "Auch der Audio-Fallback ohne Buttons konnte nicht gesendet werden.")
                                    );
                        }
                );
    }

    public static void editDeferred(
            SlashCommandInteractionEvent event,
            String title,
            String description,
            PlayerState playerState
    ) {
        editDeferred(event.getHook(), title, description, playerState);
    }

    public static MessageEmbed buildEmbed(String title, String description, PlayerState playerState) {
        String normalizedTitle = title == null ? "" : title.trim();
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(buildEmbedTitle(normalizedTitle, playerState))
                .setDescription(description == null || description.isBlank() ? "-" : description)
                .setColor(ACCENT)
                .setTimestamp(Instant.now());

        TrackView track = playerState == null ? null : playerState.currentTrack();
        if (track == null) {
            return embed
                    .addField("🎵 Status", "Keine aktive Wiedergabe", false)
                    .addField("💡 Hinweis", "Starte Musik oder Radio ueber `/play`, `/radio` oder das Dashboard.", false)
                    .build();
        }

        String duration = CommandHelper.formatDuration(track.durationMs(), track.stream());
        embed.setDescription(buildCompactDescription(description, track, duration));
        embed.addField("🎚️ Wiedergabe", buildPlaybackPanel(playerState), true);
        embed.addField("🔊 Server", buildServerPanel(playerState), true);

        if (track.uri() != null && !track.uri().isBlank()) {
            embed.setUrl(track.uri());
        }
        if (track.artworkUrl() != null && !track.artworkUrl().isBlank()) {
            if (!playerState.playingRadio() && !track.stream()) {
                embed.setImage(track.artworkUrl());
            } else {
                embed.setThumbnail(track.artworkUrl());
            }
        }
        embed.setFooter("🎛️ Steuere die Wiedergabe direkt ueber die Buttons.");
        return embed.build();
    }

    public static List<ActionRow> buildControls(PlayerState playerState) {
        String guildId = playerState == null || playerState.guildId() == null ? "0" : playerState.guildId();
        boolean hasTrack = playerState != null && playerState.currentTrack() != null;
        boolean paused = playerState != null && playerState.paused();

        Button pauseButton = Button.secondary(componentId("pause", guildId), "Pause")
                .withEmoji(Emoji.fromUnicode("⏸️"));
        Button resumeButton = Button.success(componentId("resume", guildId), "Resume")
                .withEmoji(Emoji.fromUnicode("▶️"));
        Button repeatButton = Button.primary(
                componentId("repeat", guildId),
                playerState != null && playerState.repeatEnabled() ? "Repeat an" : "Repeat aus"
        ).withEmoji(Emoji.fromUnicode("🔁"));
        Button skipButton = Button.secondary(componentId("skip", guildId), "Skip")
                .withEmoji(Emoji.fromUnicode("⏭️"));
        Button stopButton = Button.danger(componentId("stop", guildId), "Stop")
                .withEmoji(Emoji.fromUnicode("⏹️"));

        if (!hasTrack || paused) {
            pauseButton = pauseButton.asDisabled();
        }
        if (!hasTrack || !paused) {
            resumeButton = resumeButton.asDisabled();
        }
        if (!hasTrack) {
            repeatButton = repeatButton.asDisabled();
            skipButton = skipButton.asDisabled();
            stopButton = stopButton.asDisabled();
        }

        return List.of(ActionRow.of(pauseButton, resumeButton, repeatButton, skipButton, stopButton));
    }

    public static String componentId(String action, String guildId) {
        return BUTTON_PREFIX + action + ":" + guildId;
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "Unbekannt";
        }
        return value.length() > 1024 ? value.substring(0, 1021) + "..." : value;
    }

    private static String buildEmbedTitle(String title, PlayerState playerState) {
        TrackView track = playerState == null ? null : playerState.currentTrack();
        if (track == null) {
            return title == null || title.isBlank() ? "🎵 Audio" : prefixForTitle(title) + " " + title;
        }

        return (playerState.playingRadio() ? "📻 " : "🎵 ") + safe(track.title());
    }

    private static String buildCompactDescription(String description, TrackView track, String duration) {
        StringBuilder builder = new StringBuilder();
        if (description != null && !description.isBlank()) {
            builder.append(description.trim());
        }

        if (builder.length() > 0) {
            builder.append("\n\n");
        }
        builder.append("👤 ").append(safe(track.author())).append(" • ⏱️ ").append(duration);
        return builder.toString();
    }

    private static String buildPlaybackPanel(PlayerState playerState) {
        String mode = playerState.playingRadio() ? "📻 Radio" : "🎶 Musik";
        String status = playerState.paused() ? "⏸️ Pausiert" : "▶️ Laeuft";
        return mode
                + "\n" + status
                + "\n🔁 Repeat: " + (playerState.repeatEnabled() ? "An" : "Aus")
                + "\n🥁 Bass: " + (playerState.bassBoostEnabled() ? "An" : "Aus");
    }

    private static String buildServerPanel(PlayerState playerState) {
        String channel = playerState.voiceChannelName() == null || playerState.voiceChannelName().isBlank()
                ? "-"
                : playerState.voiceChannelName();
        int queueSize = playerState.queue() == null ? 0 : playerState.queue().size();
        return "🔊 " + channel
                + "\n📚 Queue: " + queueSize
                + "\n🔉 Lautstaerke: " + playerState.volume() + "%";
    }

    private static String prefixForTitle(String title) {
        String lowerCase = title == null ? "" : title.toLowerCase();
        if (lowerCase.contains("radio")) {
            return "📻";
        }
        if (lowerCase.contains("queue")) {
            return "📚";
        }
        if (lowerCase.contains("fehler") || lowerCase.contains("error")) {
            return "⚠️";
        }
        return "🎵";
    }
}
