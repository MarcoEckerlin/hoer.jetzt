package jetzt.hoer.updater.dienst;

import jetzt.hoer.updater.daten.FreigabeDaten;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * Legt beim allerersten Start die Freigaben an, ohne die der Update-Server
 * sich selbst aussperrt.
 *
 * Der Grund steht in einrichten.sh: dessen Selbstprobe schiebt ein Testabbild
 * durch Caddy hoch und wieder herunter, und weil /etc/hosts die eigene Adresse
 * auf 127.0.0.1 zeigt, kommt dieser Zugriff von der Rueckschleife. Ohne
 * Freigabe scheitert die Probe - und zwar an der Zugangskontrolle, die sie
 * gerade erst eingeschaltet hat.
 *
 * Das hier in der Anwendung zu tun statt im Einrichtungsskript ist Absicht:
 * ein Schritt im Skript laesst sich ueberspringen, ein Volume laesst sich
 * loeschen und neu anlegen. Diese Bedingung muss aber bei *jedem* ersten
 * Start gelten, nicht nur beim ersten Einrichten.
 *
 * Die Voreinstellung umfasst nur die Rueckschleife und die privaten Bereiche.
 * Aus dem Internet ist damit weiterhin nichts erreichbar - diese Adressen
 * werden dort nicht geroutet. Wer im LAN steht, braucht ausserdem immer noch
 * einen gueltigen Ausweis.
 */
@Component
public class Erstbelegung implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Erstbelegung.class);

    private final FreigabeDaten freigaben;
    private final String start;

    public Erstbelegung(FreigabeDaten freigaben,
                        @Value("${hj.freigabe-start:127.0.0.0/8,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16}")
                        String start) {
        this.freigaben = freigaben;
        this.start = start;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Nur wenn noch gar nichts da ist. Sonst kaeme bei jedem Neustart
        // zurueck, was man gerade absichtlich gesperrt hat.
        if (!freigaben.alle().isEmpty()) {
            return;
        }

        for (String roh : start.split(",")) {
            String bereich = roh.trim();
            if (bereich.isEmpty()) continue;
            try {
                freigaben.anlegen(Netzbereich.aus(bereich).toString(),
                        "Grundfreigabe", "beim ersten Start angelegt", null);
            } catch (IllegalArgumentException e) {
                log.warn("Grundfreigabe {} ist unlesbar und wird uebergangen: {}",
                        bereich, e.getMessage());
            }
        }
        log.info("Erster Start - Grundfreigaben angelegt: {}", start);
    }
}
