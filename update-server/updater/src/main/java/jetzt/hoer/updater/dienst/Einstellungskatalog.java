package jetzt.hoer.updater.dienst;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Welche Werte zentral gepflegt werden - und welche ausdruecklich nicht.
 *
 * <h2>Wozu ein Katalog und nicht einfach ein Textfeld</h2>
 *
 * <p>Eine freie Liste aus Schluessel und Wert waere schneller gebaut und
 * waere die falsche Wahl. Drei Dinge haengen daran, die man einer freien
 * Liste nicht ansieht:</p>
 *
 * <ol>
 *   <li><b>Knotenspezifisches darf nicht zentral werden.</b> Setzt jemand
 *       hier {@code HJ_SHARD_VON}, bekommen alle Knoten dieselbe Scherbe
 *       des Shardings - und der Bot ist auf der Haelfte der Server stumm.
 *       Diese Schluessel stehen deshalb gar nicht erst zur Auswahl.</li>
 *   <li><b>Geheimnisse gehoeren in den Tresor.</b> Die Voreinstellungen
 *       werden unverschluesselt ausgeliefert - sie sind Vorgaben, kein
 *       Schluesselbund. Was {@code tresor.sh} schon liefert, taucht hier
 *       nicht auf; zwei Quellen fuer denselben Wert waere ein Fehler, den
 *       man erst bemerkt, wenn die beiden auseinanderlaufen.</li>
 *   <li><b>Ein Tippfehler ist teuer.</b> {@code LAVALINK_QUALITAT} ohne
 *       "ae" wird stillschweigend ignoriert: der Knoten nimmt seinen
 *       Vorgabewert, alles laeuft, nur eben anders als gedacht. Mit einem
 *       Katalog gibt es die Auswahl statt des Tippens.</li>
 * </ol>
 *
 * <h2>Wer bekommt was</h2>
 *
 * <p>Jeder Eintrag nennt die Profile, fuer die er gilt. Ein Audio-Knoten
 * bekommt keine Autoscale-Werte, ein Controller keine Lavalink-Werte - und
 * zwar nicht aus Ordnungsliebe: die Datei, die er holt, ist damit kurz
 * genug, um sie im Zweifel von Hand zu lesen.</p>
 */
public final class Einstellungskatalog {

    private Einstellungskatalog() {
    }

    /** Wie ein Wert einzugeben ist. Entscheidet ueber das Formularfeld. */
    public enum Art {
        /** Freier Text. */
        TEXT,
        /** {@code true} oder {@code false} - im Formular ein Haken. */
        SCHALTER,
        /** Ganze Zahl. */
        ZAHL,
        /** Eine aus einer festen Liste. */
        AUSWAHL
    }

    /**
     * Ein Wert, den man zentral setzen kann.
     *
     * @param schluessel  Name in der {@code .env} des Knotens
     * @param gruppe      Ueberschrift im Formular
     * @param profile     Fuer welche Knotenprofile er ausgeliefert wird
     * @param vorgabe     Was gilt, wenn nichts gesetzt ist - derselbe Wert,
     *                    den die Compose-Datei einsetzt
     * @param art         Wie das Feld aussieht
     * @param auswahl     Nur bei {@link Art#AUSWAHL}: die zulaessigen Werte
     * @param erklaerung  Was der Wert bewirkt. Steht unter dem Feld.
     */
    public record Eintrag(String schluessel, String gruppe, List<String> profile,
                          String vorgabe, Art art, List<String> auswahl,
                          String erklaerung) {

        public boolean giltFuer(String profil) {
            return profile.contains(profil);
        }
    }

    /** Die Profile, die es gibt. Entsprechen den Knotenrollen. */
    public static final List<String> PROFILE =
            List.of("controller", "core", "lavalink", "ai-radio");

    private static Eintrag text(String schluessel, String gruppe, List<String> profile,
                                String vorgabe, String erklaerung) {
        return new Eintrag(schluessel, gruppe, profile, vorgabe, Art.TEXT, List.of(), erklaerung);
    }

    private static Eintrag schalter(String schluessel, String gruppe, List<String> profile,
                                    String vorgabe, String erklaerung) {
        return new Eintrag(schluessel, gruppe, profile, vorgabe, Art.SCHALTER,
                List.of("true", "false"), erklaerung);
    }

    private static Eintrag zahl(String schluessel, String gruppe, List<String> profile,
                                String vorgabe, String erklaerung) {
        return new Eintrag(schluessel, gruppe, profile, vorgabe, Art.ZAHL, List.of(), erklaerung);
    }

    private static Eintrag auswahl(String schluessel, String gruppe, List<String> profile,
                                   String vorgabe, List<String> moeglich, String erklaerung) {
        return new Eintrag(schluessel, gruppe, profile, vorgabe, Art.AUSWAHL, moeglich, erklaerung);
    }

    private static final List<String> AUDIO = List.of("lavalink");
    private static final List<String> KERN = List.of("controller", "core");
    private static final List<String> NUR_CONTROLLER = List.of("controller");
    private static final List<String> RADIO = List.of("ai-radio");

    /**
     * Der Katalog.
     *
     * <p>Die Reihenfolge ist die Reihenfolge im Formular. Gruppen bleiben
     * deshalb beieinander.</p>
     */
    private static final List<Eintrag> EINTRAEGE = List.of(

            // ------------------------------------------------------ Lavalink
            auswahl("LAVALINK_QUALITAET", "Lavalink", AUDIO, "hoch",
                    List.of("hoch", "mittel", "sparsam"),
                    "Puffer, Resampling und Opus-Guete. 'hoch' kostet Arbeitsspeicher "
                    + "und CPU; 'sparsam' ist fuer kleine Maschinen gedacht. Ausgewertet "
                    + "vom entrypoint.sh im lavalink-Zweig."),

            text("YOUTUBE_PLUGIN_VERSION", "Lavalink", AUDIO, "1.18.2",
                    "Version des youtube-source-Plugins. Lavalink warnt beim Start, wenn "
                    + "sie veraltet ist - YouTube tauscht sein Player-Skript woechentlich."),

            schalter("YOUTUBE_PLUGIN_SNAPSHOT", "Lavalink", AUDIO, "false",
                    "Vorabfassung statt Freigabe ziehen. Nur einschalten, wenn eine "
                    + "Sperre erst dort behoben ist."),

            schalter("YOUTUBE_OAUTH", "Lavalink", AUDIO, "false",
                    "Anmeldung an YouTube mit einem Google-Konto. Ohne sie bleiben "
                    + "altersbeschraenkte Titel stumm. Der Geraetecode erscheint beim "
                    + "Start im Log; das Token gehoert danach in den Tresor."),

            text("YT_CIPHER_URL", "Lavalink", AUDIO, "http://yt-cipher:8001",
                    "Dienst, der YouTubes Stream-Adressen entschluesselt. Leer lassen "
                    + "schaltet ihn ab - dann haengt es wieder an der Plugin-Version "
                    + "('must find sig function')."),

            // ---------------------------------------------------- Kern / Bot
            zahl("HJ_LAVALINK_WATCH_SECONDS", "Kern und Bot", KERN, "30",
                    "Abstand, in dem der Kern seine Audio-Knoten auf Erreichbarkeit "
                    + "prueft. Kuerzer heisst schneller umschalten und mehr Verkehr."),

            schalter("HJ_LAVALINK_FREE_OVERFLOW", "Kern und Bot", KERN, "true",
                    "Duerfen zahlende Server auf einen freien Knoten ausweichen, wenn "
                    + "die Premium-Knoten voll sind? Aus heisst: lieber abweisen als "
                    + "auf schlechterer Ausstattung spielen."),

            zahl("HJ_SHARDS_GESAMT", "Kern und Bot", KERN, "",
                    "Wie viele Shards der Bot insgesamt hat - eine Zahl fuer den "
                    + "ganzen Verbund. Welchen Ausschnitt ein einzelner Knoten "
                    + "bedient, steht bei ihm (HJ_SHARD_VON/BIS) und gehoert nicht "
                    + "hierher. Leer heisst: Discord entscheidet."),

            text("HJ_BOT_ADMIN_IDS", "Kern und Bot", KERN, "",
                    "Discord-Benutzerkennungen mit voller Berechtigung, durch Komma "
                    + "getrennt. Gilt ueberall gleich."),

            text("HJ_SESSION_STORE", "Kern und Bot", KERN, "",
                    "Wo Web-Sitzungen liegen. Leer heisst im Arbeitsspeicher des "
                    + "Knotens - bei mehreren Knoten hinter einem Verteiler wird man "
                    + "dann bei jedem Wechsel abgemeldet."),

            text("HJ_REDIS_HOST", "Kern und Bot", KERN, "redis",
                    "Adresse von Redis. Dienstname aus der Compose-Datei, keine IP."),

            zahl("HJ_REDIS_PORT", "Kern und Bot", KERN, "6379",
                    "Port von Redis. Nur aendern, wenn Redis ausserhalb des Stacks "
                    + "laeuft - im Netz des Stacks ist es immer 6379."),

            // -------------------------------------------------------- Premium
            schalter("HJ_PREMIUM_ENABLED", "Premium", KERN, "false",
                    "Zeigt der Bot Premium-Funktionen an?"),

            text("HJ_PREMIUM_LEISTUNG", "Premium", KERN, "",
                    "Was Premium umfasst - Text fuer die Anzeige."),

            text("HJ_PREMIUM_PREIS", "Premium", KERN, "",
                    "Preisangabe fuer die Anzeige."),

            // ------------------------------------------------------ Autoscale
            //
            // Nur der Controller. Er ist der Verwalter der uebrigen Server -
            // ein Core-Knoten, der selbst Maschinen anlegt, waere genau die
            // Doppelzustaendigkeit, die man nachts sucht.
            schalter("HJ_AUTOSCALE", "Autoscale", NUR_CONTROLLER, "false",
                    "Legt der Controller bei Bedarf selbst neue Maschinen an? "
                    + "Braucht ein Hetzner-Token - das steht im Tresor, nicht hier."),

            zahl("HJ_AUTOSCALE_SCHWELLE", "Autoscale", NUR_CONTROLLER, "",
                    "Ab welcher Auslastung eine Maschine dazukommt."),

            zahl("HJ_AUTOSCALE_ABBAU_SCHWELLE", "Autoscale", NUR_CONTROLLER, "",
                    "Unter welcher Auslastung eine wieder verschwindet. Deutlich "
                    + "unter der Aufbau-Schwelle, sonst pendelt es."),

            zahl("HJ_AUTOSCALE_MAX", "Autoscale", NUR_CONTROLLER, "",
                    "Obergrenze. Der Wert, der die Rechnung begrenzt."),

            text("HJ_AUTOSCALE_TYPE", "Autoscale", NUR_CONTROLLER, "",
                    "Servertyp bei Hetzner, etwa cx22."),

            text("HJ_AUTOSCALE_LOCATION", "Autoscale", NUR_CONTROLLER, "",
                    "Standort bei Hetzner, etwa nbg1 oder hel1. Nimm den, an dem die "
                    + "uebrigen Knoten stehen - sonst kostet jeder Handschlag Laufzeit."),

            text("HJ_AUTOSCALE_IMAGE", "Autoscale", NUR_CONTROLLER, "",
                    "Abbild fuer neue Maschinen, etwa debian-13."),

            text("HJ_AUTOSCALE_NETWORK", "Autoscale", NUR_CONTROLLER, "",
                    "Privates Netz, in das neue Maschinen kommen."),

            text("HJ_AUTOSCALE_FIREWALL", "Autoscale", NUR_CONTROLLER, "",
                    "Firewall, die neuen Maschinen zugewiesen wird."),

            text("HJ_AUTOSCALE_LOADBALANCER", "Autoscale", NUR_CONTROLLER, "",
                    "Verteiler, an dem neue Maschinen angemeldet werden."),

            // ------------------------------------------------------- KI-Radio
            text("HJ_KI_RADIO_ABBILD", "KI-Radio", RADIO, "",
                    "Abweichendes Abbild fuer das KI-Radio. Leer heisst: das aus dem "
                    + "Release-Manifest.")
    );

    /**
     * Schluessel, die hier niemals auftauchen duerfen.
     *
     * <p>Zwei Gruende, beide schon einmal teuer geworden:</p>
     *
     * <ul>
     *   <li>Knotenspezifisch - derselbe Wert auf allen Knoten waere falsch.
     *       Bei den Shard-Grenzen bedeutet es, dass ein Teil der Server
     *       niemanden hat, der auf sie hoert.</li>
     *   <li>Geheimnis - gehoert in den verschluesselten Umschlag, nicht in
     *       eine Datei, die jeder Knoten unverschluesselt abholt.</li>
     * </ul>
     *
     * <p>Die Liste ist nicht nur Dokumentation: {@link #pruefen()} laeuft
     * beim Start dagegen, und die CI prueft zusaetzlich gegen das, was
     * {@code tresor.sh} tatsaechlich ausliefert.</p>
     */
    public static final List<String> VERBOTEN = List.of(
            // knotenspezifisch
            "HJ_NODE_NR", "HJ_NODE_NAME", "HJ_NODE_BIND", "HJ_PRIVAT_IP",
            "HJ_SHARD_VON", "HJ_SHARD_BIS", "HJ_BOT_ID",
            "LAVALINK_BIND", "LAVALINK_TIER", "HJ_WEB_BIND", "HJ_WEB_PORT_HOST",
            // Oeffnet die Datenbank nach aussen. Zentral gesetzt hiesse: auf
            // allen Knoten gleichzeitig, und auf einem davon vielleicht auf
            // einer oeffentlichen Adresse. Das entscheidet man je Maschine.
            "HJ_DB_ZUGANG", "HJ_DB_BIND", "HJ_DB_PORT_HOST",
            "HJ_NETZ",
            // Geheimnisse und Ausweise
            "HJ_BOT_TOKEN", "HJ_DB_PASSWORD", "HJ_DISCORD_CLIENT_SECRET",
            "HJ_DISCORD_CLIENT_ID", "HJ_LAVALINK_PASSWORD", "YT_CIPHER_PASSWORD",
            "YOUTUBE_REFRESH_TOKEN", "HJ_HETZNER_TOKEN", "HJ_AUTOSCALE_SSH_KEYS",
            "HJ_NODE_TOKEN", "HJ_AGENT_TOKEN", "HJ_CONTROLLER_TOKEN",
            "HJ_GEHEIMNIS_SCHLUESSEL",
            // liefert schon der Tresor
            "HJ_UPDATE_HOST", "HJ_DB_HOST", "HJ_DB_PORT", "HJ_DB_NAME", "HJ_DB_USER",
            "HJ_WEB_BASE_URL", "HJ_LLM_OLLAMA_URL", "HJ_LLM_MODEL",
            // setzt auto-update.sh aus dem Manifest
            "HJ_REGISTRY", "CORE_TAG", "WEB_TAG", "LAVALINK_TAG", "KI_RADIO_TAG");

    public static List<Eintrag> alle() {
        return EINTRAEGE;
    }

    /** Was fuer dieses Profil ausgeliefert wird. */
    public static List<Eintrag> fuer(String profil) {
        return EINTRAEGE.stream().filter(e -> e.giltFuer(profil)).toList();
    }

    public static Optional<Eintrag> finden(String schluessel) {
        return EINTRAEGE.stream().filter(e -> e.schluessel().equals(schluessel)).findFirst();
    }

    /** Die Eintraege nach Gruppe, in der Reihenfolge des Katalogs. */
    public static Map<String, List<Eintrag>> nachGruppe() {
        Map<String, List<Eintrag>> gruppen = new LinkedHashMap<>();
        for (Eintrag e : EINTRAEGE) {
            gruppen.computeIfAbsent(e.gruppe(), g -> new java.util.ArrayList<>()).add(e);
        }
        return gruppen;
    }

    /**
     * Ist ein Wert fuer diesen Eintrag brauchbar?
     *
     * <p>Leer ist immer erlaubt und heisst "Vorgabe" - der Schluessel wird
     * dann gar nicht ausgeliefert, und der Knoten nimmt, was in der
     * Compose-Datei steht. Das ist der Unterschied zwischen "nicht gesetzt"
     * und "auf leer gesetzt", und er ist hier wichtig: eine leere Zeile in
     * der {@code .env} ueberschreibt die Vorgabe mit nichts.</p>
     */
    public static String pruefeWert(Eintrag e, String wert) {
        String w = wert == null ? "" : wert.trim();
        if (w.isEmpty()) {
            return "";
        }
        // Zeilenumbrueche wuerden die .env zerlegen: was nach dem Umbruch
        // steht, waere ein eigener Schluessel.
        if (w.contains("\n") || w.contains("\r")) {
            throw new IllegalArgumentException(
                    e.schluessel() + ": Zeilenumbrueche gehen nicht - die .env liest zeilenweise.");
        }
        switch (e.art()) {
            case SCHALTER -> {
                if (!w.equals("true") && !w.equals("false")) {
                    throw new IllegalArgumentException(
                            e.schluessel() + ": nur true oder false.");
                }
            }
            case ZAHL -> {
                if (!w.matches("\\d{1,9}")) {
                    throw new IllegalArgumentException(
                            e.schluessel() + ": nur ganze Zahlen.");
                }
            }
            case AUSWAHL -> {
                if (!e.auswahl().contains(w)) {
                    throw new IllegalArgumentException(
                            e.schluessel() + ": nicht in der Auswahl ("
                            + String.join(", ", e.auswahl()) + ").");
                }
            }
            case TEXT -> {
                if (w.length() > 500) {
                    throw new IllegalArgumentException(e.schluessel() + ": zu lang.");
                }
            }
        }
        return w;
    }

    /**
     * Der Katalog gegen sich selbst.
     *
     * <p>Laeuft beim Start. Ein Katalog, der einen verbotenen Schluessel
     * enthaelt, waere ein Fehler, den man sonst erst bemerkt, wenn alle
     * Knoten dieselbe Shard-Grenze haben.</p>
     */
    public static void pruefen() {
        for (Eintrag e : EINTRAEGE) {
            if (VERBOTEN.contains(e.schluessel())) {
                throw new IllegalStateException(
                        "Katalogfehler: " + e.schluessel() + " ist knotenspezifisch oder "
                        + "geheim und darf nicht zentral gesetzt werden.");
            }
            for (String p : e.profile()) {
                if (!PROFILE.contains(p)) {
                    throw new IllegalStateException(
                            "Katalogfehler: " + e.schluessel() + " nennt Profil " + p);
                }
            }
        }
        long verschieden = EINTRAEGE.stream().map(Eintrag::schluessel).distinct().count();
        if (verschieden != EINTRAEGE.size()) {
            throw new IllegalStateException("Katalogfehler: ein Schluessel steht doppelt.");
        }
    }
}
