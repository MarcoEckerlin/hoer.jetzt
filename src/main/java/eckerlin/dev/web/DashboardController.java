package eckerlin.dev.web;

import eckerlin.dev.utils.Alert;
import eckerlin.dev.services.AppConfigService;
import eckerlin.dev.services.GuildModuleSettingsService;
import eckerlin.dev.services.AdminAccessService;
import eckerlin.dev.services.BotPresentationService;
import eckerlin.dev.services.DashboardAccessService;
import eckerlin.dev.services.DiscordOAuthService;
import eckerlin.dev.web.dto.DashboardSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.net.URI;
import java.time.Duration;
import java.time.Year;

@Controller
public class DashboardController {

    public static final String SESSION_USER = "discord.dashboard.user";

    private final DiscordOAuthService discordOAuthService;
    private final DashboardAccessService dashboardAccessService;
    private final AppConfigService configService;
    private final AdminAccessService adminAccessService;
    private final BotPresentationService botPresentationService;
    private final GuildModuleSettingsService moduleSettingsService;

    public DashboardController(
            DiscordOAuthService discordOAuthService,
            DashboardAccessService dashboardAccessService,
            AppConfigService configService,
            AdminAccessService adminAccessService,
            BotPresentationService botPresentationService,
            GuildModuleSettingsService moduleSettingsService
    ) {
        this.discordOAuthService = discordOAuthService;
        this.dashboardAccessService = dashboardAccessService;
        this.configService = configService;
        this.adminAccessService = adminAccessService;
        this.botPresentationService = botPresentationService;
        this.moduleSettingsService = moduleSettingsService;
    }

    @GetMapping("/")
    public String index(Model model, HttpSession session, @RequestParam(required = false) String error) {
        DashboardSession user = getSession(session);
        if (user != null) {
            return "redirect:/dashboard";
        }

        model.addAttribute("loginConfigured", discordOAuthService.isConfigured());
        model.addAttribute("loginUrl", discordOAuthService.isConfigured() ? "/login" : "#");
        model.addAttribute("errorMessage", resolveError(error));
        model.addAttribute("baseUrl", configService.getWebBaseUrl());

        // Ohne Client-ID gibt es keinen Einladungslink - dann wird der Knopf
        // gar nicht erst angeboten, statt ins Leere zu fuehren.
        String inviteUrl = configService.getNoGuildInviteUrl();
        model.addAttribute("inviteUrl", inviteUrl);
        model.addAttribute("inviteConfigured", inviteUrl != null && !inviteUrl.isBlank());

        addCommonAttributes(model);
        return "index";
    }

    @GetMapping("/login")
    public String login() {
        if (!discordOAuthService.isConfigured()) {
            return "redirect:/?error=oauth_not_configured";
        }
        try {
            return "redirect:" + discordOAuthService.buildLoginUrl();
        } catch (RuntimeException exception) {
            return "redirect:/?error=oauth_url_invalid";
        }
    }

    @GetMapping("/auth/discord/callback")
    public String callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error,
            HttpSession session
    ) {
        if (error != null) {
            Alert.send("WARN", "LOGIN", "Discord hat die Anmeldung abgelehnt: " + error);
            return "redirect:/?error=" + error;
        }

        if (code == null || code.isBlank()) {
            Alert.send("WARN", "LOGIN", "Rueckleitung ohne Code - meist eine abgebrochene Anmeldung.");
            return "redirect:/?error=missing_code";
        }

        try {
            DashboardSession dashboardSession = discordOAuthService.authenticate(code);
            session.setAttribute(SESSION_USER, dashboardSession);
            return "redirect:/dashboard";
        } catch (RuntimeException exception) {
            // Frueher verschwand der Grund hier spurlos und der Nutzer stand
            // wieder auf der Startseite. Der Tausch von Code gegen Token
            // scheitert praktisch immer an einem von drei Dingen: falsches
            // Client-Secret, abweichende Redirect-URI oder der Container kommt
            // nicht an discord.com. Alle drei stehen jetzt im Log.
            Alert.send("WARN", "LOGIN", "Anmeldung fehlgeschlagen beim Tausch des Codes: "
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage()
                    + " | erwartete Redirect-URI: " + configService.getDiscordRedirectUri());
            return "redirect:/?error=oauth_auth_failed";
        }
    }

    @GetMapping({"/dashboard", "/dashboard/"})
    public String dashboard(Model model, HttpSession session) {
        DashboardSession user = getSession(session);
        if (user == null) {
            return "redirect:/";
        }

        boolean adminAllowed = adminAccessService.isAdmin(user);
        if (configService.isMaintenanceEnabled() && !adminAllowed) {
            return "redirect:/?error=maintenance";
        }

        var guilds = dashboardAccessService.getManageableGuilds(user);
        if (guilds.isEmpty()) {
            String inviteUrl = configService.getNoGuildInviteUrl();
            if (!inviteUrl.isBlank()) {
                return "redirect:" + inviteUrl;
            }
            return "redirect:/";
        }

        model.addAttribute("user", user);
        model.addAttribute("guilds", guilds);
        model.addAttribute("baseUrl", configService.getWebBaseUrl());
        model.addAttribute("adminAllowed", adminAllowed);
        addCommonAttributes(model);
        return "dashboard";
    }

    @GetMapping("/impressum")
    public String impressum(Model model) {
        addCommonAttributes(model);
        return "impressum";
    }

    @GetMapping("/datenschutz")
    public String datenschutz(Model model) {
        addCommonAttributes(model);
        return "datenschutz";
    }

    /**
     * Der oeffentliche Kurzlink: hoer.jetzt/invite/&lt;name&gt;
     *
     * <p>Bewusst ohne jede Anmeldung - der Link soll in einer Signatur, auf
     * einem Flyer oder in einem fremden Chat stehen koennen. Zeigt er ins
     * Leere, landet man auf der Startseite mit einer Erklaerung statt auf einer
     * nackten Fehlerseite.
     */
    @GetMapping("/invite/{slug}")
    public String invite(@PathVariable("slug") String slug, Model model) {
        String guildId = moduleSettingsService.findGuildIdByInviteSlug(slug);
        if (guildId == null) {
            addCommonAttributes(model);
            model.addAttribute("errorMessage",
                    "Diesen Einladungslink gibt es nicht (mehr).");
            return "index";
        }

        String ziel = moduleSettingsService.getInviteState(guildId).getTargetUrl();
        if (ziel == null || ziel.isBlank()) {
            addCommonAttributes(model);
            model.addAttribute("errorMessage",
                    "Dieser Einladungslink hat kein Ziel hinterlegt.");
            return "index";
        }

        moduleSettingsService.zaehleInviteKlick(guildId);
        return "redirect:" + ziel;
    }

    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/";
    }

    @GetMapping("/cookies/accept")
    public String acceptCookies(HttpServletRequest request, HttpServletResponse response) {
        writeCookieNotice(response, "accepted", Duration.ofDays(365));
        return "redirect:" + resolveCookieRedirectTarget(request, false);
    }

    @GetMapping("/cookies/reject")
    public String rejectCookies(HttpServletRequest request, HttpServletResponse response, HttpSession session) {
        writeCookieNotice(response, "rejected", Duration.ofDays(1));
        String redirectTarget = resolveCookieRedirectTarget(request, true);
        if (isProtectedPath(redirectTarget)) {
            session.invalidate();
            return "redirect:/";
        }
        return "redirect:" + redirectTarget;
    }

    private DashboardSession getSession(HttpSession session) {
        Object user = session.getAttribute(SESSION_USER);
        if (user instanceof DashboardSession dashboardSession) {
            return dashboardSession;
        }
        return null;
    }

    private String resolveError(String error) {
        if (error == null || error.isBlank()) {
            return null;
        }

        return switch (error) {
            case "oauth_not_configured" -> "Discord OAuth ist noch nicht vollstaendig konfiguriert.";
            case "oauth_url_invalid" -> "Die Login-URL konnte nicht erstellt werden. Bitte pruefe Client-ID und Redirect-URI.";
            case "missing_code" -> "Discord hat keinen Login-Code zurueckgegeben.";
            case "access_denied" -> "Der Discord-Login wurde abgebrochen.";
            case "oauth_auth_failed" -> "Die Discord-Authentifizierung ist fehlgeschlagen. Bitte pruefe Secret, Redirect-URI und Bot-Rechte.";
            case "maintenance" -> configService.getMaintenanceMessage();
            default -> "Login konnte nicht abgeschlossen werden: " + error;
        };
    }

    private void addCommonAttributes(Model model) {
        model.addAttribute("botBrand", botPresentationService.buildRuntimeView());
        model.addAttribute("maintenanceEnabled", configService.isMaintenanceEnabled());
        model.addAttribute("maintenanceMessage", configService.getMaintenanceMessage());
        model.addAttribute("legalOwnerName", configService.getLegalOwnerName());
        model.addAttribute("legalEmail", configService.getLegalEmail());
        model.addAttribute("legalAddress", configService.getLegalAddress());
        model.addAttribute("currentYear", Year.now().getValue());
    }

    private void writeCookieNotice(HttpServletResponse response, String value, Duration duration) {
        response.addHeader("Set-Cookie", ResponseCookie.from("discordbot_cookie_notice", value)
                .httpOnly(false)
                .path("/")
                .sameSite("Lax")
                .maxAge(duration)
                .build()
                .toString());
    }

    private String resolveCookieRedirectTarget(HttpServletRequest request, boolean rejecting) {
        String referer = request.getHeader("Referer");
        if (referer == null || referer.isBlank()) {
            return "/";
        }

        try {
            URI uri = URI.create(referer);
            String path = uri.getPath();
            if (path == null || path.isBlank() || !path.startsWith("/")) {
                return "/";
            }

            if (rejecting && isProtectedPath(path)) {
                return "/";
            }

            String query = uri.getQuery();
            return query == null || query.isBlank() ? path : path + "?" + query;
        } catch (RuntimeException ignored) {
            return "/";
        }
    }

    private boolean isProtectedPath(String path) {
        return "/dashboard".equals(path)
                || path.startsWith("/dashboard/")
                || "/admin".equals(path)
                || path.startsWith("/admin/");
    }
}
