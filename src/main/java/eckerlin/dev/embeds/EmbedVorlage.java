package eckerlin.dev.embeds;

import java.util.ArrayList;
import java.util.List;

/**
 * Eine frei gestaltbare Discord-Nachricht.
 *
 * <p>Bisher hatte jedes Modul seine eigenen drei bis fuenf Felder - Titel,
 * Beschreibung, Bild, Thumbnail, Farbe - und jedes baute daraus sein Embed
 * selbst zusammen. Wer mehr wollte, hatte Pech; wer dasselbe Aussehen in zwei
 * Modulen wollte, musste alles doppelt eintragen. Diese Klasse loest beides:
 * sie ist der volle Umfang dessen, was Discord kann, und sie laesst sich in
 * einer Bibliothek ablegen und von mehreren Modulen benutzen.</p>
 *
 * <h2>Bilder innerhalb und ausserhalb</h2>
 *
 * <p>Discord kennt drei Orte fuer Bilder in <em>einer</em> Nachricht:</p>
 *
 * <ul>
 *   <li>{@link #thumbnailUrl} - klein, rechts oben im Embed</li>
 *   <li>{@link #bildUrl} - gross, unten im Embed</li>
 *   <li>{@link #zusatzBilder} - ausserhalb des Embeds, aber in derselben
 *       Nachricht. Moeglich wird das ueber einen Kniff: mehrere Embeds mit
 *       <em>derselben</em> {@code url} stapelt Discord zu einer Galerie.</li>
 * </ul>
 *
 * <p>{@link #inhalt} ist der gewoehnliche Nachrichtentext ueber dem Embed -
 * der einzige Ort, an dem Erwaehnungen ({@code @rolle}) tatsaechlich
 * benachrichtigen. Im Embed sehen sie zwar richtig aus, loesen aber nichts
 * aus; das ist eine Discord-Eigenheit und keine Nachlaessigkeit hier.</p>
 */
public class EmbedVorlage {

    private String id = "";
    /** Nur fuer die Bibliothek: unter diesem Namen taucht die Vorlage in der Auswahl auf. */
    private String name = "";

    private String inhalt = "";

    private String autorName = "";
    private String autorIconUrl = "";
    private String autorUrl = "";

    private String titel = "";
    private String titelUrl = "";
    private String beschreibung = "";
    private String farbe = "#78D1FF";

    private String thumbnailUrl = "";
    private String bildUrl = "";
    private List<String> zusatzBilder = new ArrayList<>();

    private List<EmbedFeld> felder = new ArrayList<>();

    private String fusszeile = "";
    private String fusszeileIconUrl = "";
    private boolean zeitstempel;

    public EmbedVorlage copy() {
        EmbedVorlage copy = new EmbedVorlage();
        copy.setId(id);
        copy.setName(name);
        copy.setInhalt(inhalt);
        copy.setAutorName(autorName);
        copy.setAutorIconUrl(autorIconUrl);
        copy.setAutorUrl(autorUrl);
        copy.setTitel(titel);
        copy.setTitelUrl(titelUrl);
        copy.setBeschreibung(beschreibung);
        copy.setFarbe(farbe);
        copy.setThumbnailUrl(thumbnailUrl);
        copy.setBildUrl(bildUrl);
        copy.setZusatzBilder(zusatzBilder);
        copy.setFelder(felder.stream().map(EmbedFeld::copy).toList());
        copy.setFusszeile(fusszeile);
        copy.setFusszeileIconUrl(fusszeileIconUrl);
        copy.setZeitstempel(zeitstempel);
        return copy;
    }

    /**
     * Ist ueberhaupt etwas eingetragen?
     *
     * <p>Entscheidet, ob ein Modul die Vorlage benutzt oder bei seiner alten
     * Darstellung bleibt. Ohne diese Pruefung bekaemen alle bestehenden Server
     * nach dem Update ein leeres Embed serviert.</p>
     */
    public boolean istLeer() {
        return leer(titel) && leer(beschreibung) && leer(inhalt) && leer(autorName)
                && leer(bildUrl) && leer(thumbnailUrl) && leer(fusszeile)
                && felder.isEmpty() && zusatzBilder.isEmpty();
    }

    private static boolean leer(String wert) {
        return wert == null || wert.isBlank();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id == null ? "" : id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name;
    }

    public String getInhalt() {
        return inhalt;
    }

    public void setInhalt(String inhalt) {
        this.inhalt = inhalt == null ? "" : inhalt;
    }

    public String getAutorName() {
        return autorName;
    }

    public void setAutorName(String autorName) {
        this.autorName = autorName == null ? "" : autorName;
    }

    public String getAutorIconUrl() {
        return autorIconUrl;
    }

    public void setAutorIconUrl(String autorIconUrl) {
        this.autorIconUrl = autorIconUrl == null ? "" : autorIconUrl;
    }

    public String getAutorUrl() {
        return autorUrl;
    }

    public void setAutorUrl(String autorUrl) {
        this.autorUrl = autorUrl == null ? "" : autorUrl;
    }

    public String getTitel() {
        return titel;
    }

    public void setTitel(String titel) {
        this.titel = titel == null ? "" : titel;
    }

    public String getTitelUrl() {
        return titelUrl;
    }

    public void setTitelUrl(String titelUrl) {
        this.titelUrl = titelUrl == null ? "" : titelUrl;
    }

    public String getBeschreibung() {
        return beschreibung;
    }

    public void setBeschreibung(String beschreibung) {
        this.beschreibung = beschreibung == null ? "" : beschreibung;
    }

    public String getFarbe() {
        return farbe;
    }

    public void setFarbe(String farbe) {
        this.farbe = farbe == null || farbe.isBlank() ? "#78D1FF" : farbe;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    public void setThumbnailUrl(String thumbnailUrl) {
        this.thumbnailUrl = thumbnailUrl == null ? "" : thumbnailUrl;
    }

    public String getBildUrl() {
        return bildUrl;
    }

    public void setBildUrl(String bildUrl) {
        this.bildUrl = bildUrl == null ? "" : bildUrl;
    }

    public List<String> getZusatzBilder() {
        return zusatzBilder;
    }

    public void setZusatzBilder(List<String> zusatzBilder) {
        this.zusatzBilder = zusatzBilder == null ? new ArrayList<>() : new ArrayList<>(zusatzBilder);
    }

    public List<EmbedFeld> getFelder() {
        return felder;
    }

    public void setFelder(List<EmbedFeld> felder) {
        this.felder = felder == null ? new ArrayList<>() : new ArrayList<>(felder);
    }

    public String getFusszeile() {
        return fusszeile;
    }

    public void setFusszeile(String fusszeile) {
        this.fusszeile = fusszeile == null ? "" : fusszeile;
    }

    public String getFusszeileIconUrl() {
        return fusszeileIconUrl;
    }

    public void setFusszeileIconUrl(String fusszeileIconUrl) {
        this.fusszeileIconUrl = fusszeileIconUrl == null ? "" : fusszeileIconUrl;
    }

    public boolean isZeitstempel() {
        return zeitstempel;
    }

    public void setZeitstempel(boolean zeitstempel) {
        this.zeitstempel = zeitstempel;
    }

    /** Ein Feld im Embed. Discord stellt bis zu drei nebeneinander, wenn {@code inline}. */
    public static class EmbedFeld {

        private String name = "";
        private String wert = "";
        private boolean inline = true;

        public EmbedFeld copy() {
            EmbedFeld copy = new EmbedFeld();
            copy.setName(name);
            copy.setWert(wert);
            copy.setInline(inline);
            return copy;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name == null ? "" : name;
        }

        public String getWert() {
            return wert;
        }

        public void setWert(String wert) {
            this.wert = wert == null ? "" : wert;
        }

        public boolean isInline() {
            return inline;
        }

        public void setInline(boolean inline) {
            this.inline = inline;
        }
    }
}
