package eckerlin.dev.web;

import eckerlin.dev.services.AppConfigService;
import eckerlin.dev.services.BotPresentationService;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.time.Year;

@ControllerAdvice
public class GlobalTemplateAttributes {

    private final AppConfigService configService;
    private final BotPresentationService botPresentationService;

    public GlobalTemplateAttributes(AppConfigService configService, BotPresentationService botPresentationService) {
        this.configService = configService;
        this.botPresentationService = botPresentationService;
    }

    @ModelAttribute("botBrand")
    public Object botBrand() {
        return botPresentationService.buildRuntimeView();
    }

    @ModelAttribute("maintenanceEnabled")
    public boolean maintenanceEnabled() {
        return configService.isMaintenanceEnabled();
    }

    @ModelAttribute("maintenanceMessage")
    public String maintenanceMessage() {
        return configService.getMaintenanceMessage();
    }

    @ModelAttribute("legalOwnerName")
    public String legalOwnerName() {
        return configService.getLegalOwnerName();
    }

    @ModelAttribute("legalEmail")
    public String legalEmail() {
        return configService.getLegalEmail();
    }

    @ModelAttribute("legalAddress")
    public String legalAddress() {
        return configService.getLegalAddress();
    }

    @ModelAttribute("currentYear")
    public int currentYear() {
        return Year.now().getValue();
    }

    /**
     * Versionsstempel fuer CSS- und JS-Verweise.
     *
     * <p>Vorher stand hier {@code System.currentTimeMillis()}. Damit bekam jeder
     * einzelne Seitenaufruf eine neue URL fuer {@code dashboard.css} und
     * {@code dashboard.js} - der Browser-Cache lief also permanent ins Leere und
     * das mehrere hundert Kilobyte grosse Dashboard-Skript wurde bei jedem
     * Klick neu geladen. Das war der groesste Einzelposten beim traegen
     * Seitenaufbau.
     *
     * <p>Jetzt ist der Wert fuer die gesamte Laufzeit konstant: der Browser
     * cached die Dateien, ein Neustart nach dem Deploy erzeugt aber
     * automatisch eine neue Version und erzwingt das Nachladen.
     */
    private static final long ASSET_VERSION = System.currentTimeMillis();

    @ModelAttribute("assetVersion")
    public long assetVersion() {
        return ASSET_VERSION;
    }
}
