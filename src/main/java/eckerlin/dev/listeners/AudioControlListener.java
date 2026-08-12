package eckerlin.dev.listeners;

import eckerlin.dev.audio.AudioControlMessageBuilder;
import eckerlin.dev.audio.AudioService;
import eckerlin.dev.audio.PlayerState;
import eckerlin.dev.utils.Alert;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class AudioControlListener extends ListenerAdapter {

    private static final Set<String> SUPPORTED_ACTIONS = Set.of("pause", "resume", "repeat", "skip", "stop");

    private final AudioService audioService;

    public AudioControlListener(AudioService audioService) {
        this.audioService = audioService;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String componentId = event.getComponentId();
        if (componentId == null || !componentId.startsWith("audio:")) {
            return;
        }

        String[] parts = componentId.split(":");
        if (parts.length != 3) {
            event.reply("Die Audio-Steuerung ist ungueltig.").setEphemeral(true).queue();
            return;
        }

        Guild guild = event.getGuild();
        if (guild == null || !guild.getId().equals(parts[2])) {
            guild = event.getJDA().getGuildById(parts[2]);
        }
        if (guild == null) {
            event.reply("Server nicht gefunden.").setEphemeral(true).queue();
            return;
        }

        Member member = event.getMember();
        if (!canControl(member, guild)) {
            event.reply("Du musst im gleichen Voice-Channel sein oder den Server verwalten duerfen.").setEphemeral(true).queue();
            return;
        }

        String action = parts[1];
        if (!SUPPORTED_ACTIONS.contains(action)) {
            event.reply("Unbekannte Audio-Aktion.").setEphemeral(true).queue();
            return;
        }

        Guild targetGuild = guild;

        // Reihenfolge ist hier entscheidend: bisher lief die Audio-Aktion
        // komplett durch, bevor deferEdit aufgerufen wurde. pause, skip und stop
        // sprechen aber Lavalink an - dauerte das laenger als drei Sekunden,
        // war die Interaktion tot und der Nutzer sah nur noch
        // "Die Audio-Steuerung ist abgelaufen". Jetzt wird zuerst bestaetigt
        // und danach gearbeitet.
        event.deferEdit().queue(
                success -> {
                    String responseMessage = applyAction(action, targetGuild);
                    PlayerState updatedState = audioService.getPlayerState(targetGuild);
                    event.getHook()
                            .editOriginalEmbeds(AudioControlMessageBuilder.buildEmbed("Audio-Steuerung", responseMessage, updatedState))
                            .setComponents(AudioControlMessageBuilder.buildControls(updatedState))
                            .queue(
                                    ignored -> {
                                    },
                                    failure -> Alert.send("WARN", "AUDIO", "Audio-Steuerung konnte nicht aktualisiert werden: " + failure.getMessage())
                            );
                },
                failure -> Alert.send("WARN", "AUDIO", "Audio-Button war bereits abgelaufen: " + failure.getMessage())
        );
    }

    private String applyAction(String action, Guild guild) {
        return switch (action) {
            case "pause" -> audioService.pause(guild);
            case "resume" -> audioService.resume(guild);
            case "repeat" -> {
                PlayerState state = audioService.getPlayerState(guild);
                boolean enabled = !state.repeatEnabled();
                audioService.setRepeatEnabled(guild, enabled);
                yield enabled ? "Dauer-Repeat wurde aktiviert." : "Dauer-Repeat wurde deaktiviert.";
            }
            case "skip" -> audioService.skip(guild);
            case "stop" -> audioService.stop(guild);
            default -> "Unbekannte Audio-Aktion.";
        };
    }

    private boolean canControl(Member member, Guild guild) {
        if (member == null) {
            return false;
        }
        if (member.hasPermission(Permission.MANAGE_SERVER) || member.hasPermission(Permission.MANAGE_CHANNEL)) {
            return true;
        }

        AudioChannel botChannel = audioService.getConnectedVoiceChannel(guild);
        if (botChannel == null || member.getVoiceState() == null) {
            return false;
        }

        AudioChannel memberChannel = member.getVoiceState().getChannel();
        return memberChannel != null && memberChannel.getId().equals(botChannel.getId());
    }
}
