package eckerlin.dev.services;

import eckerlin.dev.web.dto.ReactionRoleEntryView;
import eckerlin.dev.web.dto.ReactionRoleModuleView;
import eckerlin.dev.web.dto.ReactionRolePanelView;
import eckerlin.dev.web.dto.VerifyModuleView;
import eckerlin.dev.web.dto.WelcomeModuleView;
import eckerlin.dev.embeds.EmbedRenderer;
import eckerlin.dev.embeds.EmbedVorlageMapper;
import eckerlin.dev.embeds.EmbedVorlage;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionRemoveEvent;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.utils.FileUpload;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class CommunityModuleService {

    private static final String VERIFY_START_PREFIX = "verify:start:";
    private static final String VERIFY_SUBMIT_PREFIX = "verify:submit:";
    private static final String VERIFY_REFRESH_PREFIX = "verify:refresh:";
    private static final String VERIFY_MODAL_PREFIX = "verify:modal:";

    private final GuildModuleSettingsService settingsService;
    private final EmbedRenderer embedRenderer;
    private final Map<String, PendingVerification> verificationChallenges = new ConcurrentHashMap<>();

    public CommunityModuleService(GuildModuleSettingsService settingsService, EmbedRenderer embedRenderer) {
        this.settingsService = settingsService;
        this.embedRenderer = embedRenderer;
    }

    /**
     * Waehlt die Gestaltung fuer ein Modul aus.
     *
     * <p>Reihenfolge: eine Vorlage aus der Bibliothek gewinnt, danach die
     * eigene Gestaltung des Moduls, und wenn beides leer ist, {@code null} -
     * dann bleibt es beim bisherigen, fest verdrahteten Aussehen. Diese
     * letzte Stufe ist wichtig: ohne sie bekaeme jeder bestehende Server nach
     * dem Update ein leeres Embed serviert.
     */
    private EmbedVorlage waehleVorlage(Guild guild, EmbedVorlage eigene, String vorlageId) {
        if (vorlageId != null && !vorlageId.isBlank()) {
            EmbedVorlage ausBibliothek = settingsService.findEmbedVorlage(guild.getId(), vorlageId);
            if (ausBibliothek != null && !ausBibliothek.istLeer()) {
                return ausBibliothek;
            }
        }
        return eigene != null && !eigene.istLeer() ? eigene : null;
    }

    public WelcomeModuleView buildWelcomeView(Guild guild) {
        GuildModuleSettingsService.WelcomeState state = settingsService.getWelcomeState(guild.getId());
        String notice;
        if (!state.isEnabled()) {
            notice = "Join-Rolle und Willkommenstext sind deaktiviert.";
        } else if (state.getRoleIds().isEmpty() && state.getChannelId().isBlank()) {
            notice = "Aktiv, aber noch ohne Rolle und Channel. Konfiguriere mindestens eine Aktion.";
        } else {
            List<String> parts = new ArrayList<>();
            long validRoleCount = state.getRoleIds().stream()
                    .map(guild::getRoleById)
                    .filter(Objects::nonNull)
                    .count();
            if (validRoleCount > 0) {
                parts.add(validRoleCount + " Join-Rolle(n)");
            }
            if (!state.getChannelId().isBlank() && guild.getTextChannelById(state.getChannelId()) != null) {
                parts.add("Willkommensnachricht aktiv");
            }
            if (state.isSendImage()) {
                parts.add("Bildkarte aktiv");
            }
            notice = parts.isEmpty() ? "Konfiguration unvollständig." : String.join(" | ", parts);
        }
        return new WelcomeModuleView(
                state.isEnabled(),
                state.getRoleIds(),
                state.getChannelId(),
                state.getWelcomeText(),
                state.isSendImage(),
                state.getBackgroundImageUrl(),
                state.getAccentColor(),
                EmbedVorlageMapper.zurOberflaeche(state.getEmbed()),
                state.getEmbedVorlageId(),
                notice
        );
    }

    public ReactionRoleModuleView buildReactionRoleView(Guild guild) {
        GuildModuleSettingsService.ReactionRoleState state = settingsService.getReactionRoleState(guild.getId());
        String notice;
        if (!state.isEnabled()) {
            notice = "Reaction Roles sind deaktiviert.";
        } else if (state.getPanels().isEmpty()) {
            notice = "Lege mindestens ein Reaction-Role-Panel an.";
        } else {
            long panelCount = state.getPanels().stream()
                    .filter(panel -> !panel.getPublishChannelId().isBlank())
                    .count();
            long entryCount = state.getPanels().stream()
                    .mapToLong(panel -> panel.getEntries().size())
                    .sum();
            notice = panelCount + " Panel(s), " + entryCount + " Emoji-Zuordnung(en) aktiv.";
        }
        return new ReactionRoleModuleView(
                state.isEnabled(),
                notice,
                state.getPanels().stream()
                        .map(panel -> new ReactionRolePanelView(
                                panel.getId(),
                                panel.getPublishChannelId(),
                                panel.getTitle(),
                                panel.getDescription(),
                                panel.getImageUrl(),
                                panel.getThumbnailUrl(),
                                panel.getAccentColor(),
                                EmbedVorlageMapper.zurOberflaeche(panel.getEmbed()),
                                panel.getEmbedVorlageId(),
                                panel.getMessageId(),
                                panel.getEntries().stream()
                                        .map(entry -> new ReactionRoleEntryView(
                                                entry.getId(),
                                                entry.getEmoji(),
                                                entry.getRoleIds(),
                                                entry.getRemovedRoleIds(),
                                                entry.getLabel(),
                                                entry.getDescription()
                                        ))
                                        .toList()
                        ))
                        .toList()
        );
    }

    public VerifyModuleView buildVerifyView(Guild guild) {
        GuildModuleSettingsService.VerifyState state = settingsService.getVerifyState(guild.getId());
        String notice;
        if (!state.isEnabled()) {
            notice = "Verify ist deaktiviert.";
        } else if (state.getPublishChannelId().isBlank() || state.getVerifiedRoleIds().isEmpty()) {
            notice = "Waehle einen Ausgabe-Channel und mindestens eine Rolle fuer Verify.";
        } else {
            notice = "Verify-Panel aktiv. Nutzer erhalten nach Code-Eingabe " + state.getVerifiedRoleIds().size() + " Rolle(n).";
        }
        return new VerifyModuleView(
                state.isEnabled(),
                state.getPublishChannelId(),
                state.getVerifiedRoleIds(),
                state.getRemovedRoleIds(),
                state.getTitle(),
                state.getDescription(),
                state.getImageUrl(),
                state.getThumbnailUrl(),
                state.getAccentColor(),
                EmbedVorlageMapper.zurOberflaeche(state.getEmbed()),
                state.getEmbedVorlageId(),
                state.getMessageId(),
                notice
        );
    }

    public String syncReactionRoleMessage(Guild guild) {
        GuildModuleSettingsService.ReactionRoleState state = settingsService.getReactionRoleState(guild.getId());
        int synced = 0;

        for (GuildModuleSettingsService.ReactionRolePanel panel : state.getPanels()) {
            TextChannel channel = panel.getPublishChannelId().isBlank() ? null : guild.getTextChannelById(panel.getPublishChannelId());
            if (channel == null) {
                continue;
            }

            try {
                Message message = findOrCreatePanelMessage(channel, panel.getMessageId(), buildReactionRoleEmbed(guild, panel, state.isEnabled()));
                settingsService.updateReactionRoleMessage(guild.getId(), panel.getId(), message.getId());
                message.clearReactions().complete();
                if (state.isEnabled()) {
                    for (GuildModuleSettingsService.ReactionRoleEntry entry : panel.getEntries()) {
                        try {
                            message.addReaction(Emoji.fromFormatted(entry.getEmoji())).queue();
                        } catch (RuntimeException ignored) {
                        }
                    }
                }
                synced++;
            } catch (RuntimeException ignored) {
            }
        }

        if (synced == 0) {
            return "Keine gueltigen Reaction-Role-Panels gefunden.";
        }

        return state.isEnabled()
                ? synced + " Reaction-Role-Panel(s) wurden synchronisiert."
                : synced + " Reaction-Role-Panel(s) wurden als deaktiviert aktualisiert.";
    }

    public String syncVerifyMessage(Guild guild) {
        GuildModuleSettingsService.VerifyState state = settingsService.getVerifyState(guild.getId());
        TextChannel channel = state.getPublishChannelId().isBlank() ? null : guild.getTextChannelById(state.getPublishChannelId());
        if (channel == null) {
            return "Kein gültiger Channel für Verify gefunden.";
        }

        List<ActionRow> components = state.isEnabled()
                ? List.of(ActionRow.of(
                Button.success(VERIFY_START_PREFIX + guild.getId(), "Verify starten")
        ))
                : List.of();

        try {
            Message message = findOrCreatePanelMessage(channel, state.getMessageId(), buildVerifyPanelEmbed(guild, state), components);
            settingsService.updateVerifyMessage(guild.getId(), message.getId());
            return state.isEnabled()
                    ? "Verify-Panel wurde synchronisiert."
                    : "Verify-Panel wurde als deaktiviert aktualisiert.";
        } catch (RuntimeException exception) {
            return "Verify konnte nicht synchronisiert werden: " + exception.getMessage();
        }
    }

    public void handleMemberJoin(Member member) {
        if (member == null || member.getUser().isBot()) {
            return;
        }

        Guild guild = member.getGuild();
        GuildModuleSettingsService.WelcomeState state = settingsService.getWelcomeState(guild.getId());
        if (!state.isEnabled()) {
            return;
        }

        if (!state.getRoleIds().isEmpty() && guild.getSelfMember().hasPermission(Permission.MANAGE_ROLES)) {
            for (String roleId : state.getRoleIds()) {
                Role role = guild.getRoleById(roleId);
                if (role != null) {
                    guild.addRoleToMember(member, role).queue(success -> {
                    }, failure -> {
                    });
                }
            }
        }

        if (!state.getChannelId().isBlank()) {
            TextChannel channel = guild.getTextChannelById(state.getChannelId());
            if (channel != null) {
                sendWelcomeMessage(channel, member, state);
            }
        }
    }

    public void handleReactionRoleAdd(MessageReactionAddEvent event) {
        if (event == null || event.getGuild() == null || event.getUser() == null || event.getUser().isBot()) {
            return;
        }
        handleReactionRoleChange(event.getGuild(), event.getMessageId(), event.getEmoji().getAsReactionCode(), event.getMember(), true);
    }

    public void handleReactionRoleRemove(MessageReactionRemoveEvent event) {
        if (event == null || event.getGuild() == null || event.getUser() == null || event.getUser().isBot()) {
            return;
        }
        Member member = event.getMember();
        if (member == null) {
            member = event.getGuild().getMemberById(event.getUserId());
        }
        handleReactionRoleChange(event.getGuild(), event.getMessageId(), event.getEmoji().getAsReactionCode(), member, false);
    }

    public boolean isVerifyStartComponent(String componentId) {
        return componentId != null && componentId.startsWith(VERIFY_START_PREFIX);
    }

    public boolean isVerifySubmitComponent(String componentId) {
        return componentId != null && componentId.startsWith(VERIFY_SUBMIT_PREFIX);
    }

    public boolean isVerifyRefreshComponent(String componentId) {
        return componentId != null && componentId.startsWith(VERIFY_REFRESH_PREFIX);
    }

    public boolean isVerifyModal(String modalId) {
        return modalId != null && modalId.startsWith(VERIFY_MODAL_PREFIX);
    }

    public String extractVerifyGuildId(String id) {
        String[] parts = id == null ? new String[0] : id.split(":");
        return parts.length >= 3 ? parts[2] : "";
    }

    public VerificationPrompt beginVerification(Guild guild, Member member) {
        return createVerificationPrompt(guild, member, false);
    }

    public VerificationPrompt refreshVerification(Guild guild, Member member) {
        return createVerificationPrompt(guild, member, true);
    }

    public Modal buildVerifyModal(String guildId) {
        TextInput codeInput = TextInput.create("code", TextInputStyle.SHORT)
                .setPlaceholder("Code aus dem Bild eingeben")
                .setRequiredRange(4, 8)
                .setRequired(true)
                .build();
        return Modal.create(VERIFY_MODAL_PREFIX + guildId, "Verifizierung")
                .addComponents(Label.of("Sicherheitscode", codeInput))
                .build();
    }

    public VerificationResult submitVerification(Guild guild, Member member, ModalMapping codeMapping) {
        if (guild == null || member == null) {
            return new VerificationResult(false, "Verifizierung konnte nicht verarbeitet werden.");
        }

        GuildModuleSettingsService.VerifyState state = settingsService.getVerifyState(guild.getId());
        if (!state.isEnabled()) {
            return new VerificationResult(false, "Verify ist auf diesem Server aktuell deaktiviert.");
        }

        List<Role> verifiedRoles = state.getVerifiedRoleIds().stream()
                .map(guild::getRoleById)
                .filter(Objects::nonNull)
                .toList();
        if (verifiedRoles.isEmpty()) {
            return new VerificationResult(false, "Die konfigurierten Verify-Rollen existieren nicht mehr.");
        }

        boolean alreadyVerified = verifiedRoles.stream()
                .allMatch(role -> member.getRoles().stream().anyMatch(existing -> Objects.equals(existing.getId(), role.getId())));
        if (alreadyVerified) {
            return new VerificationResult(true, "Du bist bereits verifiziert.");
        }

        PendingVerification pending = verificationChallenges.get(challengeKey(guild.getId(), member.getId()));
        if (pending == null || pending.expiresAt() < System.currentTimeMillis()) {
            verificationChallenges.remove(challengeKey(guild.getId(), member.getId()));
            return new VerificationResult(false, "Dein Code ist abgelaufen. Bitte starte Verify erneut.");
        }

        String input = codeMapping == null ? "" : codeMapping.getAsString().trim().toUpperCase(Locale.ROOT);
        if (!pending.code().equalsIgnoreCase(input)) {
            return new VerificationResult(false, "Der eingegebene Code stimmt nicht.");
        }

        verificationChallenges.remove(challengeKey(guild.getId(), member.getId()));
        long newlyAssigned = verifiedRoles.stream()
                .filter(role -> member.getRoles().stream().noneMatch(existing -> Objects.equals(existing.getId(), role.getId())))
                .peek(role -> guild.addRoleToMember(member, role).queue(success -> { }, failure -> { }))
                .count();

        // Und wegnehmen, was nach der Verifizierung nicht mehr gilt - meist
        // eine "Ungeprueft"-Rolle, die alles verbirgt. Ohne das blieb sie
        // kleben, und der Server hatte keinen Weg, sie wieder loszuwerden.
        for (String roleId : state.getRemovedRoleIds()) {
            Role role = guild.getRoleById(roleId);
            if (role != null && member.getRoles().stream()
                    .anyMatch(existing -> Objects.equals(existing.getId(), role.getId()))) {
                guild.removeRoleFromMember(member, role).queue(success -> { }, failure -> { });
            }
        }

        if (newlyAssigned <= 1) {
            return new VerificationResult(true, "Verifizierung erfolgreich. Deine Rolle wurde vergeben.");
        }
        return new VerificationResult(true, "Verifizierung erfolgreich. Deine Rollen wurden vergeben.");
    }

    private Message findOrCreatePanelMessage(TextChannel channel, String messageId, net.dv8tion.jda.api.entities.MessageEmbed embed) {
        return findOrCreatePanelMessage(channel, messageId, embed, List.of());
    }

    private Message findOrCreatePanelMessage(
            TextChannel channel,
            String messageId,
            net.dv8tion.jda.api.entities.MessageEmbed embed,
            List<ActionRow> components
    ) {
        Message message = null;
        if (messageId != null && !messageId.isBlank()) {
            try {
                message = channel.retrieveMessageById(messageId).complete();
            } catch (RuntimeException ignored) {
                message = null;
            }
        }

        if (message == null) {
            return channel.sendMessageEmbeds(embed)
                    .setComponents(components)
                    .complete();
        }

        message.editMessageEmbeds(embed)
                .setComponents(components)
                .complete();
        return message;
    }

    private net.dv8tion.jda.api.entities.MessageEmbed buildReactionRoleEmbed(
            Guild guild,
            GuildModuleSettingsService.ReactionRolePanel panel,
            boolean enabled
    ) {
        EmbedVorlage vorlage = waehleVorlage(guild, panel.getEmbed(), panel.getEmbedVorlageId());
        if (vorlage != null && enabled) {
            // {zuordnungen} ist der Platzhalter fuer die Liste aus Emoji und
            // Rolle - ohne ihn muesste man sie von Hand pflegen und bei jeder
            // Aenderung nachziehen.
            Map<String, String> platzhalter = embedRenderer.standardPlatzhalter(guild, null);
            platzhalter.put("{zuordnungen}", buildReactionRoleDescription(panel));
            return embedRenderer.baueEmbeds(vorlage, platzhalter).get(0);
        }

        String description = buildReactionRoleDescription(panel);
        if (!enabled) {
            description = description.isBlank()
                    ? "Dieses Rollen-Panel ist gerade nicht verfuegbar."
                    : description + "\n\nDieses Rollen-Panel ist gerade nicht verfuegbar.";
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(panel.getTitle().isBlank() ? "Reaction Roles" : panel.getTitle())
                .setDescription(description)
                .setColor(parseColor(panel.getAccentColor()))
                .setTimestamp(Instant.now())
                .setFooter(guild.getName());
        if (!panel.getThumbnailUrl().isBlank()) {
            embed.setThumbnail(panel.getThumbnailUrl());
        }
        if (!panel.getImageUrl().isBlank()) {
            embed.setImage(panel.getImageUrl());
        }
        return embed.build();
    }

    private String buildReactionRoleDescription(GuildModuleSettingsService.ReactionRolePanel panel) {
        StringBuilder builder = new StringBuilder();
        if (!panel.getDescription().isBlank()) {
            builder.append(panel.getDescription().trim()).append("\n\n");
        }
        for (GuildModuleSettingsService.ReactionRoleEntry entry : panel.getEntries()) {
            builder.append(entry.getEmoji()).append("  **")
                    .append(entry.getLabel().isBlank() ? "Rolle" : entry.getLabel())
                    .append("**");
            if (!entry.getDescription().isBlank()) {
                builder.append(" - ").append(entry.getDescription());
            }
            builder.append("\n");
        }
        return builder.toString().trim();
    }

    private net.dv8tion.jda.api.entities.MessageEmbed buildVerifyPanelEmbed(Guild guild, GuildModuleSettingsService.VerifyState state) {
        EmbedVorlage vorlage = waehleVorlage(guild, state.getEmbed(), state.getEmbedVorlageId());
        if (vorlage != null) {
            return embedRenderer.baueEmbeds(vorlage,
                    embedRenderer.standardPlatzhalter(guild, null)).get(0);
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(state.getTitle().isBlank() ? "Server-Verifizierung" : state.getTitle())
                .setDescription(state.getDescription().isBlank()
                        ? "Klicke unten auf den Button, löse den Code und hole dir deine Verify-Rolle."
                        : state.getDescription())
                .setColor(parseColor(state.getAccentColor()))
                .setTimestamp(Instant.now())
                .setFooter(guild.getName());
        if (!state.getThumbnailUrl().isBlank()) {
            embed.setThumbnail(state.getThumbnailUrl());
        }
        if (!state.getImageUrl().isBlank()) {
            embed.setImage(state.getImageUrl());
        }
        return embed.build();
    }

    private void sendWelcomeMessage(TextChannel channel, Member member, GuildModuleSettingsService.WelcomeState state) {
        Guild guild = channel.getGuild();
        EmbedVorlage vorlage = waehleVorlage(guild, state.getEmbed(), state.getEmbedVorlageId());
        if (vorlage != null) {
            // Die Willkommenskarte bleibt: sie haengt an einem eigenen Schalter
            // und wird als Datei angehaengt, nicht als Bild-Adresse.
            byte[] karte = state.isSendImage() ? createWelcomeImage(member, state) : null;
            var nachricht = channel.sendMessage(
                    embedRenderer.baue(vorlage, embedRenderer.standardPlatzhalter(guild, member)));
            if (karte != null) {
                nachricht = nachricht.addFiles(FileUpload.fromData(karte, "welcome-card.png"));
            }
            nachricht.queue(success -> { }, failure -> { });
            return;
        }

        String content = renderWelcomeText(state.getWelcomeText(), member);
        if (!state.isSendImage()) {
            channel.sendMessage(content).queue(success -> {
            }, failure -> {
            });
            return;
        }

        byte[] image = createWelcomeImage(member, state);
        if (image == null) {
            channel.sendMessage(content).queue(success -> {
            }, failure -> {
            });
            return;
        }

        channel.sendMessage(content)
                .addFiles(FileUpload.fromData(image, "welcome-card.png"))
                .queue(success -> {
                }, failure -> {
                });
    }

    private void handleReactionRoleChange(Guild guild, String messageId, String reactionCode, Member member, boolean add) {
        if (guild == null || member == null) {
            return;
        }

        GuildModuleSettingsService.ReactionRoleState state = settingsService.getReactionRoleState(guild.getId());
        if (!state.isEnabled()) {
            return;
        }

        GuildModuleSettingsService.ReactionRolePanel panel = state.getPanels().stream()
                .filter(candidate -> !candidate.getMessageId().isBlank() && Objects.equals(candidate.getMessageId(), messageId))
                .findFirst()
                .orElse(null);
        if (panel == null) {
            return;
        }

        GuildModuleSettingsService.ReactionRoleEntry entry = panel.getEntries().stream()
                .filter(candidate -> normalizeReactionEmoji(candidate.getEmoji()).equals(normalizeReactionEmoji(reactionCode)))
                .findFirst()
                .orElse(null);
        if (entry == null || !guild.getSelfMember().hasPermission(Permission.MANAGE_ROLES)) {
            return;
        }

        for (String roleId : entry.getRoleIds()) {
            Role role = guild.getRoleById(roleId);
            if (role == null) {
                continue;
            }
            if (add) {
                guild.addRoleToMember(member, role).queue(success -> { }, failure -> { });
            } else {
                guild.removeRoleFromMember(member, role).queue(success -> { }, failure -> { });
            }
        }

        // Die Gegenrichtung: Rollen, die diese Reaktion abnimmt. Beim
        // Zuruecknehmen der Reaktion kommen sie zurueck - sonst waere die
        // Aktion einseitig und der Nutzer haette keinen Weg zurueck.
        for (String roleId : entry.getRemovedRoleIds()) {
            Role role = guild.getRoleById(roleId);
            if (role == null) {
                continue;
            }
            if (add) {
                guild.removeRoleFromMember(member, role).queue(success -> { }, failure -> { });
            } else {
                guild.addRoleToMember(member, role).queue(success -> { }, failure -> { });
            }
        }
    }

    private VerificationPrompt createVerificationPrompt(Guild guild, Member member, boolean refresh) {
        if (guild == null || member == null) {
            return VerificationPrompt.error("Verify konnte nicht gestartet werden.");
        }

        GuildModuleSettingsService.VerifyState state = settingsService.getVerifyState(guild.getId());
        if (!state.isEnabled()) {
            return VerificationPrompt.error("Verify ist auf diesem Server aktuell deaktiviert.");
        }

        List<Role> verifiedRoles = state.getVerifiedRoleIds().stream()
                .map(guild::getRoleById)
                .filter(Objects::nonNull)
                .toList();
        if (verifiedRoles.isEmpty()) {
            return VerificationPrompt.error("Die konfigurierten Verify-Rollen existieren nicht mehr.");
        }

        boolean alreadyVerified = verifiedRoles.stream()
                .allMatch(role -> member.getRoles().stream().anyMatch(existing -> Objects.equals(existing.getId(), role.getId())));
        if (alreadyVerified) {
            return VerificationPrompt.success("Du bist bereits verifiziert.", null, null, List.of());
        }

        String code = generateCode();
        verificationChallenges.put(challengeKey(guild.getId(), member.getId()), new PendingVerification(code, System.currentTimeMillis() + 5 * 60_000L));

        byte[] image = createCaptchaImage(code, state.getAccentColor());
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(refresh ? "Neuer Verify-Code" : "Verify starten")
                .setDescription("Loese den Code aus dem Bild und klicke danach auf **Code eingeben**.")
                .setColor(parseColor(state.getAccentColor()))
                .setTimestamp(Instant.now());
        if (!state.getThumbnailUrl().isBlank()) {
            embed.setThumbnail(state.getThumbnailUrl());
        }
        if (image != null) {
            embed.setImage("attachment://verify-code.png");
        }
        List<ActionRow> rows = List.of(ActionRow.of(
                Button.primary(VERIFY_SUBMIT_PREFIX + guild.getId(), "Code eingeben"),
                Button.secondary(VERIFY_REFRESH_PREFIX + guild.getId(), "Neuen Code")
        ));
        return VerificationPrompt.success("Verify-Code erstellt.", embed.build(), image, rows);
    }

    private String renderWelcomeText(String template, Member member) {
        String text = template == null || template.isBlank()
                ? "Willkommen {user} auf **{guild}**."
                : template;
        return text
                .replace("{user}", member.getAsMention())
                .replace("{mention}", member.getAsMention())
                .replace("{guild}", member.getGuild().getName())
                .replace("{memberCount}", String.valueOf(member.getGuild().getMemberCount()));
    }

    private byte[] createWelcomeImage(Member member, GuildModuleSettingsService.WelcomeState state) {
        try {
            BufferedImage canvas = new BufferedImage(1100, 360, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = canvas.createGraphics();
            prepareGraphics(graphics);

            BufferedImage background = readImage(state.getBackgroundImageUrl());
            if (background != null) {
                graphics.drawImage(background, 0, 0, 1100, 360, null);
            } else {
                graphics.setPaint(new GradientPaint(0, 0, parseColor(state.getAccentColor()), 1100, 360, new Color(12, 15, 28)));
                graphics.fillRoundRect(0, 0, 1100, 360, 38, 38);
            }
            graphics.setColor(new Color(6, 10, 20, 175));
            graphics.fill(new RoundRectangle2D.Float(26, 26, 1048, 308, 34, 34));

            BufferedImage avatar = readImage(member.getUser().getEffectiveAvatarUrl());
            if (avatar != null) {
                graphics.setClip(new Ellipse2D.Float(70, 72, 180, 180));
                graphics.drawImage(avatar, 70, 72, 180, 180, null);
                graphics.setClip(null);
            }
            graphics.setStroke(new BasicStroke(5f));
            graphics.setColor(parseColor(state.getAccentColor()));
            graphics.draw(new Ellipse2D.Float(70, 72, 180, 180));

            graphics.setColor(Color.WHITE);
            graphics.setFont(new Font("SansSerif", Font.BOLD, 36));
            graphics.drawString("Willkommen auf " + member.getGuild().getName(), 300, 128);
            graphics.setFont(new Font("SansSerif", Font.BOLD, 54));
            graphics.drawString(member.getEffectiveName(), 300, 196);
            graphics.setFont(new Font("SansSerif", Font.PLAIN, 26));
            graphics.setColor(new Color(220, 228, 255));
            graphics.drawString("Mitglied #" + member.getGuild().getMemberCount(), 302, 248);

            graphics.setColor(new Color(255, 255, 255, 85));
            for (int index = 0; index < 18; index++) {
                int x = ThreadLocalRandom.current().nextInt(40, 1040);
                int y = ThreadLocalRandom.current().nextInt(30, 320);
                int size = ThreadLocalRandom.current().nextInt(6, 22);
                graphics.fillOval(x, y, size, size);
            }

            graphics.dispose();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(canvas, "png", output);
            return output.toByteArray();
        } catch (Exception ignored) {
            return null;
        }
    }

    private byte[] createCaptchaImage(String code, String accentColor) {
        try {
            BufferedImage canvas = new BufferedImage(560, 220, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = canvas.createGraphics();
            prepareGraphics(graphics);
            graphics.setPaint(new GradientPaint(0, 0, parseColor(accentColor), 560, 220, new Color(16, 21, 38)));
            graphics.fillRoundRect(0, 0, 560, 220, 32, 32);
            graphics.setColor(new Color(255, 255, 255, 18));
            for (int index = 0; index < 60; index++) {
                int x = ThreadLocalRandom.current().nextInt(0, 560);
                int y = ThreadLocalRandom.current().nextInt(0, 220);
                int radius = ThreadLocalRandom.current().nextInt(6, 18);
                graphics.fillOval(x, y, radius, radius);
            }
            graphics.setStroke(new BasicStroke(3f));
            for (int index = 0; index < 10; index++) {
                graphics.setColor(new Color(255, 255, 255, 85));
                graphics.drawLine(
                        ThreadLocalRandom.current().nextInt(0, 560),
                        ThreadLocalRandom.current().nextInt(0, 220),
                        ThreadLocalRandom.current().nextInt(0, 560),
                        ThreadLocalRandom.current().nextInt(0, 220)
                );
            }

            graphics.setFont(new Font("Monospaced", Font.BOLD, 92));
            for (int index = 0; index < code.length(); index++) {
                graphics.setColor(index % 2 == 0 ? Color.WHITE : new Color(210, 223, 255));
                int x = 70 + (index * 72);
                int y = 132 + ThreadLocalRandom.current().nextInt(-18, 22);
                graphics.rotate(Math.toRadians(ThreadLocalRandom.current().nextInt(-16, 17)), x, y);
                graphics.drawString(String.valueOf(code.charAt(index)), x, y);
                graphics.rotate(Math.toRadians(-ThreadLocalRandom.current().nextInt(-16, 17)), x, y);
            }

            graphics.setFont(new Font("SansSerif", Font.PLAIN, 24));
            graphics.setColor(new Color(230, 236, 255));
            graphics.drawString("Gib diesen Code im nächsten Schritt ein.", 58, 194);
            graphics.dispose();

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(canvas, "png", output);
            return output.toByteArray();
        } catch (Exception ignored) {
            return null;
        }
    }

    private BufferedImage readImage(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try (InputStream inputStream = URI.create(url).toURL().openStream()) {
            return ImageIO.read(inputStream);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void prepareGraphics(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    private String generateCode() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < 6; index++) {
            builder.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        }
        return builder.toString();
    }

    private Color parseColor(String value) {
        try {
            return Color.decode(value == null || value.isBlank() ? "#78D1FF" : value);
        } catch (RuntimeException ignored) {
            return new Color(0x78D1FF);
        }
    }

    private String normalizeReactionEmoji(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            return Emoji.fromFormatted(value.trim()).getAsReactionCode();
        } catch (RuntimeException ignored) {
            return value.trim();
        }
    }

    private String challengeKey(String guildId, String userId) {
        return guildId + ":" + userId;
    }

    private record PendingVerification(String code, long expiresAt) {
    }

    public record VerificationPrompt(
            boolean success,
            String message,
            net.dv8tion.jda.api.entities.MessageEmbed embed,
            byte[] image,
            List<ActionRow> components
    ) {
        public static VerificationPrompt success(
                String message,
                net.dv8tion.jda.api.entities.MessageEmbed embed,
                byte[] image,
                List<ActionRow> components
        ) {
            return new VerificationPrompt(true, message, embed, image, components == null ? List.of() : components);
        }

        public static VerificationPrompt error(String message) {
            return new VerificationPrompt(false, message, null, null, List.of());
        }
    }

    public record VerificationResult(boolean success, String message) {
    }
}

