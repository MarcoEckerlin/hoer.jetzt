package eckerlin.dev.verbund;

import org.springframework.stereotype.Component;

/**
 * Wer diese Node ist und welche Shards sie faehrt.
 *
 * <p>Alles kommt aus der Umgebung, nicht aus der Datenbank: die Angaben
 * beschreiben diesen Prozess, und ein Prozess, der erst nachschlagen muss, wer
 * er ist, hat ein Henne-Ei-Problem beim Start.</p>
 *
 * <p>Der Name ist der Rechnername - dieselbe Regel wie beim Knoten-Agenten, und
 * damit tauchen Node und Audio-Knoten im Betrieb unter demselben Namen auf.</p>
 */
@Component
public class EigeneNode {

    private final String name;
    private final String privatIp;
    private final int von;
    private final int bis;
    private final int gesamt;

    public EigeneNode() {
        this.name = ermittleName();
        this.privatIp = umgebung("HJ_PRIVAT_IP", "");
        this.gesamt = zahl("HJ_SHARDS_GESAMT", 1);
        this.von = zahl("HJ_SHARD_VON", 0);
        // Ohne Obergrenze faehrt diese Node alles - das ist der Einzelbetrieb.
        this.bis = zahl("HJ_SHARD_BIS", Math.max(0, gesamt - 1));
    }

    private static String ermittleName() {
        String ausUmgebung = umgebung("HJ_NODE_NAME", "");
        if (!ausUmgebung.isBlank()) {
            return ausUmgebung;
        }
        try {
            // Im Container ist das die Container-Kennung; deshalb setzt das
            // Compose HJ_NODE_NAME. Der Rueckfallweg ist nur die Notbremse.
            String rechner = java.net.InetAddress.getLocalHost().getHostName();
            return rechner == null || rechner.isBlank() ? "unbekannt" : rechner.split("\\.")[0];
        } catch (Exception nichtErmittelbar) {
            return "unbekannt";
        }
    }

    private static String umgebung(String name, String vorgabe) {
        String wert = System.getenv(name);
        return wert == null || wert.isBlank() ? vorgabe : wert.trim();
    }

    private static int zahl(String name, int vorgabe) {
        try {
            return Integer.parseInt(umgebung(name, String.valueOf(vorgabe)));
        } catch (NumberFormatException keineZahl) {
            return vorgabe;
        }
    }

    public String name() {
        return name;
    }

    public String privatIp() {
        return privatIp;
    }

    public int von() {
        return von;
    }

    public int bis() {
        return bis;
    }

    public int gesamt() {
        return gesamt;
    }

    /** Nur mit privater Adresse kann eine andere Node hierher weiterleiten. */
    public boolean meldbar() {
        return !privatIp.isBlank() && !"unbekannt".equals(name);
    }
}
