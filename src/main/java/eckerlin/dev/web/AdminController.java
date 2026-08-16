package eckerlin.dev.web;

import eckerlin.dev.services.AdminAccessService;
import eckerlin.dev.services.AppConfigService;
import eckerlin.dev.services.BotPresentationService;
import eckerlin.dev.web.dto.DashboardSession;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminController {

    private final AdminAccessService adminAccessService;
    private final AppConfigService configService;
    private final BotPresentationService botPresentationService;

    public AdminController(
            AdminAccessService adminAccessService,
            AppConfigService configService,
            BotPresentationService botPresentationService
    ) {
        this.adminAccessService = adminAccessService;
        this.configService = configService;
        this.botPresentationService = botPresentationService;
    }

    @GetMapping({"/admin", "/admin/"})
    public String admin(Model model, HttpSession session) {
        DashboardSession user = getSession(session);
        if (user == null) {
            return "redirect:/";
        }

        adminAccessService.requireAdmin(user);
        model.addAttribute("user", user);
        model.addAttribute("baseUrl", configService.getWebBaseUrl());
        model.addAttribute("currentDeploymentKey", configService.getCurrentDeploymentKey());
        model.addAttribute("currentDeploymentDisplayName", configService.getCurrentDeploymentDisplayName());
        model.addAttribute("botBrand", botPresentationService.buildRuntimeView());
        model.addAttribute("maintenanceEnabled", configService.isMaintenanceEnabled());
        model.addAttribute("maintenanceMessage", configService.getMaintenanceMessage());
        return "admin";
    }

    private DashboardSession getSession(HttpSession session) {
        Object user = session.getAttribute(DashboardController.SESSION_USER);
        if (user instanceof DashboardSession dashboardSession) {
            return dashboardSession;
        }
        return null;
    }
}
