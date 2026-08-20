package eckerlin.dev.security;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Funktionen, die pro Discord-Server einzeln freigeschaltet werden muessen.
 *
 * <p>Beide kosten im Betrieb Geld (Modell-Inferenz, Rechenzeit) und bewegen sich
 * urheberrechtlich im Graubereich. Sie sind deshalb standardmaessig aus und
 * lassen sich <em>nur</em> von einem Bot-Admin freischalten - der Betreiber
 * eines fremden Servers kann das weder ueber das Webpanel noch ueber einen
 * Slash-Command noch ueber MCP selbst tun.
 */
public enum GuildFeature {

    LLM_CHAT("KI-Chat", "Chat-Assistent im Textkanal, inklusive Audio-Werkzeugen."),
    AI_RADIO("KI-Radio", "Automatisch kuratiertes Radio über den Music-Brain-Dienst."),

    /**
     * Audio ueber die Premium-Knoten.
     *
     * <p>Kostet keine Inferenz, sondern Hardware: Premium-Knoten werden bewusst
     * leer gehalten, damit die Wiedergabe auch bei Last sauber bleibt. Deshalb
     * steht die Stufe hier und nicht in einer eigenen Tabelle - vergeben wird
     * sie an derselben Stelle und nach denselben Regeln wie alles andere,
     * naemlich nur durch einen Bot-Admin.</p>
     */
    PREMIUM_AUDIO("Premium-Audio", "Wiedergabe ueber die reservierten Premium-Knoten.");

    private final String label;
    private final String description;

    GuildFeature(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String key() {
        return name();
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public static Optional<GuildFeature> fromKey(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }

        String normalized = key.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(feature -> feature.name().equals(normalized))
                .findFirst();
    }
}
