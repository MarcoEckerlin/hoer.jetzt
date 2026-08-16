package eckerlin.dev.audio;

import java.util.List;

public record PlayerState(
        String guildId,
        String guildName,
        Long voiceChannelId,
        String voiceChannelName,
        Long userVoiceChannelId,
        String userVoiceChannelName,
        boolean userInVoiceChannel,
        boolean connected,
        boolean paused,
        boolean waitingForListeners,
        boolean virtualProgressActive,
        boolean playingRadio,
        boolean smartRadio,
        String activeRadioName,
        boolean repeatEnabled,
        int volume,
        boolean bassBoostEnabled,
        int voiceBitrateKbps,
        long positionMs,
        long radioCooldownRemainingMs,
        TrackView currentTrack,
        List<TrackView> queue
) {
}
