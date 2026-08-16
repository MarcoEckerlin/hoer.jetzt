package eckerlin.dev.web.dto;

import java.util.List;

/**
 * Eine Embed-Vorlage auf dem Weg zur Oberflaeche und zurueck.
 *
 * <p>Dieselbe Form fuer Hin- und Rueckweg: der Editor im Browser schickt
 * genau das zurueck, was er bekommen hat. Zwei getrennte Records waeren hier
 * nur zwei Stellen, die auseinanderlaufen koennen.</p>
 */
public record EmbedVorlageDto(
        String id,
        String name,
        String inhalt,
        String autorName,
        String autorIconUrl,
        String autorUrl,
        String titel,
        String titelUrl,
        String beschreibung,
        String farbe,
        String thumbnailUrl,
        String bildUrl,
        List<String> zusatzBilder,
        List<EmbedFeldDto> felder,
        String fusszeile,
        String fusszeileIconUrl,
        Boolean zeitstempel
) {
    public record EmbedFeldDto(String name, String wert, Boolean inline) {
    }
}
