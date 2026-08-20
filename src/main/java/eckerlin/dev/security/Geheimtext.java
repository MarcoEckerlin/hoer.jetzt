package eckerlin.dev.security;

import eckerlin.dev.utils.Alert;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Verschluesselt einzelne Werte fuer die Datenbank.
 *
 * <h2>Wofuer</h2>
 *
 * Fuer Zugangsdaten, die ein Server-Betreiber hinterlegt - allen voran den
 * API-Token seines eigenen KI-Endpunkts. Sie gehoeren ihm, nicht uns, und ein
 * Datenbankabzug soll sie nicht hergeben.
 *
 * <h2>Warum AES-GCM und nicht das Verfahren aus dem Update-Server</h2>
 *
 * Der Umschlag dort ist AES-CBC mit HMAC, weil ein Bash-Skript ihn mit
 * {@code openssl} oeffnen koennen muss - und {@code openssl enc} kann den
 * Anhang von GCM nicht. Diese Werte hier liest ausschliesslich der Bot, also
 * Java auf beiden Seiten. Dann ist GCM die richtige Wahl: es verschluesselt
 * und beglaubigt in einem Schritt, und man kann die Reihenfolge nicht falsch
 * herum bauen.
 *
 * <h2>Der Schluessel</h2>
 *
 * Aus {@code HJ_GEHEIMNIS_SCHLUESSEL}. Steht in der {@code .env} und kommt
 * ueber den Tresor - also nicht in der Datenbank, in der die Geheimtexte
 * liegen. Genau das ist der Punkt: waeren beide am selben Ort, koennte man
 * sich das Verschluesseln sparen.
 *
 * <p>Ohne gesetzten Schluessel wird <em>nicht</em> verschluesselt, und der
 * Aufrufer erfaehrt es ueber {@link #eingeschaltet()}. Stillschweigend
 * Klartext zu speichern waere die schlechtere Vorgabe; stillschweigend gar
 * nichts zu speichern aber auch. Die Oberflaeche sagt es dem Betreiber.</p>
 */
public final class Geheimtext {

    /** Kennzeichnung. Erlaubt es, spaeter ein anderes Verfahren zu erkennen. */
    private static final String MARKE = "gcm1:";

    private static final int IV_LAENGE = 12;      // fuer GCM empfohlen
    private static final int ANHANG_BITS = 128;

    private static final byte[] SCHLUESSEL = schluesselLesen();

    private Geheimtext() {
    }

    public static boolean eingeschaltet() {
        return SCHLUESSEL != null;
    }

    /**
     * @return der Geheimtext, oder der unveraenderte Klartext, wenn kein
     *         Schluessel eingerichtet ist
     */
    public static String verschluesseln(String klartext) {
        if (klartext == null || klartext.isEmpty() || SCHLUESSEL == null) {
            return klartext;
        }
        try {
            byte[] iv = new byte[IV_LAENGE];
            new SecureRandom().nextBytes(iv);

            Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(SCHLUESSEL, "AES"),
                    new GCMParameterSpec(ANHANG_BITS, iv));
            byte[] geheim = aes.doFinal(klartext.getBytes(StandardCharsets.UTF_8));

            byte[] zusammen = new byte[iv.length + geheim.length];
            System.arraycopy(iv, 0, zusammen, 0, iv.length);
            System.arraycopy(geheim, 0, zusammen, iv.length, geheim.length);

            return MARKE + Base64.getEncoder().encodeToString(zusammen);
        } catch (Exception fehler) {
            Alert.send("WARN", "SICHERHEIT", "Verschluesseln fehlgeschlagen: " + fehler.getMessage());
            throw new IllegalStateException("Wert liess sich nicht verschluesseln.", fehler);
        }
    }

    /**
     * Gegenstueck zu {@link #verschluesseln}.
     *
     * <p>Ein Wert ohne Marke wird unveraendert zurueckgegeben. Das ist der
     * Uebergang: Zeilen, die vor der Einfuehrung des Schluessels geschrieben
     * wurden, liegen im Klartext da und sollen weiter benutzbar sein, bis sie
     * einmal neu gespeichert werden.</p>
     */
    public static String entschluesseln(String gespeichert) {
        if (gespeichert == null || gespeichert.isEmpty() || !gespeichert.startsWith(MARKE)) {
            return gespeichert;
        }
        if (SCHLUESSEL == null) {
            // Verschluesselt gespeichert, aber der Schluessel fehlt jetzt.
            // Das passiert, wenn jemand HJ_GEHEIMNIS_SCHLUESSEL aus der .env
            // entfernt - und es ist wichtig, dass es auffaellt: sonst
            // scheiterte der KI-Chat mit "Token abgelehnt", und niemand
            // kaeme auf die Umgebung.
            Alert.send("WARN", "SICHERHEIT",
                    "Verschluesselter Wert, aber HJ_GEHEIMNIS_SCHLUESSEL ist nicht gesetzt.");
            return "";
        }
        try {
            byte[] zusammen = Base64.getDecoder().decode(gespeichert.substring(MARKE.length()));
            byte[] iv = java.util.Arrays.copyOfRange(zusammen, 0, IV_LAENGE);
            byte[] geheim = java.util.Arrays.copyOfRange(zusammen, IV_LAENGE, zusammen.length);

            Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(SCHLUESSEL, "AES"),
                    new GCMParameterSpec(ANHANG_BITS, iv));
            return new String(aes.doFinal(geheim), StandardCharsets.UTF_8);
        } catch (Exception fehler) {
            Alert.send("WARN", "SICHERHEIT", "Entschluesseln fehlgeschlagen: " + fehler.getMessage());
            return "";
        }
    }

    /**
     * Was in der Oberflaeche steht, wenn ein Token hinterlegt ist.
     *
     * <p>Nie der Wert selbst - auch nicht gekuerzt. Die letzten vier Zeichen
     * eines API-Tokens zu zeigen ist verbreitet und hier trotzdem falsch: der
     * Betreiber, der ihn eingetragen hat, kennt ihn ohnehin, und alle
     * anderen sollen gar nichts erfahren.</p>
     */
    public static String platzhalter(String gespeichert) {
        return gespeichert == null || gespeichert.isEmpty() ? "" : "••••••••";
    }

    /**
     * Leitet aus der Umgebungsangabe einen 256-Bit-Schluessel ab.
     *
     * <p>SHA-256 darueber, damit jede Laenge passt - sonst muesste der Wert
     * in der {@code .env} auf das Byte genau stimmen, und ein zu kurzer
     * Schluessel liesse den Bot beim Start abbrechen statt zu laufen.</p>
     */
    private static byte[] schluesselLesen() {
        String roh = System.getenv("HJ_GEHEIMNIS_SCHLUESSEL");
        if (roh == null || roh.isBlank()) {
            return null;
        }
        if (roh.trim().length() < 16) {
            Alert.send("WARN", "SICHERHEIT",
                    "HJ_GEHEIMNIS_SCHLUESSEL ist sehr kurz - mindestens 32 Zeichen benutzen.");
        }
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(roh.trim().getBytes(StandardCharsets.UTF_8));
        } catch (Exception nichtMoeglich) {
            throw new IllegalStateException("SHA-256 fehlt", nichtMoeglich);
        }
    }
}
