package eckerlin.dev.audio;

import dev.arbjerg.lavalink.client.Helpers;
import dev.arbjerg.lavalink.client.LavalinkClient;
import dev.arbjerg.lavalink.client.LavalinkNode;
import dev.arbjerg.lavalink.client.Link;
import dev.arbjerg.lavalink.client.NodeOptions;
import dev.arbjerg.lavalink.client.event.TrackEndEvent;
import dev.arbjerg.lavalink.client.event.TrackExceptionEvent;
import dev.arbjerg.lavalink.client.event.TrackStuckEvent;
import dev.arbjerg.lavalink.client.event.WebSocketClosedEvent;
import dev.arbjerg.lavalink.client.player.FilterBuilder;
import dev.arbjerg.lavalink.client.player.LavalinkLoadResult;
import dev.arbjerg.lavalink.client.player.LavalinkPlayer;
import dev.arbjerg.lavalink.client.player.LoadFailed;
import dev.arbjerg.lavalink.client.player.NoMatches;
import dev.arbjerg.lavalink.client.player.PlaylistLoaded;
import dev.arbjerg.lavalink.client.player.SearchResult;
import dev.arbjerg.lavalink.client.player.Track;
import dev.arbjerg.lavalink.client.player.TrackLoaded;
import dev.arbjerg.lavalink.libraries.jda.JDAVoiceUpdateListener;
import dev.arbjerg.lavalink.protocol.v4.Filters;
import eckerlin.dev.services.AppConfigService;
import eckerlin.dev.services.DiscordLoggingService;
import eckerlin.dev.services.GuildModuleSettingsService;
import eckerlin.dev.services.LavalinkNodeSettings;
import eckerlin.dev.services.ListenerStatsService;
import eckerlin.dev.services.MusicBrainClientService;
import eckerlin.dev.services.MusicBrainRadioResponse;
import eckerlin.dev.services.MusicTrackEventService;
import eckerlin.dev.services.RadioStationService;
import eckerlin.dev.security.GuildEntitlementService;
import eckerlin.dev.security.GuildFeature;
import eckerlin.dev.security.FeatureNotEnabledException;
import eckerlin.dev.utils.Alert;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.hooks.VoiceDispatchInterceptor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class AudioService {

    private static final long RADIO_START_COOLDOWN_MS = 8_000L;
    private static final long LOAD_TIMEOUT_SECONDS = 15L;
    /**
     * Der Ausweichversuch soll das Warten nicht verdoppeln - SoundCloud
     * antwortet schnell oder gar nicht.
     */
    private static final long FALLBACK_LOAD_TIMEOUT_SECONDS = 8L;
    private static final long RADIO_LOAD_TIMEOUT_SECONDS = 35L;
    private static final int RADIO_LOAD_MAX_ATTEMPTS = 2;
    private static final long RADIO_LOAD_RETRY_DELAY_MS = 1_500L;
    private static final long RADIO_WARM_BUFFER_MS = 28_000L;
    private static final long MUSIC_FADE_DURATION_MS = 2_400L;
    /**
     * Jeder Fade-Schritt ist ein eigener PATCH an Lavalink. Zu kleine Schritte
     * erzeugen einen Request-Sturm, der sich mit {@code setTrack}-Updates ins
     * Gehege kommt und zu Aussetzern fuehrt. 400 ms sind fein genug fuer einen
     * unhoerbaren Verlauf und erzeugen nur noch sechs Requests pro Fade.
     */
    private static final long MUSIC_FADE_STEP_MS = 400L;
    /**
     * Lavalink-Volume oberhalb von 100 ist reine digitale Verstaerkung und
     * clippt das Signal. 150 ist die harte Obergrenze, alles darueber wurde
     * frueher zugelassen (200) und war der haeufigste Grund fuer verzerrten Ton.
     */
    private static final int MAX_VOLUME = 150;
    /**
     * Empfohlene Obergrenze fuer verzerrungsfreie Wiedergabe.
     */
    public static final int CLEAN_VOLUME_LIMIT = 100;
    /**
     * Pegelreserve, die der Bass-Boost zurueckgibt, damit die angehobenen
     * Tiefen nicht in die Uebersteuerung laufen.
     */
    private static final float BASS_BOOST_HEADROOM = 0.82f;
    private static final long STREAM_RESUME_FADE_MS = 1_800L;
    private static final long MUSIC_RESUME_PREROLL_MS = 2_500L;
    private static final int SMART_RADIO_PREFETCH_QUEUE_SIZE = 2;
    private static final String AI_RADIO_LOCKED_MESSAGE =
            "AI Radio ist fuer diesen Server nicht freigeschaltet. Ein Bot-Administrator kann es im Adminpanel freigeben.";
    private static final String RADIO_COOLDOWN_PREFIX = "Bitte warte noch ";
    private static final List<String> SMART_RADIO_FALLBACK_QUERIES = List.of(
            "Coldplay Adventure of a Lifetime official audio",
            "Imagine Dragons Believer official audio",
            "OneRepublic I Lived official audio",
            "Alan Walker Faded official audio",
            "Kygo Firestone official audio",
            "Robin Schulz Sugar official audio",
            "Ed Sheeran Shivers official audio",
            "Dua Lipa Houdini official audio",
            "Ava Max Kings and Queens official audio",
            "David Guetta Titanium official audio",
            "The Weeknd Blinding Lights official audio",
            "Linkin Park Burn It Down official audio"
    );
    private static final List<String> BLOCKED_SMART_RADIO_TERMS = List.of(
            "explicit",
            "uncensored",
            "nsfw",
            "18+",
            "porno",
            "porn",
            "sex",
            "fetish",
            "nazi",
            "hitler",
            "slur"
    );

    private final AppConfigService configService;
    private final RadioStationService radioStationService;
    private final DiscordLoggingService discordLoggingService;
    private final GuildModuleSettingsService settingsService;
    private final MusicTrackEventService musicTrackEventService;
    private final MusicBrainClientService musicBrainClientService;
    private final ListenerStatsService listenerStatsService;
    private final GuildEntitlementService entitlementService;
    private final ConcurrentMap<Long, GuildAudioState> guildStates = new ConcurrentHashMap<>();
    private final ScheduledExecutorService statusScheduler = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService disconnectScheduler = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService fadeScheduler = Executors.newScheduledThreadPool(4);
    private final ConcurrentMap<Long, ScheduledFuture<?>> disconnectTasks = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, ScheduledFuture<?>> fadeTasks = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, ScheduledFuture<?>> radioWarmBufferTasks = new ConcurrentHashMap<>();

    private volatile LavalinkClient lavalinkClient;
    private volatile VoiceDispatchInterceptor voiceInterceptor;
    private volatile JDA jda;

    public AudioService(
            AppConfigService configService,
            RadioStationService radioStationService,
            DiscordLoggingService discordLoggingService,
            GuildModuleSettingsService settingsService,
            MusicTrackEventService musicTrackEventService,
            MusicBrainClientService musicBrainClientService,
            ListenerStatsService listenerStatsService,
            GuildEntitlementService entitlementService
    ) {
        this.configService = configService;
        this.radioStationService = radioStationService;
        this.discordLoggingService = discordLoggingService;
        this.settingsService = settingsService;
        this.musicTrackEventService = musicTrackEventService;
        this.musicBrainClientService = musicBrainClientService;
        this.listenerStatsService = listenerStatsService;
        this.entitlementService = entitlementService;
    }

    public synchronized void initialize(String botToken) {
        if (lavalinkClient != null) {
            return;
        }

        // Der Bot arbeitet mit genau einem Lavalink-Node. Mehrere Nodes waren
        // ohne echtes Load-Balancing nur eine zusaetzliche Fehlerquelle: bei
        // Problemen war nie eindeutig, welcher Node gerade bediente.
        LavalinkNodeSettings node = configService.getLavalinkNode();
        if (node == null || !node.enabled() || node.serverUri().isBlank()) {
            throw new IllegalStateException("Es ist kein aktiver Lavalink-Node konfiguriert.");
        }

        long userId = Helpers.getUserIdFromToken(botToken);
        lavalinkClient = new LavalinkClient(userId);
        voiceInterceptor = new JDAVoiceUpdateListener(lavalinkClient);

        LavalinkNode lavalinkNode = lavalinkClient.addNode(new NodeOptions.Builder()
                .setName(node.nodeName().isBlank() ? "main-node" : node.nodeName())
                .setServerUri(URI.create(node.serverUri()))
                .setPassword(node.password())
                .setHttpTimeout(node.httpTimeoutMs())
                .build());

        if (node.resumeEnabled()) {
            lavalinkNode.enableResuming(Duration.ofSeconds(node.resumeTimeoutSeconds()))
                    .subscribe(
                            ignored -> {
                            },
                            throwable -> Alert.send("WARN", "LAVALINK", "Resuming konnte nicht aktiviert werden: " + throwable.getMessage())
                    );
        }

        lavalinkClient.on(TrackEndEvent.class).subscribe(this::handleTrackEnd);
        // Ohne diese beiden Handler bleibt der Bot bei einem haengenden Track
        // oder einem abgerissenen Voice-Websocket einfach stumm im Channel
        // stehen, bis jemand manuell /stop ausfuehrt.
        lavalinkClient.on(TrackStuckEvent.class).subscribe(this::handleTrackStuck);
        lavalinkClient.on(TrackExceptionEvent.class).subscribe(this::handleTrackException);
        lavalinkClient.on(WebSocketClosedEvent.class).subscribe(this::handleWebSocketClosed);
        Alert.send("INFO", "LAVALINK", "Lavalink-Node \"" + lavalinkNode.getName() + "\" (" + node.serverUri() + ") initialisiert.");
    }

    public void attachJda(JDA jda) {
        this.jda = jda;
    }

    /**
     * Die aktive JDA-Instanz, sofern der Bot bereits gestartet ist.
     *
     * <p>Wird von {@code AudioToolService} genutzt. Der naheliegende Weg ueber
     * {@code DiscordBotService} scheidet aus: dieser injiziert saemtliche
     * Listener, sodass ueber LlmModuleListener -> LlmService ->
     * AudioToolService ein Abhaengigkeitszyklus entstuende, den Spring beim
     * Start abweist.
     */
    public JDA getAttachedJda() {
        return jda;
    }

    public VoiceDispatchInterceptor getVoiceDispatchInterceptor() {
        if (voiceInterceptor == null) {
            throw new IllegalStateException("Lavalink wurde noch nicht initialisiert.");
        }
        return voiceInterceptor;
    }

    public List<RadioStation> getStations() {
        return radioStationService.findAllForConfiguredBot();
    }

    public CompletableFuture<String> queueTrack(Guild guild, AudioChannel channel, String query) {
        return queueTrackInternal(guild, channel, query, true);
    }

    private CompletableFuture<String> queueTrackInternal(Guild guild, AudioChannel channel, String query, boolean disableSmartRadio) {
        if (guild == null) {
            return CompletableFuture.completedFuture("Dieser Command funktioniert nur auf einem Server.");
        }
        if (channel == null) {
            return CompletableFuture.completedFuture("Kein Voice-Channel angegeben.");
        }
        if (query == null || query.isBlank()) {
            return CompletableFuture.completedFuture("Bitte gib einen Suchbegriff oder eine URL an.");
        }

        GuildAudioState state = getGuildState(guild.getIdLong());
        if (disableSmartRadio && state.smartRadioEnabled()) {
            state.clearQueue();
            state.setCurrentTrack(null);
            getLink(guild).updatePlayer(builder -> builder.stopTrack()).subscribe();
            clearConnectedVoiceChannelStatus(guild);
            state.setSmartRadioEnabled(false);
            state.setActiveRadioName("");
            state.setWaitingForListeners(false);
        }

        boolean wasConnected = isConnected(guild);
        Link link = getLink(guild);

        return loadMusicItem(link, query)
                .thenCompose(result -> handleMusicLoadResult(guild, channel, wasConnected, result))
                .exceptionally(throwable -> {
                    if (!wasConnected) {
                        disconnectFromVoice(guild);
                    }
                    return isTimeout(throwable)
                            ? "Die Quelle antwortet zu langsam. Bitte versuche es erneut."
                            : "Laden fehlgeschlagen: " + safeTitle(rootMessage(throwable));
                });
    }

    public CompletableFuture<String> startRadio(Guild guild, AudioChannel channel, int radioId) {
        if (guild == null) {
            return CompletableFuture.completedFuture("Dieser Command funktioniert nur auf einem Server.");
        }
        if (channel == null) {
            return CompletableFuture.completedFuture("Kein Voice-Channel angegeben.");
        }

        if (radioStationService.isSmartRadioStation(radioId)) {
            return startSmartRadio(guild, channel, false);
        }

        Optional<RadioStation> stationOptional = radioStationService.findByIdForConfiguredBot(radioId);
        if (stationOptional.isEmpty()) {
            return CompletableFuture.completedFuture("Kein Radiosender mit der ID `" + radioId + "` gefunden.");
        }

        RadioStation station = stationOptional.get();
        GuildAudioState state = getGuildState(guild.getIdLong());
        state.setSmartRadioEnabled(false);
        long remainingCooldownMs = state.reserveRadioStart(System.currentTimeMillis(), RADIO_START_COOLDOWN_MS);
        if (remainingCooldownMs > 0L) {
            return CompletableFuture.completedFuture(formatRadioCooldownMessage(remainingCooldownMs));
        }

        boolean wasConnected = isConnected(guild);
        connectToVoice(guild, channel);
        cancelScheduledDisconnect(guild.getIdLong());
        cancelRadioWarmBufferTask(guild.getIdLong());
        state.setWaitingForListeners(false);

        return loadRadioWithRetry(guild, channel, wasConnected, station, RADIO_LOAD_MAX_ATTEMPTS);
    }

    public PlayerState getPlayerState(Guild guild) {
        return getPlayerState(guild, null);
    }

    public PlayerState getPlayerState(Guild guild, AudioChannel userVoiceChannel) {
        GuildAudioState state = getGuildState(guild.getIdLong());
        advanceVirtualPlaybackState(guild, state, System.currentTimeMillis());
        LavalinkPlayer player = getCachedPlayer(guild);
        var currentVoiceChannel = guild.getSelfMember().getVoiceState() == null
                ? null
                : guild.getSelfMember().getVoiceState().getChannel();

        Track currentTrack = state.currentTrack();
        if (currentTrack == null && player != null) {
            currentTrack = player.getTrack();
        }

        int volume = state.volume();
        boolean paused = state.waitingForListeners() || (player != null && player.getPaused());
        int voiceBitrateKbps = currentVoiceChannel == null ? 0 : Math.max(8, currentVoiceChannel.getBitrate() / 1000);
        long positionMs = state.waitingForListeners() && state.virtualProgressActive()
                ? state.waitingTrackPositionMs()
                : player != null ? player.getPosition() : 0L;
        long radioCooldownRemainingMs = state.radioStartCooldownRemaining(System.currentTimeMillis(), RADIO_START_COOLDOWN_MS);
        boolean playingRadio = (currentTrack != null && currentTrack.getInfo().isStream())
                || state.smartRadioEnabled()
                || !state.activeRadioName().isBlank();

        return new PlayerState(
                guild.getId(),
                guild.getName(),
                currentVoiceChannel == null ? null : currentVoiceChannel.getIdLong(),
                currentVoiceChannel == null ? null : currentVoiceChannel.getName(),
                userVoiceChannel == null ? null : userVoiceChannel.getIdLong(),
                userVoiceChannel == null ? null : userVoiceChannel.getName(),
                userVoiceChannel != null,
                currentVoiceChannel != null,
                paused,
                state.waitingForListeners(),
                state.virtualProgressActive(),
                playingRadio,
                state.smartRadioEnabled(),
                state.activeRadioName(),
                state.repeatEnabled(),
                volume,
                state.bassBoostEnabled(),
                voiceBitrateKbps,
                positionMs,
                radioCooldownRemainingMs,
                currentTrack == null ? null : TrackView.from(currentTrack),
                state.snapshotQueue().stream().map(TrackView::from).toList()
        );
    }

    public boolean setRepeatEnabled(Guild guild, boolean enabled) {
        GuildAudioState state = getGuildState(guild.getIdLong());
        state.setRepeatEnabled(enabled);
        return state.repeatEnabled();
    }

    public boolean setBassBoostEnabled(Guild guild, boolean enabled) {
        GuildAudioState state = getGuildState(guild.getIdLong());
        state.setBassBoostEnabled(enabled);

        Link link = getLink(guild);
        if (state.currentTrack() != null || link.getCachedPlayer() != null) {
            link.updatePlayer(builder -> builder.setFilters(buildFilters(state)))
                    .subscribe(
                            ignored -> {
                            },
                            throwable -> Alert.send("WARN", "AUDIO", "Bass-Filter konnte nicht aktualisiert werden: " + throwable.getMessage())
                    );
        }
        return state.bassBoostEnabled();
    }

    /** Unterscheidet eine fehlende Freischaltung von einem echten Ausfall des Dienstes. */
    private boolean isFeatureLocked(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof FeatureNotEnabledException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public boolean isRadioCooldownMessage(String message) {
        return message != null && message.startsWith(RADIO_COOLDOWN_PREFIX);
    }

    private CompletableFuture<String> startSmartRadio(Guild guild, AudioChannel channel, boolean continuing) {
        GuildAudioState state = getGuildState(guild.getIdLong());

        // Die Freischaltung wird hier geprueft und nicht erst im Music-Brain-Client:
        // dort landete eine Ablehnung in derselben Fehlerbehandlung wie ein Netzwerk-
        // ausfall und wurde stillschweigend durch den Fallback-Mix ersetzt. Damit lief
        // AI Radio auf jedem Server, auch ohne Freigabe.
        if (!entitlementService.isEnabled(guild.getId(), GuildFeature.AI_RADIO)) {
            state.setSmartRadioEnabled(false);
            if (continuing) {
                // Laufende Wiedergabe: nur stumm beenden, keine Meldung in den Chat.
                return CompletableFuture.completedFuture(AI_RADIO_LOCKED_MESSAGE);
            }
            return CompletableFuture.completedFuture(AI_RADIO_LOCKED_MESSAGE);
        }

        state.setSmartRadioEnabled(true);
        state.setRepeatEnabled(false);
        state.setActiveRadioName("AI Radio");
        state.setWaitingForListeners(false);
        cancelScheduledDisconnect(guild.getIdLong());
        connectToVoice(guild, channel);

        if (!continuing) {
            state.clearQueue();
            state.setCurrentTrack(null);
            getLink(guild).updatePlayer(builder -> builder.stopTrack()).subscribe();
        }

        if (!state.beginSmartRadioLoad()) {
            return CompletableFuture.completedFuture("AI Radio wird bereits vorbereitet.");
        }

        return musicBrainClientService.requestRadio(guild.getId(), configService.getMusicBrainBatchSize())
                .exceptionally(throwable -> {
                    // Eine fehlende Freischaltung ist kein Ausfall - hier darf kein
                    // Ersatzprogramm einspringen, sonst laeuft das Feature trotz Sperre.
                    if (isFeatureLocked(throwable)) {
                        getGuildState(guild.getIdLong()).setSmartRadioEnabled(false);
                        return new MusicBrainRadioResponse(rootMessage(throwable), List.of());
                    }
                    Alert.send("WARN", "AUDIO", "AI Radio nutzt den Fallback-Mix: " + safeTitle(rootMessage(throwable)));
                    return new MusicBrainRadioResponse(
                            "AI Radio nutzt gerade den sicheren Fallback-Mix.",
                            SMART_RADIO_FALLBACK_QUERIES
                    );
                })
                .thenCompose(response -> queueSmartRadioRecommendations(guild, channel, response, continuing))
                .whenComplete((ignored, throwable) -> state.finishSmartRadioLoad());
    }

    private CompletableFuture<String> queueSmartRadioRecommendations(
            Guild guild,
            AudioChannel channel,
            MusicBrainRadioResponse response,
            boolean continuing
    ) {
        List<String> queries = response == null || response.queries() == null
                ? List.of()
                : response.queries().stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();

        if (queries.isEmpty()) {
            String reason = response == null || response.summary() == null ? "" : response.summary().trim();
            return CompletableFuture.completedFuture(reason.isBlank()
                    ? "AI Radio konnte gerade keine passenden Titel finden."
                    : reason);
        }

        CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
        for (String query : queries) {
            chain = chain.thenCompose(added -> enqueueSmartRadioQuery(guild, channel, query)
                    .thenApply(success -> success ? added + 1 : added));
        }

        return chain.thenApply(added -> {
            if (added <= 0) {
                if (getGuildState(guild.getIdLong()).currentTrack() == null) {
                    clearConnectedVoiceChannelStatus(guild);
                    disconnectFromVoice(guild);
                }
                return "AI Radio hat keine sicheren und passenden Tracks gefunden.";
            }

            String summary = response == null || response.summary() == null ? "" : response.summary().trim();
            if (continuing) {
                return summary.isBlank()
                        ? "AI Radio hat die Queue aufgefuellt."
                        : "AI Radio hat die Queue aufgefuellt. " + summary;
            }

            return summary.isBlank()
                    ? "AI Radio Clean Shuffle wurde gestartet."
                    : "AI Radio Clean Shuffle wurde gestartet. " + summary;
        });
    }

    private CompletableFuture<Boolean> enqueueSmartRadioQuery(Guild guild, AudioChannel channel, String query) {
        boolean wasConnected = isConnected(guild);

        return loadMusicItem(getLink(guild), query)
                .thenCompose(result -> {
                    Track candidate = selectSmartRadioTrack(result);
                    if (candidate == null) {
                        if (!wasConnected && getGuildState(guild.getIdLong()).currentTrack() == null) {
                            disconnectFromVoice(guild);
                        }
                        return CompletableFuture.completedFuture(false);
                    }

                    return startOrQueueMusicTrack(guild, channel, candidate)
                            .thenApply(message -> true);
                })
                .exceptionally(throwable -> false);
    }

    private Track selectSmartRadioTrack(LavalinkLoadResult result) {
        if (result instanceof TrackLoaded trackLoaded) {
            return isAllowedSmartRadioTrack(trackLoaded.getTrack()) ? trackLoaded.getTrack() : null;
        }

        if (result instanceof SearchResult searchResult) {
            return searchResult.getTracks().stream()
                    .filter(this::isAllowedSmartRadioTrack)
                    .findFirst()
                    .orElse(null);
        }

        if (result instanceof PlaylistLoaded playlistLoaded) {
            return playlistLoaded.getTracks().stream()
                    .filter(this::isAllowedSmartRadioTrack)
                    .findFirst()
                    .orElse(null);
        }

        return null;
    }

    private boolean isAllowedSmartRadioTrack(Track track) {
        if (track == null || track.getInfo() == null || track.getInfo().isStream()) {
            return false;
        }

        long durationMs = Math.max(0L, track.getInfo().getLength());
        if (durationMs < 45_000L || durationMs > TimeUnit.MINUTES.toMillis(12)) {
            return false;
        }

        String combined = (safeTitle(track.getInfo().getTitle()) + " " + safeTitle(track.getInfo().getAuthor()))
                .toLowerCase(Locale.ROOT);
        return BLOCKED_SMART_RADIO_TERMS.stream().noneMatch(combined::contains);
    }

    public String skip(Guild guild) {
        GuildAudioState state = getGuildState(guild.getIdLong());
        if (state.currentTrack() == null) {
            return "Es laeuft gerade nichts.";
        }

        Track nextTrack = state.pollNext();
        if (nextTrack == null) {
            state.setCurrentTrack(null);
            getLink(guild).updatePlayer(builder -> builder.stopTrack()).subscribe();
            clearConnectedVoiceChannelStatus(guild);
            refreshListenerStats(guild);
            if (state.smartRadioEnabled()) {
                AudioChannel channel = resolveActiveVoiceChannel(guild);
                if (channel != null) {
                    startSmartRadio(guild, channel, true);
                    return "Track uebersprungen. AI Radio sucht direkt den naechsten Titel.";
                }
            }
            scheduleDisconnectAfterInactivity(guild, "Queue beendet");
            discordLoggingService.logMusicEvent(
                    guild,
                    "Queue beendet",
                    "Die Wiedergabe wurde beendet, weil keine Tracks mehr in der Queue sind. "
                            + "Der Bot trennt sich in " + getPlaybackIdleTimeoutSeconds(guild) + " Sekunden, wenn nichts Neues startet."
            );
            return "Track uebersprungen. Die Queue ist jetzt leer. Ich trenne mich in "
                    + getPlaybackIdleTimeoutSeconds(guild) + " Sekunden, wenn nichts Neues startet.";
        }

        cancelScheduledDisconnect(guild.getIdLong());
        state.setCurrentTrack(nextTrack);
        connectToVoice(guild, resolveActiveVoiceChannel(guild));
        state.setWaitingForListeners(false);
        if (!state.smartRadioEnabled()) {
            state.setActiveRadioName("");
        }
        playTrack(guild, nextTrack, 0L, true)
                .thenAccept(player -> {
                    updateVoiceChannelStatus(guild, nextTrack);
                    scheduleVoiceChannelStatusRefreshes(guild);
                    discordLoggingService.logMusicEvent(guild, "Track uebersprungen", "Jetzt laeuft **" + safeTitle(nextTrack.getInfo().getTitle()) + "**.");
                })
                .exceptionally(throwable -> {
                    Alert.send("WARN", "AUDIO", "Voice-Status konnte nach Skip nicht gesetzt werden: " + throwable.getMessage());
                    return null;
                });
        return "Track wurde uebersprungen.";
    }

    public String stop(Guild guild) {
        cancelScheduledDisconnect(guild.getIdLong());
        cancelFadeTask(guild.getIdLong());
        cancelRadioWarmBufferTask(guild.getIdLong());
        GuildAudioState state = getGuildState(guild.getIdLong());
        state.setSmartRadioEnabled(false);
        state.setActiveRadioName("");
        state.setWaitingForListeners(false);
        state.clearQueue();
        state.setCurrentTrack(null);
        listenerStatsService.clearGuildSessions(guild);
        getLink(guild).destroy().subscribe();
        clearConnectedVoiceChannelStatus(guild);
        disconnectFromVoice(guild);
        discordLoggingService.logMusicEvent(guild, "Playback gestoppt", "Wiedergabe und Queue wurden gestoppt.");
        return "Wiedergabe gestoppt und Queue geleert.";
    }

    public String pause(Guild guild) {
        GuildAudioState state = getGuildState(guild.getIdLong());
        if (state.currentTrack() == null) {
            return "Es laeuft gerade nichts.";
        }

        state.setWaitingForListeners(false);
        pausePlayback(guild, false);
        listenerStatsService.clearGuildSessions(guild);
        return "Wiedergabe pausiert.";
    }

    public String resume(Guild guild) {
        GuildAudioState state = getGuildState(guild.getIdLong());
        if (state.currentTrack() == null) {
            return "Es laeuft gerade nichts.";
        }

        cancelScheduledDisconnect(guild.getIdLong());
        if (state.waitingForListeners()) {
            syncAudienceState(guild);
            return "Wiedergabe wird fortgesetzt.";
        }

        playTrack(guild, state.currentTrack(), currentPlaybackPosition(guild), true)
                .exceptionally(throwable -> {
                    Alert.send("WARN", "AUDIO", "Wiedergabe konnte nicht fortgesetzt werden: " + throwable.getMessage());
                    return null;
                });
        return "Wiedergabe fortgesetzt.";
    }

    public int setVolume(Guild guild, int volume) {
        GuildAudioState state = getGuildState(guild.getIdLong());
        int clampedVolume = Math.max(0, Math.min(MAX_VOLUME, volume));
        state.setVolume(clampedVolume);

        // Ein laufender Fade wuerde die manuelle Lautstaerke sonst sofort
        // wieder ueberschreiben.
        cancelFadeTask(guild.getIdLong());

        Link link = getLink(guild);
        if (state.currentTrack() != null || link.getCachedPlayer() != null) {
            link.updatePlayer(builder -> builder.setVolume(clampedVolume)).subscribe(
                    ignored -> {
                    },
                    throwable -> Alert.send("WARN", "AUDIO", "Lautstaerke konnte nicht gesetzt werden: " + throwable.getMessage())
            );
        }
        return clampedVolume;
    }

    public int getMaxVolume() {
        return MAX_VOLUME;
    }

    public String moveQueueItem(Guild guild, int fromIndex, int toIndex) {
        GuildAudioState state = getGuildState(guild.getIdLong());
        List<Track> snapshot = state.snapshotQueue();
        if (snapshot.isEmpty()) {
            return "Die Queue ist leer.";
        }
        if (fromIndex < 0 || toIndex < 0 || fromIndex >= snapshot.size() || toIndex >= snapshot.size()) {
            return "Diese Queue-Position gibt es nicht.";
        }
        if (fromIndex == toIndex) {
            return "Der Titel ist bereits an dieser Position.";
        }

        Track movedTrack = snapshot.get(fromIndex);
        if (!state.moveQueueItem(fromIndex, toIndex)) {
            return "Der Titel konnte nicht verschoben werden.";
        }

        return "**" + safeTitle(movedTrack.getInfo().getTitle()) + "** wurde auf Position " + (toIndex + 1) + " verschoben.";
    }

    public String removeQueueItem(Guild guild, int index) {
        GuildAudioState state = getGuildState(guild.getIdLong());
        List<Track> snapshot = state.snapshotQueue();
        if (snapshot.isEmpty()) {
            return "Die Queue ist leer.";
        }
        if (index < 0 || index >= snapshot.size()) {
            return "Diese Queue-Position gibt es nicht.";
        }

        Track removedTrack = snapshot.get(index);
        if (!state.removeQueueItem(index)) {
            return "Der Titel konnte nicht aus der Queue entfernt werden.";
        }

        return "**" + safeTitle(removedTrack.getInfo().getTitle()) + "** wurde aus der Queue entfernt.";
    }

    public boolean hasActivePlayback(Guild guild) {
        GuildAudioState state = getGuildState(guild.getIdLong());
        return resolveCurrentTrack(guild) != null || (state.waitingForListeners() && state.smartRadioEnabled());
    }

    public boolean isPersistentRadioPlayback(Guild guild) {
        Track currentTrack = resolveCurrentTrack(guild);
        GuildAudioState state = getGuildState(guild.getIdLong());
        return (currentTrack != null && (currentTrack.getInfo().isStream() || state.smartRadioEnabled()))
                || (!state.activeRadioName().isBlank() && state.waitingForListeners());
    }

    public void syncAudienceState(Guild guild) {
        if (guild == null) {
            return;
        }

        VoiceChannel connectedChannel = getConnectedVoiceChannel(guild);
        GuildAudioState state = getGuildState(guild.getIdLong());
        if (connectedChannel == null) {
            state.setWaitingForListeners(false);
            cancelFadeTask(guild.getIdLong());
            cancelRadioWarmBufferTask(guild.getIdLong());
            refreshListenerStats(guild);
            return;
        }

        if (hasHumanListeners(connectedChannel)) {
            if (state.waitingForListeners()) {
                resumeForListeners(guild, connectedChannel, state);
            }
            refreshListenerStats(guild);
            return;
        }

        if (state.waitingForListeners() || (!hasActivePlayback(guild) && !state.smartRadioEnabled())) {
            refreshListenerStats(guild);
            return;
        }

        pauseForEmptyAudience(guild, connectedChannel, state);
        refreshListenerStats(guild);
    }

    public VoiceChannel getConnectedVoiceChannel(Guild guild) {
        AudioChannel channel = resolveActiveVoiceChannel(guild);
        return channel instanceof VoiceChannel voiceChannel ? voiceChannel : null;
    }

    public void refreshVoiceChannelStatus(Guild guild) {
        if (guild == null) {
            return;
        }

        Track track = getGuildState(guild.getIdLong()).currentTrack();
        if (track == null) {
            clearConnectedVoiceChannelStatus(guild);
            return;
        }

        updateVoiceChannelStatus(guild, track);
    }

    /**
     * Laedt einen Titel und weicht bei Bedarf auf eine andere Quelle aus.
     *
     * <p>YouTube sperrt Anfragen aus Rechenzentren zunehmend aus. Im Log
     * taucht das als {@code AllClientsFailedException: All clients failed to
     * load the item} auf - der Titel laesst sich dann ueber keinen der
     * konfigurierten YouTube-Clients laden. Vorher endete das schlicht in
     * "Keine Treffer gefunden", obwohl der Suchbegriff korrekt war.
     *
     * <p>Schlaegt die YouTube-Suche fehl oder liefert sie nichts Abspielbares,
     * wird dieselbe Suche deshalb ueber SoundCloud wiederholt. Direkte Links
     * bleiben unberuehrt - dort waere ein Ausweichen auf eine andere Quelle
     * nicht das, was der Nutzer angefordert hat.
     */
    private CompletableFuture<LavalinkLoadResult> loadMusicItem(Link link, String query) {
        if (looksLikeUrl(query)) {
            return link.loadItem(query)
                    .toFuture()
                    .orTimeout(LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        return link.loadItem("ytsearch:" + query)
                .toFuture()
                .orTimeout(LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .handle((result, throwable) -> {
                    if (throwable == null && hasPlayableTrack(result)) {
                        return CompletableFuture.completedFuture(result);
                    }

                    Alert.send(
                            "INFO",
                            "AUDIO",
                            "YouTube lieferte kein Ergebnis fuer \"" + safeTitle(query) + "\" - weiche auf SoundCloud aus."
                    );

                    return link.loadItem("scsearch:" + query)
                            .toFuture()
                            .orTimeout(FALLBACK_LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .handle((fallbackResult, fallbackThrowable) -> {
                                if (fallbackThrowable == null && hasPlayableTrack(fallbackResult)) {
                                    return fallbackResult;
                                }

                                // Kein Treffer auf beiden Wegen: das urspruengliche
                                // Ergebnis zurueckgeben, damit die Meldung an den
                                // Nutzer den echten Grund nennt.
                                if (throwable != null) {
                                    throw new CompletionException(throwable);
                                }
                                return result;
                            });
                })
                .thenCompose(future -> future);
    }

    private boolean hasPlayableTrack(LavalinkLoadResult result) {
        if (result instanceof TrackLoaded) {
            return true;
        }
        if (result instanceof SearchResult searchResult) {
            return !searchResult.getTracks().isEmpty();
        }
        if (result instanceof PlaylistLoaded playlistLoaded) {
            return !playlistLoaded.getTracks().isEmpty();
        }
        return false;
    }

    private CompletableFuture<String> handleMusicLoadResult(
            Guild guild,
            AudioChannel channel,
            boolean wasConnected,
            LavalinkLoadResult result
    ) {
        if (result instanceof NoMatches) {
            if (!wasConnected) {
                disconnectFromVoice(guild);
            }
            return CompletableFuture.completedFuture("Keine Treffer gefunden.");
        }

        if (result instanceof LoadFailed loadFailed) {
            if (!wasConnected) {
                disconnectFromVoice(guild);
            }
            return CompletableFuture.completedFuture("Laden fehlgeschlagen: " + safeTitle(loadFailed.getException().getMessage()));
        }

        if (result instanceof TrackLoaded trackLoaded) {
            return startOrQueueMusicTrack(guild, channel, trackLoaded.getTrack());
        }

        if (result instanceof SearchResult searchResult) {
            if (searchResult.getTracks().isEmpty()) {
                if (!wasConnected) {
                    disconnectFromVoice(guild);
                }
                return CompletableFuture.completedFuture("Keine Treffer gefunden.");
            }
            return startOrQueueMusicTrack(guild, channel, searchResult.getTracks().get(0));
        }

        if (result instanceof PlaylistLoaded playlistLoaded) {
            return handlePlaylist(guild, channel, playlistLoaded);
        }

        if (!wasConnected) {
            disconnectFromVoice(guild);
        }
        return CompletableFuture.completedFuture("Unbekanntes Lavalink-Ergebnis.");
    }

    private CompletableFuture<String> handleRadioLoadResult(
            Guild guild,
            AudioChannel channel,
            boolean wasConnected,
            RadioStation station,
            LavalinkLoadResult result
    ) {
        if (result instanceof NoMatches) {
            if (!wasConnected) {
                disconnectFromVoice(guild);
            }
            return CompletableFuture.completedFuture("Radio-ID `" + station.id() + "` liefert keinen gueltigen Stream.");
        }

        if (result instanceof LoadFailed loadFailed) {
            if (!wasConnected) {
                disconnectFromVoice(guild);
            }
            return CompletableFuture.completedFuture("Radio konnte nicht geladen werden: " + safeTitle(loadFailed.getException().getMessage()));
        }

        Track track = null;
        if (result instanceof TrackLoaded trackLoaded) {
            track = trackLoaded.getTrack();
        } else if (result instanceof SearchResult searchResult && !searchResult.getTracks().isEmpty()) {
            track = searchResult.getTracks().get(0);
        } else if (result instanceof PlaylistLoaded playlistLoaded && !playlistLoaded.getTracks().isEmpty()) {
            int selectedTrackIndex = playlistLoaded.getInfo().getSelectedTrack();
            track = selectedTrackIndex >= 0 && selectedTrackIndex < playlistLoaded.getTracks().size()
                    ? playlistLoaded.getTracks().get(selectedTrackIndex)
                    : playlistLoaded.getTracks().get(0);
        }

        if (track == null) {
            if (!wasConnected) {
                disconnectFromVoice(guild);
            }
            return CompletableFuture.completedFuture("Radio konnte nicht aufgeloest werden.");
        }

        connectToVoice(guild, channel);
        cancelScheduledDisconnect(guild.getIdLong());
        GuildAudioState state = getGuildState(guild.getIdLong());
        state.clearQueue();
        Track resolvedTrack = track;
        state.setRepeatEnabled(false);
        state.setActiveRadioName(station.name());
        state.setWaitingForListeners(false);
        state.setCurrentTrack(resolvedTrack);
        cancelFadeTask(guild.getIdLong());
        cancelRadioWarmBufferTask(guild.getIdLong());

        return getLink(guild).updatePlayer(builder -> builder
                        .setTrack(resolvedTrack)
                        .setPaused(false)
                        .setVolume(state.volume())
                        .setFilters(buildFilters(state)))
                .toFuture()
                .thenApply(player -> {
                    updateVoiceChannelStatus(guild, resolvedTrack);
                    scheduleVoiceChannelStatusRefreshes(guild);
                    refreshListenerStats(guild);
                    discordLoggingService.logMusicEvent(
                            guild,
                            "Radio gestartet",
                            "Es laeuft jetzt **" + safeTitle(station.name()) + "** in " + channel.getName() + "."
                    );
                    return "Webradio gestartet: **" + safeTitle(station.name()) + "** (`" + station.id() + "`)";
                });
    }

    private CompletableFuture<String> loadRadioWithRetry(
            Guild guild,
            AudioChannel channel,
            boolean wasConnected,
            RadioStation station,
            int attemptsRemaining
    ) {
        return getLink(guild).loadItem(station.url())
                .toFuture()
                .orTimeout(RADIO_LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .thenCompose(result -> handleRadioLoadResult(guild, channel, wasConnected, station, result))
                .handle((message, throwable) -> {
                    if (throwable == null) {
                        return CompletableFuture.completedFuture(message);
                    }

                    if (isTimeout(throwable) && attemptsRemaining > 1) {
                        Alert.send(
                                "INFO",
                                "AUDIO",
                                "Radio-Start fuer " + safeTitle(station.name()) + " brauchte einen zweiten Versuch."
                        );
                        return CompletableFuture
                                .runAsync(
                                        () -> {
                                        },
                                        CompletableFuture.delayedExecutor(RADIO_LOAD_RETRY_DELAY_MS, TimeUnit.MILLISECONDS)
                                )
                                .thenCompose(ignored -> loadRadioWithRetry(guild, channel, wasConnected, station, attemptsRemaining - 1));
                    }

                    if (!wasConnected) {
                        disconnectFromVoice(guild);
                    }
                    return CompletableFuture.completedFuture(
                            isTimeout(throwable)
                                    ? "Der Radiosender antwortet zu langsam. Bitte probiere einen anderen Sender."
                                    : "Radio konnte nicht gestartet werden: " + safeTitle(rootMessage(throwable))
                    );
                })
                .thenCompose(future -> future);
    }

    private CompletableFuture<String> startOrQueueMusicTrack(Guild guild, AudioChannel channel, Track track) {
        GuildAudioState state = getGuildState(guild.getIdLong());
        Track currentTrack = state.currentTrack();
        boolean replaceRadio = currentTrack != null && currentTrack.getInfo().isStream();

        if (currentTrack == null || replaceRadio) {
            if (replaceRadio) {
                state.clearQueue();
            }
            connectToVoice(guild, channel);
            cancelScheduledDisconnect(guild.getIdLong());
            cancelRadioWarmBufferTask(guild.getIdLong());
            state.setWaitingForListeners(false);
            state.setActiveRadioName(state.smartRadioEnabled() ? "AI Radio" : "");
            state.setCurrentTrack(track);
            return playTrack(guild, track, 0L, true)
                    .thenApply(player -> {
                        updateVoiceChannelStatus(guild, track);
                        scheduleVoiceChannelStatusRefreshes(guild);
                        discordLoggingService.logMusicEvent(
                                guild,
                                replaceRadio ? "Radio beendet" : "Track gestartet",
                                "Jetzt laeuft **" + safeTitle(track.getInfo().getTitle()) + "** in " + channel.getName() + "."
                        );
                        musicTrackEventService.recordTrackStarted(
                                guild,
                                track,
                                state.smartRadioEnabled() ? "smart_radio" : "manual"
                        );
                        topUpSmartRadioQueue(guild);
                        return replaceRadio
                                ? "Radio beendet. Jetzt laeuft: **" + safeTitle(track.getInfo().getTitle()) + "**"
                                : "Jetzt laeuft: **" + safeTitle(track.getInfo().getTitle()) + "**";
                    });
        }

        state.enqueue(track.makeClone());
        musicTrackEventService.recordTrackStarted(
                guild,
                track,
                state.smartRadioEnabled() ? "smart_radio" : "manual"
        );
        return CompletableFuture.completedFuture("Zur Queue hinzugefuegt: **" + safeTitle(track.getInfo().getTitle()) + "**");
    }

    private CompletableFuture<String> handlePlaylist(Guild guild, AudioChannel channel, PlaylistLoaded playlistLoaded) {
        if (playlistLoaded.getTracks().isEmpty()) {
            return CompletableFuture.completedFuture("Keine Tracks gefunden.");
        }

        GuildAudioState state = getGuildState(guild.getIdLong());
        List<Track> tracks = playlistLoaded.getTracks();
        int selectedTrackIndex = playlistLoaded.getInfo().getSelectedTrack();
        int firstIndex = selectedTrackIndex >= 0 && selectedTrackIndex < tracks.size() ? selectedTrackIndex : 0;
        Track firstTrack = tracks.get(firstIndex).makeClone();

        Track currentTrack = state.currentTrack();
        boolean replaceRadio = currentTrack != null && currentTrack.getInfo().isStream();

        if (currentTrack == null || replaceRadio) {
            if (replaceRadio) {
                state.clearQueue();
            }

            for (int index = 0; index < tracks.size(); index++) {
                if (index == firstIndex) {
                    continue;
                }
                state.enqueue(tracks.get(index).makeClone());
            }

            connectToVoice(guild, channel);
            cancelScheduledDisconnect(guild.getIdLong());
            cancelRadioWarmBufferTask(guild.getIdLong());
            state.setWaitingForListeners(false);
            state.setActiveRadioName("");
            state.setCurrentTrack(firstTrack);
            return playTrack(guild, firstTrack, 0L, true)
                    .thenApply(player -> {
                        updateVoiceChannelStatus(guild, firstTrack);
                        scheduleVoiceChannelStatusRefreshes(guild);
                        discordLoggingService.logMusicEvent(
                                guild,
                                "Playlist gestartet",
                                "Playlist **" + safeTitle(playlistLoaded.getInfo().getName()) + "** wurde in " + channel.getName() + " gestartet."
                        );
                        musicTrackEventService.recordTrackStarted(
                                guild,
                                firstTrack,
                                state.smartRadioEnabled() ? "smart_radio" : "playlist"
                        );
                        topUpSmartRadioQueue(guild);
                        return replaceRadio
                                ? "Radio beendet. Playlist gestartet: **" + safeTitle(playlistLoaded.getInfo().getName()) + "**"
                                : "Playlist gestartet: **" + safeTitle(playlistLoaded.getInfo().getName()) + "**";
                    });
        }

        for (Track track : tracks) {
            state.enqueue(track.makeClone());
        }
        return CompletableFuture.completedFuture(tracks.size() + " Tracks zur Queue hinzugefuegt.");
    }

    private void handleTrackEnd(TrackEndEvent event) {
        if (!event.getEndReason().getMayStartNext()) {
            return;
        }

        GuildAudioState state = getGuildState(event.getGuildId());
        Track nextTrack = null;
        if (state.repeatEnabled() && event.getTrack() != null && !event.getTrack().getInfo().isStream()) {
            nextTrack = event.getTrack().makeClone();
        }
        if (nextTrack == null) {
            nextTrack = state.pollNext();
        }
        if (nextTrack == null) {
            state.setCurrentTrack(null);
            if (!state.smartRadioEnabled()) {
                state.setActiveRadioName("");
            }
            Guild guild = resolveGuild(event.getGuildId());
            if (guild != null && state.smartRadioEnabled()) {
                AudioChannel channel = resolveActiveVoiceChannel(guild);
                if (channel != null) {
                    startSmartRadio(guild, channel, true)
                            .exceptionally(throwable -> {
                                scheduleDisconnectAfterInactivity(guild, "AI Radio beendet");
                                return null;
                            });
                    return;
                }
            }
            if (guild != null) {
                clearConnectedVoiceChannelStatus(guild);
                refreshListenerStats(guild);
                scheduleDisconnectAfterInactivity(guild, "Wiedergabe beendet");
                discordLoggingService.logMusicEvent(
                        guild,
                        "Wiedergabe beendet",
                        "Die Queue ist leer. Der Bot trennt sich in "
                                + getPlaybackIdleTimeoutSeconds(guild)
                                + " Sekunden, wenn keine neue Wiedergabe startet."
                );
            }
            return;
        }

        Track resolvedNextTrack = nextTrack;
        if (!state.smartRadioEnabled()) {
            state.setActiveRadioName("");
        }
        state.setCurrentTrack(resolvedNextTrack);
        Guild guild = resolveGuild(event.getGuildId());
        if (guild != null) {
            cancelScheduledDisconnect(guild.getIdLong());
        } else {
            event.getNode().updatePlayer(event.getGuildId(), builder -> builder
                            .setTrack(resolvedNextTrack)
                            .setPaused(false)
                            .setVolume(state.volume())
                            .setFilters(buildFilters(state)))
                    .subscribe();
            return;
        }
        playTrack(guild, resolvedNextTrack, 0L, true)
                .thenAccept(ignored -> {
                    if (guild != null) {
                        updateVoiceChannelStatus(guild, resolvedNextTrack);
                        scheduleVoiceChannelStatusRefreshes(guild);
                        discordLoggingService.logMusicEvent(guild, "Naechster Track", "Jetzt laeuft **" + safeTitle(resolvedNextTrack.getInfo().getTitle()) + "**.");
                        musicTrackEventService.recordTrackStarted(
                                guild,
                                resolvedNextTrack,
                                state.smartRadioEnabled() ? "smart_radio" : "queue"
                        );
                        topUpSmartRadioQueue(guild);
                    }
                })
                .exceptionally(throwable -> {
                    Alert.send("WARN", "AUDIO", "Naechster Track konnte nicht gestartet werden: " + throwable.getMessage());
                    return null;
                });
    }

    /**
     * Ein haengender Track liefert keine Frames mehr, erzeugt aber auch kein
     * TrackEnd. Ohne Behandlung steht der Bot stumm im Channel. Bei Streams
     * wird derselbe Sender neu geladen, bei Musik geht es direkt weiter.
     */
    private void handleTrackStuck(TrackStuckEvent event) {
        Guild guild = resolveGuild(event.getGuildId());
        if (guild == null) {
            return;
        }

        GuildAudioState state = getGuildState(event.getGuildId());
        Track currentTrack = state.currentTrack();
        Alert.send("WARN", "AUDIO", "Track haengt in Guild " + event.getGuildId() + " - es wird automatisch weitergeschaltet.");

        if (currentTrack != null && currentTrack.getInfo().isStream()) {
            restartCurrentStream(guild, state, currentTrack);
            return;
        }

        advanceToNextTrackAfterFailure(guild, state, "Track haengt");
    }

    /**
     * Dekodier- oder Quellenfehler (geblockte Videos, tote Streams) fuehrten
     * bisher zum Stillstand der Queue.
     */
    private void handleTrackException(TrackExceptionEvent event) {
        Guild guild = resolveGuild(event.getGuildId());
        if (guild == null) {
            return;
        }

        GuildAudioState state = getGuildState(event.getGuildId());
        String reason = event.getException() == null || event.getException().getMessage() == null
                ? "Unbekannter Quellenfehler"
                : event.getException().getMessage();
        Alert.send("WARN", "AUDIO", "Track-Fehler in Guild " + event.getGuildId() + ": " + reason);
        advanceToNextTrackAfterFailure(guild, state, "Quelle nicht abspielbar");
    }

    /**
     * Discord invalidiert Voice-Sessions regelmaessig (Code 4006) und trennt bei
     * Regionswechseln (4014). Lavalink verliert dann die Verbindung, die
     * Wiedergabe laeuft aber im Player weiter - fuer Hoerer bricht der Ton ab.
     * Ein erneutes Connect stellt die Session wieder her.
     */
    private void handleWebSocketClosed(WebSocketClosedEvent event) {
        int code = event.getCode();
        boolean recoverable = code == 4006 || code == 4009 || code == 4014 || code == 1006;
        if (!recoverable || !event.getByRemote()) {
            return;
        }

        Guild guild = resolveGuild(event.getGuildId());
        if (guild == null) {
            return;
        }

        GuildAudioState state = getGuildState(event.getGuildId());
        if (state.currentTrack() == null) {
            return;
        }

        AudioChannel channel = resolveActiveVoiceChannel(guild);
        if (channel == null) {
            return;
        }

        Alert.send("INFO", "AUDIO", "Voice-Verbindung wurde von Discord geschlossen (Code " + code + "), verbinde neu.");
        long positionMs = currentPlaybackPosition(guild);
        Track track = state.currentTrack();
        fadeScheduler.schedule(() -> {
            connectToVoice(guild, channel);
            playTrack(guild, track, track.getInfo().isStream() ? 0L : positionMs, false)
                    .exceptionally(throwable -> {
                        Alert.send("WARN", "AUDIO", "Automatischer Reconnect ist fehlgeschlagen: " + rootMessage(throwable));
                        return null;
                    });
        }, 900L, TimeUnit.MILLISECONDS);
    }

    private void restartCurrentStream(Guild guild, GuildAudioState state, Track streamTrack) {
        playTrack(guild, streamTrack.makeClone(), 0L, false)
                .exceptionally(throwable -> {
                    Alert.send("WARN", "AUDIO", "Stream konnte nicht neu gestartet werden: " + rootMessage(throwable));
                    advanceToNextTrackAfterFailure(guild, state, "Stream nicht erreichbar");
                    return null;
                });
    }

    private void advanceToNextTrackAfterFailure(Guild guild, GuildAudioState state, String reason) {
        Track nextTrack = state.pollNext();
        if (nextTrack != null) {
            state.setCurrentTrack(nextTrack);
            cancelScheduledDisconnect(guild.getIdLong());
            playTrack(guild, nextTrack, 0L, true)
                    .thenAccept(ignored -> {
                        updateVoiceChannelStatus(guild, nextTrack);
                        discordLoggingService.logMusicEvent(
                                guild,
                                reason,
                                "Der Titel wurde uebersprungen. Jetzt laeuft **" + safeTitle(nextTrack.getInfo().getTitle()) + "**."
                        );
                    })
                    .exceptionally(throwable -> null);
            return;
        }

        state.setCurrentTrack(null);
        if (state.smartRadioEnabled()) {
            AudioChannel channel = resolveActiveVoiceChannel(guild);
            if (channel != null) {
                startSmartRadio(guild, channel, true).exceptionally(throwable -> null);
                return;
            }
        }

        clearConnectedVoiceChannelStatus(guild);
        scheduleDisconnectAfterInactivity(guild, reason);
    }

    private GuildAudioState getGuildState(long guildId) {
        return guildStates.computeIfAbsent(guildId, ignored -> new GuildAudioState());
    }

    private Link getLink(Guild guild) {
        if (lavalinkClient == null) {
            throw new IllegalStateException("Lavalink ist noch nicht initialisiert.");
        }
        return lavalinkClient.getOrCreateLink(guild.getIdLong());
    }

    private LavalinkPlayer getCachedPlayer(Guild guild) {
        Link link = lavalinkClient == null ? null : lavalinkClient.getLinkIfCached(guild.getIdLong());
        return link == null ? null : link.getCachedPlayer();
    }

    private Track resolveCurrentTrack(Guild guild) {
        GuildAudioState state = getGuildState(guild.getIdLong());
        Track currentTrack = state.currentTrack();
        if (currentTrack != null) {
            return currentTrack;
        }

        LavalinkPlayer player = getCachedPlayer(guild);
        return player == null ? null : player.getTrack();
    }

    private void connectToVoice(Guild guild, AudioChannel channel) {
        if (channel == null) {
            return;
        }
        guild.getJDA().getDirectAudioController().connect(channel);
    }

    private void disconnectFromVoice(Guild guild) {
        listenerStatsService.clearGuildSessions(guild);
        guild.getJDA().getDirectAudioController().disconnect(guild);
    }

    private void refreshListenerStats(Guild guild) {
        if (guild == null) {
            return;
        }

        GuildAudioState state = getGuildState(guild.getIdLong());
        VoiceChannel connectedChannel = getConnectedVoiceChannel(guild);
        Track currentTrack = resolveCurrentTrack(guild);
        LavalinkPlayer player = getCachedPlayer(guild);
        boolean paused = player != null && player.getPaused();
        boolean audiblePlayback = connectedChannel != null
                && currentTrack != null
                && !state.waitingForListeners()
                && !paused;

        listenerStatsService.syncAudience(guild, connectedChannel, state, currentTrack, audiblePlayback);
    }

    private boolean isConnected(Guild guild) {
        return guild.getSelfMember().getVoiceState() != null
                && guild.getSelfMember().getVoiceState().getChannel() != null;
    }

    private AudioChannel resolveActiveVoiceChannel(Guild guild) {
        if (guild.getSelfMember().getVoiceState() != null) {
            return guild.getSelfMember().getVoiceState().getChannel();
        }
        return null;
    }

    private boolean hasHumanListeners(VoiceChannel voiceChannel) {
        return voiceChannel.getMembers().stream().anyMatch(member -> !member.getUser().isBot());
    }

    private long currentPlaybackPosition(Guild guild) {
        LavalinkPlayer player = getCachedPlayer(guild);
        return player == null ? 0L : Math.max(0L, player.getPosition());
    }

    private void pauseForEmptyAudience(Guild guild, VoiceChannel connectedChannel, GuildAudioState state) {
        Track currentTrack = resolveCurrentTrack(guild);
        long nowMs = System.currentTimeMillis();
        boolean virtualProgressActive = currentTrack != null && !currentTrack.getInfo().isStream();
        long positionMs = virtualProgressActive ? currentPlaybackPosition(guild) : 0L;

        state.beginWaitingForListeners(nowMs, positionMs, virtualProgressActive);
        cancelScheduledDisconnect(guild.getIdLong());

        if (currentTrack == null) {
            return;
        }

        if (currentTrack.getInfo().isStream() && !state.smartRadioEnabled()) {
            startRadioWarmBuffer(guild, state);
            discordLoggingService.logMusicEvent(
                    guild,
                    "Radio wartet auf Horer",
                    "Im Voice-Channel **" + connectedChannel.getName() + "** ist niemand mehr. Das Radio bleibt verbunden und wird warm gepuffert."
            );
            return;
        }

        pausePlayback(guild, true);
        discordLoggingService.logMusicEvent(
                guild,
                state.smartRadioEnabled() ? "AI Radio wartet auf Horer" : "Wiedergabe pausiert",
                "Im Voice-Channel **" + connectedChannel.getName() + "** ist niemand mehr. Die Wiedergabe wird pausiert und laeuft intern weiter."
        );
    }

    private void resumeForListeners(Guild guild, AudioChannel connectedChannel, GuildAudioState state) {
        cancelRadioWarmBufferTask(guild.getIdLong());
        advanceVirtualPlaybackState(guild, state, System.currentTimeMillis());

        Track currentTrack = state.currentTrack();
        boolean smartRadio = state.smartRadioEnabled();
        state.setWaitingForListeners(false);

        if (currentTrack == null) {
            if (smartRadio) {
                startSmartRadio(guild, connectedChannel, true);
            } else {
                scheduleDisconnectAfterInactivity(guild, "Wiedergabe beendet");
            }
            return;
        }

        if (currentTrack.getInfo().isStream() && !smartRadio) {
            cancelFadeTask(guild.getIdLong());
            getLink(guild).updatePlayer(builder -> builder
                            .setPaused(false)
                            .setVolume(0)
                            .setFilters(buildFilters(state)))
                    .subscribe(
                            ignored -> {
                                startVolumeFade(guild, 0, state.volume(), STREAM_RESUME_FADE_MS, null);
                                updateVoiceChannelStatus(guild, currentTrack);
                                scheduleVoiceChannelStatusRefreshes(guild);
                            },
                            throwable -> Alert.send("WARN", "AUDIO", "Radio konnte nach Rueckkehr nicht fortgesetzt werden: " + throwable.getMessage())
                    );
            discordLoggingService.logMusicEvent(
                    guild,
                    "Radio fortgesetzt",
                    "Im Voice-Channel **" + connectedChannel.getName() + "** ist wieder jemand. Das Radio laeuft weiter."
            );
            return;
        }

        long resumePositionMs = Math.max(0L, state.waitingTrackPositionMs() - MUSIC_RESUME_PREROLL_MS);
        playTrack(guild, currentTrack, resumePositionMs, true)
                .thenAccept(ignored -> {
                    updateVoiceChannelStatus(guild, currentTrack);
                    scheduleVoiceChannelStatusRefreshes(guild);
                })
                .exceptionally(throwable -> {
                    Alert.send("WARN", "AUDIO", "Wiedergabe konnte nach Rueckkehr nicht fortgesetzt werden: " + throwable.getMessage());
                    return null;
                });
        discordLoggingService.logMusicEvent(
                guild,
                smartRadio ? "AI Radio fortgesetzt" : "Wiedergabe fortgesetzt",
                "Im Voice-Channel **" + connectedChannel.getName() + "** ist wieder jemand. Die Queue wird an der aktuellen Position fortgesetzt."
        );
    }

    private void advanceVirtualPlaybackState(Guild guild, GuildAudioState state, long nowMs) {
        if (!state.waitingForListeners() || !state.virtualProgressActive()) {
            return;
        }

        Track currentTrack = state.currentTrack();
        if (currentTrack == null) {
            if (!state.smartRadioEnabled()) {
                state.setWaitingForListeners(false);
            }
            return;
        }

        long elapsedMs = Math.max(0L, nowMs - state.waitingStartedAtMs());
        if (elapsedMs <= 0L) {
            return;
        }

        long cursorMs = Math.max(0L, state.waitingTrackPositionMs()) + elapsedMs;
        if (state.repeatEnabled()) {
            long durationMs = Math.max(1L, currentTrack.getInfo().getLength());
            state.updateWaitingProgress(nowMs, cursorMs % durationMs);
            return;
        }

        Track resolvedTrack = currentTrack;
        while (resolvedTrack != null) {
            long durationMs = Math.max(0L, resolvedTrack.getInfo().getLength());
            if (durationMs <= 0L || cursorMs < durationMs) {
                state.setCurrentTrack(resolvedTrack);
                state.updateWaitingProgress(nowMs, cursorMs);
                return;
            }

            cursorMs -= durationMs;
            Track nextTrack = state.pollNext();
            if (nextTrack == null) {
                state.setCurrentTrack(null);
                state.updateWaitingProgress(nowMs, 0L);
                if (!state.smartRadioEnabled()) {
                    state.setWaitingForListeners(false);
                    clearConnectedVoiceChannelStatus(guild);
                    scheduleDisconnectAfterInactivity(guild, "Wiedergabe beendet");
                }
                return;
            }

            resolvedTrack = nextTrack;
        }
    }

    private CompletableFuture<LavalinkPlayer> playTrack(Guild guild, Track track, long positionMs, boolean fadeIn) {
        GuildAudioState state = getGuildState(guild.getIdLong());
        cancelFadeTask(guild.getIdLong());

        int targetVolume = state.volume();
        boolean fadeAllowed = fadeIn && !track.getInfo().isStream();
        int startVolume = fadeAllowed ? 0 : targetVolume;

        return getLink(guild).updatePlayer(builder -> {
                    builder.setTrack(track)
                            .setPaused(false)
                            .setVolume(startVolume)
                            .setFilters(buildFilters(state));
                    if (!track.getInfo().isStream() && positionMs > 0L) {
                        builder.setPosition(positionMs);
                    }
                })
                .toFuture()
                .thenApply(player -> {
                    if (fadeAllowed) {
                        startVolumeFade(guild, startVolume, targetVolume, MUSIC_FADE_DURATION_MS, null);
                    }
                    refreshListenerStats(guild);
                    return player;
                });
    }

    private void pausePlayback(Guild guild, boolean listenerPause) {
        GuildAudioState state = getGuildState(guild.getIdLong());
        Track currentTrack = state.currentTrack();
        if (currentTrack == null) {
            return;
        }

        cancelRadioWarmBufferTask(guild.getIdLong());
        cancelFadeTask(guild.getIdLong());

        if (currentTrack.getInfo().isStream()) {
            getLink(guild).updatePlayer(builder -> builder.setPaused(true)).subscribe();
            return;
        }

        int startVolume = Math.max(0, state.volume());
        startVolumeFade(guild, startVolume, 0, listenerPause ? MUSIC_FADE_DURATION_MS : 1_600L, () ->
                getLink(guild).updatePlayer(builder -> builder
                                .setPaused(true)
                                .setVolume(0)
                                .setFilters(buildFilters(state)))
                        .subscribe()
        );
    }

    private void startRadioWarmBuffer(Guild guild, GuildAudioState state) {
        long guildId = guild.getIdLong();
        long warmUntilMs = System.currentTimeMillis() + RADIO_WARM_BUFFER_MS;
        state.setRadioWarmBufferUntilMs(warmUntilMs);
        cancelRadioWarmBufferTask(guildId);
        cancelFadeTask(guildId);

        getLink(guild).updatePlayer(builder -> builder
                        .setPaused(false)
                        .setVolume(0)
                        .setFilters(buildFilters(state)))
                .subscribe(
                        ignored -> {
                        },
                        throwable -> Alert.send("WARN", "AUDIO", "Radio-Warmbuffer konnte nicht aktiviert werden: " + throwable.getMessage())
                );

        radioWarmBufferTasks.put(guildId, fadeScheduler.schedule(() -> {
            radioWarmBufferTasks.remove(guildId);
            Guild currentGuild = resolveGuild(guildId);
            if (currentGuild == null) {
                return;
            }

            GuildAudioState currentState = getGuildState(guildId);
            VoiceChannel voiceChannel = getConnectedVoiceChannel(currentGuild);
            if (voiceChannel == null || hasHumanListeners(voiceChannel) || !currentState.waitingForListeners()) {
                return;
            }

            getLink(currentGuild).updatePlayer(builder -> builder.setPaused(true)).subscribe(
                    ignored -> {
                    },
                    throwable -> Alert.send("WARN", "AUDIO", "Radio konnte nach Warmbuffer nicht pausiert werden: " + throwable.getMessage())
            );
        }, RADIO_WARM_BUFFER_MS, TimeUnit.MILLISECONDS));
    }

    private void startVolumeFade(Guild guild, int fromVolume, int toVolume, long durationMs, Runnable onComplete) {
        long guildId = guild.getIdLong();
        cancelFadeTask(guildId);

        if (durationMs <= 0L || fromVolume == toVolume) {
            getLink(guild).updatePlayer(builder -> builder.setVolume(toVolume)).subscribe();
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }

        int steps = Math.max(1, (int) Math.ceil((double) durationMs / MUSIC_FADE_STEP_MS));
        int[] currentStep = {0};
        ScheduledFuture<?>[] taskHolder = new ScheduledFuture<?>[1];
        taskHolder[0] = fadeScheduler.scheduleAtFixedRate(() -> {
            try {
                currentStep[0]++;
                double progress = Math.min(1D, currentStep[0] / (double) steps);
                // Cosinus-Verlauf statt linear: der Lautstaerkeeindruck ist
                // logarithmisch, ein linearer Verlauf klingt am Anfang zu
                // schnell und am Ende zu zaeh.
                double eased = (1D - Math.cos(Math.PI * progress)) / 2D;
                int volume = (int) Math.round(fromVolume + ((toVolume - fromVolume) * eased));

                getLink(guild).updatePlayer(builder -> builder.setVolume(volume)).subscribe(
                        ignored -> {
                        },
                        throwable -> {
                        }
                );

                if (progress >= 1D) {
                    ScheduledFuture<?> task = taskHolder[0];
                    if (task != null) {
                        task.cancel(false);
                        fadeTasks.remove(guildId, task);
                    }
                    if (onComplete != null) {
                        onComplete.run();
                    }
                }
            } catch (RuntimeException exception) {
                // Ohne diesen Schutz beendet eine einzelne Exception den
                // scheduleAtFixedRate-Task dauerhaft und die Lautstaerke
                // bleibt auf dem Zwischenwert stehen - fuer Hoerer klingt das
                // wie ein kaputter Player.
                ScheduledFuture<?> task = taskHolder[0];
                if (task != null) {
                    task.cancel(false);
                    fadeTasks.remove(guildId, task);
                }
                getLink(guild).updatePlayer(builder -> builder.setVolume(toVolume)).subscribe(
                        ignored -> {
                        },
                        throwable -> {
                        }
                );
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        }, 0L, MUSIC_FADE_STEP_MS, TimeUnit.MILLISECONDS);
        fadeTasks.put(guildId, taskHolder[0]);
    }

    private void cancelFadeTask(long guildId) {
        ScheduledFuture<?> task = fadeTasks.remove(guildId);
        if (task != null) {
            task.cancel(false);
        }
    }

    private void cancelRadioWarmBufferTask(long guildId) {
        ScheduledFuture<?> task = radioWarmBufferTasks.remove(guildId);
        if (task != null) {
            task.cancel(false);
        }
    }

    private void scheduleDisconnectAfterInactivity(Guild guild, String reason) {
        if (guild == null || !isConnected(guild)) {
            return;
        }

        long guildId = guild.getIdLong();
        int delaySeconds = getPlaybackIdleTimeoutSeconds(guild);
        cancelScheduledDisconnect(guildId);
        disconnectTasks.put(guildId, disconnectScheduler.schedule(() -> {
            disconnectTasks.remove(guildId);

            Guild currentGuild = resolveGuild(guildId);
            if (currentGuild == null || !isConnected(currentGuild) || hasActivePlayback(currentGuild)) {
                return;
            }

            clearConnectedVoiceChannelStatus(currentGuild);
            disconnectFromVoice(currentGuild);
            discordLoggingService.logMusicEvent(
                    currentGuild,
                    reason,
                    "Keine aktive Wiedergabe mehr. Der Bot wurde nach " + delaySeconds + " Sekunden Inaktivitaet getrennt."
            );
        }, delaySeconds, TimeUnit.SECONDS));
    }

    private void topUpSmartRadioQueue(Guild guild) {
        if (guild == null) {
            return;
        }

        GuildAudioState state = getGuildState(guild.getIdLong());
        if (!state.smartRadioEnabled() || state.queueSize() > SMART_RADIO_PREFETCH_QUEUE_SIZE) {
            return;
        }

        AudioChannel channel = resolveActiveVoiceChannel(guild);
        if (channel == null) {
            return;
        }

        startSmartRadio(guild, channel, true).exceptionally(throwable -> null);
    }

    private void cancelScheduledDisconnect(long guildId) {
        ScheduledFuture<?> task = disconnectTasks.remove(guildId);
        if (task != null) {
            task.cancel(false);
        }
    }

    private int getPlaybackIdleTimeoutSeconds(Guild guild) {
        return settingsService.getJoinToCreateState(guild.getId()).getAudioIdleTimeoutSeconds();
    }

    private Guild resolveGuild(long guildId) {
        if (jda == null) {
            return null;
        }
        return jda.getGuildById(guildId);
    }

    private boolean looksLikeUrl(String value) {
        String lowered = value.toLowerCase();
        return lowered.startsWith("http://") || lowered.startsWith("https://");
    }

    private String safeTitle(String value) {
        if (value == null || value.isBlank()) {
            return "Unbekannt";
        }

        if (value.length() > 120) {
            return value.substring(0, 117) + "...";
        }
        return value;
    }

    private void updateVoiceChannelStatus(Guild guild, Track track) {
        if (guild == null || track == null) {
            return;
        }

        VoiceChannel voiceChannel = getConnectedVoiceChannel(guild);
        if (voiceChannel == null) {
            return;
        }

        String status = safeChannelStatus(track.getInfo().getTitle());
        if (status.isBlank()) {
            return;
        }

        try {
            voiceChannel.modifyStatus(status).queue(
                    success -> {
                    },
                    throwable -> Alert.send("WARN", "AUDIO", "Voice-Status konnte nicht gesetzt werden: " + throwable.getMessage())
            );
        } catch (RuntimeException exception) {
            Alert.send("WARN", "AUDIO", "Voice-Status wird von diesem Channel nicht unterstuetzt.");
        }
    }

    private void scheduleVoiceChannelStatusRefreshes(Guild guild) {
        if (guild == null) {
            return;
        }

        statusScheduler.schedule(() -> refreshVoiceChannelStatus(guild), 400, TimeUnit.MILLISECONDS);
        statusScheduler.schedule(() -> refreshVoiceChannelStatus(guild), 1400, TimeUnit.MILLISECONDS);
        statusScheduler.schedule(() -> refreshVoiceChannelStatus(guild), 4200, TimeUnit.MILLISECONDS);
    }

    private void clearConnectedVoiceChannelStatus(Guild guild) {
        if (guild == null) {
            return;
        }

        VoiceChannel voiceChannel = getConnectedVoiceChannel(guild);
        if (voiceChannel == null) {
            return;
        }

        try {
            voiceChannel.modifyStatus("").queue(
                    success -> {
                    },
                    throwable -> Alert.send("WARN", "AUDIO", "Voice-Status konnte nicht entfernt werden: " + throwable.getMessage())
            );
        } catch (RuntimeException ignored) {
        }
    }

    private String safeChannelStatus(String value) {
        String status = "\uD83C\uDFB5 " + safeTitle(value);
        if (status.length() > 100) {
            return status.substring(0, 100);
        }
        return status;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Baut die Lavalink-Filterkette.
     *
     * <p>Der alte Bass-Boost hob die Baender 0-4 um bis zu +0.40 an, ohne den
     * Pegel an anderer Stelle zurueckzunehmen. Bei Lavalink entspricht ein Gain
     * von 0.25 bereits einer Verdopplung der Amplitude - +0.40 uebersteuert den
     * Mix daher zuverlaessig und erzeugt genau das Knacken und Matschen, das als
     * "schlechte Audioqualitaet" auffaellt.
     *
     * <p>Die neue Kurve arbeitet mit moderatem Tiefen-Lift, einer leichten
     * Absenkung der Mitten (damit der Bass trotzdem durchkommt) und einer
     * Gesamt-Reduktion ueber {@code setVolume}, die den zusaetzlichen Pegel
     * wieder ausgleicht. Ergebnis: hoerbar mehr Bass ohne Clipping.
     */
    private Filters buildFilters(GuildAudioState state) {
        if (!state.bassBoostEnabled()) {
            return new Filters();
        }

        return new FilterBuilder()
                .setEqualizerBand(0, 0.16f)
                .setEqualizerBand(1, 0.14f)
                .setEqualizerBand(2, 0.10f)
                .setEqualizerBand(3, 0.05f)
                .setEqualizerBand(4, 0.00f)
                .setEqualizerBand(5, -0.03f)
                .setEqualizerBand(6, -0.04f)
                .setEqualizerBand(7, -0.04f)
                .setEqualizerBand(8, -0.03f)
                .setEqualizerBand(9, -0.02f)
                .setVolume(BASS_BOOST_HEADROOM)
                .build();
    }

    private String formatRadioCooldownMessage(long remainingMs) {
        long remainingSeconds = Math.max(1L, (remainingMs + 999L) / 1_000L);
        return RADIO_COOLDOWN_PREFIX + remainingSeconds + " Sekunde" + (remainingSeconds == 1L ? "" : "n")
                + ", bevor du den naechsten Radiosender startest.";
    }
}
