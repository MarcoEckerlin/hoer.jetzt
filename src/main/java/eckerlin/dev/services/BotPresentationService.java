package eckerlin.dev.services;

import eckerlin.dev.web.dto.BotRuntimeView;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.SelfUser;
import org.springframework.stereotype.Service;

@Service
public class BotPresentationService {

    private final AppConfigService configService;
    private final DiscordBotService discordBotService;

    public BotPresentationService(AppConfigService configService, DiscordBotService discordBotService) {
        this.configService = configService;
        this.discordBotService = discordBotService;
    }

    public BotRuntimeView buildRuntimeView() {
        return discordBotService.getJdaOptional()
                .map(this::fromJda)
                .orElseGet(this::fallbackView);
    }

    private BotRuntimeView fromJda(JDA jda) {
        SelfUser selfUser = jda.getSelfUser();
        String avatarUrl = selfUser.getEffectiveAvatarUrl();
        String brandImageUrl = firstNonBlank(configService.getBrandImageUrl(), avatarUrl);
        String heroImageUrl = firstNonBlank(configService.getHeroImageUrl(), brandImageUrl, avatarUrl);
        Activity activity = jda.getPresence().getActivity();

        return new BotRuntimeView(
                firstNonBlank(selfUser.getGlobalName(), selfUser.getName(), "Discord Bot"),
                firstNonBlank(avatarUrl, brandImageUrl, heroImageUrl),
                brandImageUrl,
                heroImageUrl,
                jda.getStatus() == JDA.Status.CONNECTED,
                normalizeStatus(jda.getPresence().getStatus().name()),
                activity == null ? configService.getBotActivity() : firstNonBlank(activity.getName(), configService.getBotActivity())
        );
    }

    private BotRuntimeView fallbackView() {
        String brandImageUrl = configService.getBrandImageUrl();
        String heroImageUrl = firstNonBlank(configService.getHeroImageUrl(), brandImageUrl);
        String avatarUrl = firstNonBlank(brandImageUrl, heroImageUrl);

        return new BotRuntimeView(
                "Discord Bot",
                avatarUrl,
                firstNonBlank(brandImageUrl, avatarUrl),
                firstNonBlank(heroImageUrl, brandImageUrl, avatarUrl),
                false,
                normalizeStatus(configService.getBotStatus()),
                configService.getBotActivity()
        );
    }

    private String normalizeStatus(String rawStatus) {
        String value = rawStatus == null ? "" : rawStatus.trim().toUpperCase();
        return switch (value) {
            case "DO_NOT_DISTURB", "DND" -> "Do Not Disturb";
            case "INVISIBLE", "OFFLINE" -> "Offline";
            case "IDLE", "AWAY" -> "Away";
            case "ONLINE" -> "Online";
            default -> "Online";
        };
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }

        for (String value : values) {
            if (value != null && !value.trim().isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
