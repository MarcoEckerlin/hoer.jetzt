package eckerlin.dev.commands;

import eckerlin.dev.utils.Alert;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;

import java.awt.Color;

public final class CommandHelper {

    private static final Color INFO = new Color(0x4C83FF);
    private static final Color SUCCESS = new Color(0x6BC5A4);
    private static final Color ERROR = new Color(0xFF7D96);

    private CommandHelper() {
    }

    public static AudioChannel requireMemberVoiceChannel(SlashCommandInteractionEvent event) {
        Member member = event.getMember();
        if (member == null || member.getVoiceState() == null || member.getVoiceState().getChannel() == null) {
            replyError(event, "Voice-Channel fehlt", "Du musst in einem Voice-Channel sein.", true);
            return null;
        }

        return member.getVoiceState().getChannel();
    }

    public static void replyInfo(SlashCommandInteractionEvent event, String title, String description, boolean ephemeral) {
        reply(event, title, description, INFO, ephemeral);
    }

    public static void replySuccess(SlashCommandInteractionEvent event, String title, String description, boolean ephemeral) {
        reply(event, title, description, SUCCESS, ephemeral);
    }

    public static void replyError(SlashCommandInteractionEvent event, String title, String description, boolean ephemeral) {
        reply(event, title, description, ERROR, ephemeral);
    }

    public static void editDeferredSuccess(SlashCommandInteractionEvent event, String title, String description) {
        editDeferred(event.getHook(), title, description, SUCCESS);
    }

    public static void editDeferredError(SlashCommandInteractionEvent event, String title, String description) {
        editDeferred(event.getHook(), title, description, ERROR);
    }

    public static void followupError(SlashCommandInteractionEvent event, String title, String description) {
        event.getHook()
                .sendMessageEmbeds(buildEmbed(title, description, ERROR).build())
                .setEphemeral(true)
                .queue(
                        success -> {
                        },
                        failure -> Alert.send("WARN", "COMMAND", "Follow-up konnte nicht gesendet werden: " + failure.getMessage())
                );
    }

    public static String formatDuration(long durationMs, boolean stream) {
        if (stream) {
            return "LIVE";
        }

        long totalSeconds = durationMs / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        if (hours > 0) {
            return "%d:%02d:%02d".formatted(hours, minutes, seconds);
        }
        return "%d:%02d".formatted(minutes, seconds);
    }

    private static void reply(
            SlashCommandInteractionEvent event,
            String title,
            String description,
            Color color,
            boolean ephemeral
    ) {
        if (event.isAcknowledged()) {
            // Nach einem deferReply ist editOriginal der richtige Weg. Ein
            // sendMessage haette stattdessen eine zweite Nachricht erzeugt und
            // die Denkt-nach-Anzeige stehen lassen.
            editDeferred(event.getHook(), title, description, color);
            return;
        }

        event.replyEmbeds(buildEmbed(title, description, color).build())
                .setEphemeral(ephemeral)
                .queue(
                        success -> {
                        },
                        failure -> Alert.send("WARN", "COMMAND", "Antwort konnte nicht gesendet werden: " + failure.getMessage())
                );
    }

    private static void editDeferred(InteractionHook hook, String title, String description, Color color) {
        hook.editOriginalEmbeds(buildEmbed(title, description, color).build())
                .queue(
                        success -> {
                        },
                        failure -> hook.sendMessageEmbeds(buildEmbed(title, description, color).build())
                                .setEphemeral(true)
                                .queue(
                                        ignored -> {
                                        },
                                        ignored -> Alert.send("WARN", "COMMAND", "Deferred-Antwort konnte nicht gesendet werden.")
                                )
                );
    }

    private static EmbedBuilder buildEmbed(String title, String description, Color color) {
        return new EmbedBuilder()
                .setTitle(title == null || title.isBlank() ? "Discord Bot" : title)
                .setDescription(description == null || description.isBlank() ? "-" : description)
                .setColor(color);
    }
}
