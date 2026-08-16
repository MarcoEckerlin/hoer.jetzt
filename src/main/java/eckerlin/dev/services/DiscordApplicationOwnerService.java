package eckerlin.dev.services;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import eckerlin.dev.utils.Alert;
import net.dv8tion.jda.api.entities.ApplicationInfo;
import net.dv8tion.jda.api.entities.ApplicationTeam;
import net.dv8tion.jda.api.entities.User;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class DiscordApplicationOwnerService {

    private static final String DISCORD_API = "https://discord.com/api/v10";
    private static final Duration SUCCESS_CACHE_TTL = Duration.ofMinutes(5);
    private static final Duration FAILURE_CACHE_TTL = Duration.ofSeconds(45);

    private final AppConfigService configService;
    private final DiscordBotService discordBotService;
    private final WebClient webClient;
    private volatile CachedOwner cachedOwner;

    public DiscordApplicationOwnerService(
            AppConfigService configService,
            DiscordBotService discordBotService,
            WebClient.Builder webClientBuilder
    ) {
        this.configService = configService;
        this.discordBotService = discordBotService;
        this.webClient = webClientBuilder
                .baseUrl(DISCORD_API)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.USER_AGENT, "DiscordBot Dashboard/alpha-1.0")
                .build();
    }

    public boolean isBotOwner(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }

        return getApplicationOwner()
                .map(owner -> userId.equals(owner.ownerId()))
                .orElse(false);
    }

    public Optional<DiscordApplicationOwner> getApplicationOwner() {
        CachedOwner snapshot = cachedOwner;
        Instant now = Instant.now();
        if (snapshot != null && now.isBefore(snapshot.validUntil())) {
            return Optional.ofNullable(snapshot.owner());
        }

        synchronized (this) {
            snapshot = cachedOwner;
            now = Instant.now();
            if (snapshot != null && now.isBefore(snapshot.validUntil())) {
                return Optional.ofNullable(snapshot.owner());
            }

            Optional<DiscordApplicationOwner> owner = fetchApplicationOwner();
            cachedOwner = new CachedOwner(
                    owner.orElse(null),
                    now.plus(owner.isPresent() ? SUCCESS_CACHE_TTL : FAILURE_CACHE_TTL)
            );
            return owner;
        }
    }

    public void evictCache() {
        cachedOwner = null;
    }

    private Optional<DiscordApplicationOwner> fetchApplicationOwner() {
        Optional<DiscordApplicationOwner> viaToken = fetchOwnerViaBotToken();
        if (viaToken.isPresent()) {
            return viaToken;
        }

        return fetchOwnerViaJda();
    }

    private Optional<DiscordApplicationOwner> fetchOwnerViaBotToken() {
        String token = configService.getConfiguredBotToken().trim();
        if (token.isBlank()) {
            return Optional.empty();
        }

        try {
            DiscordApplicationResponse response = webClient.get()
                    .uri("/applications/@me")
                    .headers(headers -> headers.set(HttpHeaders.AUTHORIZATION, "Bot " + token))
                    .retrieve()
                    .bodyToMono(DiscordApplicationResponse.class)
                    .blockOptional(Duration.ofSeconds(10))
                    .orElse(null);

            return mapResponse(response);
        } catch (RuntimeException exception) {
            Alert.send("WARN", "WEB", "Discord-Application-Owner konnte per Bot-Token nicht geladen werden: " + shortMessage(exception));
            return Optional.empty();
        }
    }

    private Optional<DiscordApplicationOwner> fetchOwnerViaJda() {
        try {
            return discordBotService.getJdaOptional()
                    .flatMap(jda -> mapApplicationInfo(jda.retrieveApplicationInfo().complete()));
        } catch (RuntimeException exception) {
            Alert.send("WARN", "WEB", "Discord-Application-Owner konnte per JDA nicht geladen werden: " + shortMessage(exception));
            return Optional.empty();
        }
    }

    private Optional<DiscordApplicationOwner> mapApplicationInfo(ApplicationInfo applicationInfo) {
        if (applicationInfo == null) {
            return Optional.empty();
        }

        ApplicationTeam team = applicationInfo.getTeam();
        if (team != null && team.getOwner() != null && team.getOwner().getUser() != null) {
            User owner = team.getOwner().getUser();
            return Optional.of(new DiscordApplicationOwner(
                    applicationInfo.getId(),
                    owner.getId(),
                    displayName(owner.getGlobalName(), owner.getName())
            ));
        }

        User owner = applicationInfo.getOwner();
        if (owner == null) {
            return Optional.empty();
        }

        return Optional.of(new DiscordApplicationOwner(
                applicationInfo.getId(),
                owner.getId(),
                displayName(owner.getGlobalName(), owner.getName())
        ));
    }

    private Optional<DiscordApplicationOwner> mapResponse(DiscordApplicationResponse response) {
        if (response == null || blank(response.id()).isBlank()) {
            return Optional.empty();
        }

        String ownerId = "";
        String ownerName = "";

        if (response.owner() != null) {
            ownerId = blank(response.owner().id());
            ownerName = displayName(response.owner().globalName(), response.owner().username());
        }

        if (ownerId.isBlank() && response.team() != null) {
            ownerId = blank(response.team().ownerUserId());
            ownerName = response.team().memberName(ownerId);
        }

        if (ownerId.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(new DiscordApplicationOwner(response.id().trim(), ownerId, ownerName));
    }

    private String shortMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.replaceAll("\\s+", " ").trim();
    }

    private String displayName(String globalName, String username) {
        String preferred = blank(globalName);
        return preferred.isBlank() ? blank(username) : preferred;
    }

    private String blank(String value) {
        return value == null ? "" : value.trim();
    }

    private record CachedOwner(DiscordApplicationOwner owner, Instant validUntil) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DiscordApplicationResponse(
            String id,
            DiscordUserResponse owner,
            DiscordTeamResponse team
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DiscordUserResponse(
            String id,
            String username,
            @JsonProperty("global_name") String globalName
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DiscordTeamResponse(
            @JsonProperty("owner_user_id") String ownerUserId,
            List<DiscordTeamMemberResponse> members
    ) {
        private String memberName(String userId) {
            if (userId == null || userId.isBlank() || members == null) {
                return "";
            }

            return members.stream()
                    .map(DiscordTeamMemberResponse::user)
                    .filter(user -> user != null && userId.equals(user.id()))
                    .findFirst()
                    .map(user -> {
                        String preferred = user.globalName();
                        return preferred == null || preferred.isBlank() ? blankUser(user.username()) : preferred.trim();
                    })
                    .orElse("");
        }

        private String blankUser(String value) {
            return value == null ? "" : value.trim();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DiscordTeamMemberResponse(
            DiscordUserResponse user
    ) {
    }
}
