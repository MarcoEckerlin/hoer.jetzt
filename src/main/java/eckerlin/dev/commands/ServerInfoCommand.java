package eckerlin.dev.commands;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.springframework.stereotype.Component;

import java.awt.Color;

@Component
public class ServerInfoCommand implements SlashCommand {

    @Override
    public SlashCommandData getCommandData() {
        return Commands.slash("serverinfo", "Zeigt Informationen ueber den Server");
    }

    @Override
    public boolean requiresDeferredReply() {
        // Greift auf Lavalink beziehungsweise die Datenbank zu und kann daher
        // das Drei-Sekunden-Fenster von Discord reissen.
        return true;
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        Guild guild = event.getGuild();
        if (guild == null) {
            event.reply("Nur auf einem Server verfuegbar.").setEphemeral(true).queue();
            return;
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Server Info")
                .setColor(new Color(0x57F287))
                .setThumbnail(guild.getIconUrl())
                .addField("Name", guild.getName(), true)
                .addField("ID", guild.getId(), true)
                .addField("Owner", guild.getOwner() == null ? "-" : guild.getOwner().getUser().getAsTag(), true)
                .addField("Mitglieder", String.valueOf(guild.getMemberCount()), true)
                .addField("Channels", String.valueOf(guild.getChannels().size()), true)
                .addField("Boost Tier", String.valueOf(guild.getBoostTier().getKey()), true)
                .addField("Erstellt", guild.getTimeCreated().toString(), false);

        event.replyEmbeds(embed.build()).queue();
    }
}
