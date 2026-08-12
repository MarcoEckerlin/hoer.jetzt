package eckerlin.dev.utils;

import eckerlin.dev.services.BotSettings;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.hooks.VoiceDispatchInterceptor;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;

import java.util.Locale;

public class Client {

    public static JDA build(BotSettings settings, VoiceDispatchInterceptor voiceDispatchInterceptor, Object... listeners) {
        JDABuilder builder = JDABuilder
                .createDefault(settings.token())
                .setStatus(parseStatus(settings.status()))
                .setVoiceDispatchInterceptor(voiceDispatchInterceptor)
                .enableIntents(
                        GatewayIntent.GUILD_MEMBERS,
                        GatewayIntent.GUILD_MESSAGES,
                        GatewayIntent.GUILD_MESSAGE_REACTIONS,
                        GatewayIntent.GUILD_MODERATION,
                        GatewayIntent.GUILD_VOICE_STATES,
                        GatewayIntent.MESSAGE_CONTENT
                )
                .setMemberCachePolicy(MemberCachePolicy.ALL)
                .enableCache(CacheFlag.VOICE_STATE)
                .disableCache(CacheFlag.EMOJI, CacheFlag.STICKER)
                .addEventListeners(listeners)
                .setAutoReconnect(true);

        if (settings.activity() != null && !settings.activity().isBlank()) {
            builder.setActivity(Activity.playing(settings.activity().trim()));
        }

        return builder.build();
    }

    public static OnlineStatus parseStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return OnlineStatus.ONLINE;
        }

        try {
            String normalized = rawStatus.trim().toUpperCase(Locale.ROOT);
            return switch (normalized) {
                case "DND", "DO_NOT_DISTURB" -> OnlineStatus.DO_NOT_DISTURB;
                case "AWAY", "IDLE" -> OnlineStatus.IDLE;
                case "OFFLINE", "INVISIBLE" -> OnlineStatus.INVISIBLE;
                case "ONLINE" -> OnlineStatus.ONLINE;
                default -> OnlineStatus.valueOf(normalized);
            };
        } catch (IllegalArgumentException exception) {
            return OnlineStatus.ONLINE;
        }
    }
}
