package eckerlin.dev.services;

/**
 * Einstellungen eines Lavalink-Nodes.
 *
 * <p>Frueher war es bewusst genau ein Node je Deployment. Seit der Bot mit dem
 * {@code TierAwareLoadBalancer} selbst auswaehlt, duerfen es beliebig viele
 * sein - der Schluessel ist jetzt {@link #nodeName()}, nicht mehr
 * {@link #deploymentKey()}.
 *
 * @param tier       {@code free} oder {@code premium}. Bestimmt, welche Server
 *                   auf diesem Node landen duerfen. Unbekannte Werte gelten als
 *                   {@code free} - ein Tippfehler soll niemanden versehentlich
 *                   auf die Premium-Hardware lassen.
 * @param maxPlayers Obergrenze gleichzeitiger Wiedergaben. 0 = unbegrenzt.
 */
public record LavalinkNodeSettings(
        long id,
        String deploymentKey,
        String nodeName,
        String serverUri,
        String password,
        int httpTimeoutMs,
        boolean resumeEnabled,
        long resumeTimeoutSeconds,
        boolean enabled,
        String tier,
        int maxPlayers
) {
}
