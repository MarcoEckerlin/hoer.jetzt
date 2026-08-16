package eckerlin.dev.services;

import net.dv8tion.jda.api.sharding.ShardManager;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class BotPresenceService {

    private final AppConfigService configService;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicInteger rotationIndex = new AtomicInteger();

    private volatile ShardManager shards;
    private volatile boolean started;

    public BotPresenceService(AppConfigService configService) {
        this.configService = configService;
    }

    public synchronized void attachShards(ShardManager shards) {
        this.shards = shards;
        refreshNow();

        if (started) {
            return;
        }

        started = true;
        scheduler.scheduleAtFixedRate(this::refreshQuietly, 5, 5, TimeUnit.MINUTES);
    }

    public void refreshNow() {
        applyPresence();
    }

    private void refreshQuietly() {
        try {
            applyPresence();
        } catch (RuntimeException ignored) {
        }
    }

    private void applyPresence() {
        ShardManager verbund = shards;
        if (verbund == null) {
            return;
        }

        String activityText = resolveActivityText();
        // Der Verbund setzt den Status auf allen Shards. Wuerde man ihn nur
        // auf einem setzen, zeigten Server je nach Shard verschiedene Zustaende.
        verbund.setPresence(
                parseStatus(configService.getBotStatus()),
                activityText.isBlank() ? null : Activity.playing(activityText)
        );
    }

    private String resolveActivityText() {
        List<String> activities = configService.getBotActivityRotation();
        if (activities.isEmpty()) {
            return configService.getBotActivity();
        }

        int index = Math.floorMod(rotationIndex.getAndIncrement(), activities.size());
        return activities.get(index);
    }

    private OnlineStatus parseStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return OnlineStatus.ONLINE;
        }

        String normalized = rawStatus.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "DO_NOT_DISTURB", "DND" -> OnlineStatus.DO_NOT_DISTURB;
            case "IDLE", "AWAY" -> OnlineStatus.IDLE;
            case "INVISIBLE", "OFFLINE" -> OnlineStatus.INVISIBLE;
            case "ONLINE" -> OnlineStatus.ONLINE;
            default -> OnlineStatus.ONLINE;
        };
    }
}
