package eckerlin.dev.security;

/**
 * Wird geworfen, wenn ein kostenpflichtiges Feature auf einem Server nicht
 * freigeschaltet ist oder sein Tageslimit erreicht hat.
 *
 * <p>Bewusst eine eigene Klasse und keine {@link java.io.IOException}: eine
 * Ablehnung ist kein Ausfall. Aufrufer duerfen darauf nicht mit einem
 * Ersatzprogramm reagieren, sondern muessen den Vorgang beenden.</p>
 */
public class FeatureNotEnabledException extends RuntimeException {

    private final GuildFeature feature;

    public FeatureNotEnabledException(GuildFeature feature, String message) {
        super(message);
        this.feature = feature;
    }

    public GuildFeature feature() {
        return feature;
    }
}
