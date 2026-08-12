package eckerlin.dev.commands;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.springframework.stereotype.Component;

import java.awt.Color;

@Component
public class UserInfoCommand implements SlashCommand {

    @Override
    public SlashCommandData getCommandData() {
        return Commands.slash("userinfo", "Zeigt Informationen zu einem Nutzer")
                .addOption(OptionType.USER, "user", "Der Nutzer", false);
    }

    @Override
    public boolean requiresDeferredReply() {
        // Greift auf Lavalink beziehungsweise die Datenbank zu und kann daher
        // das Drei-Sekunden-Fenster von Discord reissen.
        return true;
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        User user = event.getOption("user") == null ? event.getUser() : event.getOption("user").getAsUser();
        Member member = event.getGuild() == null ? null : event.getGuild().getMember(user);

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("User Info")
                .setColor(new Color(0x5865F2))
                .setThumbnail(user.getEffectiveAvatarUrl())
                .addField("Name", user.getAsTag(), true)
                .addField("ID", user.getId(), true)
                .addField("Bot", user.isBot() ? "Ja" : "Nein", true)
                .addField("Discord erstellt", user.getTimeCreated().toString(), false);

        if (member != null) {
            embed.addField("Server beigetreten", member.getTimeJoined() == null ? "-" : member.getTimeJoined().toString(), false);
            embed.addField("Rollen", String.valueOf(member.getRoles().size()), true);
        }

        event.replyEmbeds(embed.build()).queue();
    }
}
