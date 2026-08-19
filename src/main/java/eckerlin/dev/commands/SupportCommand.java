package eckerlin.dev.commands;

import eckerlin.dev.services.AppConfigService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
// JDA 6 hat die Komponenten umgehaengt: api.components.buttons, nicht mehr
// api.interactions.components.buttons wie in JDA 5.
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.util.List;

/**
 * Der Weg zum Support-Server.
 *
 * <h2>Warum ein Befehl und keine Aktivitaet</h2>
 *
 * <p>Der naheliegende Wunsch war, die Statuszeile des Bots anklickbar zu
 * machen. Das geht nicht: die einzige Aktivitaetsart mit Adresse ist
 * STREAMING, und JDA prueft die gegen
 * {@code https?://(www\.)?(twitch\.tv/|youtube\.com/watch\?v=).+} - eine
 * Discord-Einladung faellt durch. Fuer Aktivitaeten gibt es ausserdem keine
 * Knoepfe; die Rich-Presence-Knoepfe aus der Game-SDK setzt der Discord-Client
 * auf dem Rechner des Spielers, nicht ein Bot ueber das Gateway.</p>
 *
 * <p>Ein Knopf unter einer Nachricht ist dagegen genau dafuer da und
 * funktioniert fuer Bots ohne Einschraenkung. Deshalb dieser Befehl.</p>
 *
 * <h2>Nur fuer den Aufrufer sichtbar</h2>
 *
 * <p>Die Antwort ist ephemer. Wer Hilfe sucht, will sie bekommen und nicht den
 * Kanal damit fuellen - und der naechste, der fragt, tippt denselben Befehl,
 * statt nach oben zu scrollen.</p>
 */
@Component
public class SupportCommand implements SlashCommand {

    private final AppConfigService configService;

    public SupportCommand(AppConfigService configService) {
        this.configService = configService;
    }

    @Override
    public SlashCommandData getCommandData() {
        return Commands.slash("support", "Zeigt, wo es Hilfe zum Bot gibt");
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        String adresse = configService.getSupportUrl();

        if (adresse.isBlank()) {
            // Kein Server hinterlegt. Das ist kein Fehler, sondern ein
            // Zustand - und der Nutzer soll ihn nicht als Panne erleben.
            event.reply("Für diesen Bot ist noch kein Support-Server hinterlegt.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Support")
                .setDescription("Fragen, Fehler oder Wünsche? Der Knopf führt direkt zum Server.")
                .setColor(new Color(0x76, 0x67, 0xFF));

        event.replyEmbeds(embed.build())
                .setComponents(List.of(ActionRow.of(Button.link(adresse, "Support-Server öffnen"))))
                .setEphemeral(true)
                .queue();
    }
}
