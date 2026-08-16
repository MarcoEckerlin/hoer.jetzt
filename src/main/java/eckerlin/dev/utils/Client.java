package eckerlin.dev.utils;

import eckerlin.dev.services.BotSettings;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.sharding.DefaultShardManagerBuilder;
import net.dv8tion.jda.api.sharding.ShardManager;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.hooks.VoiceDispatchInterceptor;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;

import java.util.Locale;

public class Client {

    /**
     * Baut den Bot als Shard-Verbund.
     *
     * <p>Discord teilt einen Bot ab einer gewissen Groesse in Shards: jeder
     * bekommt einen festen Teil der Server, nach
     * {@code (guild_id >> 22) % shard_count}. Das ist Discords Protokoll, keine
     * Empfehlung - ein Server wird von genau einem Shard bedient.</p>
     *
     * <p>Daraus folgt etwas, das den ganzen Umbau traegt: <b>jeder Server
     * gehoert zu jedem Zeitpunkt genau einem Prozess.</b> Wenn nur ein Prozess
     * fuer einen Server schreibt, schreibt auch nur eine Node fuer ihn - und
     * die Konflikte, vor denen man bei Multi-Master-Replikation Angst hat,
     * entstehen gar nicht erst.</p>
     *
     * <p>Auch mit genau einem Shard laeuft alles wie bisher. Der
     * ShardManager ist dann ein Verbund aus einem - kein Nachteil, aber der
     * Weg dorthin ist schon gebahnt.</p>
     *
     * <p><b>Achtung bei mehreren Prozessen:</b> Discord erlaubt genau eine
     * Verbindung je Shard-Nummer. Startet dieselbe Nummer zweimal, wirft
     * Discord beide hinaus. Welcher Prozess welche Nummern faehrt, legen
     * HJ_SHARD_VON und HJ_SHARD_BIS fest - das darf sich nie ueberlappen.</p>
     */
    public static ShardManager build(BotSettings settings, VoiceDispatchInterceptor voiceDispatchInterceptor, Object... listeners) {
        DefaultShardManagerBuilder builder = DefaultShardManagerBuilder
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

        int gesamt = ganzzahl("HJ_SHARDS_GESAMT", -1);
        int von = ganzzahl("HJ_SHARD_VON", -1);
        int bis = ganzzahl("HJ_SHARD_BIS", -1);

        if (gesamt > 0) {
            builder.setShardsTotal(gesamt);
            if (von >= 0 && bis >= von) {
                builder.setShards(von, bis);
            }
        }
        // Ohne Angabe fragt JDA Discord nach der empfohlenen Zahl - fuer eine
        // einzelne Node genau richtig.

        if (settings.activity() != null && !settings.activity().isBlank()) {
            builder.setActivity(Activity.playing(settings.activity().trim()));
        }

        return builder.build();
    }

    private static int ganzzahl(String name, int vorgabe) {
        String wert = System.getenv(name);
        if (wert == null || wert.isBlank()) {
            return vorgabe;
        }
        try {
            return Integer.parseInt(wert.trim());
        } catch (NumberFormatException ignoriert) {
            return vorgabe;
        }
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
