package eckerlin.dev.web;

import eckerlin.dev.utils.DB;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

import javax.sql.DataSource;
import java.time.Duration;

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

    /**
     * Wie die Sitzungskennung im Cookie steht.
     *
     * <h2>Warum ohne Base64</h2>
     *
     * <p>Spring Session kodiert die Kennung ab Werk in Base64 und dekodiert sie
     * beim Lesen wieder. Solange dasselbe Cookie immer von Spring Session
     * geschrieben wurde, faellt das nicht auf. Beim Umstieg von den
     * Tomcat-eigenen Sitzungen auf die Datenbank schon: der Browser schickt
     * weiterhin das alte Cookie mit demselben Namen, dessen Wert aber
     * Klartext ist. Base64-dekodiert wird daraus Binaermuell - und der enthaelt
     * regelmaessig ein Nullbyte:</p>
     *
     * <pre>
     * ERROR: invalid byte sequence for encoding "UTF8": 0x00
     *   Where: unnamed portal parameter $1
     *   ... JdbcIndexedSessionRepository.findById
     * </pre>
     *
     * <p>Das ist kein Anmeldefehler, sondern eine HTTP 500 auf <em>jeder</em>
     * Seite, solange das Cookie im Browser liegt. Betroffen ist jeder, der die
     * Oberflaeche vor der Umstellung benutzt hat - also ausgerechnet die
     * Stammnutzer. Ohne Base64 wird der Cookie-Wert genommen, wie er ist: ein
     * alter Wert findet dann schlicht keine Sitzung, und es entsteht eine
     * neue.</p>
     *
     * <p>Bewusst nur diese eine Einstellung und keine eigene Filterung des
     * Cookie-Werts: ein Nullbyte kommt hier nicht aus dem Browser, sondern
     * entsteht erst beim Dekodieren. Faellt das Dekodieren weg, faellt auch die
     * Ursache weg.</p>
     */
    @Bean
    public CookieSerializer cookieSerializer() {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();

        // Derselbe Name wie bisher - ein neuer haette alle angemeldeten
        // Sitzungen auf einen Schlag entwertet.
        serializer.setCookieName("discordbot_session");
        serializer.setUseBase64Encoding(false);
        serializer.setCookiePath("/");
        serializer.setCookieMaxAge((int) Duration.ofDays(30).toSeconds());
        serializer.setUseHttpOnlyCookie(true);
        serializer.setSameSite("Lax");
        return serializer;
    }
}
