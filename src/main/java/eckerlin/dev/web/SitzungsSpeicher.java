package eckerlin.dev.web;

import eckerlin.dev.utils.DB;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Der Datenbank-Pool des Bots, bekanntgemacht bei Spring.
 *
 * <h2>Warum das hier steht</h2>
 *
 * <p>Der Bot verwaltet seine Verbindungen selbst (siehe {@link DB}); Spring
 * wusste bisher nichts davon. Das ging gut, solange niemand danach fragte.
 * Sobald aber eine Bibliothek im Klassenpfad liegt, die eine
 * {@link DataSource} braucht - hier Spring Session - sucht Spring Boot selbst
 * eine, findet keine, und versucht daraufhin, aus
 * {@code spring.datasource.url} eine zu bauen. Die Eigenschaft gibt es hier
 * nicht, denn die Zugangsdaten stehen in der {@code config.json}. Ergebnis:</p>
 *
 * <pre>
 * Failed to configure a DataSource: 'url' attribute is not specified
 * Reason: Failed to determine a suitable driver class
 * </pre>
 *
 * <p>Der Bot startete gar nicht mehr, und die Meldung zeigte auf eine
 * Einstellung, die es in diesem Projekt nie gab.</p>
 *
 * <h2>Warum ohne Bedingung</h2>
 *
 * <p>Frueher stand hier ein {@code @ConditionalOnProperty}: die Bohne sollte
 * nur entstehen, wenn die Sitzungsablage eingeschaltet ist. Das war die
 * falsche Reihenfolge. Ist die Bohne <em>nicht</em> da, baut Spring Boot seine
 * eigene - und scheitert. Die Bohne muss also immer da sein; ob Sitzungen in
 * der Datenbank landen, entscheidet stattdessen {@link eckerlin.dev.Main} durch
 * Aus- oder Einschalten der Session-Autokonfiguration.</p>
 *
 * <p>Kosten hat das keine: es ist derselbe Pool, den der Bot ohnehin oeffnet,
 * nur unter einem Namen, den Spring kennt. Ein zweiter Pool waere die
 * schlechte Loesung - doppelt so viele offene Verbindungen an einer Datenbank,
 * deren Obergrenze woanders festgelegt ist.</p>
 */
@Configuration
public class SitzungsSpeicher {

    @Bean
    public DataSource dataSource() {
        return DB.pool();
    }
}
