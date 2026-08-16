package eckerlin.dev.services.tools;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;

/**
 * Aufrufkontext eines Werkzeugs.
 *
 * <p>Es gibt genau zwei Auspraegungen:
 * <ul>
 *   <li>{@link #fromChat(Guild, Member)} - der Aufruf stammt aus einer
 *       Discord-Nachricht. Server und Mitglied sind bekannt, und es gilt
 *       dieselbe Rechtepruefung wie bei den Slash-Commands.</li>
 *   <li>{@link #fromMcp(String)} - der Aufruf kommt von einem externen Client
 *       ueber MCP. Es gibt kein Discord-Mitglied; die Berechtigung wurde bereits
 *       ueber das Zugriffstoken des Endpunkts geprueft.</li>
 * </ul>
 */
public record ToolContext(Guild guild, Member member, String guildReference, boolean requiresPermissionCheck) {

    public static ToolContext fromChat(Guild guild, Member member) {
        return new ToolContext(guild, member, guild == null ? null : guild.getId(), true);
    }

    public static ToolContext fromMcp(String guildReference) {
        return new ToolContext(null, null, guildReference, false);
    }
}
