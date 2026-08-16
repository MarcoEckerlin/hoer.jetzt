package eckerlin.dev.cluster;

import eckerlin.dev.utils.Alert;
import eckerlin.dev.utils.Config;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

/**
 * Der gemeinsame Kurzzeitspeicher aller Prozesse einer Node.
 *
 * <p>Redis ist hier nicht "Cache, damit es schneller geht". Es ist die
 * Schicht, die den getrennten Web-Dienst ueberhaupt erst moeglich macht: die
 * Weboberflaeche musste bisher <em>im selben Prozess</em> wie der Bot laufen,
 * weil sie {@code AudioService} direkt aufrief. Ueber Veroeffentlichen und
 * Abonnieren geht das von jedem Prozess aus.</p>
 *
 * <p>Was hier liegt, ist entweder fluechtig oder gehoert ohnehin dieser Node -
 * deshalb wird Redis <b>nicht</b> zwischen Nodes repliziert. Das waere eine
 * zweite verteilte Datenbank mit denselben Problemen und ohne Gewinn.</p>
 *
 * <p><b>Ohne Redis laeuft alles weiter.</b> Jede Methode hier faellt still auf
 * "nichts gefunden" zurueck. Ein einzelner Prozess braucht Redis nicht, und
 * ein Ausfall darf den Bot nicht mitnehmen - er darf ihn nur langsamer und
 * dummer machen.</p>
 */
@Service
public class RedisService {

    /** Wie lange ein Player-Zustand gilt. Laenger waere gelogen: der Prozess koennte weg sein. */
    private static final int PLAYER_TTL_SEKUNDEN = 30;
    /** Ein Shard meldet sich alle 10 s; nach 30 s gilt er als verschwunden. */
    private static final int BESITZ_TTL_SEKUNDEN = 30;

    private final JedisPool pool;
    private final boolean aktiv;
    private final ExecutorService horcher = Executors.newCachedThreadPool(aufgabe -> {
        Thread strang = new Thread(aufgabe, "redis-horcher");
        strang.setDaemon(true);
        return strang;
    });

    public RedisService() {
        var redis = Config.config.optJSONObject("redis");
        String host = redis == null ? "" : redis.optString("host", "").trim();

        if (host.isBlank()) {
            this.pool = null;
            this.aktiv = false;
            Alert.send("INFO", "REDIS", "Kein Redis eingetragen - Einzelbetrieb.");
            return;
        }

        JedisPoolConfig konfiguration = new JedisPoolConfig();
        konfiguration.setMaxTotal(32);
        konfiguration.setMaxIdle(8);
        konfiguration.setMinIdle(2);
        // Sonst haelt der Pool tote Verbindungen fest, wenn Redis kurz weg war.
        konfiguration.setTestOnBorrow(true);

        String passwort = redis.optString("password", "").trim();
        this.pool = new JedisPool(
                konfiguration,
                host,
                redis.optInt("port", 6379),
                (int) Duration.ofSeconds(2).toMillis(),
                passwort.isBlank() ? null : passwort
        );

        boolean erreichbar;
        try (Jedis verbindung = pool.getResource()) {
            erreichbar = "PONG".equalsIgnoreCase(verbindung.ping());
        } catch (RuntimeException fehler) {
            erreichbar = false;
            Alert.send("WARN", "REDIS", "Redis nicht erreichbar (" + host + "): " + fehler.getMessage());
        }
        this.aktiv = erreichbar;
        if (erreichbar) {
            Alert.send("INFO", "REDIS", "Redis verbunden: " + host);
        }
    }

    public boolean istAktiv() {
        return aktiv && pool != null;
    }

    // ------------------------------------------------------------------
    // Wer bedient welchen Server
    // ------------------------------------------------------------------

    /**
     * Meldet, dass dieser Prozess einen Server bedient.
     *
     * <p>Mit Ablaufdatum: verschwindet der Prozess, verschwindet auch der
     * Eintrag. Ein Besitz ohne Ablauf waere nach dem ersten Absturz eine
     * Luege, an die sich alle anderen halten.</p>
     */
    public void besitzMelden(String guildId, String prozessId) {
        setzen("guild:" + guildId + ":owner", prozessId, BESITZ_TTL_SEKUNDEN);
    }

    public Optional<String> besitzerVon(String guildId) {
        return lesen("guild:" + guildId + ":owner");
    }

    // ------------------------------------------------------------------
    // Player-Zustand fuer die Weboberflaeche
    // ------------------------------------------------------------------

    public void playerZustandSchreiben(String guildId, String json) {
        setzen("player:" + guildId, json, PLAYER_TTL_SEKUNDEN);
    }

    public Optional<String> playerZustand(String guildId) {
        return lesen("player:" + guildId);
    }

    // ------------------------------------------------------------------
    // Gesundheit der Node
    // ------------------------------------------------------------------

    public void gesundheitMelden(String knotenName, Map<String, String> werte) {
        if (!istAktiv()) {
            return;
        }
        try (Jedis verbindung = pool.getResource()) {
            String schluessel = "node:" + knotenName + ":health";
            verbindung.hset(schluessel, werte);
            verbindung.expire(schluessel, BESITZ_TTL_SEKUNDEN);
        } catch (RuntimeException fehler) {
            stillSchweigen(fehler);
        }
    }

    // ------------------------------------------------------------------
    // Befehle von der Weboberflaeche an den besitzenden Prozess
    // ------------------------------------------------------------------

    public void befehlSenden(String guildId, String json) {
        if (!istAktiv()) {
            return;
        }
        try (Jedis verbindung = pool.getResource()) {
            verbindung.publish("cmd:" + guildId, json);
        } catch (RuntimeException fehler) {
            stillSchweigen(fehler);
        }
    }

    /**
     * Hoert auf Befehle fuer die eigenen Server.
     *
     * <p>Laeuft auf einem eigenen Strang, weil {@code subscribe} blockiert,
     * bis jemand kuendigt. Der Strang ist ein Daemon - er haelt die Anwendung
     * beim Beenden nicht auf.</p>
     */
    public void befehleAbonnieren(String muster, BiConsumer<String, String> empfaenger) {
        if (!istAktiv()) {
            return;
        }
        horcher.execute(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try (Jedis verbindung = pool.getResource()) {
                    verbindung.psubscribe(new JedisPubSub() {
                        @Override
                        public void onPMessage(String vorlage, String kanal, String nachricht) {
                            try {
                                empfaenger.accept(kanal, nachricht);
                            } catch (RuntimeException fehler) {
                                Alert.send("WARN", "REDIS",
                                        "Befehl aus " + kanal + " nicht verarbeitet: " + fehler.getMessage());
                            }
                        }
                    }, muster);
                } catch (RuntimeException fehler) {
                    // Verbindung weg: kurz warten und neu anmelden. Ohne diese
                    // Schleife waere der Bot nach dem ersten Redis-Neustart
                    // dauerhaft taub, ohne dass es jemand merkt.
                    Alert.send("WARN", "REDIS", "Abonnement abgerissen, neuer Versuch in 5 s: " + fehler.getMessage());
                    try {
                        Thread.sleep(5000L);
                    } catch (InterruptedException unterbrochen) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        });
    }

    // ------------------------------------------------------------------

    private void setzen(String schluessel, String wert, int ttlSekunden) {
        if (!istAktiv()) {
            return;
        }
        try (Jedis verbindung = pool.getResource()) {
            verbindung.setex(schluessel, ttlSekunden, wert);
        } catch (RuntimeException fehler) {
            stillSchweigen(fehler);
        }
    }

    private Optional<String> lesen(String schluessel) {
        if (!istAktiv()) {
            return Optional.empty();
        }
        try (Jedis verbindung = pool.getResource()) {
            return Optional.ofNullable(verbindung.get(schluessel));
        } catch (RuntimeException fehler) {
            stillSchweigen(fehler);
            return Optional.empty();
        }
    }

    /**
     * Ein Redis-Fehler darf die Wiedergabe nie stoeren.
     *
     * <p>Deshalb nur eine Zeile ins Protokoll und weiter. Wer hier eine
     * Ausnahme durchreichte, haette bei einem Redis-Neustart einen stummen
     * Bot - fuer einen Cache ein absurder Preis.</p>
     */
    private void stillSchweigen(RuntimeException fehler) {
        Alert.send("WARN", "REDIS", "Zugriff fehlgeschlagen: " + fehler.getMessage());
    }
}
