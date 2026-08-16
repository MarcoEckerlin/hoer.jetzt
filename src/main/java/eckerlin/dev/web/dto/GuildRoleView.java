package eckerlin.dev.web.dto;

/**
 * Eine Discord-Rolle fuer die Rechtematrix.
 *
 * @param everyone true fuer die @everyone-Rolle. Deren ID entspricht auf Discord
 *                 der Server-ID; sie wird in der Oberflaeche gesondert
 *                 dargestellt, weil sie fuer jedes Mitglied gilt.
 * @param managed  von einer Integration verwaltete Rolle (Bot- oder Boost-Rolle).
 *                 Solche Rollen lassen sich Mitgliedern nicht frei zuweisen.
 */
public record GuildRoleView(
        String id,
        String name,
        String color,
        int position,
        boolean managed,
        boolean everyone
) {
}
