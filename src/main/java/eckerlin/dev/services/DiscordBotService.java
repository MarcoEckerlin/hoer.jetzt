package eckerlin.dev.services;

import eckerlin.dev.audio.AudioService;
import eckerlin.dev.commands.SlashCommandListener;
import eckerlin.dev.listeners.AudioControlListener;
import eckerlin.dev.listeners.CommunityModuleListener;
import eckerlin.dev.listeners.InviteTrackerListener;
import eckerlin.dev.listeners.LlmModuleListener;
import eckerlin.dev.listeners.TicketModuleListener;
import eckerlin.dev.listeners.VoiceChannelModuleListener;
import eckerlin.dev.listeners.DiscordLoggingListener;
import eckerlin.dev.utils.Alert;
import eckerlin.dev.utils.Client;
import eckerlin.dev.utils.Config;
import eckerlin.dev.utils.DB;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.sharding.ShardManager;
import net.dv8tion.jda.api.entities.Guild;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class DiscordBotService {

    private final BotSettingsService botSettingsService;
    private final AudioService audioService;
    private final SlashCommandListener slashCommandListener;
    private final AudioControlListener audioControlListener;
    private final VoiceChannelModuleListener voiceChannelModuleListener;
    private final InviteTrackerListener inviteTrackerListener;
    private final CommunityModuleListener communityModuleListener;
    private final LlmModuleListener llmModuleListener;
    private final TicketModuleListener ticketModuleListener;
    private final DiscordLoggingListener discordLoggingListener;
    private final BotPresenceService botPresenceService;
    private final TicketModuleService ticketModuleService;
    private final CommunityModuleService communityModuleService;
    private volatile ShardManager shards;

    public DiscordBotService(
            BotSettingsService botSettingsService,
            AudioService audioService,
            SlashCommandListener slashCommandListener,
            AudioControlListener audioControlListener,
            VoiceChannelModuleListener voiceChannelModuleListener,
            InviteTrackerListener inviteTrackerListener,
            CommunityModuleListener communityModuleListener,
            LlmModuleListener llmModuleListener,
            TicketModuleListener ticketModuleListener,
            DiscordLoggingListener discordLoggingListener,
            BotPresenceService botPresenceService,
            TicketModuleService ticketModuleService,
            CommunityModuleService communityModuleService
    ) {
        this.botSettingsService = botSettingsService;
        this.audioService = audioService;
        this.slashCommandListener = slashCommandListener;
        this.audioControlListener = audioControlListener;
        this.voiceChannelModuleListener = voiceChannelModuleListener;
        this.inviteTrackerListener = inviteTrackerListener;
        this.communityModuleListener = communityModuleListener;
        this.llmModuleListener = llmModuleListener;
        this.ticketModuleListener = ticketModuleListener;
        this.discordLoggingListener = discordLoggingListener;
        this.botPresenceService = botPresenceService;
        this.ticketModuleService = ticketModuleService;
        this.communityModuleService = communityModuleService;
    }

    public synchronized ShardManager start() throws InterruptedException {
        if (shards != null) {
            return shards;
        }

        BotSettings settings = botSettingsService.load();
        Alert.send("INFO", "DISCORD", "Starte Discord Bot...");

        audioService.initialize(settings.token());
        shards = Client.build(
                settings,
                audioService.getVoiceDispatchInterceptor(),
                slashCommandListener,
                audioControlListener,
                voiceChannelModuleListener,
                inviteTrackerListener,
                communityModuleListener,
                llmModuleListener,
                ticketModuleListener,
                discordLoggingListener
        );
        // awaitReady() gibt es am Verbund nicht - jeder Shard meldet sich
        // einzeln. Warten heisst hier: warten, bis alle verbunden sind.
        for (JDA shard : shards.getShards()) {
            shard.awaitReady();
        }
        audioService.attachShards(shards);
        botPresenceService.attachShards(shards);
        Alert.send("INFO", "DISCORD", shards.getShardsRunning() + " Shard(s) verbunden, "
                + shards.getGuilds().size() + " Server.");
        warneVorDoppeltemShard();
        shards.getGuilds().forEach(guild -> {
            ticketModuleService.syncPublishedPanels(guild);
            communityModuleService.syncReactionRoleMessage(guild);
            communityModuleService.syncVerifyMessage(guild);
        });
        Alert.send("SUCCESS", "DISCORD", "Discord Bot ist online.");
        return shards;
    }

    /**
     * Warnt, wenn mehrere Nodes denselben Shard fahren.
     *
     * <p>Discord erlaubt je Shard-Nummer eine Verbindung. Ohne
     * {@code HJ_SHARDS_GESAMT} fragt JDA Discord nach der empfohlenen Zahl -
     * bei wenigen Servern ist das eine, und jede Node nimmt dann Shard 0. Das
     * faellt nicht auf: der Bot ist online, Befehle funktionieren, nur
     * antworten zwei Prozesse auf dasselbe Ereignis. Einer gewinnt die
     * Interaktion, der andere laeuft ins Leere - und bei der Wiedergabe wollen
     * beide in denselben Sprachkanal.</p>
     *
     * <p>Bewusst nur eine Warnung und kein Abbruch: welche Node welche Shards
     * faehrt, entscheidet der Verbund, und ein Start, der daran scheitert,
     * waere schlimmer als der Doppelbetrieb. Sichtbar muss es aber sein - das
     * war es bisher an keiner Stelle.</p>
     */
    private void warneVorDoppeltemShard() {
        // Nur wenn ueberhaupt keine Aufteilung vorgegeben ist. Wer sie gesetzt
        // hat, weiss was er tut, und ueberlappende Bereiche pruefen wir hier
        // nicht nach - das ist Sache des Verbunds.
        if (System.getenv("HJ_SHARDS_GESAMT") != null && !System.getenv("HJ_SHARDS_GESAMT").isBlank()) {
            return;
        }
        if (!DB.isAvailable()) {
            return;
        }

        // Die angemeldeten Knoten sind der beste vorhandene Hinweis darauf,
        // dass diese Installation aus mehr als einer Maschine besteht.
        String sql = """
                SELECT count(DISTINCT node_name) FROM deployment_lavalink_nodes
                WHERE bot_id = ? AND herkunft <> 'manuell'
                  AND zuletzt_gesehen > now() - interval '10 minutes'
                """;

        try (Connection verbindung = DB.connection();
             PreparedStatement anweisung = verbindung.prepareStatement(sql)) {
            anweisung.setInt(1, Config.config.optInt("bot_id", 1));
            try (ResultSet ergebnis = anweisung.executeQuery()) {
                if (ergebnis.next() && ergebnis.getInt(1) > 1) {
                    Alert.send("WARN", "DISCORD",
                            "Mehrere Nodes, aber keine Shard-Aufteilung: diese Instanz faehrt Shard 0, "
                            + "die anderen ebenfalls. Discord liefert jedes Ereignis dann an alle - "
                            + "Befehle werden mehrfach verarbeitet. HJ_SHARDS_GESAMT und "
                            + "HJ_SHARD_VON/HJ_SHARD_BIS je Node setzen, oder Discord nur auf einer "
                            + "Node starten.");
                }
            }
        } catch (SQLException ignoriert) {
            // Eine Warnung, die selbst zum Fehler wird, hilft niemandem.
        }
    }

    public Optional<ShardManager> getShardsOptional() {
        return Optional.ofNullable(shards);
    }

    public ShardManager requireShards() {
        if (shards == null) {
            throw new IllegalStateException("Discord Bot wurde noch nicht gestartet.");
        }
        return shards;
    }

    /**
     * Irgendein verbundener Shard.
     *
     * <p>Nur fuer Auskuenfte, die fuer den ganzen Bot gelten und nicht an einem
     * Server haengen: der eigene Benutzer, die Anwendungsinformationen. Wer
     * etwas mit einem bestimmten Server vorhat, nimmt {@code guild.getJDA()} -
     * sonst landet die Anfrage auf einem Shard, der diesen Server gar nicht
     * kennt.</p>
     */
    public JDA requireJda() {
        ShardManager verbund = requireShards();
        return verbund.getShards().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Kein Shard verbunden."));
    }

    public Optional<JDA> getJdaOptional() {
        return shards == null || shards.getShards().isEmpty()
                ? Optional.empty()
                : Optional.of(shards.getShards().get(0));
    }

    /**
     * Sucht einen Server im ganzen Verbund.
     *
     * <p>Der wichtigste Unterschied zum Einzelbetrieb: ein <em>einzelner</em>
     * Shard kennt nur seine eigenen Server. Wer {@code getShards().get(0)
     * .getGuildById(...)} schreibt, bekommt bei zwei Shards die Haelfte aller
     * Server nicht - und zwar ohne Fehlermeldung, einfach als "gibt es nicht".
     * Deshalb geht jede Suche ueber den Verbund.</p>
     */
    public Optional<Guild> getGuildById(String guildId) {
        if (shards == null || guildId == null || !guildId.matches("\\d{5,32}")) {
            return Optional.empty();
        }
        return Optional.ofNullable(shards.getGuildById(guildId));
    }

    public List<Guild> getGuilds() {
        return shards == null ? List.of() : shards.getGuilds();
    }
}
