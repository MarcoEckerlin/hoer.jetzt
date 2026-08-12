package eckerlin.dev.services.tools;

import eckerlin.dev.audio.AudioService;
import eckerlin.dev.audio.PlayerState;
import eckerlin.dev.audio.RadioStation;
import eckerlin.dev.audio.TrackView;
import eckerlin.dev.utils.Alert;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Stellt die Audio-Funktionen des Bots als aufrufbare Werkzeuge bereit.
 *
 * <p>Diese Klasse ist die gemeinsame Grundlage fuer zwei Wege:
 * <ul>
 *   <li>Function-Calling des Sprachmodells im Discord-Chat
 *       ("@Bot spiel mal Atemlos von Helene Fischer")</li>
 *   <li>den MCP-Endpunkt, ueber den externe Clients den Bot steuern</li>
 * </ul>
 *
 * <p>Bewusst enthalten sind ausschliesslich Audio-Funktionen. Moderation -
 * Kick, Ban, Timeout - bleibt aussen vor: im Chat kann jeder Servermitglied dem
 * Bot schreiben, und was dort steht, wird fuer das Modell zur Anweisung. Bei
 * Musik ist der schlimmste Missbrauch ein ungewollter Liedwechsel.
 */
@Service
public class AudioToolService {

    /**
     * Ladevorgaenge duerfen den aufrufenden Thread nicht unbegrenzt blockieren.
     * Etwas grosszuegiger als das Ladelimit im AudioService, damit dessen
     * eigener Fallback noch greifen kann.
     */
    private static final long EXECUTION_TIMEOUT_SECONDS = 30L;

    private final AudioService audioService;

    // Bewusst KEINE Injektion von DiscordBotService: dieser injiziert alle
    // Listener, und ueber LlmModuleListener -> LlmService -> AudioToolService
    // entstuende ein Zyklus, den Spring beim Start ablehnt. Die JDA-Instanz
    // kommt deshalb aus dem AudioService.
    public AudioToolService(AudioService audioService) {
        this.audioService = audioService;
    }

    /**
     * Liefert die Werkzeugbeschreibungen.
     *
     * @param includeGuildParameter im Chat ergibt sich der Server aus der
     *                              Nachricht; ein externer MCP-Client muss ihn
     *                              dagegen angeben.
     */
    public List<BotTool> tools(boolean includeGuildParameter) {
        return List.of(
                new BotTool(
                        "play_music",
                        "Spielt einen Titel ab oder haengt ihn an die Warteschlange an. "
                                + "Akzeptiert einen Suchbegriff wie \"Helene Fischer Atemlos\" oder eine direkte URL.",
                        schema(includeGuildParameter, property("query", "string",
                                "Titel, Interpret oder URL"), "query"),
                        false
                ),
                new BotTool(
                        "play_radio",
                        "Startet einen Webradio-Sender aus der hinterlegten Senderliste. "
                                + "Name oder Nummer des Senders angeben.",
                        schema(includeGuildParameter, property("station", "string",
                                "Sendername oder Sendernummer"), "station"),
                        false
                ),
                new BotTool(
                        "skip_track",
                        "Ueberspringt den laufenden Titel und startet den naechsten aus der Warteschlange.",
                        schema(includeGuildParameter, new JSONObject()),
                        false
                ),
                new BotTool(
                        "pause_playback",
                        "Pausiert die laufende Wiedergabe.",
                        schema(includeGuildParameter, new JSONObject()),
                        false
                ),
                new BotTool(
                        "resume_playback",
                        "Setzt eine pausierte Wiedergabe fort.",
                        schema(includeGuildParameter, new JSONObject()),
                        false
                ),
                new BotTool(
                        "stop_playback",
                        "Stoppt die Wiedergabe vollstaendig, leert die Warteschlange und trennt die Sprachverbindung.",
                        schema(includeGuildParameter, new JSONObject()),
                        false
                ),
                new BotTool(
                        "set_volume",
                        "Setzt die Lautstaerke in Prozent. Werte ueber 100 verstaerken digital und koennen hoerbar verzerren.",
                        schema(includeGuildParameter, property("level", "integer",
                                "Lautstaerke von 0 bis 150"), "level"),
                        false
                ),
                new BotTool(
                        "get_playback_status",
                        "Gibt zurueck, was gerade laeuft: Titel, Interpret, Lautstaerke, Sprachkanal und Warteschlange.",
                        schema(includeGuildParameter, new JSONObject()),
                        true
                ),
                new BotTool(
                        "list_radio_stations",
                        "Listet die verfuegbaren Webradio-Sender mit Nummer und Name auf.",
                        BotTool.noParameters(),
                        true
                )
        );
    }

    /**
     * Zusaetzliches Werkzeug nur fuer externe Clients: ohne Serverliste
     * koennen sie den Pflichtparameter {@code guild} nicht sinnvoll fuellen.
     */
    public BotTool listGuildsTool() {
        return new BotTool(
                "list_servers",
                "Listet die Discord-Server auf, auf denen der Bot aktiv ist, mit Name und ID.",
                BotTool.noParameters(),
                true
        );
    }

    public ToolResult execute(String toolName, JSONObject arguments, ToolContext context) {
        JSONObject args = arguments == null ? new JSONObject() : arguments;

        try {
            return switch (toolName) {
                case "list_radio_stations" -> listStations();
                case "list_servers" -> listServers();
                case "play_music" -> playMusic(args, context);
                case "play_radio" -> playRadio(args, context);
                case "skip_track" -> withGuild(context, guild -> ToolResult.ok(audioService.skip(guild)));
                case "pause_playback" -> withGuild(context, guild -> ToolResult.ok(audioService.pause(guild)));
                case "resume_playback" -> withGuild(context, guild -> ToolResult.ok(audioService.resume(guild)));
                case "stop_playback" -> withGuild(context, guild -> ToolResult.ok(audioService.stop(guild)));
                case "set_volume" -> setVolume(args, context);
                case "get_playback_status" -> withGuild(context, this::describePlayback);
                default -> ToolResult.error("Unbekanntes Werkzeug: " + toolName);
            };
        } catch (RuntimeException exception) {
            Alert.send("WARN", "TOOLS", "Werkzeug " + toolName + " fehlgeschlagen: " + exception.getMessage());
            return ToolResult.error("Der Aufruf ist fehlgeschlagen: "
                    + (exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()));
        }
    }

    // ----------------------------------------------------------- Werkzeuge

    private ToolResult playMusic(JSONObject args, ToolContext context) {
        String query = args.optString("query", "").trim();
        if (query.isBlank()) {
            return ToolResult.error("Es wurde kein Suchbegriff angegeben.");
        }

        return withGuild(context, guild -> {
            AudioChannel channel = resolveVoiceChannel(guild, context);
            if (channel == null) {
                return ToolResult.error(noVoiceChannelHint(context));
            }
            return await(audioService.queueTrack(guild, channel, query));
        });
    }

    private ToolResult playRadio(JSONObject args, ToolContext context) {
        String station = args.optString("station", "").trim();
        if (station.isBlank()) {
            return ToolResult.error("Es wurde kein Sender angegeben.");
        }

        Optional<RadioStation> match = findStation(station);
        if (match.isEmpty()) {
            return ToolResult.error("Kein Sender gefunden, der zu \"" + station
                    + "\" passt. Mit list_radio_stations lassen sich die verfuegbaren Sender abrufen.");
        }

        return withGuild(context, guild -> {
            AudioChannel channel = resolveVoiceChannel(guild, context);
            if (channel == null) {
                return ToolResult.error(noVoiceChannelHint(context));
            }
            return await(audioService.startRadio(guild, channel, match.get().id()));
        });
    }

    private ToolResult setVolume(JSONObject args, ToolContext context) {
        if (!args.has("level")) {
            return ToolResult.error("Es wurde keine Lautstaerke angegeben.");
        }

        int requested = args.optInt("level", 100);
        return withGuild(context, guild -> {
            int applied = audioService.setVolume(guild, requested);
            String hint = applied > AudioService.CLEAN_VOLUME_LIMIT
                    ? " Oberhalb von " + AudioService.CLEAN_VOLUME_LIMIT + "% wird digital verstaerkt, das kann verzerren."
                    : "";
            return ToolResult.ok("Lautstaerke steht jetzt auf " + applied + "%." + hint);
        });
    }

    private ToolResult describePlayback(Guild guild) {
        PlayerState state = audioService.getPlayerState(guild);
        TrackView track = state.currentTrack();

        StringBuilder text = new StringBuilder();
        if (track == null) {
            text.append("Aktuell laeuft nichts.");
        } else {
            text.append("Es laeuft: ").append(track.title());
            if (track.author() != null && !track.author().isBlank()) {
                text.append(" von ").append(track.author());
            }
            text.append(state.paused() ? " (pausiert)" : "");
        }

        if (!state.activeRadioName().isBlank()) {
            text.append(" | Sender: ").append(state.activeRadioName());
        }
        text.append(" | Lautstaerke: ").append(state.volume()).append("%");
        text.append(" | Sprachkanal: ")
                .append(state.voiceChannelName() == null ? "nicht verbunden" : state.voiceChannelName());
        text.append(" | Warteschlange: ").append(state.queue().size()).append(" Titel");

        return ToolResult.ok(text.toString());
    }

    private ToolResult listStations() {
        List<RadioStation> stations = audioService.getStations();
        if (stations.isEmpty()) {
            return ToolResult.error("Es sind keine Radiosender hinterlegt.");
        }

        StringBuilder text = new StringBuilder("Verfuegbare Sender:");
        // Nur eine ueberschaubare Auswahl, damit die Antwort das Kontextfenster
        // des Modells nicht sprengt.
        stations.stream().limit(40).forEach(station ->
                text.append("\n").append(station.id()).append(" - ").append(station.name()));

        if (stations.size() > 40) {
            text.append("\n... und ").append(stations.size() - 40).append(" weitere.");
        }
        return ToolResult.ok(text.toString());
    }

    private ToolResult listServers() {
        JDA jda = audioService.getAttachedJda();
        if (jda == null) {
            return ToolResult.error("Der Bot ist derzeit nicht mit Discord verbunden.");
        }

        List<Guild> guilds = jda.getGuilds();
        if (guilds.isEmpty()) {
            return ToolResult.error("Der Bot ist auf keinem Server aktiv.");
        }

        StringBuilder text = new StringBuilder("Server:");
        guilds.forEach(guild -> text.append("\n").append(guild.getName())
                .append(" (ID ").append(guild.getId()).append(")"));
        return ToolResult.ok(text.toString());
    }

    // ------------------------------------------------------------- Helfer

    private ToolResult withGuild(ToolContext context, GuildAction action) {
        Guild guild = resolveGuild(context);
        if (guild == null) {
            return ToolResult.error("Der Server konnte nicht ermittelt werden. "
                    + "Externe Clients muessen den Parameter \"guild\" setzen; mit list_servers lassen sich die IDs abrufen.");
        }

        if (context.requiresPermissionCheck() && !mayControl(context.member(), guild)) {
            return ToolResult.error("Dafuer musst du im selben Sprachkanal wie der Bot sein "
                    + "oder das Recht haben, den Server zu verwalten.");
        }

        return action.apply(guild);
    }

    private Guild resolveGuild(ToolContext context) {
        if (context.guild() != null) {
            return context.guild();
        }

        String reference = context.guildReference();
        if (reference == null || reference.isBlank()) {
            return null;
        }

        JDA jda = audioService.getAttachedJda();
        if (jda == null) {
            return null;
        }

        String trimmed = reference.trim();

        // getGuildById wirft bei nicht-numerischen Werten eine Exception
        // ("is not a valid snowflake"), statt null zu liefern. Deshalb wird
        // die ID-Suche nur bei reinen Ziffernfolgen versucht.
        if (trimmed.chars().allMatch(Character::isDigit)) {
            Guild byId = jda.getGuildById(trimmed);
            if (byId != null) {
                return byId;
            }
        }

        String normalized = trimmed.toLowerCase(Locale.ROOT);
        return jda.getGuilds().stream()
                .filter(guild -> guild.getName().toLowerCase(Locale.ROOT).equals(normalized))
                .findFirst()
                .orElseGet(() -> jda.getGuilds().stream()
                        .filter(guild -> guild.getName().toLowerCase(Locale.ROOT).contains(normalized))
                        .findFirst()
                        .orElse(null));
    }

    /**
     * Bestimmt den Sprachkanal fuer eine Wiedergabe.
     *
     * <p>Im Chat ist das der Kanal, in dem der anfragende Nutzer sitzt - genau
     * wie bei {@code /play}. Fuer externe Clients ohne Nutzerbezug wird der
     * Kanal genommen, in dem der Bot bereits verbunden ist.
     */
    private AudioChannel resolveVoiceChannel(Guild guild, ToolContext context) {
        Member member = context.member();
        if (member != null && member.getVoiceState() != null && member.getVoiceState().getChannel() != null) {
            return member.getVoiceState().getChannel();
        }

        return audioService.getConnectedVoiceChannel(guild);
    }

    private String noVoiceChannelHint(ToolContext context) {
        return context.member() != null
                ? "Du musst zuerst einem Sprachkanal beitreten."
                : "Der Bot ist derzeit in keinem Sprachkanal. Bitte den Bot zuerst ueber Discord "
                + "oder das Dashboard in einen Kanal holen.";
    }

    /**
     * Dieselbe Regel wie bei den Slash-Commands und den Audio-Buttons.
     */
    private boolean mayControl(Member member, Guild guild) {
        if (member == null) {
            return false;
        }
        if (member.hasPermission(Permission.MANAGE_SERVER) || member.hasPermission(Permission.MANAGE_CHANNEL)) {
            return true;
        }

        AudioChannel botChannel = audioService.getConnectedVoiceChannel(guild);
        if (botChannel == null) {
            // Noch spielt nichts - dann darf jeder starten, der selbst in
            // einem Sprachkanal sitzt.
            return member.getVoiceState() != null && member.getVoiceState().getChannel() != null;
        }

        AudioChannel memberChannel = member.getVoiceState() == null ? null : member.getVoiceState().getChannel();
        return memberChannel != null && memberChannel.getId().equals(botChannel.getId());
    }

    private Optional<RadioStation> findStation(String reference) {
        List<RadioStation> stations = audioService.getStations();
        String normalized = reference.trim().toLowerCase(Locale.ROOT);

        try {
            int id = Integer.parseInt(normalized);
            Optional<RadioStation> byId = stations.stream().filter(station -> station.id() == id).findFirst();
            if (byId.isPresent()) {
                return byId;
            }
        } catch (NumberFormatException ignored) {
            // Kein Zahlenwert - dann wird ueber den Namen gesucht.
        }

        return stations.stream()
                .filter(station -> station.name().toLowerCase(Locale.ROOT).equals(normalized))
                .findFirst()
                .or(() -> stations.stream()
                        .filter(station -> station.name().toLowerCase(Locale.ROOT).contains(normalized))
                        .findFirst());
    }

    private ToolResult await(CompletableFuture<String> future) {
        try {
            return ToolResult.ok(future.get(EXECUTION_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } catch (TimeoutException exception) {
            return ToolResult.error("Die Quelle antwortet zu langsam. Der Ladevorgang laeuft moeglicherweise noch weiter.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Der Aufruf wurde abgebrochen.");
        } catch (Exception exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            return ToolResult.error("Fehlgeschlagen: "
                    + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()));
        }
    }

    // ------------------------------------------------------- Schema-Bauer

    private JSONObject property(String name, String type, String description) {
        return new JSONObject().put(name, new JSONObject()
                .put("type", type)
                .put("description", description));
    }

    private JSONObject schema(boolean includeGuildParameter, JSONObject properties, String... required) {
        JSONObject allProperties = new JSONObject();
        for (String key : properties.keySet()) {
            allProperties.put(key, properties.get(key));
        }

        JSONArray requiredNames = new JSONArray();
        for (String name : required) {
            requiredNames.put(name);
        }

        if (includeGuildParameter) {
            allProperties.put("guild", new JSONObject()
                    .put("type", "string")
                    .put("description", "ID oder Name des Discord-Servers. Mit list_servers abrufbar."));
            requiredNames.put("guild");
        }

        return new JSONObject()
                .put("type", "object")
                .put("properties", allProperties)
                .put("required", requiredNames);
    }

    @FunctionalInterface
    private interface GuildAction {
        ToolResult apply(Guild guild);
    }
}
