package eckerlin.dev.commands;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class BanCommand implements SlashCommand {

    @Override
    public SlashCommandData getCommandData() {
        return Commands.slash("ban", "Bannt einen Nutzer")
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.BAN_MEMBERS))
                .addOption(OptionType.USER, "user", "Zu bannender Nutzer", true)
                .addOption(OptionType.STRING, "grund", "Grund", false);
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        String reason = event.getOption("grund") == null ? "Kein Grund angegeben" : event.getOption("grund").getAsString();
        var user = event.getOption("user").getAsUser();

        event.deferReply(true).queue(hook ->
                        event.getGuild().ban(user, 0, TimeUnit.DAYS)
                                .reason(reason)
                                .queue(
                                        success -> hook.editOriginal(user.getAsTag() + " wurde gebannt.").queue(),
                                        error -> hook.editOriginal("Ban fehlgeschlagen: " + error.getMessage()).queue()
                                )
                ,
                error -> CommandHelper.replyError(event, "Ban", "Der Ban konnte nicht gestartet werden.", true)
        );
    }
}
