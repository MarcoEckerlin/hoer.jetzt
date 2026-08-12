package eckerlin.dev.services;

import eckerlin.dev.commands.SlashCommand;
import eckerlin.dev.web.dto.DashboardCommandView;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class DashboardCommandCatalogService {

    private final List<CommandDefinition> commands;

    public DashboardCommandCatalogService(List<SlashCommand> slashCommands) {
        this.commands = slashCommands.stream()
                .map(command -> new CommandDefinition(
                        command.getCommandData().getName(),
                        command.getCommandData().getDescription(),
                        categoryFor(command.getCommandData().getName())
                ))
                .sorted(Comparator
                        .comparing(CommandDefinition::category)
                        .thenComparing(CommandDefinition::name))
                .toList();
    }

    public List<DashboardCommandView> getCommandViews(String guildId, GuildModuleSettingsService settingsService) {
        return commands.stream()
                .map(command -> new DashboardCommandView(
                        command.name(),
                        command.description(),
                        command.category(),
                        settingsService.isCommandEnabled(guildId, command.name())
                ))
                .toList();
    }

    public boolean hasCommand(String commandName) {
        return commands.stream().anyMatch(command -> command.name().equals(commandName));
    }

    private static String categoryFor(String commandName) {
        return switch (commandName) {
            case "play", "radio", "radios", "queue", "pause", "resume", "skip", "stop", "volume", "repeat", "bass" -> "Audio";
            case "kick", "ban", "timeout" -> "Moderation";
            default -> "Utility";
        };
    }

    private record CommandDefinition(String name, String description, String category) {
    }
}
