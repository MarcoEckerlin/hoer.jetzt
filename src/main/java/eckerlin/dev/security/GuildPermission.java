package eckerlin.dev.security;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Rechte, die pro Discord-Server an Rollen vergeben werden koennen.
 *
 * <p>Vorher gab es nur eine einzige Schwelle: wer auf Discord
 * {@code ADMINISTRATOR} oder {@code MANAGE_GUILD} hatte, durfte im Webpanel und
 * bei den Commands alles. Das war fuer fremde Server zu grob - Musiksteuerung
 * und Modulkonfiguration sind sehr unterschiedliche Vertrauensstufen.
 *
 * <p>Die Schluessel werden so in der Datenbank abgelegt. Sie duerfen daher nicht
 * umbenannt werden, ohne eine Migration mitzuliefern.
 */
public enum GuildPermission {

    WEB_ACCESS("Webpanel öffnen", "Darf das Serverpanel für diesen Server überhaupt aufrufen."),
    MUSIC_CONTROL("Musik steuern", "Abspielen, Pause, Überspringen, Lautstärke, Webradio."),
    MUSIC_MANAGE("Warteschlange verwalten", "Titel entfernen, umsortieren, Warteschlange leeren."),
    MODULE_CONFIG("Module konfigurieren", "Willkommen, Verifizierung, Tickets, Logs und die übrigen Module ändern."),
    TICKET_STAFF("Ticket-Team", "Tickets einsehen, übernehmen und schließen."),
    LOG_VIEW("Logs einsehen", "Discord-Logs und Ereignisverlauf im Panel ansehen."),
    AI_USE("KI benutzen", "Den KI-Chat ansprechen — greift nur, wenn die Funktion freigeschaltet ist."),
    PERMISSION_MANAGE("Rollenrechte verwalten", "Diese Rechtematrix selbst ändern. Sparsam vergeben.");

    private final String label;
    private final String description;

    GuildPermission(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String key() {
        return name();
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public static Optional<GuildPermission> fromKey(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }

        String normalized = key.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(permission -> permission.name().equals(normalized))
                .findFirst();
    }

    public static Set<GuildPermission> all() {
        return new LinkedHashSet<>(Arrays.asList(values()));
    }
}
