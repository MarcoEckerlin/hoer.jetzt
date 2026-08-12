package eckerlin.dev.commands;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.springframework.stereotype.Component;

@Component
public class KickCommand implements SlashCommand {

    @Override
    public SlashCommandData getCommandData() {
        return Commands.slash("kick", "Kickt ein Mitglied vom Server")
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.KICK_MEMBERS))
                .addOption(OptionType.USER, "user", "Zu kickendes Mitglied", true)
                .addOption(OptionType.STRING, "grund", "Grund", false);
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        Member target = event.getOption("user").getAsMember();
        String reason = event.getOption("grund") == null ? "Kein Grund angegeben" : event.getOption("grund").getAsString();

        if (target == null) {
            event.reply("Mitglied nicht gefunden.").setEphemeral(true).queue();
            return;
        }

        event.deferReply(true).queue(hook ->
                        event.getGuild().kick(target)
                                .reason(reason)
                                .queue(
                                        success -> hook.editOriginal(target.getUser().getAsTag() + " wurde gekickt.").queue(),
                                        error -> hook.editOriginal("Kick fehlgeschlagen: " + error.getMessage()).queue()
                                )
                ,
                error -> CommandHelper.replyError(event, "Kick", "Der Kick konnte nicht gestartet werden.", true)
        );
    }
}
