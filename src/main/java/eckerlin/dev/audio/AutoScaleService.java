package eckerlin.dev.audio;

import eckerlin.dev.utils.Alert;
import eckerlin.dev.utils.Config;
import eckerlin.dev.utils.DB;
import eckerlin.dev.web.dto.AudioNodeUsageView;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Nimmt bei Last einen Knoten dazu und baut ihn bei Leerlauf wieder ab.
 *
 * <h2>Warum nicht sofort bei 75 Prozent</h2>
 *
 * <p>Eine einzelne Messung ueber der Schwelle heisst wenig: ein startender
 * Titel, ein Filterwechsel, ein Playlist-Import - die Last springt staendig.
 * Wer darauf reagiert, erzeugt Server fuer Lastspitzen, die zehn Sekunden
 * dauern, und zahlt sie einen Monat lang. Deshalb muss die Schwelle
 * durchgehend ueber mehrere Messungen halten, bevor etwas passiert.</p>
 *
 * <h2>Warum der Abbau viel traeger ist als der Aufbau</h2>
 *
 * <p>Ein Knoten zu wenig heisst stotternde Musik, ein Knoten zu viel heisst
 * vier Euro im Monat. Diese beiden Fehler sind nicht gleich teuer, also
 * duerfen sie nicht gleich schnell ausgeloest werden. Aufbau nach wenigen
 * Minuten, Abbau erst nach einer halben Stunde Ruhe - und nie, solange auf
 * dem Knoten noch etwas spielt.</p>
 *
 * <h2>Was es nicht anfasst</h2>
 *
 * <p>Nur Knoten mit {@code herkunft = 'auto'}. Ein von Hand eingetragener oder
 * selbst angemeldeter Knoten gehoert jemandem - den raeumt hier nichts ab,
 * auch nicht, wenn er tagelang still ist.</p>
 */
@Service
public class AutoScaleService {

    /** Wie oft nachgesehen wird. */
    private static final Duration TAKT = Duration.ofSeconds(60);

    private final AudioService audioService;
    private final HetznerService hetznerService;
    private final int botId = Config.config.optInt("bot_id", 1);

    private final ScheduledExecutorService wache =
            Executors.newSingleThreadScheduledExecutor(auftrag -> {
                Thread strang = new Thread(auftrag, "autoscale");
                strang.setDaemon(true);
                return strang;
            });

    /** Seit wann die Last ununterbrochen ueber der Schwelle liegt. */
    private volatile Instant ueberSchwelleSeit;
    /** Seit wann Ruhe herrscht - Grundlage fuer den Abbau. */
    private volatile Instant ruheSeit;
    /** Bis wann nach einem Aufbau nichts weiter passiert. */
    private volatile Instant sperreBis = Instant.EPOCH;
    private volatile String letzteMeldung = "noch nichts entschieden";

    public AutoScaleService(AudioService audioService, HetznerService hetznerService) {
        this.audioService = audioService;
        this.hetznerService = hetznerService;
    }

    @PostConstruct
    public void starten() {
        wache.scheduleWithFixedDelay(this::nachsehenLeise, 90, TAKT.toSeconds(), TimeUnit.SECONDS);
    }

    @PreDestroy
    public void beenden() {
        wache.shutdownNow();
    }

    // ------------------------------------------------------------------ Zustand

    public record Lage(
            boolean eingeschaltet,
            boolean tokenVorhanden,
            double schwelle,
            int obergrenze,
            int autoKnoten,
            double hoechstlast,
            String meldung
    ) {
    }

    public Lage lage() {
        List<AudioNodeUsageView> knoten = audioService.knotenAuslastung();
        return new Lage(
                eingeschaltet(),
                hetznerService.eingeschaltet(),
                schwelle(),
                obergrenze(),
                autoKnotenNamen().size(),
                hoechstlast(knoten),
                letzteMeldung
        );
    }

    // ------------------------------------------------------------------ Kern

    private void nachsehenLeise() {
        try {
            nachsehen();
        } catch (RuntimeException fehler) {
            // Ein Taktgeber, der eine Ausnahme durchlaesst, wird von
            // scheduleWithFixedDelay stillschweigend abgeschaltet - und niemand
            // merkt es, bis Wochen spaeter kein Knoten mehr dazukommt.
            Alert.send("WARN", "AUDIO", "Autoscaling-Durchlauf gescheitert: " + fehler.getMessage());
        }
    }

    private void nachsehen() {
        if (!eingeschaltet() || !hetznerService.eingeschaltet()) {
            return;
        }
        if (Instant.now().isBefore(sperreBis)) {
            return;
        }

        List<AudioNodeUsageView> knoten = audioService.knotenAuslastung();
        if (knoten.isEmpty()) {
            return;
        }

        double last = hoechstlast(knoten);
        boolean allesUeber = knoten.stream()
                .filter(AudioNodeUsageView::erreichbar)
                .allMatch(k -> k.cpuLast() >= schwelle());
        boolean voll = knoten.stream()
                .filter(AudioNodeUsageView::erreichbar)
                .allMatch(this::amAnschlag);

        if ((allesUeber || voll) && knoten.stream().anyMatch(AudioNodeUsageView::erreichbar)) {
            ruheSeit = null;
            if (ueberSchwelleSeit == null) {
                ueberSchwelleSeit = Instant.now();
            }
            if (Duration.between(ueberSchwelleSeit, Instant.now()).compareTo(aufbauNach()) >= 0) {
                aufbauen(last);
            } else {
                letzteMeldung = "Last bei %.0f %% - beobachte noch.".formatted(last * 100);
            }
            return;
        }

        ueberSchwelleSeit = null;

        if (last < abbauSchwelle()) {
            if (ruheSeit == null) {
                ruheSeit = Instant.now();
            }
            if (Duration.between(ruheSeit, Instant.now()).compareTo(abbauNach()) >= 0) {
                abbauen();
            }
        } else {
            ruheSeit = null;
            letzteMeldung = "Last bei %.0f %% - alles im Rahmen.".formatted(last * 100);
        }
    }

    private void aufbauen(double last) {
        List<String> vorhanden = autoKnotenNamen();
        if (vorhanden.size() >= obergrenze()) {
            letzteMeldung = "Last bei %.0f %%, aber die Obergrenze von %d Knoten ist erreicht."
                    .formatted(last * 100, obergrenze());
            // Einmal warnen, nicht jede Minute: die Meldung steht oben in der
            // Oberflaeche, das reicht als Dauerzustand.
            sperreBis = Instant.now().plus(Duration.ofMinutes(30));
            Alert.send("WARN", "AUDIO", letzteMeldung);
            return;
        }

        String name = naechsterName();
        Optional<HetznerService.Server> erzeugt = hetznerService.anlegen(name, "free");
        if (erzeugt.isEmpty()) {
            letzteMeldung = "Knoten %s liess sich nicht anlegen - siehe Protokoll.".formatted(name);
            sperreBis = Instant.now().plus(Duration.ofMinutes(10));
            return;
        }

        merken(name, erzeugt.get().id());
        ueberSchwelleSeit = null;

        // Der Server braucht ein paar Minuten, bis er sich anmeldet. In dieser
        // Zeit ist die Last unveraendert hoch - ohne Sperre wuerde bis zur
        // Obergrenze hochskaliert, bevor der erste neue Knoten ueberhaupt da
        // ist.
        sperreBis = Instant.now().plus(anlaufzeit());
        letzteMeldung = "Knoten %s angelegt (Last war %.0f %%). Er meldet sich in wenigen Minuten."
                .formatted(name, last * 100);
        Alert.send("INFO", "AUDIO", letzteMeldung);
    }

    private void abbauen() {
        List<AudioNodeUsageView> knoten = audioService.knotenAuslastung();
        Map<String, Long> auto = autoKnoten();
        if (auto.isEmpty()) {
            return;
        }

        // Nur einen leeren Knoten abbauen, und nur wenn danach noch einer
        // uebrig bleibt. Der letzte Knoten wird nie abgeraeumt, auch nicht bei
        // voelliger Stille - sonst ist der Bot beim naechsten /play stumm und
        // wartet Minuten auf einen neuen Server.
        long ueberlebende = knoten.stream().filter(AudioNodeUsageView::erreichbar).count();
        if (ueberlebende <= 1) {
            return;
        }

        // Nur "spielend" blockiert den Abbau, nicht "gesamt".
        //
        // Zuerst stand hier beides. Das klang vorsichtig, hiess in der Praxis
        // aber, dass nie abgebaut wurde: bei vier Knoten und verstreuten
        // Servern ist selten einer voellig leer, und stille Verbindungen
        // loesen sich nicht von selbst auf. Der Bot haette also hochskaliert
        // und die Server bis zum Monatsende behalten.
        //
        // Eine stille Verbindung umzuhaengen ist harmlos - es laeuft ja kein
        // Ton, der abreissen koennte. Die Bibliothek verteilt sie beim
        // Wegfallen des Knotens von selbst neu.
        for (AudioNodeUsageView eintrag : knoten) {
            Long hetznerId = auto.get(eintrag.name());
            if (hetznerId == null || eintrag.spielend() > 0) {
                continue;
            }

            if (eintrag.gesamt() > 0) {
                Alert.send("INFO", "AUDIO", "Knoten %s wird abgebaut - %d stille Verbindung(en) ziehen um."
                        .formatted(eintrag.name(), eintrag.gesamt()));
            }

            if (hetznerService.loeschen(hetznerId)) {
                vergessen(eintrag.name());
                audioService.knotenNeuEinlesen();
                ruheSeit = null;
                sperreBis = Instant.now().plus(Duration.ofMinutes(10));
                letzteMeldung = "Knoten %s war leer und wurde abgebaut.".formatted(eintrag.name());
                Alert.send("INFO", "AUDIO", letzteMeldung);
            }
            return;
        }

        letzteMeldung = "Wenig los, aber auf jedem Autoscaling-Knoten läuft noch etwas.";
    }

    // ------------------------------------------------------------------ Daten

    /** Von der Weboberflaeche aus: einen Knoten von Hand anlegen. Erwartet TOTP. */
    public Optional<String> vonHandAnlegen(String stufe) {
        if (!hetznerService.eingeschaltet()) {
            return Optional.empty();
        }
        String name = naechsterName();
        Optional<HetznerService.Server> erzeugt = hetznerService.anlegen(name, stufe);
        if (erzeugt.isEmpty()) {
            return Optional.empty();
        }
        merken(name, erzeugt.get().id());
        return Optional.of(name);
    }

    /**
     * Traegt den kommenden Knoten schon vor seiner Anmeldung ein.
     *
     * <p>Ohne diesen Vorabeintrag waere er zwischen Erzeugung und Anmeldung
     * unsichtbar - und da das mehrere Minuten dauert, wuerde der naechste
     * Durchlauf ihn nicht mitzaehlen und einen zweiten anlegen. Die Adresse
     * bleibt vorerst leer und {@code enabled} falsch: der Bot soll ihn noch
     * nicht anzusprechen versuchen.</p>
     */
    private void merken(String name, long hetznerId) {
        try (Connection verbindung = DB.connection();
             PreparedStatement anweisung = verbindung.prepareStatement("""
                     INSERT INTO deployment_lavalink_nodes
                         (bot_id, deployment_key, node_name, server_uri, tier, enabled, herkunft, hetzner_id)
                     VALUES (?, 'standard', ?, '', 'free', false, 'auto', ?)
                     """)) {
            anweisung.setInt(1, botId);
            anweisung.setString(2, name);
            anweisung.setLong(3, hetznerId);
            anweisung.executeUpdate();
        } catch (SQLException fehler) {
            Alert.send("WARN", "AUDIO", "Neuer Knoten %s konnte nicht vorgemerkt werden: %s"
                    .formatted(name, fehler.getMessage()));
        }
    }

    private void vergessen(String name) {
        try (Connection verbindung = DB.connection();
             PreparedStatement anweisung = verbindung.prepareStatement(
                     "DELETE FROM deployment_lavalink_nodes WHERE bot_id = ? AND node_name = ? AND herkunft = 'auto'")) {
            anweisung.setInt(1, botId);
            anweisung.setString(2, name);
            anweisung.executeUpdate();
        } catch (SQLException fehler) {
            Alert.send("WARN", "AUDIO", "Knoten %s konnte nicht ausgetragen werden: %s"
                    .formatted(name, fehler.getMessage()));
        }
    }

    /** Name → Hetzner-ID, nur die selbst erzeugten. */
    private Map<String, Long> autoKnoten() {
        Map<String, Long> gefunden = new LinkedHashMap<>();
        try (Connection verbindung = DB.connection();
             PreparedStatement anweisung = verbindung.prepareStatement("""
                     SELECT node_name, hetzner_id FROM deployment_lavalink_nodes
                      WHERE bot_id = ? AND herkunft = 'auto' AND hetzner_id IS NOT NULL
                     """)) {
            anweisung.setInt(1, botId);
            try (ResultSet ergebnis = anweisung.executeQuery()) {
                while (ergebnis.next()) {
                    gefunden.put(ergebnis.getString("node_name"), ergebnis.getLong("hetzner_id"));
                }
            }
        } catch (SQLException fehler) {
            Alert.send("WARN", "AUDIO", "Autoscaling-Knoten nicht lesbar: " + fehler.getMessage());
        }
        return gefunden;
    }

    private List<String> autoKnotenNamen() {
        return new ArrayList<>(autoKnoten().keySet());
    }

    /**
     * Der naechste freie Name.
     *
     * <p>Er wird zugleich der Rechnername des Servers und damit - weil der
     * Agent {@code hostname -s} nimmt - der Knotenname. Deshalb nur Zeichen,
     * die als Hostname zulaessig sind.</p>
     */
    private String naechsterName() {
        List<String> vergeben = autoKnotenNamen();
        for (int nummer = 1; nummer <= 99; nummer++) {
            String vorschlag = "hj-auto-" + nummer;
            if (!vergeben.contains(vorschlag)) {
                return vorschlag;
            }
        }
        return "hj-auto-" + System.currentTimeMillis() % 100000;
    }

    // ------------------------------------------------------------------ Regler

    private boolean amAnschlag(AudioNodeUsageView knoten) {
        return knoten.obergrenze() > 0 && knoten.gesamt() >= knoten.obergrenze();
    }

    private double hoechstlast(List<AudioNodeUsageView> knoten) {
        return knoten.stream()
                .filter(AudioNodeUsageView::erreichbar)
                .mapToDouble(AudioNodeUsageView::cpuLast)
                .max()
                .orElse(0);
    }

    private boolean eingeschaltet() {
        String wert = System.getenv("HJ_AUTOSCALE");
        return wert != null && wert.trim().equalsIgnoreCase("true");
    }

    private double schwelle() {
        return zahl("HJ_AUTOSCALE_SCHWELLE", 0.75);
    }

    private double abbauSchwelle() {
        // Deutlich unter der Aufbauschwelle. Laegen beide dicht beieinander,
        // pendelte das System zwischen Anlegen und Abbauen - und jeder Zyklus
        // kostet eine Serverstunde.
        return zahl("HJ_AUTOSCALE_ABBAU_SCHWELLE", 0.35);
    }

    private int obergrenze() {
        return (int) zahl("HJ_AUTOSCALE_MAX", 4);
    }

    private Duration aufbauNach() {
        return Duration.ofSeconds((long) zahl("HJ_AUTOSCALE_AUFBAU_SEKUNDEN", 180));
    }

    private Duration abbauNach() {
        return Duration.ofSeconds((long) zahl("HJ_AUTOSCALE_ABBAU_SEKUNDEN", 1800));
    }

    private Duration anlaufzeit() {
        return Duration.ofSeconds((long) zahl("HJ_AUTOSCALE_ANLAUF_SEKUNDEN", 600));
    }

    private double zahl(String name, double vorgabe) {
        String wert = System.getenv(name);
        if (wert == null || wert.isBlank()) {
            return vorgabe;
        }
        try {
            return Double.parseDouble(wert.trim());
        } catch (NumberFormatException fehler) {
            return vorgabe;
        }
    }
}
