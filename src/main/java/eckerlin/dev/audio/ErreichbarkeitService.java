package eckerlin.dev.audio;

import eckerlin.dev.web.dto.AudioNodeUsageView;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Wie schnell antworten die Gegenstellen.
 *
 * <p>Gemessen wird ein TCP-Verbindungsaufbau, kein ICMP-Ping. Zwei Gruende:
 * ein echter Ping braucht Rohsockel und damit erhoehte Rechte im Container,
 * und er beantwortet die falsche Frage. Dass eine Maschine auf ICMP antwortet,
 * heisst nicht, dass der Dienst darauf lauscht - beim Loadbalancer ist genau
 * das der interessante Unterschied.</p>
 *
 * <p>Alle Ziele werden nebenlaeufig gemessen. Nacheinander waere die
 * Gesamtdauer die Summe aller Zeitlimits, und ein einziges totes Ziel wuerde
 * die Ansicht um Sekunden verzoegern.</p>
 */
@Service
public class ErreichbarkeitService {

    private static final int ZEITLIMIT_MS = 2500;

    private final AudioService audioService;
    private final KnotenRegistrierungService registrierung;

    public ErreichbarkeitService(AudioService audioService, KnotenRegistrierungService registrierung) {
        this.audioService = audioService;
        this.registrierung = registrierung;
    }

    /**
     * @param art  loadbalancer, knoten, agent oder dienst - fuer die Gruppierung
     *             in der Oberflaeche
     * @param ms   Antwortzeit in Millisekunden, -1 wenn nicht erreichbar
     */
    public record Messung(String name, String art, String ziel, boolean erreichbar, long ms, String meldung) {
    }

    public List<Messung> messen() {
        List<Callable<Messung>> auftraege = new ArrayList<>();

        for (Ziel ziel : ziele()) {
            auftraege.add(() -> messe(ziel));
        }

        // Ein eigener Pool je Aufruf: die Messung laeuft selten und kurz, ein
        // dauerhafter Pool waere hier nur ein Thread, der 99 % der Zeit
        // schlaeft.
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(12, auftraege.size())));
        try {
            List<Messung> ergebnisse = new ArrayList<>();
            for (Future<Messung> aufgabe : pool.invokeAll(auftraege, ZEITLIMIT_MS + 1500L, TimeUnit.MILLISECONDS)) {
                try {
                    ergebnisse.add(aufgabe.get());
                } catch (Exception fehler) {
                    // Abgelaufen oder abgebrochen - das ist selbst die Auskunft.
                }
            }
            return ergebnisse;
        } catch (InterruptedException fehler) {
            Thread.currentThread().interrupt();
            return List.of();
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ intern

    private record Ziel(String name, String art, String host, int port) {
    }

    private List<Ziel> ziele() {
        Set<Ziel> gesammelt = new LinkedHashSet<>();

        // Der Loadbalancer davor. Ohne ihn ist die Weboberflaeche fuer
        // niemanden erreichbar, auch wenn der Bot selbst tadellos laeuft -
        // deshalb steht er hier an erster Stelle.
        for (String eintrag : umgebung("HJ_PING_ZIELE", "Loadbalancer=77.42.8.217:443").split("[,;]")) {
            String sauber = eintrag.trim();
            if (sauber.isEmpty()) {
                continue;
            }
            String name = sauber.contains("=") ? sauber.substring(0, sauber.indexOf('=')).trim() : sauber;
            String adresse = sauber.contains("=") ? sauber.substring(sauber.indexOf('=') + 1).trim() : sauber;
            zerlegen(adresse, 443).ifPresent(teile ->
                    gesammelt.add(new Ziel(name, "dienst", teile.host(), teile.port())));
        }

        for (AudioNodeUsageView knoten : audioService.knotenAuslastung()) {
            zerlegen(knoten.adresse(), 2333).ifPresent(teile ->
                    gesammelt.add(new Ziel(knoten.name(), "knoten", teile.host(), teile.port())));

            registrierung.agentUrl(knoten.name()).flatMap(url -> zerlegen(url, 8099)).ifPresent(teile ->
                    gesammelt.add(new Ziel(knoten.name() + " (Agent)", "agent", teile.host(), teile.port())));
        }

        return new ArrayList<>(gesammelt);
    }

    private Messung messe(Ziel ziel) {
        long beginn = System.nanoTime();
        try (Socket steckdose = new Socket()) {
            steckdose.connect(new InetSocketAddress(ziel.host(), ziel.port()), ZEITLIMIT_MS);
            long ms = (System.nanoTime() - beginn) / 1_000_000;
            return new Messung(ziel.name(), ziel.art(), ziel.host() + ":" + ziel.port(), true, ms, "");
        } catch (java.io.IOException fehler) {
            String grund = fehler.getMessage() == null ? fehler.getClass().getSimpleName() : fehler.getMessage();
            return new Messung(ziel.name(), ziel.art(), ziel.host() + ":" + ziel.port(), false, -1, grund);
        }
    }

    private record HostPort(String host, int port) {
    }

    private java.util.Optional<HostPort> zerlegen(String adresse, int vorgabePort) {
        if (adresse == null || adresse.isBlank()) {
            return java.util.Optional.empty();
        }
        String sauber = adresse.trim();

        try {
            if (sauber.contains("://")) {
                URI uri = URI.create(sauber);
                if (uri.getHost() == null) {
                    return java.util.Optional.empty();
                }
                int port = uri.getPort() > 0 ? uri.getPort()
                        : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : vorgabePort;
                return java.util.Optional.of(new HostPort(uri.getHost(), port));
            }

            int doppelpunkt = sauber.lastIndexOf(':');
            if (doppelpunkt > 0 && doppelpunkt == sauber.indexOf(':')) {
                return java.util.Optional.of(new HostPort(
                        sauber.substring(0, doppelpunkt),
                        Integer.parseInt(sauber.substring(doppelpunkt + 1))));
            }
            return java.util.Optional.of(new HostPort(sauber, vorgabePort));
        } catch (RuntimeException fehler) {
            return java.util.Optional.empty();
        }
    }

    private String umgebung(String name, String vorgabe) {
        String wert = System.getenv(name);
        return wert == null || wert.isBlank() ? vorgabe : wert.trim();
    }
}
