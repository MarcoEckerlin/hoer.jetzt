package eckerlin.dev.commands;

import eckerlin.dev.audio.AudioControlMessageBuilder;
import eckerlin.dev.audio.AudioService;
import eckerlin.dev.audio.RadioStation;
import eckerlin.dev.services.RadioStationService;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class RadioCommand implements SlashCommand {

    private final AudioService audioService;

    public RadioCommand(AudioService audioService) {
        this.audioService = audioService;
    }

    @Override
    public SlashCommandData getCommandData() {
        return Commands.slash("radio", "Startet Webradio oder KI-Radio")
                .addOptions(
                        new OptionData(OptionType.STRING, "sender", "Radiosender aus der Datenbank", false, true),
                        new OptionData(OptionType.INTEGER, "id", "Radio-ID aus der Datenbank", false)
                );
    }

    @Override
    public List<Command.Choice> complete(CommandAutoCompleteInteractionEvent event) {
        if (!"sender".equals(event.getFocusedOption().getName())) {
            return List.of();
        }

        String query = event.getFocusedOption().getValue().trim().toLowerCase(Locale.ROOT);
        return audioService.getStations(event.getGuild() == null ? null : event.getGuild().getId()).stream()
                .filter(station -> matchesStation(station, query))
                .limit(25)
                .map(station -> new Command.Choice(formatStationChoice(station), String.valueOf(station.id())))
                .toList();
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

        // Das KI-Radio hat bewusst eine negative ID (siehe RadioStationService),
        // deshalb reicht "groesser null" als Pruefung nicht mehr.
        Integer radioId = resolveRadioId(event);
        if (radioId == null || (radioId < 1 && radioId != RadioStationService.SMART_RADIO_ID)) {
            CommandHelper.replyError(
                    event,
                    "Radio",
                    "Bitte wähle einen Radiosender oder gib eine gültige Radio-ID an. Mit `/radios` kannst du dir die Senderliste anzeigen lassen.",
                    true
            );
            return;
        }

        audioService.startRadio(event.getGuild(), channel, radioId)
                .thenAccept(message -> {
                    if (audioService.isRadioCooldownMessage(message)) {
                        CommandHelper.editDeferredError(event, "Radio", message);
                        return;
                    }

                    AudioControlMessageBuilder.editDeferred(
                            event,
                            "Radio",
                            message,
                            audioService.getPlayerState(event.getGuild())
                    );
                })
                .exceptionally(throwable -> {
                    CommandHelper.editDeferredError(event, "Radio", "Der Radiosender konnte nicht gestartet werden.");
                    return null;
                });
    }

    private Integer resolveRadioId(SlashCommandInteractionEvent event) {
        String guildId = event.getGuild() == null ? null : event.getGuild().getId();
        OptionMapping senderOption = event.getOption("sender");
        if (senderOption != null && !senderOption.getAsString().isBlank()) {
            String selectedValue = senderOption.getAsString().trim();
            try {
                return Integer.parseInt(selectedValue);
            } catch (NumberFormatException ignored) {
                String query = selectedValue.toLowerCase(Locale.ROOT);
                return audioService.getStations(guildId).stream()
                        .filter(station -> station.name().equalsIgnoreCase(selectedValue)
                                || station.name().toLowerCase(Locale.ROOT).contains(query))
                        .map(RadioStation::id)
                        .findFirst()
                        .orElse(null);
            }
        }

        OptionMapping idOption = event.getOption("id");
        return idOption == null ? null : idOption.getAsInt();
    }

    private boolean matchesStation(RadioStation station, String query) {
        if (query.isBlank()) {
            return true;
        }

        return station.name().toLowerCase(Locale.ROOT).contains(query)
                || String.valueOf(station.id()).contains(query);
    }

    private String formatStationChoice(RadioStation station) {
        if (station.id() == RadioStationService.SMART_RADIO_ID) {
            return station.name();
        }

        String label = "#" + station.id() + " " + station.name();
        return label.length() <= 100 ? label : label.substring(0, 97) + "...";
    }
}
