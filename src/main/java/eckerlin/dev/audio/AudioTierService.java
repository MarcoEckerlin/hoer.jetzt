package eckerlin.dev.audio;

import eckerlin.dev.security.GuildEntitlementService;
import eckerlin.dev.security.GuildFeature;
import eckerlin.dev.utils.Config;
import eckerlin.dev.utils.DB;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Kennt die Leistungsstufe jedes Audio-Knotens und jedes Discord-Servers.
 *
 * <p>Bewusst ohne weitere Spring-Abhaengigkeiten, damit der Load-Balancer -
 * der tief in der Lavalink-Bibliothek haengt - ihn ohne Umwege benutzen kann.</p>
 *
 * <p>Die Stufe eines Servers steckt in den Freischaltungen ({@code guild_entitlements})
 * und damit an derselben Stelle wie KI-Chat und KI-Radio: alles, was nur ein
 * Bot-Administrator vergeben darf, liegt in einer Tabelle und hat eine Oberflaeche.</p>
 */
@Service
public class AudioTierService {

    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private final int botId = Config.config.optInt("bot_id", 1);
    private final GuildEntitlementService entitlementService;
    private final AtomicReference<Knoten> cache = new AtomicReference<>(null);

    public AudioTierService(GuildEntitlementService entitlementService) {
        this.entitlementService = entitlementService;
    }

    /** Stufe eines Discord-Servers. Ohne Freischaltung immer Standard. */
    public NodeTier tierOf(long guildId) {
        return entitlementService.isEnabled(String.valueOf(guildId), GuildFeature.PREMIUM_AUDIO)
                ? NodeTier.PREMIUM
                : NodeTier.FREE;
    }

    /** Stufe eines Knotens anhand seines Namens. Unbekannte Knoten gelten als Standard. */
    public NodeTier tierOfNode(String nodeName) {
        Knoten knoten = laden();
        return knoten.stufen().getOrDefault(nodeName, NodeTier.FREE);
    }

    /** Obergrenze gleichzeitiger Wiedergaben. 0 bedeutet unbegrenzt. */
    public int capacityOfNode(String nodeName) {
        Knoten knoten = laden();
        return knoten.grenzen().getOrDefault(nodeName, 0);
    }

    /** Verwirft den Zwischenspeicher - nach Aenderungen im Adminbereich. */
    public void invalidate() {
        cache.set(null);
    }

    private Knoten laden() {
        Knoten vorhanden = cache.get();
        if (vorhanden != null && Instant.now().isBefore(vorhanden.gueltigBis())) {
            return vorhanden;
        }

        Map<String, NodeTier> stufen = new LinkedHashMap<>();
        Map<String, Integer> grenzen = new LinkedHashMap<>();

        try (Connection connection = DB.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT node_name, tier, max_players FROM deployment_lavalink_nodes WHERE bot_id = ?")) {
            statement.setInt(1, botId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String name = resultSet.getString("node_name");
                    if (name == null || name.isBlank()) {
                        continue;
                    }
                    stufen.put(name, NodeTier.fromKey(resultSet.getString("tier")));
                    grenzen.put(name, Math.max(0, resultSet.getInt("max_players")));
                }
            }
        } catch (SQLException exception) {
            // Bei einem Datenbankproblem lieber alles als Standard behandeln, als
            // versehentlich jeden Server auf die Premium-Knoten zu lassen.
            Knoten alt = cache.get();
            if (alt != null) {
                return alt;
            }
        }

        Knoten frisch = new Knoten(stufen, grenzen, Instant.now().plus(CACHE_TTL));
        cache.set(frisch);
        return frisch;
    }

    private record Knoten(Map<String, NodeTier> stufen, Map<String, Integer> grenzen, Instant gueltigBis) {
    }
}
