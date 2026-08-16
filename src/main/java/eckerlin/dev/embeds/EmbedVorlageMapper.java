package eckerlin.dev.embeds;

import eckerlin.dev.web.dto.EmbedVorlageDto;

import java.util.ArrayList;
import java.util.List;

/**
 * Uebersetzt zwischen gespeichertem Zustand und dem, was die Oberflaeche
 * schickt.
 *
 * <p>Eigene Klasse statt Methoden an beiden Enden: die Umwandlung wird von
 * vier Modulen gebraucht, und vier Kopien derselben zwanzig Zuweisungen waeren
 * vier Stellen, an denen ein neues Feld vergessen werden kann.</p>
 */
public final class EmbedVorlageMapper {

    private EmbedVorlageMapper() {
    }

    public static EmbedVorlageDto zurOberflaeche(EmbedVorlage vorlage) {
        EmbedVorlage sicher = vorlage == null ? new EmbedVorlage() : vorlage;
        return new EmbedVorlageDto(
                sicher.getId(),
                sicher.getName(),
                sicher.getInhalt(),
                sicher.getAutorName(),
                sicher.getAutorIconUrl(),
                sicher.getAutorUrl(),
                sicher.getTitel(),
                sicher.getTitelUrl(),
                sicher.getBeschreibung(),
                sicher.getFarbe(),
                sicher.getThumbnailUrl(),
                sicher.getBildUrl(),
                List.copyOf(sicher.getZusatzBilder()),
                sicher.getFelder().stream()
                        .map(feld -> new EmbedVorlageDto.EmbedFeldDto(feld.getName(), feld.getWert(), feld.isInline()))
                        .toList(),
                sicher.getFusszeile(),
                sicher.getFusszeileIconUrl(),
                sicher.isZeitstempel()
        );
    }

    public static EmbedVorlage ausOberflaeche(EmbedVorlageDto dto) {
        EmbedVorlage vorlage = new EmbedVorlage();
        if (dto == null) {
            return vorlage;
        }
        vorlage.setId(dto.id());
        vorlage.setName(dto.name());
        vorlage.setInhalt(dto.inhalt());
        vorlage.setAutorName(dto.autorName());
        vorlage.setAutorIconUrl(dto.autorIconUrl());
        vorlage.setAutorUrl(dto.autorUrl());
        vorlage.setTitel(dto.titel());
        vorlage.setTitelUrl(dto.titelUrl());
        vorlage.setBeschreibung(dto.beschreibung());
        vorlage.setFarbe(dto.farbe());
        vorlage.setThumbnailUrl(dto.thumbnailUrl());
        vorlage.setBildUrl(dto.bildUrl());

        List<String> bilder = new ArrayList<>();
        for (String bild : dto.zusatzBilder() == null ? List.<String>of() : dto.zusatzBilder()) {
            if (bild != null && !bild.isBlank()) {
                bilder.add(bild.trim());
            }
        }
        vorlage.setZusatzBilder(bilder);

        List<EmbedVorlage.EmbedFeld> felder = new ArrayList<>();
        for (EmbedVorlageDto.EmbedFeldDto feldDto : dto.felder() == null
                ? List.<EmbedVorlageDto.EmbedFeldDto>of() : dto.felder()) {
            if (feldDto == null) {
                continue;
            }
            boolean leer = (feldDto.name() == null || feldDto.name().isBlank())
                    && (feldDto.wert() == null || feldDto.wert().isBlank());
            if (leer) {
                continue;
            }
            EmbedVorlage.EmbedFeld feld = new EmbedVorlage.EmbedFeld();
            feld.setName(feldDto.name());
            feld.setWert(feldDto.wert());
            feld.setInline(feldDto.inline() == null || feldDto.inline());
            felder.add(feld);
        }
        vorlage.setFelder(felder);

        vorlage.setFusszeile(dto.fusszeile());
        vorlage.setFusszeileIconUrl(dto.fusszeileIconUrl());
        vorlage.setZeitstempel(dto.zeitstempel() != null && dto.zeitstempel());
        return vorlage;
    }
}
