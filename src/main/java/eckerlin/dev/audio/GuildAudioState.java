package eckerlin.dev.audio;

import dev.arbjerg.lavalink.client.player.Track;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public class GuildAudioState {

    private final Deque<Track> queue = new ArrayDeque<>();
    private Track currentTrack;
    private int volume = 100;
    private boolean repeatEnabled;
    private boolean bassBoostEnabled;
    private boolean smartRadioEnabled;
    private boolean smartRadioLoading;
    private long lastRadioStartAtMs;
    private boolean waitingForListeners;
    private boolean virtualProgressActive;
    private long waitingStartedAtMs;
    private long waitingTrackPositionMs;
    private String activeRadioName = "";
    private long radioWarmBufferUntilMs;
    private long playbackRevision;

    public synchronized void clearQueue() {
        queue.clear();
    }

    public synchronized void enqueue(Track track) {
        queue.addLast(track);
    }

    public synchronized void enqueueAll(List<Track> tracks) {
        queue.addAll(tracks);
    }

    public synchronized Track pollNext() {
        return queue.pollFirst();
    }

    public synchronized List<Track> snapshotQueue() {
        return List.copyOf(queue);
    }

    public synchronized int queueSize() {
        return queue.size();
    }

    public synchronized boolean removeQueueItem(int index) {
        if (index < 0 || index >= queue.size()) {
            return false;
        }

        List<Track> items = new ArrayList<>(queue);
        items.remove(index);
        queue.clear();
        queue.addAll(items);
        return true;
    }

    public synchronized boolean moveQueueItem(int fromIndex, int toIndex) {
        if (fromIndex < 0 || toIndex < 0 || fromIndex >= queue.size() || toIndex >= queue.size() || fromIndex == toIndex) {
            return false;
        }

        List<Track> items = new ArrayList<>(queue);
        Track moved = items.remove(fromIndex);
        items.add(toIndex, moved);
        queue.clear();
        queue.addAll(items);
        return true;
    }

    public synchronized Track currentTrack() {
        return currentTrack;
    }

    public synchronized void setCurrentTrack(Track currentTrack) {
        this.currentTrack = currentTrack;
        if (currentTrack != null) {
            this.playbackRevision++;
        }
    }

    public synchronized int volume() {
        return volume;
    }

    public synchronized void setVolume(int volume) {
        this.volume = volume;
    }

    public synchronized boolean repeatEnabled() {
        return repeatEnabled;
    }

    public synchronized void setRepeatEnabled(boolean repeatEnabled) {
        this.repeatEnabled = repeatEnabled;
    }

    public synchronized boolean bassBoostEnabled() {
        return bassBoostEnabled;
    }

    public synchronized void setBassBoostEnabled(boolean bassBoostEnabled) {
        this.bassBoostEnabled = bassBoostEnabled;
    }

    public synchronized boolean smartRadioEnabled() {
        return smartRadioEnabled;
    }

    public synchronized void setSmartRadioEnabled(boolean smartRadioEnabled) {
        this.smartRadioEnabled = smartRadioEnabled;
        if (!smartRadioEnabled) {
            this.smartRadioLoading = false;
        }
    }

    public synchronized boolean beginSmartRadioLoad() {
        if (smartRadioLoading) {
            return false;
        }

        smartRadioLoading = true;
        return true;
    }

    public synchronized void finishSmartRadioLoad() {
        smartRadioLoading = false;
    }

    public synchronized long reserveRadioStart(long nowMs, long cooldownMs) {
        long remainingMs = Math.max(0L, (lastRadioStartAtMs + cooldownMs) - nowMs);
        if (remainingMs > 0L) {
            return remainingMs;
        }

        lastRadioStartAtMs = nowMs;
        return 0L;
    }

    public synchronized long radioStartCooldownRemaining(long nowMs, long cooldownMs) {
        return Math.max(0L, (lastRadioStartAtMs + cooldownMs) - nowMs);
    }

    public synchronized boolean waitingForListeners() {
        return waitingForListeners;
    }

    public synchronized void setWaitingForListeners(boolean waitingForListeners) {
        this.waitingForListeners = waitingForListeners;
        if (!waitingForListeners) {
            this.virtualProgressActive = false;
            this.waitingStartedAtMs = 0L;
            this.waitingTrackPositionMs = 0L;
            this.radioWarmBufferUntilMs = 0L;
        }
    }

    public synchronized boolean virtualProgressActive() {
        return virtualProgressActive;
    }

    public synchronized void beginWaitingForListeners(long nowMs, long trackPositionMs, boolean virtualProgressActive) {
        this.waitingForListeners = true;
        this.waitingStartedAtMs = nowMs;
        this.waitingTrackPositionMs = Math.max(0L, trackPositionMs);
        this.virtualProgressActive = virtualProgressActive;
    }

    public synchronized long waitingStartedAtMs() {
        return waitingStartedAtMs;
    }

    public synchronized long waitingTrackPositionMs() {
        return waitingTrackPositionMs;
    }

    public synchronized void updateWaitingProgress(long nowMs, long trackPositionMs) {
        this.waitingStartedAtMs = nowMs;
        this.waitingTrackPositionMs = Math.max(0L, trackPositionMs);
    }

    public synchronized String activeRadioName() {
        return activeRadioName;
    }

    public synchronized void setActiveRadioName(String activeRadioName) {
        this.activeRadioName = activeRadioName == null ? "" : activeRadioName.trim();
    }

    public synchronized long radioWarmBufferUntilMs() {
        return radioWarmBufferUntilMs;
    }

    public synchronized void setRadioWarmBufferUntilMs(long radioWarmBufferUntilMs) {
        this.radioWarmBufferUntilMs = Math.max(0L, radioWarmBufferUntilMs);
    }

    public synchronized long playbackRevision() {
        return playbackRevision;
    }
}
