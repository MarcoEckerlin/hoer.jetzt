package eckerlin.dev.commands;

import eckerlin.dev.services.AppConfigService;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.springframework.stereotype.Component;

@Component
public class WebCommand implements SlashCommand {

    private final AppConfigService configService;

    public WebCommand(AppConfigService configService) {
        this.configService = configService;
    }

    @Override
    public SlashCommandData getCommandData() {
        return Commands.slash("webinterface", "Gibt den Link zum Webinterface aus");
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        event.reply("Webinterface: " + configService.getWebBaseUrl()).setEphemeral(true).queue();
    }
}
