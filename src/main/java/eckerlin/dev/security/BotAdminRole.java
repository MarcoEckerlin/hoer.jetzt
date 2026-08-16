package eckerlin.dev.security;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Stufen der Bot-Verwaltung. Gilt instanzweit, nicht pro Discord-Server.
 *
 * <p>Ein Bot-Admin umgeht saemtliche Rollenpruefungen auf fremden Servern -
 * im Webpanel, bei Slash-Commands, bei Buttons und ueber MCP.
 */
public enum BotAdminRole {

    /** Nur lesen: Server einsehen, Audit-Log ansehen. Aendert nichts. */
    SUPPORT(1, "Support"),

    /** Alles ausser der Verwaltung anderer Bot-Admins. */
    ADMIN(2, "Admin"),

    /**
     * Der Betreiber. Wird automatisch aus dem Discord-Application-Owner
     * uebernommen und laesst sich im Panel nicht entfernen - sonst koennte man
     * sich selbst aussperren.
     */
    OWNER(3, "Owner");

    private final int level;
    private final String label;

    BotAdminRole(int level, String label) {
        this.level = level;
        this.label = label;
    }

    public int level() {
        return level;
    }

    public String label() {
        return label;
    }

    public boolean atLeast(BotAdminRole required) {
        return required != null && this.level >= required.level;
    }

    public static Optional<BotAdminRole> fromKey(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }

        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(role -> role.name().equals(normalized))
                .findFirst();
    }
}
