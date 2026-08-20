package jetzt.hoer.updater.dienst;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Wessen Adresse zaehlt - und wem man sie glaubt.
 *
 * <h2>Das Problem</h2>
 *
 * Die echte Adresse eines Aufrufers steht hinter einem Proxy nicht mehr in
 * der Verbindung, sondern in einem Kopf: {@code CF-Connecting-IP} oder
 * {@code X-Forwarded-For}. Beides sind gewoehnliche Kopfzeilen. Jeder kann
 * sie schicken.
 *
 * <p>Solange der Dienst nur ueber die Proxy-Kette erreichbar ist, macht das
 * nichts: Cloudflare ueberschreibt {@code CF-Connecting-IP} mit dem, was es
 * selbst gesehen hat. Ist der Port dagegen offen, sucht sich jeder Aufrufer
 * seine Adresse selbst aus - und die IP-Freigabe ist wirkungslos, ohne dass
 * es irgendwo auffiele. Im Protokoll steht dann die erfundene Adresse.</p>
 *
 * <h2>Die Loesung</h2>
 *
 * Den Koepfen wird nur geglaubt, wenn die Gegenstelle der Verbindung selbst
 * ein bekannter Proxy ist. Kommt jemand direkt, gilt seine tatsaechliche
 * Adresse, und was er in die Koepfe geschrieben hat, wird verworfen.
 *
 * <p>Damit ist der Port gefahrlos zu oeffnen: die Freigabe wirkt in beiden
 * Faellen, nur die Quelle der Adresse unterscheidet sich.</p>
 */
@Service
public class Vorfeld {

    private final List<Netzbereich> proxys;

    public Vorfeld(@Value("${hj.proxy.vertrauen:127.0.0.1/32,::1/128,172.16.0.0/12,10.0.0.0/8,192.168.0.0/16}")
                   String bereiche) {
        java.util.List<Netzbereich> gesammelt = new java.util.ArrayList<>();
        for (String teil : bereiche.split(",")) {
            String t = teil.trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                gesammelt.add(Netzbereich.aus(t));
            } catch (RuntimeException schrott) {
                // Ein unbrauchbarer Eintrag darf den Start nicht verhindern -
                // sonst legt ein Tippfehler in der Umgebung den Dienst lahm.
                // Er faellt weg, und das steht im Log.
                org.slf4j.LoggerFactory.getLogger(Vorfeld.class)
                        .warn("Proxy-Bereich unbrauchbar, uebergangen: {}", t);
            }
        }
        this.proxys = List.copyOf(gesammelt);
    }

    /**
     * Die Adresse, die fuer Freigabe und Protokoll gilt.
     *
     * @param gegenstelle wer die Verbindung tatsaechlich aufgebaut hat
     *                    ({@code request.getRemoteAddr()})
     * @param cloudflare  Inhalt von {@code CF-Connecting-IP}, darf null sein
     * @param weitergereicht Inhalt von {@code X-Forwarded-For}, darf null sein
     */
    public String adresse(String gegenstelle, String cloudflare, String weitergereicht) {
        String direkt = saeubern(gegenstelle);

        // Kommt der Aufruf nicht von einem bekannten Proxy, zaehlt allein die
        // Verbindung. Die Koepfe koennen erfunden sein.
        if (!istProxy(direkt)) {
            return direkt;
        }

        if (cloudflare != null && !cloudflare.isBlank()) {
            return saeubern(cloudflare);
        }
        if (weitergereicht != null && !weitergereicht.isBlank()) {
            // Der *erste* Eintrag. Jede Zwischenstelle haengt hinten an, vorne
            // steht also der urspruengliche Aufrufer. Bei genau einer
            // Zwischenstelle waere der letzte richtig - die Entscheidung muss
            // zur tatsaechlichen Kette passen und nicht zu einer Faustregel.
            return saeubern(weitergereicht.split(",")[0]);
        }
        return direkt;
    }

    /** Ob diese Adresse als Zwischenstelle gilt und ihre Koepfe zaehlen. */
    public boolean istProxy(String adresse) {
        if (adresse == null || adresse.isBlank()) {
            return false;
        }
        return proxys.stream().anyMatch(b -> b.enthaelt(adresse));
    }

    /**
     * IPv4-gemappte IPv6-Adressen und Portangaben abschneiden.
     *
     * <p>Tomcat liefert hinter Docker gern {@code ::ffff:172.18.0.1}. Ohne
     * diesen Schritt faende der Vergleich gegen 172.16.0.0/12 nichts, und
     * jeder Aufruf aus dem eigenen Netz gaelte als fremd.</p>
     */
    private static String saeubern(String roh) {
        if (roh == null) {
            return "";
        }
        String a = roh.trim();
        if (a.startsWith("::ffff:")) {
            a = a.substring(7);
        }
        // "1.2.3.4:5678" - aber nicht bei IPv6, dort sind Doppelpunkte normal.
        int dp = a.indexOf(':');
        if (dp > 0 && a.indexOf(':', dp + 1) < 0 && a.contains(".")) {
            a = a.substring(0, dp);
        }
        return a;
    }
}
