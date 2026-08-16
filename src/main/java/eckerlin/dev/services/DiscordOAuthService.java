package eckerlin.dev.services;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import eckerlin.dev.web.dto.DashboardSession;
import eckerlin.dev.web.dto.DiscordGuildAccess;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;

@Service
public class DiscordOAuthService {

    private static final String DISCORD_API = "https://discord.com/api/v10";

    private final AppConfigService configService;
    private final WebClient webClient;

    public DiscordOAuthService(AppConfigService configService, WebClient.Builder webClientBuilder) {
        this.configService = configService;
        this.webClient = webClientBuilder.baseUrl(DISCORD_API).build();
    }

    public boolean isConfigured() {
        return configService.isDiscordOAuthConfigured();
    }

    public String buildLoginUrl() {
        return UriComponentsBuilder.fromUriString("https://discord.com/oauth2/authorize")
                .queryParam("client_id", configService.getDiscordClientId())
                .queryParam("response_type", "code")
                .queryParam("redirect_uri", configService.getDiscordRedirectUri())
                .queryParam("scope", "identify guilds")
                .build()
                .encode()
                .toUriString();
    }

    public DashboardSession authenticate(String code) {
        if (!isConfigured()) {
            throw new IllegalStateException("Discord OAuth ist nicht konfiguriert.");
        }

        DiscordTokenResponse token = exchangeCode(code);
        DiscordUserResponse user = fetchCurrentUser(token.accessToken());
        List<DiscordGuildAccess> guilds = fetchGuilds(token.accessToken());

        String displayName = user.globalName() == null || user.globalName().isBlank()
                ? user.username()
                : user.globalName();

        return new DashboardSession(
                user.id(),
                displayName,
                avatarUrl(user.id(), user.avatar()),
                token.accessToken(),
                guilds
        );
    }

    private DiscordTokenResponse exchangeCode(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", configService.getDiscordClientId());
        form.add("client_secret", configService.getDiscordClientSecret());
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", configService.getDiscordRedirectUri());

        return webClient.post()
                .uri("/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .bodyToMono(DiscordTokenResponse.class)
                .blockOptional()
                .filter(token -> token.accessToken() != null && !token.accessToken().isBlank())
                .orElseThrow(() -> new IllegalStateException("Discord hat kein gueltiges Access-Token geliefert."));
    }

    private DiscordUserResponse fetchCurrentUser(String accessToken) {
        return webClient.get()
                .uri("/users/@me")
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .bodyToMono(DiscordUserResponse.class)
                .blockOptional()
                .orElseThrow(() -> new IllegalStateException("Discord-Benutzerdaten konnten nicht geladen werden."));
    }

    private List<DiscordGuildAccess> fetchGuilds(String accessToken) {
        List<DiscordGuildResponse> guilds = webClient.get()
                .uri("/users/@me/guilds")
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<DiscordGuildResponse>>() {
                })
                .block();

        if (guilds == null) {
            return List.of();
        }

        return guilds.stream()
                .map(guild -> new DiscordGuildAccess(
                        guild.id(),
                        guild.name(),
                        guild.icon(),
                        guild.owner(),
                        parsePermissions(guild.permissions())
                ))
                .toList();
    }

    private long parsePermissions(String rawPermissions) {
        try {
            return Long.parseLong(rawPermissions);
        } catch (NumberFormatException ignored) {
            return Long.parseUnsignedLong(rawPermissions);
        }
    }

    private String avatarUrl(String userId, String avatarHash) {
        if (avatarHash == null || avatarHash.isBlank()) {
            return "https://cdn.discordapp.com/embed/avatars/0.png";
        }
        return "https://cdn.discordapp.com/avatars/" + userId + "/" + avatarHash + ".png?size=256";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DiscordTokenResponse(@JsonProperty("access_token") String accessToken) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DiscordUserResponse(
            String id,
            String username,
            @JsonProperty("global_name") String globalName,
            String avatar
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DiscordGuildResponse(
            String id,
            String name,
            String icon,
            boolean owner,
            String permissions
    ) {
    }
}
