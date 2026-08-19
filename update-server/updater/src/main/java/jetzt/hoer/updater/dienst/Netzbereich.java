package jetzt.hoer.updater.dienst;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * Ein Adressbereich in CIDR-Schreibweise, und die Frage, ob eine Adresse
 * darin liegt.
 *
 * Bewusst von Hand statt ueber eine Bibliothek: es sind dreissig Zeilen, und
 * eine Abhaengigkeit, die ueber die Zugangskontrolle entscheidet, will
 * gepflegt werden.
 *
 * Java loest "::ffff:1.2.3.4" von sich aus in eine Inet4Address auf - eine
 * IPv4-Adresse, die ueber einen IPv6-Anschluss hereinkommt, wird also
 * richtig gegen einen IPv4-Bereich geprueft. Bleiben die Laengen trotzdem
 * verschieden, ist es schlicht die falsche Familie und die Antwort ist nein.
 */
public final class Netzbereich {

    private final byte[] netz;
    private final int praefix;
    private final String text;

    private Netzbereich(byte[] netz, int praefix, String text) {
        this.netz = netz;
        this.praefix = praefix;
        this.text = text;
    }

    /**
     * Nimmt "10.0.0.0/8", "192.168.1.5" oder "2001:db8::/32" entgegen. Eine
     * Adresse ohne Laengenangabe wird zur vollen Laenge ergaenzt - damit gibt
     * es intern nur eine Form.
     *
     * @throws IllegalArgumentException wenn sich daraus kein Bereich ergibt.
     *         Das ist Absicht: eine Freischaltung, die nicht verstanden wird,
     *         darf nicht stillschweigend als "passt auf nichts" enden.
     */
    public static Netzbereich aus(String eingabe) {
        String roh = eingabe == null ? "" : eingabe.trim();
        if (roh.isEmpty()) {
            throw new IllegalArgumentException("Leere Angabe.");
        }

        String adressteil = roh;
        Integer angegeben = null;

        int schraeg = roh.lastIndexOf('/');
        if (schraeg >= 0) {
            adressteil = roh.substring(0, schraeg).trim();
            try {
                angegeben = Integer.parseInt(roh.substring(schraeg + 1).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Keine Praefixlaenge: " + roh);
            }
        }

        byte[] bytes;
        try {
            // Nur wenn es wie eine Adresse aussieht. getByName wuerde sonst
            // einen Namen im DNS nachschlagen - eine Zugangsliste, die von
            // der Namensaufloesung abhaengt, ist keine.
            if (!adressteil.matches("[0-9A-Fa-f:.]+")) {
                throw new IllegalArgumentException("Keine Adresse: " + adressteil);
            }
            bytes = InetAddress.getByName(adressteil).getAddress();
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Keine Adresse: " + adressteil);
        }

        int laenge = bytes.length * 8;
        int praefix = angegeben == null ? laenge : angegeben;
        if (praefix < 0 || praefix > laenge) {
            throw new IllegalArgumentException("Praefixlaenge passt nicht zur Adresse: " + roh);
        }

        // Wirtsbits ausnullen. Sonst gaebe "10.1.2.3/8" einen Bereich, der
        // gegen sich selbst nicht mehr passt.
        byte[] netz = maskieren(bytes, praefix);
        String normal = adresse(netz) + "/" + praefix;
        return new Netzbereich(netz, praefix, normal);
    }

    public boolean enthaelt(InetAddress adresse) {
        if (adresse == null) return false;
        byte[] pruef = adresse.getAddress();
        if (pruef.length != netz.length) return false;
        return Arrays.equals(maskieren(pruef, praefix), netz);
    }

    public boolean enthaelt(String adresse) {
        try {
            if (adresse == null || !adresse.matches("[0-9A-Fa-f:.]+")) return false;
            return enthaelt(InetAddress.getByName(adresse));
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /** Die aufgeraeumte Schreibweise - das, was gespeichert wird. */
    @Override
    public String toString() {
        return text;
    }

    private static byte[] maskieren(byte[] roh, int praefix) {
        byte[] ergebnis = roh.clone();
        for (int i = 0; i < ergebnis.length; i++) {
            int rest = praefix - i * 8;
            if (rest >= 8) continue;
            ergebnis[i] = rest <= 0 ? 0 : (byte) (ergebnis[i] & (0xFF << (8 - rest)));
        }
        return ergebnis;
    }

    private static String adresse(byte[] bytes) {
        try {
            return InetAddress.getByAddress(bytes).getHostAddress();
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
