package eckerlin.dev.audio;

import dev.arbjerg.lavalink.client.LavalinkClient;
import dev.arbjerg.lavalink.client.LavalinkNode;
import dev.arbjerg.lavalink.client.loadbalancing.ILoadBalancer;
import dev.arbjerg.lavalink.client.loadbalancing.VoiceRegion;
import dev.arbjerg.lavalink.client.loadbalancing.builtin.IPenaltyProvider;
import eckerlin.dev.utils.Alert;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;

/**
 * Verteilt Server auf Audio-Knoten - getrennt nach Leistungsstufe.
 *
 * <p>Warum ein eigener Load-Balancer und keine Auswahl an den Aufrufstellen:
 * {@code lavalink-client} fragt den Balancer nicht nur beim ersten Verbinden,
 * sondern auch dann, wenn ein Knoten ausfaellt und die laufenden Verbindungen
 * verschoben werden. Sitzt die Logik hier, gilt die Stufentrennung auch bei
 * einem Ausfall - ohne dass irgendein Aufrufer davon wissen muss.</p>
 *
 * <p>Innerhalb einer Stufe entscheidet die Bewertung der Bibliothek
 * ({@code penalties}: laufende Wiedergaben, CPU-Last, fehlgeschlagene Ladungen)
 * plus ein Aufschlag fuer Knoten, die ihre eingestellte Obergrenze erreichen.
 * So bekommt ein neuer Server immer den am wenigsten belasteten Knoten.</p>
 *
 * <p><b>Ueberlauf.</b> Die Trennung ist eine Zuteilung, keine Mauer. Sind alle
 * Standard-Knoten am Anschlag, bekaeme der naechste Server sonst gar keinen
 * Ton, waehrend nebenan eine Premium-Maschine leer laeuft. Deshalb duerfen
 * Standard-Server dann auf Premium ausweichen - aber nur auf Knoten, die noch
 * Luft haben, und mit einer Reserve, damit ein Premium-Server nicht vor
 * verschlossener Tuer steht. Sobald wieder ein Standard-Knoten frei ist, holt
 * der Stufenabgleich sie von selbst zurueck.</p>
 */
public class TierAwareLoadBalancer implements ILoadBalancer {

    /** Aufschlag fuer einen vollen Knoten. Hoch genug, um jeden freien Knoten vorzuziehen. */
    private static final int PENALTY_VOLL = 1_000_000;

    /**
     * Aufschlag fuer einen Knoten, der nur im Ueberlauf infrage kommt.
     *
     * <p>Damit gewinnt jeder noch so belastete Standard-Knoten gegen einen
     * leeren Premium-Knoten. Der Ueberlauf ist die letzte Wahl, nicht die
     * bequeme.
     */
    private static final int PENALTY_UEBERLAUF = 10_000;

    private final LavalinkClient client;
    private final Function<Long, NodeTier> guildTier;
    private final Function<String, NodeTier> nodeTier;
    private final Function<String, Integer> nodeKapazitaet;
    private final BooleanSupplier ueberlaufErlaubt;
    private final DoubleSupplier ueberlaufAbCpu;
    private final IntSupplier premiumReserve;
    private final List<IPenaltyProvider> zusatzBewertung = new ArrayList<>();
    private final Set<String> ueberlaufGemeldet = ConcurrentHashMap.newKeySet();

    /**
     * @param guildTier        Stufe eines Discord-Servers, anhand seiner ID
     * @param nodeTier         Stufe eines Knotens, anhand seines Namens
     * @param nodeKapazitaet   Obergrenze gleichzeitiger Wiedergaben je Knoten,
     *                         0 oder kleiner bedeutet unbegrenzt
     * @param ueberlaufErlaubt ob Standard-Server auf Premium ausweichen duerfen
     * @param ueberlaufAbCpu   ab welcher CPU-Last (0..1) ein Knoten als voll gilt
     * @param premiumReserve   wie viele Plaetze auf einem Premium-Knoten fuer
     *                         Premium-Server freigehalten werden
     */
    public TierAwareLoadBalancer(
            LavalinkClient client,
            Function<Long, NodeTier> guildTier,
            Function<String, NodeTier> nodeTier,
            Function<String, Integer> nodeKapazitaet,
            BooleanSupplier ueberlaufErlaubt,
            DoubleSupplier ueberlaufAbCpu,
            IntSupplier premiumReserve
    ) {
        this.client = client;
        this.guildTier = guildTier;
        this.nodeTier = nodeTier;
        this.nodeKapazitaet = nodeKapazitaet;
        this.ueberlaufErlaubt = ueberlaufErlaubt;
        this.ueberlaufAbCpu = ueberlaufAbCpu;
        this.premiumReserve = premiumReserve;
    }

    /**
     * Kotlin gibt dieser Methode eine Standardimplementierung, fuer Java bleibt
     * sie trotzdem abstrakt - ohne sie laesst sich die Klasse nicht uebersetzen.
     * Ohne Gilde gibt es keine Stufe, also die Standardauswahl.
     */
    @Override
    public LavalinkNode selectNode() {
        return selectNode(null, null);
    }

    @Override
    public LavalinkNode selectNode(VoiceRegion region, Long guildId) {
        List<LavalinkNode> verfuegbar = new ArrayList<>();
        for (LavalinkNode node : client.getNodes()) {
            if (node.getAvailable()) {
                verfuegbar.add(node);
            }
        }
        if (verfuegbar.isEmpty()) {
            throw new IllegalStateException("Kein Audio-Knoten erreichbar.");
        }

        // Ohne Server-ID (etwa beim Vorwaermen) gilt die Standardstufe.
        NodeTier gewuenscht = guildId == null ? NodeTier.FREE : guildTier.apply(guildId);

        List<LavalinkNode> pool = filtern(verfuegbar, gewuenscht);
        List<LavalinkNode> ueberlauf = List.of();

        // Standard am Anschlag: dann darf Premium aushelfen. Die Knoten kommen
        // in einen getrennten Topf, weil sie ueber PENALTY_UEBERLAUF nur dann
        // gewinnen, wenn wirklich kein Standard-Knoten mehr kann.
        if (gewuenscht == NodeTier.FREE && ueberlaufErlaubt.getAsBoolean() && alleVoll(pool)) {
            ueberlauf = mitLuft(filtern(verfuegbar, NodeTier.PREMIUM), premiumReserve.getAsInt());
            if (!ueberlauf.isEmpty()) {
                meldeUeberlaufEinmal(guildId);
                pool = new ArrayList<>(pool);
                pool.addAll(ueberlauf);
            }
        }

        if (pool.isEmpty() && gewuenscht == NodeTier.PREMIUM) {
            // Lieber Musik in Standardqualitaet als gar keine. Der Ausfall wird
            // protokolliert, damit er nicht unbemerkt zum Dauerzustand wird.
            pool = filtern(verfuegbar, NodeTier.FREE);
            if (!pool.isEmpty()) {
                Alert.send("WARN", "LAVALINK",
                        "Kein Premium-Knoten verfuegbar - Server " + guildId + " laeuft vorerst auf Standard.");
            }
        }

        if (pool.isEmpty()) {
            // Auch die Standardstufe ist leer: dann zaehlt nur noch, dass ueberhaupt
            // etwas laeuft.
            Alert.send("WARN", "LAVALINK", "Kein Knoten der passenden Stufe - nehme irgendeinen erreichbaren.");
            pool = verfuegbar;
        }

        LavalinkNode bester = null;
        int besteBewertung = Integer.MAX_VALUE;
        for (LavalinkNode node : pool) {
            int bewertung = bewerten(node, region);
            if (ueberlauf.contains(node)) {
                bewertung += PENALTY_UEBERLAUF;
            }
            if (bewertung < besteBewertung) {
                besteBewertung = bewertung;
                bester = node;
            }
        }
        return bester == null ? pool.get(0) : bester;
    }

    /**
     * Ist auf diesem Knoten noch Platz?
     *
     * <p>Zwei Grenzen, weil beide allein nicht reichen: die Obergrenze zaehlt
     * Wiedergaben und sagt nichts ueber die Maschine, die CPU-Last sagt nichts
     * ueber eine bewusst gesetzte Obergrenze. Ohne Obergrenze (0) entscheidet
     * allein die Last.
     *
     * @param reserve Plaetze, die nicht mitgezaehlt werden - fuer Server, die
     *                auf diesen Knoten gehoeren
     */
    private boolean hatLuft(LavalinkNode node, int reserve) {
        int grenze = kapazitaet(node.getName());
        if (grenze > 0 && laufendeWiedergaben(node) >= Math.max(1, grenze - Math.max(0, reserve))) {
            return false;
        }
        return cpuLast(node) < ueberlaufAbCpu.getAsDouble();
    }

    /** Kein Knoten mehr frei - oder gar keiner vorhanden. */
    private boolean alleVoll(List<LavalinkNode> nodes) {
        for (LavalinkNode node : nodes) {
            if (hatLuft(node, 0)) {
                return false;
            }
        }
        return true;
    }

    private List<LavalinkNode> mitLuft(List<LavalinkNode> nodes, int reserve) {
        List<LavalinkNode> treffer = new ArrayList<>();
        for (LavalinkNode node : nodes) {
            if (hatLuft(node, reserve)) {
                treffer.add(node);
            }
        }
        return treffer;
    }

    private double cpuLast(LavalinkNode node) {
        var stats = node.getStats();
        // Ein Knoten ohne Statistik ist frisch verbunden. Ihn als ausgelastet
        // zu behandeln waere falsch - er hat schlicht noch nichts gemeldet.
        return stats == null ? 0.0 : stats.getCpu().getSystemLoad();
    }

    /**
     * Einmal je Server melden, nicht bei jeder Auswahl.
     *
     * <p>Der Balancer wird auch bei jedem Ausfall und jedem Umzug gefragt. Ohne
     * diese Bremse stuende das Protokoll voll mit derselben Zeile, und die
     * Meldung, auf die es ankommt, ginge darin unter.
     */
    private void meldeUeberlaufEinmal(Long guildId) {
        String schluessel = String.valueOf(guildId);
        if (ueberlaufGemeldet.add(schluessel)) {
            Alert.send("INFO", "LAVALINK",
                    "Standard-Knoten ausgelastet - Server " + schluessel
                            + " weicht auf einen Premium-Knoten aus.");
        }
    }

    /** Nach einem Stufenabgleich darf derselbe Server wieder gemeldet werden. */
    public void ueberlaufMeldungenZuruecksetzen() {
        ueberlaufGemeldet.clear();
    }

    private List<LavalinkNode> filtern(List<LavalinkNode> nodes, NodeTier tier) {
        List<LavalinkNode> treffer = new ArrayList<>();
        for (LavalinkNode node : nodes) {
            if (nodeTier.apply(node.getName()) == tier) {
                treffer.add(node);
            }
        }
        return treffer;
    }

    private int bewerten(LavalinkNode node, VoiceRegion region) {
        int summe;
        try {
            summe = node.getPenalties().calculateTotal();
        } catch (RuntimeException exception) {
            // Ein Knoten ohne Statistik ist frisch verbunden - das ist kein Fehler.
            summe = 0;
        }

        int grenze = kapazitaet(node.getName());
        if (grenze > 0 && laufendeWiedergaben(node) >= grenze) {
            summe += PENALTY_VOLL;
        }

        for (IPenaltyProvider provider : zusatzBewertung) {
            summe += provider.getPenalty(node, region);
        }
        return summe;
    }

    private int kapazitaet(String nodeName) {
        Integer wert = nodeKapazitaet.apply(nodeName);
        return wert == null ? 0 : wert;
    }

    private int laufendeWiedergaben(LavalinkNode node) {
        var stats = node.getStats();
        return stats == null ? 0 : stats.getPlayingPlayers();
    }

    @Override
    public void addPenaltyProvider(IPenaltyProvider penaltyProvider) {
        zusatzBewertung.add(penaltyProvider);
    }

    @Override
    public void removePenaltyProvider(IPenaltyProvider penaltyProvider) {
        zusatzBewertung.remove(penaltyProvider);
    }

    /** Momentaufnahme der Knoten je Stufe - fuer die Anzeige im Adminbereich. */
    public Map<String, Integer> auslastung() {
        Map<String, Integer> werte = new java.util.LinkedHashMap<>();
        for (LavalinkNode node : client.getNodes()) {
            werte.put(node.getName(), laufendeWiedergaben(node));
        }
        return werte;
    }
}
