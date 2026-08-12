package eckerlin.dev.commands;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.springframework.stereotype.Component;

@Component
public class HelpCommand implements SlashCommand {

    @Override
    public SlashCommandData getCommandData() {
        return Commands.slash("help", "Zeigt die wichtigsten Bot-Funktionen");
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        String message = """
                **Audio**
                `/play query:<song/url>` - Musik suchen oder URL abspielen
                `/radio sender:<auswahl>` - Webradio oder AI Radio direkt aus der Senderliste starten
                `/radio id:<db-id>` - alternativ per Datenbank-ID starten
                `/radios` - Radiosenderliste ansehen
                `/queue`, `/pause`, `/resume`, `/skip`, `/stop`, `/volume`, `/repeat`, `/bass`
                
                **Server**
                `/userinfo`, `/serverinfo`, `/poll`, `/webinterface`
                
                **Moderation**
                `/kick`, `/ban`, `/timeout`
                """;

        CommandHelper.replyInfo(event, "Hilfe", message, true);
    }
}
