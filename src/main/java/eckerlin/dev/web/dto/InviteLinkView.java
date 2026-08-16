package eckerlin.dev.web.dto;

/**
 * Der Einladungs-Kurzlink eines Servers.
 *
 * @param slug      der frei gewaehlte Name hinter /invite/
 * @param targetUrl die Discord-Einladung, auf die er zeigt
 * @param clicks    wie oft der Link bisher benutzt wurde
 * @param publicUrl fertig zusammengesetzt, damit die Oberflaeche die
 *                  oeffentliche Adresse nicht selbst raten muss
 */
public record InviteLinkView(
        boolean enabled,
        String slug,
        String targetUrl,
        long clicks,
        String publicUrl
) {
}
