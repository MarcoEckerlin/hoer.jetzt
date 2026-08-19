package jetzt.hoer.updater.dienst;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Die beiden Passwoerter.
 *
 *   Knoten-Passwort   4096 Bit Zufall. Oeffnet Abbilder, Release, Tresor und
 *                     die Meldestelle. Liegt dauerhaft auf jedem Knoten.
 *
 *   Aufsetz-Passwort  Kurz und tippbar. Oeffnet nur /knoten/ - das
 *                     Installationsskript und die Compose-Dateien.
 *
 * Beide kommen als Basic-Auth herein. Warum Basic und nicht ein eigener Kopf:
 * "docker login" kann genau das und sonst nichts. Ein Bearer-Token muesste
 * man Docker auf einem zweiten Weg unterschieben, und dann haette man zwei
 * Verfahren fuer dieselbe Sache.
 *
 * Der Vergleich laeuft ueber MessageDigest.isEqual - der bricht nicht beim
 * ersten abweichenden Zeichen ab. Ein String.equals verraet ueber die
 * Laufzeit, wie viele Zeichen am Anfang schon stimmen; bei einem Passwort,
 * das ueber das Netz erreichbar ist, ist das kein theoretischer Einwand.
 */
@Service
public class Zugang {

    private final byte[] knoten;
    private final byte[] aufsetzen;

    public Zugang(@Value("${hj.token.knoten}") String knoten,
                  @Value("${hj.token.aufsetzen}") String aufsetzen) {
        this.knoten = knoten.trim().getBytes(StandardCharsets.UTF_8);
        this.aufsetzen = aufsetzen.trim().getBytes(StandardCharsets.UTF_8);
    }

    public boolean knotenPasswort(String kopf) {
        return stimmt(kopf, knoten);
    }

    public boolean aufsetzPasswort(String kopf) {
        return stimmt(kopf, aufsetzen);
    }

    /**
     * @param kopf der vollstaendige Authorization-Kopf, also "Basic base64(benutzer:passwort)"
     */
    private static boolean stimmt(String kopf, byte[] erwartet) {
        String passwort = passwortAus(kopf);
        if (passwort == null) return false;
        return MessageDigest.isEqual(passwort.getBytes(StandardCharsets.UTF_8), erwartet);
    }

    /**
     * Der Benutzername wird nicht geprueft. Es gibt genau ein Passwort je
     * Bereich; ein zusaetzlicher Name waere ein zweites Geheimnis, das keines
     * ist - er steht in jeder Anleitung. Docker verlangt aber, dass einer
     * dasteht, deshalb wird er gelesen und verworfen.
     */
    private static String passwortAus(String kopf) {
        if (kopf == null || !kopf.regionMatches(true, 0, "Basic ", 0, 6)) {
            return null;
        }
        try {
            String roh = new String(Base64.getDecoder().decode(kopf.substring(6).trim()),
                    StandardCharsets.UTF_8);
            int doppel = roh.indexOf(':');
            return doppel < 0 ? "" : roh.substring(doppel + 1);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
