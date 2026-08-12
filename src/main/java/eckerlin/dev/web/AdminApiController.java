package eckerlin.dev.web;

import eckerlin.dev.services.AdminAccessService;
import eckerlin.dev.services.AdminConfigurationService;
import eckerlin.dev.services.BotPresenceService;
import eckerlin.dev.services.BotPresentationService;
import eckerlin.dev.services.VmControlService;
import eckerlin.dev.web.dto.ActionResponse;
import eckerlin.dev.web.dto.AdminConfigurationView;
import eckerlin.dev.web.dto.BotRuntimeView;
import eckerlin.dev.web.dto.AdminSettingsRequest;
import eckerlin.dev.web.dto.DashboardSession;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.sql.SQLException;

@RestController
@RequestMapping("/api/admin")
public class AdminApiController {

    private final AdminAccessService adminAccessService;
    private final AdminConfigurationService adminConfigurationService;
    private final BotPresenceService botPresenceService;
    private final BotPresentationService botPresentationService;
    private final VmControlService vmControlService;

    public AdminApiController(
            AdminAccessService adminAccessService,
            AdminConfigurationService adminConfigurationService,
            BotPresenceService botPresenceService,
            BotPresentationService botPresentationService,
            VmControlService vmControlService
    ) {
        this.adminAccessService = adminAccessService;
        this.adminConfigurationService = adminConfigurationService;
        this.botPresenceService = botPresenceService;
        this.botPresentationService = botPresentationService;
        this.vmControlService = vmControlService;
    }

    @GetMapping("/config")
    public AdminConfigurationView config(HttpSession session) {
        adminAccessService.requireAdmin(requireSession(session));
        return adminConfigurationService.buildView();
    }

    @GetMapping("/runtime")
    public BotRuntimeView runtime(HttpSession session) {
        adminAccessService.requireAdmin(requireSession(session));
        return botPresentationService.buildRuntimeView();
    }

    @PostMapping("/config")
    public ActionResponse saveConfig(@RequestBody AdminSettingsRequest request, HttpSession session) {
        adminAccessService.requireAdmin(requireSession(session));
        try {
            adminConfigurationService.save(request);
            botPresenceService.refreshNow();
        } catch (SQLException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Admin-Einstellungen konnten nicht gespeichert werden.");
        }
        return new ActionResponse(true, "Admin-Einstellungen wurden gespeichert.");
    }

    @PostMapping("/actions/restart-vm")
    public ActionResponse restartVm(HttpSession session) {
        adminAccessService.requireAdmin(requireSession(session));
        vmControlService.scheduleVmRestart();
        return new ActionResponse(true, "Der VM-Neustart wurde angefordert. Die Verbindung bricht in wenigen Sekunden ab.");
    }

    private DashboardSession requireSession(HttpSession session) {
        Object user = session.getAttribute(DashboardController.SESSION_USER);
        if (user instanceof DashboardSession dashboardSession) {
            return dashboardSession;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Bitte zuerst ueber Discord anmelden.");
    }
}
