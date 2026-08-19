package jetzt.hoer.updater.dienst;

import jetzt.hoer.updater.daten.FreigabeDaten;
import jetzt.hoer.updater.daten.ZugriffDaten;
import jetzt.hoer.updater.modell.Freigabe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entscheidet, ob eine Adresse an die Abbilder, das Release und den Tresor darf.
 *
 * Warum das hier steht und nicht in der Caddy-Konfiguration: Caddys
 * remote_ip-Vergleich will die Adressen in der Konfigurationsdatei haben.
 * Jede Freischaltung waere ein Schreibvorgang plus ein Neuladen des
 * Webservers - waehrend moeglicherweise gerade ein Knoten zieht. Hier ist
 * eine Freischaltung eine Zeile in der Datenbank und gilt sofort.
 *
 * Der Zwischenspeicher ist kein Feinschliff. Ein "docker pull" fragt je
 * Abbildschicht einmal nach; bei vier Abbildern sind das schnell mehrere
 * hundert Anfragen in wenigen Sekunden, und jede einzelne wuerde sonst die
 * Freigabeliste neu laden und neu vergleichen.
 */
@Service
public class Torwaechter {

    private static final Logger log = LoggerFactory.getLogger(Torwaechter.class);

    /**
     * Wie lange eine getroffene Entscheidung wiederverwendet wird. Kurz genug,
     * dass eine Sperre auch ohne ausdrueckliches Verwerfen binnen einer halben
     * Minute greift - das ist die Sicherung fuer den Fall, dass irgendwo ein
     * verwerfen() vergessen wurde.
     */
    private static final Duration HALTBAR = Duration.ofSeconds(30);

    /** Derselbe Grund von derselben Adresse landet hoechstens so oft im Protokoll. */
    private static final Duration PROTOKOLL_TAKT = Duration.ofMinutes(1);

    private record Entscheid(boolean erlaubt, String grund, Instant ablauf) {
    }

    private final FreigabeDaten freigaben;
    private final ZugriffDaten zugriffe;

    private final Map<String, Entscheid> zwischenspeicher = new ConcurrentHashMap<>();
    private final Map<String, Instant> zuletztProtokolliert = new ConcurrentHashMap<>();

    public Torwaechter(FreigabeDaten freigaben, ZugriffDaten zugriffe) {
        this.freigaben = freigaben;
        this.zugriffe = zugriffe;
    }

    /**
     * @param adresse die Gegenstelle, wie Caddy sie meldet
     * @param pfad    nur zur Protokollierung
     * @return true, wenn durchgelassen werden darf
     */
    public boolean darf(String adresse, String pfad) {
        if (adresse == null || adresse.isBlank()) {
            protokollieren(adresse, pfad, false, "keine Adresse");
            return false;
        }

        Entscheid gemerkt = zwischenspeicher.get(adresse);
        Instant jetzt = Instant.now();

        if (gemerkt == null || gemerkt.ablauf().isBefore(jetzt)) {
            gemerkt = entscheiden(adresse, jetzt);
            zwischenspeicher.put(adresse, gemerkt);
        }

        protokollieren(adresse, pfad, gemerkt.erlaubt(), gemerkt.grund());
        return gemerkt.erlaubt();
    }

    private Entscheid entscheiden(String adresse, Instant jetzt) {
        List<Freigabe> liste = freigaben.gueltige();
        for (Freigabe f : liste) {
            try {
                if (Netzbereich.aus(f.bereich()).enthaelt(adresse)) {
                    return new Entscheid(true, f.name().isBlank() ? f.bereich() : f.name(),
                            jetzt.plus(HALTBAR));
                }
            } catch (IllegalArgumentException e) {
                // Eine unlesbare Zeile darf nicht die ganze Liste lahmlegen -
                // sonst sperrt ein Tippfehler in einem Eintrag alle Knoten aus.
                log.warn("Freigabe {} ist unlesbar und wird uebergangen: {}",
                        f.id(), e.getMessage());
            }
        }
        return new Entscheid(false, "nicht freigeschaltet", jetzt.plus(HALTBAR));
    }

    /**
     * Nach jeder Aenderung an den Freigaben aufzurufen. Ohne das wuerde eine
     * Sperre erst nach Ablauf der Haltbarkeit wirken - und genau in dem
     * Moment, in dem man einen Knoten sperrt, will man nicht dreissig
     * Sekunden warten.
     */
    public void verwerfen() {
        zwischenspeicher.clear();
    }

    /**
     * Ein ziehender Knoten erzeugt Hunderte Anfragen. Ungebremst stuende danach
     * dasselbe Ereignis hundertfach im Protokoll und die eine abgelehnte
     * Anfrage, die man sucht, waere darin nicht mehr zu finden.
     */
    private void protokollieren(String adresse, String pfad, boolean erlaubt, String grund) {
        String schluessel = adresse + "|" + bereich(pfad) + "|" + erlaubt + "|" + grund;
        Instant jetzt = Instant.now();
        Instant letztes = zuletztProtokolliert.get(schluessel);
        if (letztes != null && letztes.plus(PROTOKOLL_TAKT).isAfter(jetzt)) {
            return;
        }
        zuletztProtokolliert.put(schluessel, jetzt);
        try {
            zugriffe.merken(adresse == null ? "" : adresse, bereich(pfad), erlaubt, grund);
        } catch (RuntimeException e) {
            // Ein volles Dateisystem darf keine Knoten aussperren. Das
            // Protokoll ist die Nebensache, das Durchlassen die Hauptsache.
            log.warn("Zugriff liess sich nicht protokollieren: {}", e.getMessage());
        }
    }

    /**
     * Aus "/v2/hoerjetzt/core/blobs/sha256:..." wird "/v2/". Die vollstaendige
     * Adresse waere je Schicht eine andere Zeile und traegt fuer die Frage,
     * die man an dieses Protokoll stellt, nichts bei.
     */
    private static String bereich(String pfad) {
        if (pfad == null || pfad.isBlank()) return "?";
        for (String bekannt : new String[]{"/v2/", "/release/", "/tresor/", "/knoten/", "/melden"}) {
            if (pfad.startsWith(bekannt)) return bekannt;
        }
        int zweiter = pfad.indexOf('/', 1);
        return zweiter > 0 ? pfad.substring(0, zweiter + 1) : pfad;
    }

    /** Fuer die Oberflaeche: wie viele Entscheidungen gerade gemerkt sind. */
    public int gemerkt() {
        return zwischenspeicher.size();
    }
}
