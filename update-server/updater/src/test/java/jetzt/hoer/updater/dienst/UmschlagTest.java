package jetzt.hoer.updater.dienst;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Der Umschlag traegt Bot-Token, Datenbank-Passwort und Client-Secret zu den
 * Knoten. Was hier durchfaellt, faellt im Betrieb still durch.
 *
 * <p>Der Gegenpart laeuft als {@code openssl}-Skript auf dem Knoten
 * ({@code deploy/agent/tresor-oeffnen.sh}). Diese Proben pruefen die
 * Java-Seite; dass beide Seiten zusammenpassen, ist beim Bau des Verfahrens
 * gegen echtes openssl gemessen worden - siehe Kommentar in
 * {@link Umschlag}.</p>
 */
class UmschlagTest {

    private static KeyPair paar() throws Exception {
        KeyPairGenerator erzeuger = KeyPairGenerator.getInstance("RSA");
        // 2048 statt 3072 nur hier: die Probe soll schnell laufen. Im Betrieb
        // erzeugt aufsetzen.sh 3072 Bit.
        erzeuger.initialize(2048);
        return erzeuger.generateKeyPair();
    }

    private static String alsPem(java.security.PublicKey oeffentlich) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(oeffentlich.getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
    }

    @Test
    @DisplayName("Was hineingeht, kommt heraus")
    void hinUndZurueck() throws Exception {
        KeyPair knoten = paar();
        String inhalt = "HJ_DB_PASSWORD=geheim\nHJ_BOT_TOKEN=abc.def\n";

        String umschlag = Umschlag.verschliessen(
                inhalt.getBytes(StandardCharsets.UTF_8), alsPem(knoten.getPublic()));

        assertTrue(umschlag.startsWith(Umschlag.KENNUNG),
                "Die Kennung muss in der ersten Zeile stehen - daran erkennt das "
                + "Knotenskript, ob es ein Umschlag ist oder noch der alte Klartext.");
        assertFalse(umschlag.contains("geheim"),
                "Der Klartext darf im Umschlag nirgends auftauchen.");

        assertEquals(inhalt, new String(
                Umschlag.oeffnen(umschlag, knoten.getPrivate()), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Ein fremder Knoten bekommt ihn nicht auf")
    void fremderSchluesselScheitert() throws Exception {
        KeyPair fuerIhn = paar();
        KeyPair einAnderer = paar();

        String umschlag = Umschlag.verschliessen(
                "geheim".getBytes(StandardCharsets.UTF_8), alsPem(fuerIhn.getPublic()));

        // Das ist der eigentliche Zweck: ein aufgemachter Knoten gibt die
        // Zugangsdaten der anderen nicht preis.
        assertThrows(IllegalStateException.class,
                () -> Umschlag.oeffnen(umschlag, einAnderer.getPrivate()));
    }

    @Test
    @DisplayName("Ein veraenderter Geheimtext wird abgewiesen, nicht entschluesselt")
    void verfaelschungFaelltAuf() throws Exception {
        KeyPair knoten = paar();
        String umschlag = Umschlag.verschliessen(
                "HJ_DB_PASSWORD=geheim".getBytes(StandardCharsets.UTF_8),
                alsPem(knoten.getPublic()));

        String[] zeilen = umschlag.strip().split("\\R");
        byte[] geheim = Base64.getDecoder().decode(zeilen[3]);
        geheim[geheim.length - 1] ^= 0x01;
        zeilen[3] = Base64.getEncoder().encodeToString(geheim);
        String verfaelscht = String.join("\n", zeilen);

        // Der HMAC wird VOR dem Entschluesseln geprueft. Andersherum verriete
        // das Auffuellmuster von CBC den Klartext Byte fuer Byte - siehe
        // Klassenkommentar von Umschlag.
        IllegalStateException fehler = assertThrows(IllegalStateException.class,
                () -> Umschlag.oeffnen(verfaelscht, knoten.getPrivate()));
        assertTrue(fehler.getMessage().toLowerCase().contains("pruefsumme"),
                "Es muss an der Pruefsumme scheitern und nicht am Auffuellen - "
                + "sonst wird vor dem Pruefen entschluesselt. Gemeldet wurde: "
                + fehler.getMessage());
    }

    @Test
    @DisplayName("Zwei Umschlaege desselben Inhalts sehen verschieden aus")
    void keinWiedererkennen() throws Exception {
        KeyPair knoten = paar();
        byte[] gleich = "immer dasselbe".getBytes(StandardCharsets.UTF_8);
        String pem = alsPem(knoten.getPublic());

        // Ohne frischen Zufall je Umschlag liesse sich von aussen ablesen,
        // dass sich am Tresor nichts geaendert hat - und wann doch.
        assertNotEquals(Umschlag.verschliessen(gleich, pem),
                        Umschlag.verschliessen(gleich, pem));
    }

    @Test
    @DisplayName("Ein unlesbarer Schluessel faellt beim Hinterlegen auf, nicht spaeter")
    void unlesbarerSchluessel() {
        assertThrows(IllegalArgumentException.class,
                () -> Umschlag.ausPem("-----BEGIN PUBLIC KEY-----\nkein base64!\n-----END PUBLIC KEY-----"));
        assertThrows(IllegalArgumentException.class, () -> Umschlag.ausPem(""));
    }

    @Test
    @DisplayName("Der Fingerabdruck haengt am Schluessel, nicht an der Schreibweise")
    void fingerabdruck() throws Exception {
        KeyPair knoten = paar();
        String pem = alsPem(knoten.getPublic());
        // Andere Zeilenumbrueche, derselbe Schluessel - sonst sieht ein
        // neu formatiertes PEM in der Uebersicht wie ein Schluesseltausch aus.
        String andersUmgebrochen = pem.replace("\n", "\r\n");
        assertEquals(Umschlag.fingerabdruck(pem), Umschlag.fingerabdruck(andersUmgebrochen));
    }
}
