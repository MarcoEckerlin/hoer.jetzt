package eckerlin.dev.audio;

/**
 * Leistungsstufe eines Audio-Knotens und eines Discord-Servers.
 *
 * <p>Die Stufe entscheidet, welcher Lavalink-Knoten einen Server bedient.
 * Premium-Knoten laufen auf besserer Hardware und werden bewusst leer
 * gehalten; sie werden nur Servern zugeteilt, die ein Bot-Administrator
 * freigeschaltet hat.</p>
 */
public enum NodeTier {

    FREE("free", "Standard"),
    PREMIUM("premium", "Premium");

    private final String key;
    private final String label;

    NodeTier(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    /** Unbekannte oder fehlende Werte gelten als Standard - niemals als Premium. */
    public static NodeTier fromKey(String value) {
        if (value == null) {
            return FREE;
        }
        String normalized = value.trim().toLowerCase();
        for (NodeTier tier : values()) {
            if (tier.key.equals(normalized)) {
                return tier;
            }
        }
        return FREE;
    }
}
