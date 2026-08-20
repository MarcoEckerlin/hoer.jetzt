package eckerlin.dev.audio;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Der gewuenschte Zustand eines Hetzner-Servers.
 *
 * <p>Bisher standen diese Angaben einzeln in {@code HJ_AUTOSCALE_*} und galten
 * fuer alle Knoten gleich. Das reichte, solange das Autoscaling genau eine
 * Sorte Maschine bestellte. Sobald es "Free Small" neben "Premium Large" neben
 * "Core+Lavalink" gibt, braucht es benannte Vorlagen - sonst wird bei jeder
 * Aenderung die Umgebung umgeschrieben, und die gilt dann rueckwirkend fuer
 * alles.</p>
 *
 * <p>Die Umgebung bleibt als <em>eine</em> Vorlage erhalten ({@link
 * #ausUmgebung}). Bestehende Installationen laufen damit unveraendert weiter,
 * und wer keine Vorlagen anlegt, merkt vom Umbau nichts.</p>
 *
 * @param name           Name der Vorlage, nicht des Servers
 * @param serverTyp      z.B. {@code cpx41}
 * @param abbild         z.B. {@code debian-12}
 * @param standort       z.B. {@code fsn1}; leer, wenn {@code rechenzentrum} gesetzt ist
 * @param rechenzentrum  z.B. {@code fsn1-dc14} - genauer als der Standort
 * @param sshSchluessel  Namen oder Kennungen; Hetzner loest Namen selbst auf
 * @param platzierung    Placement Group, Kennung
 * @param firewalls      Kennungen
 * @param netze          Kennungen privater Netze
 * @param speicher       zusaetzliche Volumes, Kennungen
 * @param ipv4           oeffentliche IPv4 zuweisen
 * @param ipv6           oeffentliche IPv6 zuweisen
 * @param marken         zusaetzliche Labels
 * @param module         was auf dem Knoten laufen soll
 * @param stufe          {@code free} oder {@code premium}
 */
public record Servervorlage(
        String name,
        String serverTyp,
        String abbild,
        String standort,
        String rechenzentrum,
        List<String> sshSchluessel,
        Long platzierung,
        List<Long> firewalls,
        List<Long> netze,
        List<Long> speicher,
        boolean ipv4,
        boolean ipv6,
        Map<String, String> marken,
        List<String> module,
        String stufe) {

    /**
     * Die Vorlage, die es schon immer gab - nur jetzt mit Namen.
     *
     * <p>Liest {@code HJ_AUTOSCALE_*} genau wie bisher. Damit bleibt der
     * bestehende Betrieb unveraendert: wer nie eine Vorlage anlegt, bekommt
     * weiterhin das, was in seiner Umgebung steht.</p>
     */
    public static Servervorlage ausUmgebung(String stufe) {
        return new Servervorlage(
                "aus der Umgebung",
                wert("HJ_AUTOSCALE_TYPE", "cx33"),
                wert("HJ_AUTOSCALE_IMAGE", "debian-12"),
                wert("HJ_AUTOSCALE_LOCATION", "hel1"),
                "",
                teile(wert("HJ_AUTOSCALE_SSH_KEYS", "")),
                null,
                List.of(),
                List.of(),
                List.of(),
                true,
                // IPv6 kostet bei Hetzner nichts und spart spaeter die Frage,
                // warum ein Knoten manche Ziele nicht erreicht.
                true,
                Map.of(),
                List.of("lavalink"),
                stufe);
    }

    /**
     * Die Marken, mit denen der Server bei Hetzner auftaucht.
     *
     * <p>{@code hoerjetzt=knoten} ist die wichtigste: daran erkennt das
     * Aufraeumen, welche Maschinen ihm gehoeren. Ohne sie muesste es sich auf
     * Namensmuster verlassen, und ein umbenannter Server waere entweder
     * unsichtbar oder - schlimmer - ein fremder Server sichtbar.</p>
     *
     * <p>Eigene Marken koennen die Pflichtmarken nicht ueberschreiben: sie
     * werden zuerst eingetragen und danach von den festen ueberschrieben.</p>
     */
    public Map<String, String> alleMarken() {
        Map<String, String> alle = new LinkedHashMap<>();
        if (marken != null) {
            alle.putAll(marken);
        }
        alle.put("hoerjetzt", "knoten");
        alle.put("stufe", stufe == null || stufe.isBlank() ? "free" : stufe);
        alle.put("vorlage", name == null ? "" : name.replaceAll("[^A-Za-z0-9_.-]", "-"));
        if (module != null && !module.isEmpty()) {
            // Hetzner-Marken duerfen kein Komma - deshalb Unterstrich.
            alle.put("module", String.join("_", module));
        }
        return alle;
    }

    private static String wert(String schluessel, String vorgabe) {
        String v = System.getenv(schluessel);
        return v == null || v.isBlank() ? vorgabe : v.trim();
    }

    private static List<String> teile(String roh) {
        if (roh == null || roh.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(roh.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
