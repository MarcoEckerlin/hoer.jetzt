package eckerlin.dev.services;

/**
 * Einstellungen des Lavalink-Nodes eines Deployments.
 *
 * <p>Der Bot betreibt bewusst genau einen Node je Deployment. Die frueher
 * vorhandene Sortierreihenfolge ist damit gegenstandslos und wurde entfernt -
 * die Spalte {@code sort_order} bleibt in der Datenbank nur bestehen, damit
 * keine Migration noetig ist.
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
        boolean enabled
) {
}
