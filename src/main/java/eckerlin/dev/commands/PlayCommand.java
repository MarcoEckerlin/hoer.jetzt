package eckerlin.dev.commands;

import eckerlin.dev.audio.AudioControlMessageBuilder;
import eckerlin.dev.audio.AudioService;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.springframework.stereotype.Component;

@Component
public class PlayCommand implements SlashCommand {

    private final AudioService audioService;

    public PlayCommand(AudioService audioService) {
        this.audioService = audioService;
    }

    @Override
    public SlashCommandData getCommandData() {
        return Commands.slash("play", "Spielt Musik ueber URL oder Suchbegriff ab")
                .addOption(OptionType.STRING, "query", "Songname oder URL", true);
    }

    @Override
    public boolean requiresDeferredReply() {
        return true;
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        AudioChannel channel = CommandHelper.requireMemberVoiceChannel(event);
        if (channel == null) {
            return;
        }

        OptionMapping queryOption = event.getOption("query");
        if (queryOption == null || queryOption.getAsString().isBlank()) {
            CommandHelper.editDeferredError(event, "Audio", "Bitte gib einen Songnamen oder eine URL an.");
            return;
        }

        audioService.queueTrack(event.getGuild(), channel, queryOption.getAsString())
                .thenAccept(message -> AudioControlMessageBuilder.editDeferred(
                        event.getHook(),
                        "Audio",
                        message,
                        audioService.getPlayerState(event.getGuild())
                ))
                .exceptionally(throwable -> {
                    CommandHelper.editDeferredError(event, "Audio", "Die Wiedergabe konnte nicht gestartet werden.");
                    return null;
                });
    }
}
