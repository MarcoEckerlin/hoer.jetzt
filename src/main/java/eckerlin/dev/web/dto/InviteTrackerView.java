package eckerlin.dev.web.dto;

import java.util.List;

public record InviteTrackerView(
        boolean enabled,
        boolean canReadInvites,
        String notice,
        List<TrackedInviteView> activeInvites,
        List<InviteJoinEventView> recentJoins
) {
}
