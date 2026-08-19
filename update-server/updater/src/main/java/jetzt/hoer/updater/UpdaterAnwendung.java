package jetzt.hoer.updater;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * hoer.jetzt - Update-Server, Verwaltungsteil.
 *
 * Zwei Aufgaben, die bewusst in einem Dienst liegen, weil beide auf denselben
 * Datenbestand schauen:
 *
 *   1. Torwaechter. Caddy fragt vor jedem Zugriff auf die Abbilder, das
 *      Release und den Tresor hier nach, ob diese Adresse darf. Damit wirkt
 *      eine Freischaltung sofort - ohne Caddy neu zu laden.
 *
 *   2. Uebersicht. Weil jeder Zugriff hier vorbeikommt und die Knoten nach
 *      einem Update ihren Stand melden, weiss dieser Dienst von selbst, wer
 *      wann da war und was er faehrt. Dafuer ist keine Abfrage in Richtung
 *      der Knoten noetig - die sitzen hinter fremdem NAT.
 *
 * Die beiden Aufgaben haengen an getrennten Ports, siehe {@link jetzt.hoer.updater.konfig.PortKonfiguration}.
 */
@SpringBootApplication
@EnableScheduling
public class UpdaterAnwendung {

    public static void main(String[] args) {
        SpringApplication.run(UpdaterAnwendung.class, args);
    }
}
