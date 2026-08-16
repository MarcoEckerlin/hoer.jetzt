package eckerlin.dev;

import eckerlin.dev.utils.Config;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

@SpringBootApplication
public class Main {

    public static void main(String[] args) {
        int port = Config.resolveServerPort();

        /*
         * Wo die Anmeldungen liegen.
         *
         * "datenbank" legt sie in PostgreSQL ab: dann ueberleben sie einen
         * Neustart und gelten auf allen Nodes. Alles andere laesst sie im
         * Arbeitsspeicher des jeweiligen Bots - dort waren sie immer, und dort
         * sind sie nach jedem Neustart weg.
         */
        String sitzungsSpeicher = System.getenv("HJ_SESSION_STORE");
        boolean inDatenbank = "datenbank".equalsIgnoreCase(
                sitzungsSpeicher == null ? "" : sitzungsSpeicher.trim());

        // LinkedHashMap statt Map.ofEntries: ein Eintrag kommt nur unter einer
        // Bedingung dazu, und Map.ofEntries nimmt eine feste Liste. Der erste
        // Versuch behalf sich mit einem leeren Wert - eine leere Zeile in einer
        // Ausschlussliste ist aber kein "nichts ausschliessen", sondern ein
        // Klassenname, den es nicht gibt.
        Map<String, Object> eigenschaften = new LinkedHashMap<>();
        eigenschaften.put("server.port", port);
        eigenschaften.put("server.address", "0.0.0.0");
        eigenschaften.put("server.forward-headers-strategy", "framework");
        eigenschaften.put("server.servlet.session.timeout", "30d");
        eigenschaften.put("server.servlet.session.cookie.name", "discordbot_session");
        eigenschaften.put("server.servlet.session.cookie.max-age", "30d");
        eigenschaften.put("server.servlet.session.cookie.http-only", true);
        eigenschaften.put("server.servlet.session.cookie.same-site", "lax");
        eigenschaften.put("server.servlet.session.tracking-modes", "cookie");
        eigenschaften.put("spring.servlet.multipart.max-file-size", "4MB");
        eigenschaften.put("spring.servlet.multipart.max-request-size", "4MB");

        if (inDatenbank) {
            // Legt die beiden Tabellen beim ersten Start selbst an. Das
            // mitgelieferte Skript gehoert zur Bibliotheksfassung - es von Hand
            // in unser Schema zu kopieren hiesse, es bei jedem Versionssprung
            // nachzupflegen.
            eigenschaften.put("spring.session.jdbc.initialize-schema", "always");
            eigenschaften.put("spring.session.jdbc.table-name", "SPRING_SESSION");
        } else {
            /*
             * Aus heisst: die Autokonfiguration gar nicht erst laden.
             *
             * Der erste Versuch setzte "spring.session.store-type" auf "none".
             * Diese Eigenschaft gibt es seit Spring Boot 3 nicht mehr - sie
             * wurde stillschweigend ignoriert. Spring Session schaltete sich
             * trotzdem ein, verlangte eine DataSource, und der Start brach ab
             * mit einer Meldung ueber "spring.datasource.url" - einer
             * Einstellung, die es in diesem Projekt nie gab. Wer die sucht,
             * sucht am falschen Ende.
             *
             * Ein Ausschluss wirkt unabhaengig von der Boot-Fassung: was nicht
             * geladen wird, kann nichts fordern.
             */
            eigenschaften.put("spring.autoconfigure.exclude",
                    "org.springframework.boot.autoconfigure.session.SessionAutoConfiguration");
        }

        new SpringApplicationBuilder(Main.class)
                .properties(eigenschaften)
                .run(args);
    }
}
