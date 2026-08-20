package jetzt.hoer.updater.dienst;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

/**
 * Verschluesselt Zugangsdaten an genau einen Knoten.
 *
 * <h2>Warum ueberhaupt</h2>
 *
 * Der Tresor lag bisher im Klartext im Auslieferungsverzeichnis, geschuetzt
 * nur durch das gemeinsame Passwort und die Adressfreigabe. Wer eine
 * Verbindung mitschneiden oder das Passwort abgreifen konnte, hatte Bot-Token,
 * Datenbank-Passwort und Client-Secret. Ab hier kann eine Antwort nur noch der
 * Knoten lesen, an den sie gerichtet ist - sein privater Schluessel verlaesst
 * ihn nie.
 *
 * <h2>Warum dieses Verfahren und kein anderes</h2>
 *
 * Der Gegenpart laeuft in einem Bash-Skript auf dem Host. Damit fallen die
 * naheliegenden Verfahren nacheinander aus:
 *
 * <ul>
 *   <li><b>CMS</b> - waere das Naheliegende und war frueher hier gebaut.
 *       Das JDK kann es aber nicht ohne BouncyCastle, und eine
 *       Bibliothek mehr im Abbild fuer einen Umschlag ist ein schlechter
 *       Tausch.</li>
 *   <li><b>AES-GCM</b> - waere die bessere Betriebsart, aber
 *       {@code openssl enc} kann den Authentifizierungsanhang nicht
 *       verarbeiten. Auf dem Knoten waere sie damit nicht zu oeffnen.</li>
 *   <li><b>Python</b> - {@code python3} ist auf den Knoten vorhanden (der
 *       Agent benutzt es), seine Standardbibliothek kennt aber kein AES.</li>
 * </ul>
 *
 * Bleibt AES-256-CBC mit HMAC-SHA256 als <em>Encrypt-then-MAC</em>: beides
 * kann {@code openssl} auf dem Knoten unmittelbar, und beides ist im JDK
 * enthalten. Der Sitzungsschluessel wird mit RSA-OAEP an den Knoten gerichtet.
 *
 * <h2>Encrypt-then-MAC, und zwar in dieser Reihenfolge</h2>
 *
 * Der HMAC laeuft ueber IV <em>und</em> Geheimtext und wird vor dem
 * Entschluesseln geprueft. Andersherum - erst entschluesseln, dann pruefen -
 * verraet das Auffuellmuster von CBC einem Angreifer den Klartext Byte fuer
 * Byte. Das ist keine Feinheit, sondern der Unterschied zwischen sicher und
 * gebrochen, und der Grund, warum die Reihenfolge hier festgeschrieben ist.
 *
 * <p>Zwei getrennte Schluessel fuer Verschluesselung und HMAC, beide aus
 * einem gemeinsamen Zufallswert abgeleitet. Denselben Schluessel fuer beides
 * zu nehmen ist in der Praxis meist unschaedlich und trotzdem eine Annahme,
 * die man nicht braucht.</p>
 */
public final class Umschlag {

    /** Kennzeichnung und Fassung. Steht in der ersten Zeile, damit ein
     *  spaeterer Wechsel des Verfahrens erkennbar ist statt stillschweigend
     *  falsch entschluesselt zu werden. */
    public static final String KENNUNG = "HJTRESOR1";

    private static final String RSA = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private static final String AES = "AES/CBC/PKCS5Padding";
    private static final String HMAC = "HmacSHA256";

    private Umschlag() {
    }

    /**
     * Richtet Daten an einen Knoten.
     *
     * @param klartext        was der Knoten bekommen soll
     * @param oeffentlicherPem der oeffentliche Schluessel des Knotens, PEM
     * @return der Umschlag als Text, zeilenweise Base64
     */
    public static String verschliessen(byte[] klartext, String oeffentlicherPem) {
        try {
            PublicKey empfaenger = ausPem(oeffentlicherPem);
            SecureRandom zufall = new SecureRandom();

            // Ein Zufallswert, zwei Schluessel. Getrennt abgeleitet, damit der
            // HMAC-Schluessel nie derselbe ist wie der AES-Schluessel.
            byte[] wurzel = new byte[32];
            zufall.nextBytes(wurzel);
            byte[] aesSchluessel = ableiten(wurzel, "aes");
            byte[] macSchluessel = ableiten(wurzel, "mac");

            byte[] iv = new byte[16];
            zufall.nextBytes(iv);

            Cipher aes = Cipher.getInstance(AES);
            aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesSchluessel, "AES"),
                    new IvParameterSpec(iv));
            byte[] geheim = aes.doFinal(klartext);

            byte[] pruefsumme = mac(macSchluessel, iv, geheim);

            Cipher rsa = Cipher.getInstance(RSA);
            rsa.init(Cipher.ENCRYPT_MODE, empfaenger, new OAEPParameterSpec(
                    "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
            byte[] wurzelGeschlossen = rsa.doFinal(wurzel);

            Base64.Encoder b64 = Base64.getEncoder();
            return KENNUNG + "\n"
                    + b64.encodeToString(wurzelGeschlossen) + "\n"
                    + b64.encodeToString(iv) + "\n"
                    + b64.encodeToString(geheim) + "\n"
                    + b64.encodeToString(pruefsumme) + "\n";
        } catch (Exception fehler) {
            throw new IllegalStateException("Umschlag liess sich nicht schliessen: "
                    + fehler.getMessage(), fehler);
        }
    }

    /**
     * Nur fuer die Selbstprobe: oeffnet einen Umschlag wieder.
     *
     * <p>Im Betrieb macht das der Knoten mit {@code openssl}. Diese Methode
     * gibt es, damit sich das Verfahren pruefen laesst, ohne einen Knoten zu
     * brauchen - und damit die Probe genau das prueft, was auch ausgeliefert
     * wird.</p>
     */
    public static byte[] oeffnen(String umschlag, java.security.PrivateKey privat) {
        try {
            String[] zeilen = umschlag.strip().split("\\R");
            if (zeilen.length != 5 || !zeilen[0].equals(KENNUNG)) {
                throw new IllegalArgumentException("Kein " + KENNUNG + "-Umschlag.");
            }
            Base64.Decoder b64 = Base64.getDecoder();
            byte[] wurzelGeschlossen = b64.decode(zeilen[1]);
            byte[] iv = b64.decode(zeilen[2]);
            byte[] geheim = b64.decode(zeilen[3]);
            byte[] pruefsumme = b64.decode(zeilen[4]);

            Cipher rsa = Cipher.getInstance(RSA);
            rsa.init(Cipher.DECRYPT_MODE, privat, new OAEPParameterSpec(
                    "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
            byte[] wurzel = rsa.doFinal(wurzelGeschlossen);

            byte[] macSchluessel = ableiten(wurzel, "mac");
            // Erst pruefen, dann entschluesseln - siehe Klassenkommentar.
            if (!MessageDigest.isEqual(pruefsumme, mac(macSchluessel, iv, geheim))) {
                throw new IllegalStateException("Pruefsumme stimmt nicht - Umschlag verfaelscht.");
            }

            Cipher aes = Cipher.getInstance(AES);
            aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(ableiten(wurzel, "aes"), "AES"),
                    new IvParameterSpec(iv));
            return aes.doFinal(geheim);
        } catch (Exception fehler) {
            throw new IllegalStateException("Umschlag liess sich nicht oeffnen: "
                    + fehler.getMessage(), fehler);
        }
    }

    // ------------------------------------------------------------- Werkzeug

    /**
     * Zwei Schluessel aus einem Zufallswert.
     *
     * <p>SHA-256 ueber Zweck und Wurzel. Das ist HKDF sehr aehnlich und fuer
     * diesen Fall ausreichend: die Wurzel ist bereits gleichverteilter Zufall
     * in voller Laenge, HKDFs Extraktionsschritt haette also nichts zu tun.
     * Der Zweck geht als Praefix ein, damit die beiden Ergebnisse
     * unabhaengig sind.</p>
     */
    private static byte[] ableiten(byte[] wurzel, String zweck) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(zweck.getBytes(StandardCharsets.UTF_8));
        sha.update((byte) 0);
        sha.update(wurzel);
        return sha.digest();
    }

    private static byte[] mac(byte[] schluessel, byte[] iv, byte[] geheim) throws Exception {
        Mac hmac = Mac.getInstance(HMAC);
        hmac.init(new SecretKeySpec(schluessel, HMAC));
        hmac.update(iv);
        hmac.update(geheim);
        return hmac.doFinal();
    }

    /**
     * Liest einen oeffentlichen Schluessel im PEM-Format.
     *
     * <p>Erzeugt auf dem Knoten mit
     * {@code openssl rsa -in knoten.key -pubout} - also genau die Form, die
     * dort ohnehin anfaellt.</p>
     */
    public static PublicKey ausPem(String pem) {
        try {
            String roh = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
            byte[] daten = Base64.getDecoder().decode(roh);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(daten));
        } catch (Exception fehler) {
            throw new IllegalArgumentException("Unlesbarer oeffentlicher Schluessel: "
                    + fehler.getMessage(), fehler);
        }
    }

    /** Erkennungswert eines Schluessels - fuer Uebersicht und Protokoll. */
    public static String fingerabdruck(String pem) {
        try {
            byte[] summe = MessageDigest.getInstance("SHA-256")
                    .digest(ausPem(pem).getEncoded());
            StringBuilder bau = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                if (i > 0 && i % 2 == 0) bau.append(':');
                bau.append(String.format("%02x", summe[i]));
            }
            return bau.toString();
        } catch (Exception fehler) {
            return "(unlesbar)";
        }
    }

    /** Nur fuer Proben: der Umschlag als Bytes. */
    static byte[] alsBytes(String text) {
        ByteArrayOutputStream aus = new ByteArrayOutputStream();
        byte[] daten = text.getBytes(StandardCharsets.UTF_8);
        aus.write(daten, 0, daten.length);
        return aus.toByteArray();
    }
}
