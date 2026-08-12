package eckerlin.dev.commands;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class TimeoutCommand implements SlashCommand {

    @Override
    public SlashCommandData getCommandData() {
        return Commands.slash("timeout", "Verpasst einem Mitglied einen Timeout")
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MODERATE_MEMBERS))
                .addOption(OptionType.USER, "user", "Mitglied", true)
                .addOption(OptionType.INTEGER, "minuten", "Dauer in Minuten", true)
                .addOption(OptionType.STRING, "grund", "Grund", false);
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        Member target = event.getOption("user").getAsMember();
        long minutes = event.getOption("minuten").getAsLong();
        String reason = event.getOption("grund") == null ? "Kein Grund angegeben" : event.getOption("grund").getAsString();

        if (target == null) {
            event.reply("Mitglied nicht gefunden.").setEphemeral(true).queue();
            return;
        }

        event.deferReply(true).queue(hook ->
                        target.timeoutFor(Duration.ofMinutes(minutes))
                                .reason(reason)
                                .queue(
                                        success -> hook.editOriginal(target.getUser().getAsTag() + " hat jetzt " + minutes + " Minuten Timeout.").queue(),
                                        error -> hook.editOriginal("Timeout fehlgeschlagen: " + error.getMessage()).queue()
                                )
                ,
                error -> CommandHelper.replyError(event, "Timeout", "Der Timeout konnte nicht gestartet werden.", true)
        );
    }
}
