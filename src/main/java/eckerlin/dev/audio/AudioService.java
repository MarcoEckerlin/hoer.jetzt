package eckerlin.dev.audio;

import dev.arbjerg.lavalink.client.Helpers;
import dev.arbjerg.lavalink.client.LavalinkClient;
import dev.arbjerg.lavalink.client.LavalinkNode;
import dev.arbjerg.lavalink.client.Link;
import dev.arbjerg.lavalink.client.NodeOptions;
import dev.arbjerg.lavalink.client.event.ReadyEvent;
import dev.arbjerg.lavalink.client.event.TrackEndEvent;
import dev.arbjerg.lavalink.client.event.TrackExceptionEvent;
import dev.arbjerg.lavalink.client.event.TrackStuckEvent;
import dev.arbjerg.lavalink.client.event.WebSocketClosedEvent;
import dev.arbjerg.lavalink.client.player.FilterBuilder;
import dev.arbjerg.lavalink.client.player.LavalinkLoadResult;
import dev.arbjerg.lavalink.protocol.v4.Stats;
import dev.arbjerg.lavalink.client.player.LavalinkPlayer;
import dev.arbjerg.lavalink.client.player.LoadFailed;
import dev.arbjerg.lavalink.client.player.NoMatches;
import dev.arbjerg.lavalink.client.player.PlaylistLoaded;
import dev.arbjerg.lavalink.client.player.SearchResult;
import dev.arbjerg.lavalink.client.player.Track;
import dev.arbjerg.lavalink.client.player.TrackLoaded;
import dev.arbjerg.lavalink.libraries.jda.JDAVoiceUpdateListener;
import dev.arbjerg.lavalink.protocol.v4.Filters;
import dev.arbjerg.lavalink.protocol.v4.VoiceState;
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
import eckerlin.dev.web.dto.AudioNodeGuildView;
import eckerlin.dev.web.dto.AudioNodeUsageView;
import eckerlin.dev.web.dto.GuildStreamView;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.sharding.ShardManager;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.hooks.VoiceDispatchInterceptor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
            "AI Radio ist für diesen Server nicht freigeschaltet. Ein Bot-Administrator kann es im Adminpanel freigeben.";
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
    private final AudioTierService tierService;
    private final PlaybackStateService playbackStateService;
    private final ConcurrentMap<Long, GuildAudioState> guildStates = new ConcurrentHashMap<>();
    private final ScheduledExecutorService statusScheduler = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService disconnectScheduler = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService fadeScheduler = Executors.newScheduledThreadPool(4);
    /**
     * Wie alt eine gemerkte Position hoechstens sein darf.
     *
     * <p>Gesichert wird alle zehn Sekunden. Eine Minute Spielraum faengt auch
     * einen laengeren Abriss ab; darueber hinaus ist der Wert eine Vermutung,
     * und dann faengt man lieber vorne an als mitten im naechsten Titel.</p>
     */
    private static final long POSITION_MAX_ALTER_MS = 60_000L;
    /** Beobachtet die Knotentabelle. Siehe {@link #knotenWacheStarten()}. */
    private final ScheduledExecutorService knotenWache = Executors.newSingleThreadScheduledExecutor();
    /**
     * Mit welchen Einstellungen ein Knoten angemeldet wurde.
     *
     * <p>Der Name allein reicht als Vergleich nicht: wer im Adminbereich eine
     * falsche Adresse korrigiert oder das Passwort dreht, aendert den Namen ja
     * gerade nicht. Ohne diesen Abdruck bliebe der Knoten mit den alten Daten
     * angemeldet und der Bot muesste neu gestartet werden.
     */
    private final ConcurrentMap<String, String> knotenAbdruck = new ConcurrentHashMap<>();
    /** Resume-Dauer je Knoten, damit sie bei jeder Neuanmeldung wieder gesetzt werden kann. */
    private final ConcurrentMap<String, Long> resumeDauer = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, ScheduledFuture<?>> disconnectTasks = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, ScheduledFuture<?>> fadeTasks = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, ScheduledFuture<?>> radioWarmBufferTasks = new ConcurrentHashMap<>();

    private volatile LavalinkClient lavalinkClient;
    private volatile TierAwareLoadBalancer balancer;
    private volatile VoiceDispatchInterceptor voiceInterceptor;
    private volatile ShardManager shards;

    public AudioService(
            AppConfigService configService,
            RadioStationService radioStationService,
            DiscordLoggingService discordLoggingService,
            GuildModuleSettingsService settingsService,
            MusicTrackEventService musicTrackEventService,
            MusicBrainClientService musicBrainClientService,
            ListenerStatsService listenerStatsService,
            GuildEntitlementService entitlementService,
            AudioTierService tierService,
            PlaybackStateService playbackStateService
    ) {
        this.configService = configService;
        this.radioStationService = radioStationService;
        this.discordLoggingService = discordLoggingService;
        this.settingsService = settingsService;
        this.musicTrackEventService = musicTrackEventService;
        this.musicBrainClientService = musicBrainClientService;
        this.listenerStatsService = listenerStatsService;
        this.entitlementService = entitlementService;
        this.tierService = tierService;
        this.playbackStateService = playbackStateService;
    }

    public synchronized void initialize(String botToken) {
        if (lavalinkClient != null) {
            return;
        }

        // Frueher lief der Bot bewusst auf genau einem Node: mehrere ohne echtes
        // Load-Balancing waren nur eine Fehlerquelle, weil nie klar war, welcher
        // Node gerade bediente. Diese Auswahl gibt es jetzt - siehe
        // TierAwareLoadBalancer - und damit sind mehrere Nodes sinnvoll.
        List<LavalinkNodeSettings> nodes = configService.getDeploymentNodes().stream()
                .filter(item -> item != null && item.enabled() && !item.serverUri().isBlank())
                .toList();

        // Frische Installation: in der Datenbank steht noch kein Knoten, weil
        // ihn niemand im Adminbereich eingetragen hat. Dann gilt, was in der
        // Konfiguration steht - sonst gaebe es beim ersten Start keine Musik
        // und der Grund waere aus der Meldung nicht zu erraten.
        if (nodes.isEmpty()) {
            LavalinkNodeSettings ausDerKonfiguration = configService.getLavalinkNode();
            if (ausDerKonfiguration != null
                    && ausDerKonfiguration.serverUri() != null
                    && !ausDerKonfiguration.serverUri().isBlank()) {
                nodes = List.of(ausDerKonfiguration);
                Alert.send("INFO", "LAVALINK",
                        "Kein Knoten in der Datenbank - es gilt der Eintrag aus der Konfiguration ("
                                + ausDerKonfiguration.serverUri() + ").");
            }
        }

        if (nodes.isEmpty()) {
            throw new IllegalStateException("Es ist kein aktiver Lavalink-Node konfiguriert.");
        }

        long userId = Helpers.getUserIdFromToken(botToken);
        lavalinkClient = new LavalinkClient(userId);
        voiceInterceptor = new JDAVoiceUpdateListener(lavalinkClient);

        // Muss vor dem ersten addNode stehen: die Bibliothek fragt den Balancer
        // bereits, wenn ein Node verbindet und Links uebernommen werden.
        balancer = new TierAwareLoadBalancer(
                lavalinkClient,
                tierService::tierOf,
                tierService::tierOfNode,
                tierService::capacityOfNode,
                configService::isFreeOverflowAllowed,
                configService::getOverflowCpuThreshold,
                configService::getPremiumReserve
        );
        lavalinkClient.setLoadBalancer(balancer);

        for (LavalinkNodeSettings node : nodes) {
            knotenAnmelden(node);
        }

        // Beim Verbinden meldet der Knoten seine Session-ID und ob das Resuming
        // gegriffen hat. Beides brauchen wir: die ID fuer den naechsten Start,
        // das Ergebnis fuer die Entscheidung, ob der Zustand wiederhergestellt
        // werden muss.
        lavalinkClient.on(ReadyEvent.class).subscribe(event -> {
            playbackStateService.rememberSession(event.getNode().getName(), event.getSessionId());

            // Resuming bei JEDER Anmeldung neu setzen, nicht nur beim ersten
            // Verbinden. Nach einem Neustart des Knotens ist die Einstellung
            // dort weg - und wer sie nicht erneuert, verliert beim naechsten
            // Abriss alle Player, obwohl Resuming eingeschaltet aussieht.
            resumingSetzen(event.getNode());
            if (event.getResumed()) {
                Alert.send("INFO", "LAVALINK",
                        "Knoten " + event.getNode().getName() + " wieder aufgenommen - Wiedergabe läuft weiter.");
            }
            restoreState(event.getResumed());

            // Ein Knoten ist wieder da. Server, die waehrend seines Ausfalls
            // ausgewichen sind, gehoeren zurueck - aber erst, wenn er seine
            // ersten Statistiken gemeldet hat, sonst waehlt der Balancer im
            // Blindflug.
            statusScheduler.schedule(() -> {
                try {
                    int umgezogen = stufenAngleichen();
                    if (umgezogen > 0) {
                        Alert.send("INFO", "LAVALINK", umgezogen + " Server auf die passende Stufe zurückgezogen.");
                    }
                } catch (RuntimeException fehler) {
                    Alert.send("WARN", "LAVALINK", "Stufenabgleich fehlgeschlagen: " + fehler.getMessage());
                }
            }, 20, TimeUnit.SECONDS);
        });

        // Der Zustand wird periodisch gesichert statt bei jeder Aenderung: die
        // Abspielposition laeuft ohnehin weiter, ein ereignisbasiertes Sichern
        // haette also immer einen veralteten Wert geschrieben. Zehn Sekunden
        // sind der Kompromiss zwischen Genauigkeit und Schreiblast.
        statusScheduler.scheduleAtFixedRate(this::persistAllStates, 15, 10, TimeUnit.SECONDS);

        knotenWacheStarten();

        lavalinkClient.on(TrackEndEvent.class).subscribe(this::handleTrackEnd);
        // Ohne diese beiden Handler bleibt der Bot bei einem haengenden Track
        // oder einem abgerissenen Voice-Websocket einfach stumm im Channel
        // stehen, bis jemand manuell /stop ausfuehrt.
        lavalinkClient.on(TrackStuckEvent.class).subscribe(this::handleTrackStuck);
        lavalinkClient.on(TrackExceptionEvent.class).subscribe(this::handleTrackException);
        lavalinkClient.on(WebSocketClosedEvent.class).subscribe(this::handleWebSocketClosed);
        // Ueberbleibsel aus der Zeit mit genau einem Knoten: hier gab es eine
        // einzelne Variable. Jetzt zaehlt die Uebersicht ueber alle.
        Alert.send("INFO", "LAVALINK", nodes.size() == 1
                ? "Lavalink-Node \"" + nodes.get(0).nodeName() + "\" (" + nodes.get(0).serverUri() + ") initialisiert."
                : nodes.size() + " Lavalink-Knoten initialisiert: " + nodes.stream()
                        .map(item -> item.nodeName() + " [" + tierService.tierOfNode(item.nodeName()).key() + "]")
                        .collect(java.util.stream.Collectors.joining(", ")));
    }

    public void attachShards(ShardManager shards) {
        this.shards = shards;
    }

    /**
     * Der Shard-Verbund, sofern der Bot bereits gestartet ist.
     *
     * <p>Wird von {@code AudioToolService} genutzt. Der naheliegende Weg ueber
     * {@code DiscordBotService} scheidet aus: dieser injiziert saemtliche
     * Listener, sodass ueber LlmModuleListener -> LlmService ->
     * AudioToolService ein Abhaengigkeitszyklus entstuende, den Spring beim
     * Start abweist.
     */
    public ShardManager getAttachedShards() {
        return shards;
    }

    public VoiceDispatchInterceptor getVoiceDispatchInterceptor() {
        if (voiceInterceptor == null) {
            throw new IllegalStateException("Lavalink wurde noch nicht initialisiert.");
        }
        return voiceInterceptor;
    }

    // Hier stand ein getStations() ohne Serverbezug, das das AI-Radio
    // bedingungslos mitlieferte. Niemand rief es auf - aber der naechste
    // Aufrufer haette die Freigabe umgangen, ohne es zu merken, denn der
    // Signatur sah man das nicht an. Wer eine Senderliste braucht, nimmt
    // getStations(guildId): dort entscheidet die Freigabe des Servers.

    /** Name, unter dem sich ein Knoten anmeldet. Er ist der Schluessel fuer Stufe und Session. */
    private static String knotenName(LavalinkNodeSettings node) {
        return node.nodeName() == null || node.nodeName().isBlank()
                ? "node-" + node.id()
                : node.nodeName().trim();
    }

    /**
     * Alles, was eine Neuanmeldung noetig macht, in einer Zeile.
     *
     * <p>Bewusst ohne Stufe und Obergrenze: die liest der Balancer bei jeder
     * Auswahl frisch aus der Datenbank. Ein Wechsel von {@code free} auf
     * {@code premium} braucht also keine neue Verbindung, nur einen
     * Stufenabgleich.
     */
    private static String abdruck(LavalinkNodeSettings node) {
        return node.serverUri().trim()
                + "|" + node.password()
                + "|" + node.httpTimeoutMs()
                + "|" + node.resumeEnabled()
                + "|" + node.resumeTimeoutSeconds();
    }

    private void knotenAnmelden(LavalinkNodeSettings node) {
        String name = knotenName(node);
        knotenAbdruck.put(name, abdruck(node));

        // Die gespeicherte Session-ID ist der Unterschied zwischen "Musik
        // laeuft nach dem Update weiter" und "nach 60 Sekunden ist Stille":
        // Lavalink gibt laufende Player nur an dieselbe Session zurueck.
        String bekannteSession = playbackStateService.sessionIdOf(name);

        LavalinkNode lavalinkNode = lavalinkClient.addNode(new NodeOptions.Builder()
                .setName(name)
                .setServerUri(URI.create(node.serverUri()))
                .setPassword(node.password())
                .setHttpTimeout(node.httpTimeoutMs())
                .setSessionId(bekannteSession)
                .build());

        resumeDauer.put(name, node.resumeEnabled() ? node.resumeTimeoutSeconds() : 0L);
        resumingSetzen(lavalinkNode);

        Alert.send("INFO", "LAVALINK",
                "Knoten " + name + " (" + tierService.tierOfNode(name).key() + ") angemeldet.");
    }

    /**
     * Schaltet Resuming am Knoten ein.
     *
     * <p>Ohne Resuming gibt Lavalink laufende Player sofort auf, sobald die
     * Verbindung zum Bot abreisst - ein Netzwackler von zwei Sekunden reicht
     * dann fuer Stille auf allen Servern dieses Knotens. Mit Resuming haelt er
     * sie die eingestellte Zeit fest, und der Bot uebernimmt sie mit derselben
     * Session zurueck.</p>
     */
    private void resumingSetzen(LavalinkNode node) {
        Long sekunden = resumeDauer.get(node.getName());
        if (sekunden == null || sekunden <= 0) {
            return;
        }
        node.enableResuming(Duration.ofSeconds(sekunden)).subscribe(
                ignored -> {
                },
                throwable -> Alert.send("WARN", "LAVALINK",
                        "Resuming für " + node.getName() + " konnte nicht aktiviert werden: " + throwable.getMessage())
        );
    }

    /**
     * Gleicht die angemeldeten Knoten mit der Datenbank ab.
     *
     * <p>Knoten wurden frueher ausschliesslich beim Start eingelesen. Wer im
     * Adminbereich einen hinzufuegte, sah ihn dort stehen - der Bot kannte ihn
     * aber bis zum naechsten Neustart nicht, und die Auswahl landete weiter auf
     * dem alten. Das hier schliesst die Luecke.
     *
     * <p>Ein entfernter Knoten gibt seine laufenden Wiedergaben ab: die
     * Bibliothek verteilt sie ueber denselben Weg neu, den sie bei einem
     * Ausfall geht - mit Track, Position und Lautstaerke.
     *
     * @return Zahl der hinzugefuegten und entfernten Knoten
     */
    public synchronized int knotenNeuEinlesen() {
        if (lavalinkClient == null) {
            return 0;
        }

        List<LavalinkNodeSettings> gewuenscht = configService.getDeploymentNodes().stream()
                .filter(item -> item != null && item.enabled()
                        && item.serverUri() != null && !item.serverUri().isBlank())
                .toList();

        Set<String> sollNamen = gewuenscht.stream()
                .map(AudioService::knotenName)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> istNamen = lavalinkClient.getNodes().stream()
                .map(LavalinkNode::getName)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        int geaendert = 0;

        for (LavalinkNodeSettings node : gewuenscht) {
            String name = knotenName(node);

            if (!istNamen.contains(name)) {
                knotenAnmelden(node);
                Alert.send("INFO", "LAVALINK", "Knoten " + name + " neu dazugekommen.");
                geaendert++;
                continue;
            }

            // Gleicher Name, andere Daten: abmelden und neu anmelden. Der
            // Umweg ist noetig, weil sich Adresse und Passwort an einem
            // laufenden Knoten nicht nachtraeglich aendern lassen.
            String neu = abdruck(node);
            if (!neu.equals(knotenAbdruck.get(name))) {
                for (LavalinkNode vorhanden : List.copyOf(lavalinkClient.getNodes())) {
                    if (vorhanden.getName().equals(name)) {
                        lavalinkClient.removeNode(vorhanden);
                    }
                }
                knotenAnmelden(node);
                Alert.send("INFO", "LAVALINK",
                        "Knoten " + name + " mit geänderten Daten neu verbunden (" + node.serverUri() + ").");
                geaendert++;
            }
        }

        // Rueckwaerts ueber eine Kopie: removeNode greift in dieselbe Liste.
        for (LavalinkNode node : List.copyOf(lavalinkClient.getNodes())) {
            if (!sollNamen.contains(node.getName())) {
                lavalinkClient.removeNode(node);
                knotenAbdruck.remove(node.getName());
                Alert.send("INFO", "LAVALINK", "Knoten " + node.getName() + " abgemeldet.");
                geaendert++;
            }
        }

        if (geaendert > 0) {
            tierService.invalidate();
        }
        return geaendert;
    }

    /**
     * Trennt einen Knoten und meldet ihn sofort wieder an.
     *
     * <p>Das ist kein Neustart des Lavalink-Dienstes - dazu muesste der Bot auf
     * den fremden Host greifen, und das soll er nicht koennen. Es ist der
     * Griff, der in der Praxis dasselbe loest: ein Knoten, der zwar antwortet,
     * aber nichts mehr abspielt, oder eine haengende Session nach einem
     * Netzausfall. Die laufenden Server ziehen dabei auf andere Knoten um und
     * kommen mit dem naechsten Stufenabgleich zurueck.
     *
     * @return {@code false}, wenn kein Knoten dieses Namens eingetragen ist
     */
    public synchronized boolean knotenNeuVerbinden(String name) {
        if (lavalinkClient == null || name == null || name.isBlank()) {
            return false;
        }

        LavalinkNodeSettings einstellungen = configService.getDeploymentNodes().stream()
                .filter(item -> item != null && item.serverUri() != null && !item.serverUri().isBlank())
                .filter(item -> knotenName(item).equals(name.trim()))
                .findFirst()
                .orElse(null);
        if (einstellungen == null) {
            return false;
        }

        String kanonisch = knotenName(einstellungen);
        for (LavalinkNode vorhanden : List.copyOf(lavalinkClient.getNodes())) {
            if (vorhanden.getName().equals(kanonisch)) {
                lavalinkClient.removeNode(vorhanden);
            }
        }
        knotenAbdruck.remove(kanonisch);

        if (!einstellungen.enabled()) {
            Alert.send("INFO", "LAVALINK", "Knoten " + name + " ist abgeschaltet - nur getrennt, nicht neu verbunden.");
            return true;
        }

        knotenAnmelden(einstellungen);
        Alert.send("INFO", "LAVALINK", "Knoten " + name + " auf Anforderung neu verbunden.");

        // Wie nach einem Ausfall: erst wenn der Knoten wieder Zahlen meldet,
        // laesst sich sinnvoll zurueckziehen.
        knotenWache.schedule(() -> {
            try {
                stufenAngleichen();
            } catch (RuntimeException fehler) {
                Alert.send("WARN", "LAVALINK", "Stufenabgleich nach Neuverbinden fehlgeschlagen: " + fehler.getMessage());
            }
        }, 20, TimeUnit.SECONDS);
        return true;
    }

    /**
     * Beobachtet die Knotentabelle und meldet Aenderungen selbsttaetig an.
     *
     * <p>Bisher wurde nur beim Speichern im Adminbereich abgeglichen. Das deckt
     * den Normalfall ab, aber nicht die Faelle, die in der Praxis nerven: ein
     * Eintrag, der ueber die Datenbank oder von einer zweiten Oberflaeche kommt;
     * ein Knoten, der beim Speichern noch gar nicht lief; ein korrigierter
     * Tippfehler in der Adresse. In all diesen Faellen half bislang nur ein
     * Neustart des Bots - also eine Unterbrechung fuer alle Server, nur damit
     * ein einzelner Knoten dazukommt.
     *
     * <p>Der Abgleich selbst kostet eine kleine Abfrage. Wer ihn nicht will,
     * setzt {@code HJ_LAVALINK_WATCH_SECONDS=0}.
     */
    private void knotenWacheStarten() {
        long sekunden = configService.getLavalinkWatchSeconds();
        if (sekunden <= 0) {
            Alert.send("INFO", "LAVALINK", "Knotenwache abgeschaltet - neue Knoten kommen erst beim Speichern dazu.");
            return;
        }

        // Erst nach einer Minute anlaufen: beim Start sind die Knoten gerade
        // frisch angemeldet und verbinden sich noch.
        knotenWache.scheduleWithFixedDelay(this::knotenWacheLauf, 60, sekunden, TimeUnit.SECONDS);
        Alert.send("INFO", "LAVALINK", "Knotenwache aktiv - Abgleich alle " + sekunden + " Sekunden.");
    }

    private void knotenWacheLauf() {
        try {
            // Erst die Verbindungen, dann die Tabelle.
            //
            // Vorher lief die Wache nur weiter, wenn sich die Knotentabelle
            // geaendert hatte. Ein Knoten, der neu startet, aendert seine Zeile
            // aber nicht - er schliesst nur die Verbindung. Genau das ist
            // passiert: der Premium-Knoten war um 07:33 weg, um 07:39 laengst
            // wieder erreichbar, und dieser Prozess hatte es nie bemerkt. Jede
            // Wiedergabe endete danach mit "Node is not available".
            int wiederverbunden = totgeglaubteKnotenNeuVerbinden();

            int geaendert = knotenNeuEinlesen();
            if (geaendert == 0 && wiederverbunden == 0) {
                return;
            }
            if (geaendert > 0) {
                Alert.send("INFO", "LAVALINK",
                        geaendert + " Änderung(en) an den Knoten übernommen - ohne Neustart.");
            }

            // Ein frisch angemeldeter Knoten hat noch keine Statistiken
            // gemeldet. Vorher umzuziehen hiesse, im Blindflug zu waehlen.
            knotenWache.schedule(() -> {
                try {
                    int umgezogen = stufenAngleichen();
                    if (umgezogen > 0) {
                        Alert.send("INFO", "LAVALINK", umgezogen + " Server auf die passende Stufe gezogen.");
                    }
                } catch (RuntimeException fehler) {
                    Alert.send("WARN", "LAVALINK", "Stufenabgleich fehlgeschlagen: " + fehler.getMessage());
                }
            }, 20, TimeUnit.SECONDS);
        } catch (RuntimeException fehler) {
            // Eine unerreichbare Datenbank darf die Wache nicht beenden -
            // scheduleWithFixedDelay wuerde die Aufgabe sonst still einstellen.
            Alert.send("WARN", "LAVALINK", "Knotenabgleich fehlgeschlagen: " + fehler.getMessage());
        }
    }

    /**
     * Verbindet Knoten neu, die eingetragen und eingeschaltet sind, aber keine
     * Verbindung haben.
     *
     * <p>Die Bibliothek baut eine normal geschlossene Verbindung nicht von
     * selbst wieder auf - und ein Lavalink, das neu startet, schliesst normal.
     * Ohne diese Pruefung bleibt der Knoten bis zum naechsten Neustart des Bots
     * verschwunden, obwohl er laengst wieder antwortet.</p>
     *
     * @return wie viele Knoten neu verbunden wurden
     */
    /*
     * Das Neuverbinden raeumt auch die haengenden Server auf.
     *
     * knotenNeuVerbinden ruft removeNode - und damit wirft die Bibliothek den
     * Knoten samt seiner Links weg. Genau diese Links waren das Problem: sie
     * zeigten auf einen Knoten, den es nicht mehr gab, und jeder Abspielversuch
     * endete in "Node is not available", weil schon das Aufraeumen des Links
     * ueber den toten Knoten laufen wollte. Nach dem Wegwerfen legt der naechste
     * Zugriff den Link ueber den Balancer neu an - auf einem Knoten, der lebt.
     */
    private int totgeglaubteKnotenNeuVerbinden() {
        if (lavalinkClient == null) {
            return 0;
        }

        int wieder = 0;
        for (LavalinkNode knoten : List.copyOf(lavalinkClient.getNodes())) {
            if (knoten.getAvailable()) {
                continue;
            }
            String name = knoten.getName();
            Alert.send("WARN", "LAVALINK",
                    "Knoten " + name + " ist nicht verbunden - wird neu aufgebaut.");
            try {
                if (knotenNeuVerbinden(name)) {
                    wieder++;
                }
            } catch (RuntimeException fehler) {
                Alert.send("WARN", "LAVALINK",
                        "Knoten " + name + " liess sich nicht neu verbinden: " + fehler.getMessage());
            }
        }
        return wieder;
    }

    /**
     * Kurze Mitteilung an den Server.
     *
     * <p>Der Bot merkt sich bislang keinen Kanal je Server - die Antworten
     * haengen an der jeweiligen Interaktion. Deshalb hier der Systemkanal, und
     * sonst der erste, in den geschrieben werden darf. Scheitert auch das,
     * bleibt es beim Protokolleintrag: eine Mitteilung ist nichts, wofuer sich
     * ein Fehler lohnt.
     */
    private void meldeImChat(Guild guild, String text) {
        try {
            TextChannel ziel = guild.getSystemChannel();
            if (ziel == null || !ziel.canTalk()) {
                ziel = guild.getTextChannels().stream()
                        .filter(TextChannel::canTalk)
                        .findFirst()
                        .orElse(null);
            }
            if (ziel != null) {
                ziel.sendMessage(text).queue(ignored -> {
                }, ignored -> {
                });
            }
        } catch (RuntimeException ignored) {
            // Nicht der Rede wert.
        }
    }

    /**
     * Zieht Server auf die Knotenstufe, die ihnen zusteht.
     *
     * <p>Faellt ein Premium-Knoten aus, weicht der Bot auf Standard aus - das
     * ist gewollt, lieber schlechter platziert als still. Kommt der Knoten
     * zurueck, blieben die Server bisher dort liegen: die Zuordnung entsteht
     * beim Verbinden und haelt, bis die Wiedergabe endet. Das hier holt sie
     * zurueck.
     *
     * <p>Der Umzug laeuft wie beim Ausfall: Track, Position, Lautstaerke,
     * Pausenzustand und Filter werden auf dem Zielknoten neu gesetzt. Hoerbar
     * ist hoechstens ein kurzer Aussetzer.
     *
     * @return Zahl der umgezogenen Server
     */
    public int stufenAngleichen() {
        if (lavalinkClient == null) {
            return 0;
        }

        ShardManager jda = getAttachedShards();
        if (jda == null) {
            return 0;
        }

        // Die Lage hat sich geaendert - wer jetzt wieder ausweichen muss, darf
        // das erneut melden.
        if (balancer != null) {
            balancer.ueberlaufMeldungenZuruecksetzen();
        }

        int umgezogen = 0;
        for (Guild guild : jda.getGuilds()) {
            Link link = lavalinkClient.getLinkIfCached(guild.getIdLong());
            if (link == null) {
                continue;
            }

            LavalinkNode aktuell = link.getNode();
            NodeTier gewuenscht = tierService.tierOf(guild.getIdLong());
            if (tierService.tierOfNode(aktuell.getName()) == gewuenscht) {
                continue;
            }

            LavalinkNode ziel;
            try {
                ziel = lavalinkClient.getLoadBalancer().selectNode(null, guild.getIdLong());
            } catch (RuntimeException ausnahme) {
                continue;
            }

            if (ziel == null || ziel.getName().equals(aktuell.getName())
                    || tierService.tierOfNode(ziel.getName()) != gewuenscht) {
                continue;
            }

            if (umziehen(guild, link, ziel)) {
                umgezogen++;
            }
        }
        return umgezogen;
    }

    /**
     * Verschiebt die laufende Wiedergabe eines Servers auf einen anderen Knoten.
     *
     * <p>Die Bibliothek kann das selbst, haelt die Methode aber unter
     * Verschluss - und der Knoten eines Links laesst sich von aussen nicht
     * setzen. Deshalb der Umweg ueber Abbauen und Neuaufbauen: der Player wird
     * zerstoert, wodurch die Bibliothek auch den Link vergisst, und der naechste
     * Zugriff legt ihn ueber den Balancer neu an - auf dem richtigen Knoten.
     *
     * <p>Der Preis ist eine kurze Luecke im Ton. Die Alternative waere, den
     * Server bis zum naechsten Stopp falsch liegen zu lassen.
     */
    private boolean umziehen(Guild guild, Link link, LavalinkNode ziel) {
        LavalinkPlayer spieler = link.getCachedPlayer();
        if (spieler == null || spieler.getVoiceState() == null) {
            // Ohne bekannten Sprachzustand liesse sich nichts wieder aufbauen.
            return false;
        }

        String von = link.getNode().getName();
        VoiceState sprachzustand = spieler.getVoiceState();
        Track titel = spieler.getTrack();
        long position = spieler.getPosition();
        int lautstaerke = spieler.getVolume();
        boolean pausiert = spieler.getPaused();
        Filters filter = spieler.getFilters();
        long guildId = guild.getIdLong();

        link.destroy().subscribe(
                ignored -> {
                    Link neu = lavalinkClient.getOrCreateLink(guildId);
                    neu.createOrUpdatePlayer()
                            .setVoiceState(sprachzustand)
                            .setTrack(titel)
                            .setPosition(position)
                            .setVolume(lautstaerke)
                            .setPaused(pausiert)
                            .setFilters(filter)
                            .subscribe(
                                    fertig -> {
                                        String nach = neu.getNode().getName();
                                        Alert.send("INFO", "LAVALINK",
                                                guild.getName() + " von " + von + " auf " + nach + " umgezogen.");
                                        // Gemeldet wird nur der Wechsel, nicht das
                                        // Betreten eines Kanals: hier ist es die
                                        // Erklaerung fuer den Aussetzer, den man
                                        // gerade gehoert hat.
                                        if (tierService.tierOfNode(nach) == NodeTier.PREMIUM) {
                                            meldeImChat(guild,
                                                    "Der Premium-Knoten ist wieder da - die Wiedergabe läuft jetzt wieder darüber.");
                                        } else if (tierService.tierOfNode(von) == NodeTier.PREMIUM) {
                                            meldeImChat(guild,
                                                    "Der Premium-Knoten ist ausgefallen. Die Wiedergabe läuft vorerst über einen "
                                                    + "Standard-Knoten weiter — sobald Premium zurück ist, wird zurückgewechselt.");
                                        }
                                    },
                                    fehler -> Alert.send("WARN", "LAVALINK",
                                            "Umzug von " + guild.getName() + " auf " + ziel.getName()
                                                    + " fehlgeschlagen: " + fehler.getMessage())
                            );
                },
                fehler -> Alert.send("WARN", "LAVALINK",
                        "Umzug von " + guild.getName() + " scheiterte beim Abbauen: " + fehler.getMessage())
        );

        return true;
    }

    /**
     * Auslastung aller Knoten samt der Server, die gerade auf ihnen liegen.
     *
     * <p>Nur fuer den Adminbereich gedacht. Die Zahlen kommen direkt aus der
     * Bibliothek, es wird nichts nachgerechnet - was hier steht, ist genau das,
     * wonach der Bot seine Auswahl trifft.
     */
    public List<AudioNodeUsageView> knotenAuslastung() {
        if (lavalinkClient == null) {
            return List.of();
        }

        // Erst die Server einsammeln und nach Knotennamen buendeln, damit
        // anschliessend jeder Knoten seine eigene Liste bekommt.
        Map<String, List<AudioNodeGuildView>> proKnoten = new LinkedHashMap<>();
        ShardManager jda = getAttachedShards();
        if (jda != null) {
            for (Guild guild : jda.getGuilds()) {
                Link link = lavalinkClient.getLinkIfCached(guild.getIdLong());
                if (link == null) {
                    continue;
                }

                String knoten = link.getNode().getName();
                NodeTier gewuenscht = tierService.tierOf(guild.getIdLong());
                NodeTier tatsaechlich = tierService.tierOfNode(knoten);
                GuildAudioState zustand = guildStates.get(guild.getIdLong());
                Track laufend = zustand == null ? null : zustand.currentTrack();

                proKnoten.computeIfAbsent(knoten, ignored -> new ArrayList<>())
                        .add(new AudioNodeGuildView(
                                guild.getId(),
                                guild.getName(),
                                gewuenscht.key(),
                                gewuenscht == tatsaechlich,
                                laufend != null,
                                laufend == null ? "" : laufend.getInfo().getTitle()
                        ));
            }
        }

        List<AudioNodeUsageView> ergebnis = new ArrayList<>();
        for (LavalinkNode node : lavalinkClient.getNodes()) {
            Stats stats = node.getStats();
            ergebnis.add(new AudioNodeUsageView(
                    node.getName(),
                    node.getBaseUri(),
                    tierService.tierOfNode(node.getName()).key(),
                    node.getAvailable(),
                    tierService.capacityOfNode(node.getName()),
                    stats == null ? 0 : stats.getPlayingPlayers(),
                    stats == null ? 0 : stats.getPlayers(),
                    stats == null ? 0.0 : stats.getCpu().getSystemLoad(),
                    stats == null ? 0L : stats.getUptime() / 1000L,
                    node.getAvailable() ? node.getPenalties().calculateTotal() : -1,
                    proKnoten.getOrDefault(node.getName(), List.of())
            ));
        }
        return List.copyOf(ergebnis);
    }

    /**
     * Technische Sicht auf die Wiedergabe eines Servers - fuer das Serverpanel.
     *
     * <p>Die Zahlen kommen aus derselben Quelle wie die Auswahl des Bots. Wenn
     * jemand meldet "es ruckelt", steht hier, wo man nachsehen muss.
     */
    public GuildStreamView streamInfo(Guild guild) {
        if (lavalinkClient == null || guild == null) {
            return new GuildStreamView(false, "", "", false, 0, "", true);
        }

        Link link = lavalinkClient.getLinkIfCached(guild.getIdLong());
        NodeTier gewuenscht = tierService.tierOf(guild.getIdLong());
        if (link == null) {
            return new GuildStreamView(false, "", "", false, 0, gewuenscht.label(), true);
        }

        LavalinkNode node = link.getNode();
        NodeTier tatsaechlich = tierService.tierOfNode(node.getName());
        Stats stats = node.getStats();

        return new GuildStreamView(
                true,
                node.getName(),
                tatsaechlich.label(),
                node.getAvailable(),
                stats == null ? 0 : stats.getPlayingPlayers(),
                gewuenscht.label(),
                gewuenscht == tatsaechlich
        );
    }

    /** Name des Knotens, auf dem dieser Server gerade liegt. Leer, wenn keiner. */
    public String knotenVon(Guild guild) {
        if (lavalinkClient == null || guild == null) {
            return "";
        }
        Link link = lavalinkClient.getLinkIfCached(guild.getIdLong());
        return link == null ? "" : link.getNode().getName();
    }

    /** Stufe des Knotens, auf dem dieser Server liegt. */
    public String knotenStufeVon(Guild guild) {
        String name = knotenVon(guild);
        return name.isBlank() ? "" : tierService.tierOfNode(name).label();
    }

    /** Senderliste aus Sicht eines Servers - ohne AI-Radio, wenn es dort gesperrt ist. */
    public List<RadioStation> getStations(String guildId) {
        boolean aiRadio = guildId != null
                && entitlementService.isEnabled(guildId, GuildFeature.AI_RADIO);
        return radioStationService.findAll(guildId, aiRadio);
    }

    public CompletableFuture<String> queueTrack(Guild guild, AudioChannel channel, String query) {
        return queueTrackInternal(guild, channel, query, true);
    }

    /**
     * Sucht, ohne abzuspielen - die Vorschau in der Weboberflaeche.
     *
     * <p>Bisher gab es nur "Suchbegriff rein, erster Treffer laeuft". Bei einem
     * eindeutigen Titel geht das gut; bei "Wonderwall" landet man beim Cover
     * eines Zufallskanals und merkt es erst, wenn es spielt. Hier kommt die
     * Trefferliste zurueck, und der Nutzer waehlt.</p>
     *
     * <p>Es laeuft ueber denselben {@code loadMusicItem} wie das Abspielen -
     * mitsamt SoundCloud-Ausweichweg. Eine zweite Suchlogik waere eine zweite
     * Wahrheit: die Vorschau zeigte dann Treffer, die das Abspielen nicht
     * findet, oder umgekehrt.</p>
     *
     * @param grenze wie viele Treffer hoechstens zurueckkommen
     */
    public CompletableFuture<List<TrackView>> sucheVorschau(Guild guild, String query, int grenze) {
        if (guild == null || query == null || query.isBlank()) {
            return CompletableFuture.completedFuture(List.of());
        }

        return loadMusicItem(getLink(guild), query.trim())
                .thenApply(ergebnis -> treffer(ergebnis).stream()
                        .limit(Math.max(1, grenze))
                        .map(TrackView::from)
                        .toList())
                .exceptionally(fehler -> List.of());
    }

    /** Die Titel eines Ladeergebnisses, egal in welcher Form sie kamen. */
    private List<Track> treffer(LavalinkLoadResult ergebnis) {
        if (ergebnis instanceof TrackLoaded geladen) {
            return List.of(geladen.getTrack());
        }
        if (ergebnis instanceof SearchResult suche) {
            return suche.getTracks();
        }
        if (ergebnis instanceof PlaylistLoaded liste) {
            return liste.getTracks();
        }
        return List.of();
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

        Optional<RadioStation> stationOptional = radioStationService.findById(radioId, guild.getId());
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


    // ---------------------------------------------------------------- Zustand

    /** Wie alt ein gespeicherter Zustand hoechstens sein darf, um ihn zu uebernehmen. */
    private static final long ZUSTAND_MAX_ALTER_SEKUNDEN = 15 * 60;

    private volatile boolean zustandWiederhergestellt = false;

    /**
     * Schreibt den Zustand eines Servers weg.
     *
     * <p>Wird bei jeder Aenderung an Warteschlange oder Titel aufgerufen. Die
     * Position kommt aus dem zwischengespeicherten Player und ist damit auf
     * wenige Sekunden genau - fuer das Fortsetzen nach einem Neustart reicht
     * das, und es kostet keinen zusaetzlichen Aufruf am Knoten.</p>
     */
    /** Sichert den Zustand aller Server, bei denen gerade etwas laeuft. */
    private void persistAllStates() {
        ShardManager jda = getAttachedShards();
        if (jda == null || playbackStateService == null) {
            return;
        }
        for (Map.Entry<Long, GuildAudioState> eintrag : guildStates.entrySet()) {
            GuildAudioState state = eintrag.getValue();
            Guild guild = jda.getGuildById(eintrag.getKey());
            if (guild == null) {
                continue;
            }
            if (state.currentTrack() == null && state.queueSize() == 0) {
                playbackStateService.clear(guild.getId());
                continue;
            }
            persistState(guild);
        }
    }

    private void persistState(Guild guild) {
        if (guild == null || playbackStateService == null) {
            return;
        }
        try {
            GuildAudioState state = getGuildState(guild.getIdLong());
            Track current = state.currentTrack();
            List<Track> queue = state.snapshotQueue();

            if (current == null && queue.isEmpty()) {
                playbackStateService.clear(guild.getId());
                return;
            }

            LavalinkPlayer player = getCachedPlayer(guild);
            long position = player == null ? 0L : player.getPosition();
            // Nebenbei fuer den Notfall merken: reisst die Verbindung ab, ist
            // der Wert im Player weg, und ohne ihn faengt der Titel von vorne an.
            state.merkePosition(position);

            VoiceChannel channel = guild.getSelfMember().getVoiceState() == null
                    ? null
                    : (guild.getSelfMember().getVoiceState().getChannel() instanceof VoiceChannel vc ? vc : null);

            List<String> kodiert = new ArrayList<>();
            for (Track track : queue) {
                if (track != null && track.getEncoded() != null) {
                    kodiert.add(track.getEncoded());
                }
            }

            playbackStateService.save(new PlaybackStateService.Snapshot(
                    guild.getId(),
                    channel == null ? null : channel.getId(),
                    current == null ? null : current.getEncoded(),
                    position,
                    kodiert,
                    state.volume(),
                    state.repeatEnabled(),
                    state.bassBoostEnabled(),
                    state.smartRadioEnabled(),
                    state.activeRadioName(),
                    System.currentTimeMillis()
            ));
        } catch (RuntimeException exception) {
            // Das Wegschreiben darf die Wiedergabe nie stoeren.
            Alert.send("WARN", "AUDIO", "Zustand konnte nicht gesichert werden: " + exception.getMessage());
        }
    }

    /**
     * Holt die gespeicherten Zustaende zurueck in den Arbeitsspeicher.
     *
     * <p>Laeuft genau einmal je Start und nur, wenn eine JDA-Instanz da ist.
     * Bei einem gelungenen Resume spielt Lavalink bereits weiter - dann fehlt
     * dem Bot nur sein eigenes Wissen: Warteschlange, Wiederholung, Radio.
     * Ohne Resume bleibt die Warteschlange erhalten, aber es wird nichts von
     * selbst gestartet: nach einem laengeren Ausfall waere das eine
     * Ueberraschung im Sprachkanal.</p>
     */
    private synchronized void restoreState(boolean resumed) {
        if (zustandWiederhergestellt || playbackStateService == null) {
            return;
        }
        ShardManager jda = getAttachedShards();
        if (jda == null) {
            return;
        }
        zustandWiederhergestellt = true;

        Map<String, PlaybackStateService.Snapshot> zustaende =
                playbackStateService.loadRecent(ZUSTAND_MAX_ALTER_SEKUNDEN);
        if (zustaende.isEmpty()) {
            return;
        }

        for (PlaybackStateService.Snapshot snapshot : zustaende.values()) {
            Guild guild = jda.getGuildById(snapshot.guildId());
            if (guild == null) {
                continue;
            }
            GuildAudioState state = getGuildState(guild.getIdLong());
            state.setVolume(snapshot.volume());
            state.setRepeatEnabled(snapshot.repeatEnabled());
            state.setBassBoostEnabled(snapshot.bassBoostEnabled());
            state.setSmartRadioEnabled(snapshot.smartRadioEnabled());
            state.setActiveRadioName(snapshot.radioName());

            // Ohne Resume ist der laufende Titel verloren - er kommt deshalb
            // wieder an den Anfang der Warteschlange, statt still zu verfallen.
            List<String> zuLaden = new ArrayList<>();
            if (!resumed && snapshot.currentEncoded() != null) {
                zuLaden.add(snapshot.currentEncoded());
            }
            zuLaden.addAll(snapshot.queueEncoded());
            if (zuLaden.isEmpty()) {
                continue;
            }

            decodeAll(guild, zuLaden);
        }

        Alert.send("INFO", "AUDIO",
                zustaende.size() + " Wiedergabezustände wiederhergestellt (Resume: " + resumed + ").");
    }

    /** Wandelt gespeicherte Zeichenketten wieder in Tracks - der Reihe nach. */
    private void decodeAll(Guild guild, List<String> kodiert) {
        GuildAudioState state = getGuildState(guild.getIdLong());
        LavalinkNode node;
        try {
            node = getLink(guild).getNode();
        } catch (RuntimeException exception) {
            return;
        }

        List<Track> wieder = new ArrayList<>();
        for (String eintrag : kodiert) {
            try {
                Track track = node.decodeTrack(eintrag).block(Duration.ofSeconds(5));
                if (track != null) {
                    wieder.add(track);
                }
            } catch (RuntimeException exception) {
                // Einzelne nicht mehr dekodierbare Titel ueberspringen, statt
                // die ganze Warteschlange zu verlieren.
            }
        }
        if (!wieder.isEmpty()) {
            state.enqueueAll(wieder);
        }
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
                        ? "AI Radio hat die Queue aufgefüllt."
                        : "AI Radio hat die Queue aufgefüllt. " + summary;
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
            return "Es läuft gerade nichts.";
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
                    return "Übersprungen — AI-Radio sucht den nächsten Titel.";
                }
            }
            scheduleDisconnectAfterInactivity(guild, "Queue beendet");
            discordLoggingService.logMusicEvent(
                    guild,
                    "Queue beendet",
                    "Die Wiedergabe wurde beendet, weil keine Tracks mehr in der Queue sind. "
                            + "Der Bot trennt sich in " + getPlaybackIdleTimeoutSeconds(guild) + " Sekunden, wenn nichts Neues startet."
            );
            return "Übersprungen. Warteschlange leer — ich trenne mich in "
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
                    discordLoggingService.logMusicEvent(guild, "Track übersprungen", "Jetzt läuft **" + safeTitle(nextTrack.getInfo().getTitle()) + "**.");
                })
                .exceptionally(throwable -> {
                    Alert.send("WARN", "AUDIO", "Voice-Status konnte nach Skip nicht gesetzt werden: " + throwable.getMessage());
                    return null;
                });
        return "Übersprungen.";
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

        // Die Sitzungsnachricht verliert ihre Knoepfe und bleibt als Notiz
        // stehen. Sie zu loeschen waere unhoeflich - sie ist der Verlauf.
        AudioControlMessageBuilder.beendeSitzung(guild, "Gestoppt, Warteschlange geleert.");
        return "Wiedergabe gestoppt und Queue geleert.";
    }

    public String pause(Guild guild) {
        GuildAudioState state = getGuildState(guild.getIdLong());
        if (state.currentTrack() == null) {
            return "Es läuft gerade nichts.";
        }

        state.setWaitingForListeners(false);
        pausePlayback(guild, false);
        listenerStatsService.clearGuildSessions(guild);
        return "Wiedergabe pausiert.";
    }

    public String resume(Guild guild) {
        GuildAudioState state = getGuildState(guild.getIdLong());
        if (state.currentTrack() == null) {
            return "Es läuft gerade nichts.";
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
                    throwable -> Alert.send("WARN", "AUDIO", "Lautstärke konnte nicht gesetzt werden: " + throwable.getMessage())
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
                            "YouTube lieferte kein Ergebnis für \"" + safeTitle(query) + "\" - weiche auf SoundCloud aus."
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
            return CompletableFuture.completedFuture("Radio-ID `" + station.id() + "` liefert keinen gültigen Stream.");
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
            return CompletableFuture.completedFuture("Radio konnte nicht aufgelöst werden.");
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
                            "Es läuft jetzt **" + safeTitle(station.name()) + "** in " + channel.getName() + "."
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
                                "Radio-Start für " + safeTitle(station.name()) + " brauchte einen zweiten Versuch."
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
                                "Jetzt läuft **" + safeTitle(track.getInfo().getTitle()) + "** in " + channel.getName() + "."
                        );
                        musicTrackEventService.recordTrackStarted(
                                guild,
                                track,
                                state.smartRadioEnabled() ? "smart_radio" : "manual"
                        );
                        topUpSmartRadioQueue(guild);
                        return replaceRadio
                                ? "Radio beendet. Jetzt läuft: **" + safeTitle(track.getInfo().getTitle()) + "**"
                                : "Jetzt läuft: **" + safeTitle(track.getInfo().getTitle()) + "**";
                    });
        }

        state.enqueue(track.makeClone());
        musicTrackEventService.recordTrackStarted(
                guild,
                track,
                state.smartRadioEnabled() ? "smart_radio" : "manual"
        );
        return CompletableFuture.completedFuture("Zur Queue hinzugefügt: **" + safeTitle(track.getInfo().getTitle()) + "**");
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
        return CompletableFuture.completedFuture(tracks.size() + " Tracks zur Queue hinzugefügt.");
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
                        discordLoggingService.logMusicEvent(guild, "Nächster Track", "Jetzt läuft **" + safeTitle(resolvedNextTrack.getInfo().getTitle()) + "**.");
                        musicTrackEventService.recordTrackStarted(
                                guild,
                                resolvedNextTrack,
                                state.smartRadioEnabled() ? "smart_radio" : "queue"
                        );
                        topUpSmartRadioQueue(guild);
                    }
                })
                .exceptionally(throwable -> {
                    Alert.send("WARN", "AUDIO", "Nächster Track konnte nicht gestartet werden: " + throwable.getMessage());
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
        Alert.send("WARN", "AUDIO", "Track hängt in Guild " + event.getGuildId() + " - es wird automatisch weitergeschaltet.");

        if (currentTrack != null && currentTrack.getInfo().isStream()) {
            restartCurrentStream(guild, state, currentTrack);
            return;
        }

        advanceToNextTrackAfterFailure(guild, state, "Track hängt");
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
        // Der Knotenname gehoert dazu.
        //
        // Ohne ihn stand in jeder Meldung nur "Track-Fehler in Guild X". Dass
        // saemtliche Fehler von genau einem Knoten kamen - dessen Cipher-Dienst
        // nicht aufloesbar war -, liess sich daraus nicht erkennen; es sah nach
        // einem YouTube-Problem aller Knoten aus.
        String knoten = event.getNode() == null ? "?" : event.getNode().getName();
        Alert.send("WARN", "AUDIO",
                "Track-Fehler in Guild " + event.getGuildId() + " auf Knoten " + knoten + ": " + reason);

        // Der haeufigste Fall zuerst: YouTube laesst den Titel nicht laufen.
        // Dann lohnt sich SoundCloud, bevor der Titel uebersprungen wird.
        if (istYoutubeSperre(reason, event.getTrack()) && weicheAufSoundCloudAus(guild, state, event.getTrack())) {
            return;
        }

        advanceToNextTrackAfterFailure(guild, state, "Quelle nicht abspielbar");
    }

    /**
     * Erkennt YouTubes Bot-Abwehr.
     *
     * <p>Sie kommt <em>nicht</em> beim Laden, sondern erst beim Abspielen: die
     * Suche liefert Treffer, der Titel wird in die Warteschlange gelegt, der
     * Bot betritt den Kanal, setzt den Kanalstatus, antwortet im Chat - und
     * dann meldet Lavalink {@code AllClientsFailedException: This video
     * requires login}. Fuer den Hoerer sieht das aus, als tue der Bot alles
     * richtig und bleibe nur stumm.</p>
     *
     * <p>Der Ausweichweg in {@code loadMusicItem} greift hier nicht: der lief
     * schon durch und war erfolgreich. Deshalb dieselbe Entscheidung noch
     * einmal an der Stelle, an der es tatsaechlich scheitert.</p>
     */
    private boolean istYoutubeSperre(String meldung, Track track) {
        if (track == null || track.getInfo() == null) {
            return false;
        }
        String quelle = track.getInfo().getSourceName() == null
                ? "" : track.getInfo().getSourceName().toLowerCase(java.util.Locale.ROOT);
        if (!quelle.contains("youtube")) {
            return false;
        }

        String text = meldung == null ? "" : meldung.toLowerCase(java.util.Locale.ROOT);
        return text.contains("requires login")
                || text.contains("all clients failed")
                || text.contains("sign in")
                || text.contains("not a bot")
                || text.contains("playability")
                // Der generische Abbruch mitten im Lied. Lavalink nennt keinen
                // Grund, gemessen steckte dahinter aber regelmaessig derselbe
                // YouTube-Weg. Bei einem YouTube-Titel ist SoundCloud die
                // bessere Antwort als Stille - schlimmstenfalls scheitert auch
                // das, und wir landen wieder beim Ueberspringen.
                || text.contains("something broke when playing")
                // Der Entschluesselungsdienst des Knotens ist nicht erreichbar.
                // Kein YouTube-Problem, sondern ein kaputter Knoten - fuer den
                // Hoerer aber dasselbe, und SoundCloud laeuft trotzdem.
                || text.contains("unknownhostexception")
                || text.contains("name or service not known");
    }

    /**
     * Denselben Titel ueber SoundCloud holen und sofort abspielen.
     *
     * <p>Gesucht wird nach "Interpret Titel", nicht nach der YouTube-Adresse -
     * die kennt SoundCloud naturgemaess nicht. Das ist ein Naeherungstreffer;
     * bei einem Live-Mitschnitt oder einem Remix kann etwas anderes kommen als
     * gemeint. Immer noch besser als Stille, und die Meldung im Chat sagt, was
     * passiert ist.</p>
     *
     * <p>Der Ausweichweg greift genau einmal je Titel: das Ergebnis kommt von
     * SoundCloud und kann nicht erneut an YouTube scheitern - stolpert es
     * trotzdem, laeuft der normale Weg (ueberspringen), keine Schleife.</p>
     */
    private boolean weicheAufSoundCloudAus(Guild guild, GuildAudioState state, Track gescheitert) {
        String titel = gescheitert.getInfo().getTitle() == null ? "" : gescheitert.getInfo().getTitle();
        String interpret = gescheitert.getInfo().getAuthor() == null ? "" : gescheitert.getInfo().getAuthor();
        String suche = (interpret + " " + titel).trim();
        if (suche.isBlank()) {
            return false;
        }

        Alert.send("INFO", "AUDIO",
                "YouTube verweigert \"" + safeTitle(titel) + "\" (Anmeldung verlangt) - weiche auf SoundCloud aus.");

        getLink(guild).loadItem("scsearch:" + suche)
                .toFuture()
                .orTimeout(FALLBACK_LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .thenAccept(ergebnis -> {
                    Track ersatz = treffer(ergebnis).stream().findFirst().orElse(null);
                    if (ersatz == null) {
                        advanceToNextTrackAfterFailure(guild, state, "Quelle nicht abspielbar");
                        return;
                    }

                    state.setCurrentTrack(ersatz);
                    cancelScheduledDisconnect(guild.getIdLong());
                    playTrack(guild, ersatz, 0L, true)
                            .thenAccept(ignoriert -> {
                                updateVoiceChannelStatus(guild, ersatz);
                                discordLoggingService.logMusicEvent(
                                        guild,
                                        "Quelle gewechselt",
                                        "YouTube gab **" + safeTitle(titel) + "** nicht her. Läuft jetzt über "
                                                + "SoundCloud: **" + safeTitle(ersatz.getInfo().getTitle()) + "**."
                                );
                            })
                            .exceptionally(fehler -> {
                                advanceToNextTrackAfterFailure(guild, state, "Quelle nicht abspielbar");
                                return null;
                            });
                })
                .exceptionally(fehler -> {
                    advanceToNextTrackAfterFailure(guild, state, "Quelle nicht abspielbar");
                    return null;
                });

        return true;
    }

    /**
     * Discord invalidiert Voice-Sessions regelmaessig (Code 4006) und trennt bei
     * Regionswechseln (4014). Lavalink verliert dann die Verbindung, die
     * Wiedergabe laeuft aber im Player weiter - fuer Hoerer bricht der Ton ab.
     * Ein erneutes Connect stellt die Session wieder her.
     */
    private void handleWebSocketClosed(WebSocketClosedEvent event) {
        int code = event.getCode();

        // 4014 hiess bisher "wieder verbinden". Das ist falsch: der Code sagt,
        // dass der Bot aus dem Kanal geworfen oder der Kanal geloescht wurde.
        // Sich dagegen zurueckzuverbinden ist bestenfalls aufdringlich - und
        // scheitert bei einem geloeschten Kanal ohnehin in einer Schleife.
        if (code == 4014) {
            Guild raus = resolveGuild(event.getGuildId());
            if (raus != null) {
                getGuildState(event.getGuildId()).vergissPosition();
                Alert.send("INFO", "AUDIO", "Aus dem Sprachkanal entfernt (Code 4014) - kein Reconnect.");
            }
            return;
        }

        boolean recoverable = code == 4006 || code == 4009 || code == 1006;
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

        // Eine flatternde Verbindung schickt diese Ereignisse in Serie. Ohne
        // Sperre startete jedes einen eigenen Reconnect - und jeder davon den
        // Titel neu. Das war der sichtbare Teil von "Titel setzt sich zurueck".
        if (!state.beginneReconnect()) {
            return;
        }

        Alert.send("INFO", "AUDIO", "Voice-Verbindung wurde von Discord geschlossen (Code " + code + "), verbinde neu.");
        long positionMs = currentPlaybackPosition(guild);
        Track track = state.currentTrack();
        fadeScheduler.schedule(() -> {
            try {
                connectToVoice(guild, channel);
                playTrack(guild, track, track.getInfo().isStream() ? 0L : positionMs, false)
                        .whenComplete((ergebnis, throwable) -> {
                            state.beendeReconnect();
                            if (throwable != null) {
                                Alert.send("WARN", "AUDIO",
                                        "Automatischer Reconnect ist fehlgeschlagen: " + rootMessage(throwable));
                            }
                        });
            } catch (RuntimeException fehler) {
                state.beendeReconnect();
                Alert.send("WARN", "AUDIO", "Reconnect abgebrochen: " + fehler.getMessage());
            }
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
                                "Übersprungen. Jetzt läuft **" + safeTitle(nextTrack.getInfo().getTitle()) + "**."
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

    /**
     * Die Abspielposition - notfalls aus der Erinnerung.
     *
     * <p>Nach einem Verbindungsabriss meldet der Player 0, obwohl der Titel
     * schon zwei Minuten lief. Diese 0 an playTrack weiterzureichen hiess:
     * Titel faengt von vorne an. Deshalb gewinnt der groessere der beiden
     * Werte, und der gemerkte wird um die verstrichene Zeit fortgeschrieben.</p>
     */
    private long currentPlaybackPosition(Guild guild) {
        LavalinkPlayer player = getCachedPlayer(guild);
        long ausPlayer = player == null ? 0L : Math.max(0L, player.getPosition());
        long gemerkt = getGuildState(guild.getIdLong()).letztePosition(POSITION_MAX_ALTER_MS);
        return Math.max(ausPlayer, gemerkt);
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
                "Im Voice-Channel **" + connectedChannel.getName() + "** ist niemand mehr. Die Wiedergabe wird pausiert und läuft intern weiter."
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
                            throwable -> Alert.send("WARN", "AUDIO", "Radio konnte nach Rückkehr nicht fortgesetzt werden: " + throwable.getMessage())
                    );
            discordLoggingService.logMusicEvent(
                    guild,
                    "Radio fortgesetzt",
                    "Im Voice-Channel **" + connectedChannel.getName() + "** ist wieder jemand. Das Radio läuft weiter."
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
                    Alert.send("WARN", "AUDIO", "Wiedergabe konnte nach Rückkehr nicht fortgesetzt werden: " + throwable.getMessage());
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
                    "Keine aktive Wiedergabe mehr. Der Bot wurde nach " + delaySeconds + " Sekunden Inaktivität getrennt."
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
        if (shards == null) {
            return null;
        }
        return shards.getGuildById(guildId);
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
            Alert.send("WARN", "AUDIO", "Voice-Status wird von diesem Channel nicht unterstützt.");
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
     * "schlechte Audioqualität" auffaellt.
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
                + ", bevor du den nächsten Radiosender startest.";
    }
}
