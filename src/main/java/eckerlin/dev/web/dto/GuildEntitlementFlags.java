package eckerlin.dev.web.dto;

/**
 * Welche freischaltpflichtigen Funktionen dieser Server nutzen darf.
 *
 * <p>Die Oberflaeche blendet danach ganze Bereiche aus. Das ist bewusst nur
 * Kosmetik - die eigentliche Sperre sitzt im Server, nicht im Browser. Aber
 * eine Schaltflaeche, die nur eine Absage liefert, ist schlechter als keine.
 */
public record GuildEntitlementFlags(
        boolean llmChat,
        boolean aiRadio,
        boolean premiumAudio
) {
}
