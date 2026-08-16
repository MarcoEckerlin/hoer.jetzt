package eckerlin.dev.security;

import eckerlin.dev.utils.Config;
import eckerlin.dev.utils.DB;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

/**
 * Zweiter Faktor (TOTP) fuer die eine Stelle, an der Geld entsteht.
 *
 * <h2>Wofuer genau</h2>
 *
 * <p>Das Autoscaling laeuft ohne Rueckfrage - es reagiert auf Last und muss das
 * auch nachts um drei tun. Bewusst geschuetzt ist der andere Weg: wenn ein
 * Mensch im Webinterface auf "Knoten anlegen" drueckt, etwa fuer einen
 * Premium-Server. Dort steckt kein Regelwerk dahinter, sondern eine
 * Entscheidung - und ein uebernommener Adminzugang waere sonst gleichbedeutend
 * mit einer offenen Kreditkarte.</p>
 *
 * <h2>Warum von Hand und nicht ueber eine Bibliothek</h2>
 *
 * <p>TOTP ist HMAC-SHA1 ueber einen Zaehler, acht Zeilen. Eine Abhaengigkeit
 * dafuer aufzunehmen hiesse, sie auf Dauer mitzupflegen - fuer Code, der sich
 * seit RFC 6238 nicht geaendert hat.</p>
 */
@Service
public class ZweiFaktorService {

    private static final String BASIS32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int STELLEN = 6;
    private static final long SCHRITT = 30;
    /**
     * Wie viele Zeitschritte Abweichung erlaubt sind.
     *
     * <p>Einer nach vorne und einer zurueck. Ohne Toleranz scheitert jeder
     * Code, der beim Abtippen die Grenze ueberschreitet, und bei einer Uhr,
     * die eine halbe Minute nachgeht, funktioniert gar nichts mehr - der
     * haeufigste Grund, warum TOTP "nicht geht".</p>
     */
    private static final int TOLERANZ = 1;

    private final int botId = Config.config.optInt("bot_id", 1);
    private final SecureRandom zufall = new SecureRandom();

    public boolean eingerichtet(String benutzerId) {
        return geheimnis(benutzerId) != null;
    }

    /**
     * Erzeugt ein neues Geheimnis und liefert die {@code otpauth:}-Adresse.
     *
     * <p>Das Geheimnis wird sofort gespeichert, aber erst mit dem ersten
     * gueltigen Code scharf: sonst haette sich jemand aussperren koennen, der
     * die Einrichtung anfaengt und den Browser schliesst.</p>
     */
    public String einrichten(String benutzerId, String anzeigename) throws SQLException {
        byte[] roh = new byte[20];
        zufall.nextBytes(roh);
        String geheim = base32(roh);

        try (Connection verbindung = DB.connection();
             PreparedStatement anweisung = verbindung.prepareStatement(
                     "UPDATE bot_admins SET totp_secret = ?, totp_letzter_schritt = NULL WHERE bot_id = ? AND user_id = ?")) {
            anweisung.setString(1, geheim);
            anweisung.setInt(2, botId);
            anweisung.setString(3, benutzerId.trim());
            if (anweisung.executeUpdate() == 0) {
                throw new IllegalStateException("Nur eingetragene Bot-Admins koennen einen zweiten Faktor einrichten.");
            }
        }

        String name = anzeigename == null || anzeigename.isBlank() ? benutzerId : anzeigename;
        return "otpauth://totp/%s:%s?secret=%s&issuer=%s&digits=%d&period=%d".formatted(
                URLEncoder.encode("hoer.jetzt", StandardCharsets.UTF_8),
                URLEncoder.encode(name, StandardCharsets.UTF_8),
                geheim,
                URLEncoder.encode("hoer.jetzt", StandardCharsets.UTF_8),
                STELLEN, SCHRITT);
    }

    public void entfernen(String benutzerId) throws SQLException {
        try (Connection verbindung = DB.connection();
             PreparedStatement anweisung = verbindung.prepareStatement(
                     "UPDATE bot_admins SET totp_secret = NULL, totp_letzter_schritt = NULL WHERE bot_id = ? AND user_id = ?")) {
            anweisung.setInt(1, botId);
            anweisung.setString(2, benutzerId.trim());
            anweisung.executeUpdate();
        }
    }

    /**
     * Prueft einen Code und verbraucht ihn.
     *
     * @return true, wenn der Code stimmt und noch nicht benutzt wurde
     */
    public boolean pruefen(String benutzerId, String code) {
        String geheim = geheimnis(benutzerId);
        if (geheim == null || code == null) {
            return false;
        }

        String sauber = code.replaceAll("\\s+", "");
        if (!sauber.matches("\\d{" + STELLEN + "}")) {
            return false;
        }

        long jetzt = Instant.now().getEpochSecond() / SCHRITT;
        Long letzter = letzterSchritt(benutzerId);

        for (int versatz = -TOLERANZ; versatz <= TOLERANZ; versatz++) {
            long schritt = jetzt + versatz;
            if (letzter != null && schritt <= letzter) {
                // Schon eingeloest. Ein zweites Mal gilt derselbe Code nicht -
                // sonst waere er 90 Sekunden lang wiederverwendbar.
                continue;
            }
            if (rechne(geheim, schritt).equals(sauber)) {
                schrittMerken(benutzerId, schritt);
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ intern

    private String geheimnis(String benutzerId) {
        if (benutzerId == null || benutzerId.isBlank()) {
            return null;
        }
        try (Connection verbindung = DB.connection();
             PreparedStatement anweisung = verbindung.prepareStatement(
                     "SELECT totp_secret FROM bot_admins WHERE bot_id = ? AND user_id = ? LIMIT 1")) {
            anweisung.setInt(1, botId);
            anweisung.setString(2, benutzerId.trim());
            try (ResultSet ergebnis = anweisung.executeQuery()) {
                if (!ergebnis.next()) {
                    return null;
                }
                String wert = ergebnis.getString("totp_secret");
                return wert == null || wert.isBlank() ? null : wert.trim();
            }
        } catch (SQLException fehler) {
            return null;
        }
    }

    private Long letzterSchritt(String benutzerId) {
        try (Connection verbindung = DB.connection();
             PreparedStatement anweisung = verbindung.prepareStatement(
                     "SELECT totp_letzter_schritt FROM bot_admins WHERE bot_id = ? AND user_id = ? LIMIT 1")) {
            anweisung.setInt(1, botId);
            anweisung.setString(2, benutzerId.trim());
            try (ResultSet ergebnis = anweisung.executeQuery()) {
                if (!ergebnis.next()) {
                    return null;
                }
                long wert = ergebnis.getLong("totp_letzter_schritt");
                return ergebnis.wasNull() ? null : wert;
            }
        } catch (SQLException fehler) {
            return null;
        }
    }

    private void schrittMerken(String benutzerId, long schritt) {
        try (Connection verbindung = DB.connection();
             PreparedStatement anweisung = verbindung.prepareStatement(
                     "UPDATE bot_admins SET totp_letzter_schritt = ? WHERE bot_id = ? AND user_id = ?")) {
            anweisung.setLong(1, schritt);
            anweisung.setInt(2, botId);
            anweisung.setString(3, benutzerId.trim());
            anweisung.executeUpdate();
        } catch (SQLException fehler) {
            // Nicht schoen, aber kein Grund, eine gueltige Anmeldung
            // abzulehnen: der Code bleibt dann eben seine 30 Sekunden gueltig.
        }
    }

    private String rechne(String geheim, long schritt) {
        byte[] schluessel = entbase32(geheim);
        byte[] zaehler = new byte[8];
        long wert = schritt;
        for (int i = 7; i >= 0; i--) {
            zaehler[i] = (byte) (wert & 0xff);
            wert >>= 8;
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(schluessel, "HmacSHA1"));
            byte[] abdruck = mac.doFinal(zaehler);

            int versatz = abdruck[abdruck.length - 1] & 0x0f;
            int binaer = ((abdruck[versatz] & 0x7f) << 24)
                    | ((abdruck[versatz + 1] & 0xff) << 16)
                    | ((abdruck[versatz + 2] & 0xff) << 8)
                    | (abdruck[versatz + 3] & 0xff);

            return String.format("%0" + STELLEN + "d", binaer % (int) Math.pow(10, STELLEN));
        } catch (GeneralSecurityException fehler) {
            throw new IllegalStateException("HmacSHA1 fehlt in dieser Laufzeit.", fehler);
        }
    }

    private String base32(byte[] daten) {
        StringBuilder gebaut = new StringBuilder();
        int puffer = 0;
        int bits = 0;
        for (byte b : daten) {
            puffer = (puffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                gebaut.append(BASIS32.charAt((puffer >> (bits - 5)) & 0x1f));
                bits -= 5;
            }
        }
        if (bits > 0) {
            gebaut.append(BASIS32.charAt((puffer << (5 - bits)) & 0x1f));
        }
        return gebaut.toString();
    }

    private byte[] entbase32(String text) {
        String sauber = text.trim().toUpperCase().replace("=", "");
        int puffer = 0;
        int bits = 0;
        java.io.ByteArrayOutputStream heraus = new java.io.ByteArrayOutputStream();
        for (char zeichen : sauber.toCharArray()) {
            int stelle = BASIS32.indexOf(zeichen);
            if (stelle < 0) {
                continue;
            }
            puffer = (puffer << 5) | stelle;
            bits += 5;
            if (bits >= 8) {
                heraus.write((puffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return heraus.toByteArray();
    }
}
