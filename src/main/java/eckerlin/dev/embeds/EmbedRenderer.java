package eckerlin.dev.embeds;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Macht aus einer {@link EmbedVorlage} eine versandfertige Nachricht.
 *
 * <p>Die ganze Umsetzung liegt bewusst an einer Stelle. Frueher baute jedes
 * Modul sein Embed selbst - mit jeweils eigenen Vorstellungen davon, was bei
 * einem leeren Feld passiert und welche Platzhalter es gibt. Ergebnis waren
 * vier leicht verschiedene Verhaltensweisen, die niemand auseinanderhalten
 * konnte.</p>
 */
@Service
public class EmbedRenderer {

    /** Discords Obergrenzen. Ueberschreiten heisst: die Nachricht geht gar nicht raus. */
    private static final int MAX_TITEL = 256;
    private static final int MAX_BESCHREIBUNG = 4096;
    private static final int MAX_FELD_NAME = 256;
    private static final int MAX_FELD_WERT = 1024;
    private static final int MAX_FUSSZEILE = 2048;
    private static final int MAX_INHALT = 2000;
    private static final int MAX_FELDER = 25;
    /** Eine Nachricht traegt hoechstens zehn Embeds - eines davon ist das Haupt-Embed. */
    private static final int MAX_ZUSATZBILDER = 9;

    /**
     * Der Anker, ueber den Discord mehrere Embeds zu einer Bildergalerie
     * zusammenfasst. Der Wert selbst ist gleichgueltig - entscheidend ist,
     * dass alle beteiligten Embeds dieselbe {@code url} tragen.
     */
    private static final String GALERIE_ANKER = "https://hoer.jetzt/#galerie";

    /**
     * Baut die Nachricht.
     *
     * @param vorlage       was gestaltet wurde
     * @param platzhalter   zusaetzliche Ersetzungen des aufrufenden Moduls
     */
    public MessageCreateData baue(EmbedVorlage vorlage, Map<String, String> platzhalter) {
        MessageCreateBuilder nachricht = new MessageCreateBuilder();

        String inhalt = ersetze(vorlage.getInhalt(), platzhalter);
        if (!inhalt.isBlank()) {
            nachricht.setContent(kuerzen(inhalt, MAX_INHALT));
        }

        nachricht.setEmbeds(baueEmbeds(vorlage, platzhalter));
        return nachricht.build();
    }

    /**
     * Nur die Embeds - fuer Aufrufer, die den Nachrichtentext selbst setzen
     * (etwa das Ticket-System, das eine Rollen-Erwaehnung voranstellt).
     */
    public List<MessageEmbed> baueEmbeds(EmbedVorlage vorlage, Map<String, String> platzhalter) {
        List<MessageEmbed> embeds = new ArrayList<>();
        EmbedBuilder haupt = new EmbedBuilder();

        String autor = ersetze(vorlage.getAutorName(), platzhalter);
        if (!autor.isBlank()) {
            haupt.setAuthor(
                    kuerzen(autor, MAX_TITEL),
                    sauber(vorlage.getAutorUrl()),
                    sauber(vorlage.getAutorIconUrl())
            );
        }

        String titel = ersetze(vorlage.getTitel(), platzhalter);
        if (!titel.isBlank()) {
            haupt.setTitle(kuerzen(titel, MAX_TITEL), sauber(vorlage.getTitelUrl()));
        }

        String beschreibung = ersetze(vorlage.getBeschreibung(), platzhalter);
        if (!beschreibung.isBlank()) {
            haupt.setDescription(kuerzen(beschreibung, MAX_BESCHREIBUNG));
        }

        haupt.setColor(farbe(vorlage.getFarbe()));

        if (sauber(vorlage.getThumbnailUrl()) != null) {
            haupt.setThumbnail(vorlage.getThumbnailUrl().trim());
        }
        if (sauber(vorlage.getBildUrl()) != null) {
            haupt.setImage(vorlage.getBildUrl().trim());
        }

        int gezaehlt = 0;
        for (EmbedVorlage.EmbedFeld feld : vorlage.getFelder()) {
            if (gezaehlt >= MAX_FELDER) {
                break;
            }
            String name = ersetze(feld.getName(), platzhalter);
            String wert = ersetze(feld.getWert(), platzhalter);
            if (name.isBlank() && wert.isBlank()) {
                continue;
            }
            // Discord lehnt leere Feldnamen ab. Ein Bindestrich ist haesslich,
            // aber sichtbar - eine verworfene Nachricht waere es nicht.
            haupt.addField(
                    kuerzen(name.isBlank() ? "-" : name, MAX_FELD_NAME),
                    kuerzen(wert.isBlank() ? "-" : wert, MAX_FELD_WERT),
                    feld.isInline()
            );
            gezaehlt++;
        }

        String fusszeile = ersetze(vorlage.getFusszeile(), platzhalter);
        if (!fusszeile.isBlank()) {
            haupt.setFooter(kuerzen(fusszeile, MAX_FUSSZEILE), sauber(vorlage.getFusszeileIconUrl()));
        }
        if (vorlage.isZeitstempel()) {
            haupt.setTimestamp(Instant.now());
        }

        List<String> zusatz = vorlage.getZusatzBilder().stream()
                .filter(url -> sauber(url) != null)
                .limit(MAX_ZUSATZBILDER)
                .toList();

        // Der Galerie-Kniff: nur wenn wirklich Zusatzbilder da sind, bekommt
        // auch das Haupt-Embed den Anker. Sonst haenge an jeder Nachricht ein
        // sinnloser Link am Titel.
        if (!zusatz.isEmpty()) {
            haupt.setUrl(GALERIE_ANKER);
        }
        embeds.add(haupt.build());

        for (String bild : zusatz) {
            embeds.add(new EmbedBuilder()
                    .setUrl(GALERIE_ANKER)
                    .setImage(bild.trim())
                    .build());
        }
        return embeds;
    }

    /**
     * Die Platzhalter, die ueberall gelten.
     *
     * <p>Module reichen ihre eigenen zusaetzlich herein - {@code {code}} beim
     * Verify, {@code {anliegen}} beim Ticket. Unbekannte Platzhalter bleiben
     * absichtlich stehen: ein Tippfehler soll sichtbar sein und nicht als
     * Leerstelle verschwinden.</p>
     */
    public Map<String, String> standardPlatzhalter(Guild guild, Member member) {
        Map<String, String> werte = new LinkedHashMap<>();
        if (guild != null) {
            werte.put("{server}", guild.getName());
            werte.put("{guild}", guild.getName());
            werte.put("{mitglieder}", String.valueOf(guild.getMemberCount()));
            werte.put("{serverid}", guild.getId());
            if (guild.getIconUrl() != null) {
                werte.put("{servericon}", guild.getIconUrl());
            }
        }
        if (member != null) {
            werte.put("{user}", member.getAsMention());
            werte.put("{name}", member.getEffectiveName());
            werte.put("{username}", member.getUser().getName());
            werte.put("{userid}", member.getId());
            werte.put("{avatar}", member.getEffectiveAvatarUrl());
        }
        return werte;
    }

    private static String ersetze(String vorlage, Map<String, String> platzhalter) {
        if (vorlage == null || vorlage.isBlank()) {
            return "";
        }
        String text = vorlage;
        if (platzhalter != null) {
            for (Map.Entry<String, String> eintrag : platzhalter.entrySet()) {
                text = text.replace(eintrag.getKey(), eintrag.getValue() == null ? "" : eintrag.getValue());
            }
        }
        return text;
    }

    private static String kuerzen(String text, int laenge) {
        if (text == null) {
            return "";
        }
        return text.length() <= laenge ? text : text.substring(0, laenge - 1) + "…";
    }

    /** Leere und offensichtlich unbrauchbare Adressen zu {@code null} - JDA wirft sonst. */
    private static String sauber(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String getrimmt = url.trim();
        return getrimmt.startsWith("http://") || getrimmt.startsWith("https://") ? getrimmt : null;
    }

    private static Color farbe(String hex) {
        try {
            return Color.decode(hex == null || hex.isBlank() ? "#78D1FF" : hex.trim());
        } catch (NumberFormatException ignored) {
            return new Color(0x78D1FF);
        }
    }
}
