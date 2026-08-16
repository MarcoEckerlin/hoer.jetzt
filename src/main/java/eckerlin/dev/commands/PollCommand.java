package eckerlin.dev.commands;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

@Component
public class PollCommand implements SlashCommand {

    private static final String[] NUMBER_EMOJIS = {"1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣", "6️⃣", "7️⃣", "8️⃣", "9️⃣", "🔟"};

    @Override
    public SlashCommandData getCommandData() {
        return Commands.slash("poll", "Erstellt eine Umfrage")
                .addOption(OptionType.STRING, "frage", "Die Frage", true)
                .addOption(OptionType.STRING, "optionen", "Mit | getrennt, z.B. Ja | Nein | Vielleicht", true);
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        String question = event.getOption("frage").getAsString();
        String rawOptions = event.getOption("optionen").getAsString();
        String[] parts = rawOptions.split("\\|");

        List<String> options = new ArrayList<>();
        for (String part : parts) {
            String cleaned = part.trim();
            if (!cleaned.isBlank()) {
                options.add(cleaned);
            }
        }

        if (options.size() < 2 || options.size() > 10) {
            event.reply("Bitte gib zwischen 2 und 10 Optionen an.").setEphemeral(true).queue();
            return;
        }

        List<String> lines = new ArrayList<>();
        for (int index = 0; index < options.size(); index++) {
            lines.add(NUMBER_EMOJIS[index] + " " + options.get(index));
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Umfrage")
                .setColor(new Color(0xFEE75C))
                .setDescription("**" + question + "**\n\n" + String.join("\n", lines))
                .setFooter("Erstellt von " + event.getUser().getAsTag());

        event.reply("Umfrage erstellt.").setEphemeral(true).queue();
        event.getChannel().sendMessageEmbeds(embed.build()).queue(message -> addReactions(message, options.size()));
    }

    private void addReactions(Message message, int optionCount) {
        for (int index = 0; index < optionCount; index++) {
            message.addReaction(Emoji.fromUnicode(NUMBER_EMOJIS[index])).queue();
        }
    }
}
