package eckerlin.dev.verbund;

import eckerlin.dev.utils.Alert;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Schickt Dashboard-Anfragen an die Node, die den Server fuehrt.
 *
 * <h2>Warum es das braucht</h2>
 *
 * <p>Mit aufgeteilten Shards kennt jede Node nur ihre eigene Haelfte der
 * Discord-Server. Der Lastverteiler weiss davon nichts und schickt einen
 * Benutzer auf irgendeine der beiden. Landet er auf der falschen, findet der
 * Bot seinen Server schlicht nicht - fuer den Benutzer sieht das aus, als sei
 * der Bot dort nicht mehr drin.</p>
 *
 * <p>Dieser Filter faengt genau diesen Fall ab: Server-Kennung aus dem Pfad
 * lesen, Shard ausrechnen, und wenn er nicht hierher gehoert, die Anfrage
 * unveraendert an die zustaendige Node weiterreichen.</p>
 *
 * <h2>Warum die Anmeldung dabei haelt</h2>
 *
 * <p>Weitergereicht wird samt {@code Cookie}-Kopfzeile. Die Sitzungen liegen
 * in der gemeinsamen Datenbank (Spring Session) und werden repliziert - die
 * andere Node erkennt dieselbe Sitzung also wieder. Ohne diese Voraussetzung
 * waere die Weiterleitung eine Abmeldung.</p>
 *
 * <h2>Was bewusst nicht weitergereicht wird</h2>
 *
 * <p>Die Kopfzeile {@code X-hoerjetzt-weitergeleitet} markiert eine bereits
 * weitergeleitete Anfrage. Kommt sie herein, wird nicht noch einmal
 * weitergereicht - sonst koennten sich zwei Nodes bei einem Fehler im
 * Verzeichnis gegenseitig im Kreis schicken, bis die Verbindungen ausgehen.</p>
 */
@Component
@Order(20)
public class GuildWeiterleitung extends OncePerRequestFilter {

    /**
     * Nur Anfragen zu einem konkreten Server - Listen und Allgemeines nicht.
     *
     * <p>Der Verwaltungsbereich gehoert mit dazu: „Bot verlaesst diesen Server“
     * braucht das Guild-Objekt und laeuft auf der falschen Node ins Leere.
     * (Freigaben landen zwar in der Datenbank und wuerden auch so wirken - sie
     * hier auszunehmen waere aber eine Sonderregel, die man beim naechsten
     * Endpunkt vergisst.)</p>
     */
    private static final Pattern PFAD =
            Pattern.compile("^/api/(?:dashboard|admin/management)/guilds/(\\d+)(/.*)?$");

    /**
     * Zweiter Weg zur Server-Kennung: der Abfrageparameter.
     *
     * <p>Das Muster oben setzt voraus, dass die Kennung im Pfad steht. Das ist
     * die Hausregel, aber sie war nicht durchgehalten:
     * {@code /api/dashboard/radio/stations?guildId=…} traegt sie im
     * Abfrageteil. Diese Anfrage wurde deshalb nie weitergeleitet und lief auf
     * der Node, die den Server gar nicht kennt - Ergebnis war „Der Bot ist auf
     * diesem Server nicht (mehr) vorhanden“ auf der Radioseite, obwohl der Bot
     * dort steht.</p>
     *
     * <p>Der Fehler ist stumm: er trifft nur die Haelfte der Server (die auf
     * dem anderen Shard) und sieht aus wie ein geloeschter Server. Deshalb
     * nicht nur den einen Endpunkt umgebaut, sondern hier nachgesehen - sonst
     * faellt der naechste Endpunkt genauso hinein.</p>
     */
    private static final Pattern NUR_ZIFFERN = Pattern.compile("\\d{5,32}");

    private static final String MARKE = "X-hoerjetzt-weitergeleitet";

    /** Kopfzeilen, die der Zielserver selbst setzt und die nicht kopiert werden duerfen. */
    private static final List<String> NICHT_KOPIEREN = List.of(
            "host", "content-length", "connection", "transfer-encoding", "accept-encoding");

    private static final int VERBINDUNGS_TIMEOUT_MS = 5000;
    private static final int LESE_TIMEOUT_MS = 20000;

    private final KnotenVerzeichnis verzeichnis;
    private final EigeneNode eigene;

    public GuildWeiterleitung(KnotenVerzeichnis verzeichnis, EigeneNode eigene) {
        this.verzeichnis = verzeichnis;
        this.eigene = eigene;
    }

    /**
     * Die Server-Kennung einer Anfrage - aus dem Pfad, sonst aus dem
     * Abfrageparameter. {@code null}, wenn die Anfrage keinen Server meint.
     *
     * <p>Der Pfad hat Vorrang: steht die Kennung dort, ist sie Teil der
     * Adresse und damit verbindlich. Der Parameter ist der Nachzuegler fuer
     * die Endpunkte, die sich nicht an die Hausregel halten.</p>
     */
    private static String guildIdVon(HttpServletRequest anfrage) {
        Matcher treffer = PFAD.matcher(anfrage.getRequestURI());
        if (treffer.matches()) {
            return treffer.group(1);
        }

        // Nur unterhalb von /api/dashboard und /api/admin - sonst wuerde ein
        // beliebiger Endpunkt mit einem gleichnamigen Parameter mitgerissen.
        String pfad = anfrage.getRequestURI();
        if (!pfad.startsWith("/api/dashboard/") && !pfad.startsWith("/api/admin/")) {
            return null;
        }

        // Bewusst der rohe Abfrageteil und nicht getParameter(): Letzteres
        // liest bei einem Formular-POST den Rumpf mit, und genau den muss
        // dieser Filter danach unveraendert weiterreichen. Einmal gelesen ist
        // der Strom leer, und die Weiterleitung schickt eine leere Anfrage -
        // ein Fehler, der erst beim Schreiben auffiele, nicht beim Lesen.
        String abfrage = anfrage.getQueryString();
        if (abfrage == null) {
            return null;
        }
        for (String paar : abfrage.split("&")) {
            int gleich = paar.indexOf('=');
            if (gleich < 0 || !"guildId".equals(paar.substring(0, gleich))) {
                continue;
            }
            String wert = paar.substring(gleich + 1);
            return NUR_ZIFFERN.matcher(wert).matches() ? wert : null;
        }
        return null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest anfrage, HttpServletResponse antwort, FilterChain kette)
            throws ServletException, IOException {

        if (anfrage.getHeader(MARKE) != null) {
            kette.doFilter(anfrage, antwort);
            return;
        }

        String guildId = guildIdVon(anfrage);
        if (guildId == null) {
            kette.doFilter(anfrage, antwort);
            return;
        }

        Optional<KnotenVerzeichnis.Knoten> ziel = verzeichnis.fuer(guildId, eigene.name());
        if (ziel.isEmpty()) {
            kette.doFilter(anfrage, antwort);
            return;
        }

        try {
            weiterreichen(anfrage, antwort, ziel.get());
        } catch (IOException fehler) {
            // Die andere Node antwortet nicht. Selbst versuchen ist sinnlos -
            // der Server liegt dort. Eine klare Meldung ist mehr wert als ein
            // "nicht gefunden", das nach einem geloeschten Server aussieht.
            Alert.send("WARN", "VERBUND", "Weiterleitung an " + ziel.get().name()
                    + " fehlgeschlagen: " + fehler.getMessage());
            antwort.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
            antwort.setContentType("application/json;charset=UTF-8");
            antwort.getWriter().write(
                    "{\"message\":\"Die Node, die diesen Server führt (" + ziel.get().name()
                    + "), antwortet gerade nicht.\"}");
        }
    }

    private void weiterreichen(HttpServletRequest anfrage, HttpServletResponse antwort,
                               KnotenVerzeichnis.Knoten ziel) throws IOException {
        String abfrage = anfrage.getQueryString();
        URI adresse = URI.create(ziel.basis() + anfrage.getRequestURI()
                + (abfrage == null || abfrage.isBlank() ? "" : "?" + abfrage));

        HttpURLConnection verbindung = (HttpURLConnection) adresse.toURL().openConnection();
        verbindung.setRequestMethod(anfrage.getMethod());
        verbindung.setConnectTimeout(VERBINDUNGS_TIMEOUT_MS);
        verbindung.setReadTimeout(LESE_TIMEOUT_MS);
        verbindung.setInstanceFollowRedirects(false);
        verbindung.setRequestProperty(MARKE, eigene.name());

        anfrage.getHeaderNames().asIterator().forEachRemaining(name -> {
            if (!NICHT_KOPIEREN.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                verbindung.setRequestProperty(name, anfrage.getHeader(name));
            }
        });

        if (!"GET".equalsIgnoreCase(anfrage.getMethod()) && !"HEAD".equalsIgnoreCase(anfrage.getMethod())) {
            verbindung.setDoOutput(true);
            try (InputStream rein = anfrage.getInputStream(); OutputStream raus = verbindung.getOutputStream()) {
                rein.transferTo(raus);
            }
        }

        int status = verbindung.getResponseCode();
        antwort.setStatus(status);
        verbindung.getHeaderFields().forEach((name, werte) -> {
            if (name == null || NICHT_KOPIEREN.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                return;
            }
            werte.forEach(wert -> antwort.addHeader(name, wert));
        });

        // Bei einem Fehlerstatus liefert HttpURLConnection den Rumpf ueber
        // getErrorStream(); getInputStream() wuerfe dann eine Ausnahme und die
        // eigentliche Fehlermeldung ginge verloren.
        try (InputStream quelle = status >= 400 ? verbindung.getErrorStream() : verbindung.getInputStream()) {
            if (quelle != null) {
                quelle.transferTo(antwort.getOutputStream());
            }
        } finally {
            verbindung.disconnect();
        }
    }
}
