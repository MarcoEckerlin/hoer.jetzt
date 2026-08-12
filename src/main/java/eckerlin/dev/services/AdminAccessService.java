package eckerlin.dev.services;

import eckerlin.dev.security.AccessGuard;
import eckerlin.dev.security.BotAdminRole;
import eckerlin.dev.web.dto.DashboardSession;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Zugang zum Adminbereich.
 *
 * <p>Frueher hing das an genau einer Bedingung: der angemeldete Nutzer musste
 * der Discord-Botinhaber sein. Damit war der Bereich fuer genau eine Person
 * nutzbar und liess sich nicht delegieren.
 *
 * <p>Die Pruefung liegt jetzt im {@link AccessGuard}; diese Klasse bleibt als
 * schmale Fassade bestehen, damit die bisherigen Aufrufstellen unveraendert
 * weiterlaufen.
 */
@Service
public class AdminAccessService {

    private final AccessGuard accessGuard;

    public AdminAccessService(AccessGuard accessGuard) {
        this.accessGuard = accessGuard;
    }

    /** Irgendeine Stufe der Bot-Verwaltung — reicht fuer lesenden Zugriff. */
    public boolean isAdmin(DashboardSession session) {
        return accessGuard.isBotAdmin(session);
    }

    public Optional<BotAdminRole> roleOf(DashboardSession session) {
        return accessGuard.botAdminRole(session);
    }

    /** Lesender Zugriff auf den Adminbereich. */
    public BotAdminRole requireAdmin(DashboardSession session) {
        return accessGuard.requireBotAdmin(session, BotAdminRole.SUPPORT);
    }

    /** Schreibende Aktionen: Einstellungen, Freischaltungen, Serververwaltung. */
    public BotAdminRole requireWriteAdmin(DashboardSession session) {
        return accessGuard.requireBotAdmin(session, BotAdminRole.ADMIN);
    }

    /** Verwaltung anderer Bot-Admins — nur der Betreiber selbst. */
    public BotAdminRole requireOwner(DashboardSession session) {
        return accessGuard.requireBotAdmin(session, BotAdminRole.OWNER);
    }
}
