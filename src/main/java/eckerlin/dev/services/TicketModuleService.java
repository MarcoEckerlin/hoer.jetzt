package eckerlin.dev.services;

import eckerlin.dev.web.dto.TicketModuleView;
import eckerlin.dev.web.dto.TicketOptionView;
import eckerlin.dev.web.dto.TicketPanelView;
import eckerlin.dev.web.dto.TicketTranscriptView;
import eckerlin.dev.embeds.EmbedRenderer;
import eckerlin.dev.embeds.EmbedVorlageMapper;
import eckerlin.dev.embeds.EmbedVorlage;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.FileUpload;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class TicketModuleService {

    private static final String CREATE_PREFIX = "ticket:create:";
    private static final String CREATE_BUTTON_PREFIX = "ticket:create-button:";
    private static final String CLOSE_PREFIX = "ticket:close:";
    private static final String CLAIM_PREFIX = "ticket:claim:";
    private static final String PAUSE_PREFIX = "ticket:pause:";
    private static final Color PANEL_COLOR = new Color(0x8D7BFF);
    private static final Color TICKET_COLOR = new Color(0x78D1FF);

    private final GuildModuleSettingsService settingsService;
    private final TicketTranscriptService transcriptService;
    private final AppConfigService configService;

    private final EmbedRenderer embedRenderer;

    public TicketModuleService(
            GuildModuleSettingsService settingsService,
            TicketTranscriptService transcriptService,
            AppConfigService configService,
            EmbedRenderer embedRenderer) {
        this.settingsService = settingsService;
        this.transcriptService = transcriptService;
        this.configService = configService;
        this.embedRenderer = embedRenderer;
    }

    public TicketModuleView buildView(Guild guild) {
        GuildModuleSettingsService.TicketSystemState state = settingsService.getTicketState(guild.getId());
        List<TicketTranscriptView> transcripts = transcriptService.findByGuild(guild.getId(), 20).stream()
                .map(entry -> new TicketTranscriptView(
                        entry.id(),
                        entry.openerDisplay(),
                        entry.ticketSubject(),
                        entry.createdAt()
                ))
                .toList();

        String notice;
        if (!state.isEnabled()) {
            notice = "Das Ticket-System ist aktuell deaktiviert.";
        } else if (state.getPanels().isEmpty()) {
            notice = "Lege mindestens eine Tafel mit einem Anliegen an.";
        } else {
            notice = state.getPanels().size() + " Ticket-Panel(s) aktiv. Claims, Pausen, Rollen-Zugriff und Transcripts laufen direkt ueber Discord.";
        }

        return new TicketModuleView(
                state.isEnabled(),
                state.getTranscriptChannelId(),
                notice,
                state.getActiveTickets().size(),
                state.getPanels().stream().map(panel -> new TicketPanelView(
                        panel.getId(),
                        panel.getTitle(),
                        panel.getDescription(),
                        panel.getInteractionMode(),
                        panel.getPublishChannelId(),
                        panel.getCategoryId(),
                        panel.getPlaceholder(),
                        panel.getWelcomeMessage(),
                        panel.getImageUrl(),
                        panel.getThumbnailUrl(),
                        panel.getAccentColor(),
                        EmbedVorlageMapper.zurOberflaeche(panel.getEmbed()),
                        panel.getEmbedVorlageId(),
                        panel.getNotifyRoleId(),
                        panel.getSupportRoleIds(),
                        panel.isAllowClaim(),
                        panel.isAllowPause(),
                        panel.isAllowCreatorClose(),
                        panel.isOneTicketPerUser(),
                        panel.getMessageId(),
                        panel.getOptions().stream().map(option -> new TicketOptionView(
                                option.getId(),
                                option.getLabel(),
                                option.getDescription(),
                                option.getEmoji(),
                                option.getChannelNameTemplate(),
                                option.getSupportRoleIds()
                        )).toList()
                )).toList(),
                transcripts
        );
    }

    public String syncPublishedPanels(Guild guild) {
        GuildModuleSettingsService.TicketSystemState state = settingsService.getTicketState(guild.getId());
        int synced = 0;

        for (GuildModuleSettingsService.TicketPanel panel : state.getPanels()) {
            TextChannel publishChannel = guild.getTextChannelById(panel.getPublishChannelId());
            if (publishChannel == null) {
                continue;
            }

            try {
                var embed = buildPanelEmbed(guild, panel, state.isEnabled());
                var components = buildPanelComponents(panel, state.isEnabled());

                String currentMessageId = panel.getMessageId();
                Message message = null;
                if (currentMessageId != null && !currentMessageId.isBlank()) {
                    try {
                        message = publishChannel.retrieveMessageById(currentMessageId).complete();
                    } catch (RuntimeException ignored) {
                        message = null;
                    }
                }

                if (message == null) {
                    Message created = publishChannel.sendMessageEmbeds(embed)
                            .setComponents(components)
                            .complete();
                    settingsService.updateTicketPanelMessage(guild.getId(), panel.getId(), created.getId());
                } else {
                    message.editMessageEmbeds(embed)
                            .setComponents(components)
                            .complete();
                }
                synced++;
            } catch (RuntimeException ignored) {
            }
        }

        if (synced == 0) {
            return state.isEnabled()
                    ? "Keine Ticket-Panels mit gueltigem Ausgabe-Channel gefunden."
                    : "Ticket-System gespeichert. Vorhandene Panel-Nachrichten wurden nicht erneut gesendet.";
        }

        return state.isEnabled()
                ? synced + " Ticket-Panel(s) wurden in Discord synchronisiert."
                : synced + " Ticket-Panel(s) wurden als deaktiviert aktualisiert.";
    }

    public CreateTicketResult createTicket(Guild guild, Member member, String panelId, String optionId) {
        GuildModuleSettingsService.TicketSystemState state = settingsService.getTicketState(guild.getId());
        if (!state.isEnabled()) {
            return new CreateTicketResult(false, "Das Ticket-System ist gerade deaktiviert.", null);
        }

        GuildModuleSettingsService.TicketPanel panel = settingsService.findTicketPanel(guild.getId(), panelId).orElse(null);
        if (panel == null) {
            return new CreateTicketResult(false, "Das Ticket-Panel wurde nicht gefunden.", null);
        }

        GuildModuleSettingsService.TicketOption option = panel.getOptions().stream()
                .filter(candidate -> optionId.equals(candidate.getId()))
                .findFirst()
                .orElse(null);
        if (option == null) {
            return new CreateTicketResult(false, "Die ausgewaehlte Ticket-Kategorie existiert nicht mehr.", null);
        }

        if (panel.isOneTicketPerUser()
                && settingsService.findActiveTicketForUser(guild.getId(), panel.getId(), member.getId()).isPresent()) {
            return new CreateTicketResult(false, "Du hast in diesem Panel bereits ein offenes Ticket.", null);
        }

        Category category = panel.getCategoryId().isBlank() ? null : guild.getCategoryById(panel.getCategoryId());
        // Vor dem Anlegen vergeben: die Nummer steht im Kanalnamen, also muss
        // sie feststehen, bevor der Kanal entsteht.
        int nummer = settingsService.naechsteTicketNummer(guild.getId());
        String channelName = buildChannelName(option.getChannelNameTemplate(), member, guild, option.getLabel(), nummer);
        String topic = "Ticket fuer " + member.getEffectiveName() + " | " + option.getLabel();

        var action = category == null
                ? guild.createTextChannel(channelName)
                : guild.createTextChannel(channelName, category);

        action = action
                .setTopic(topic)
                .addPermissionOverride(
                        guild.getPublicRole(),
                        List.of(),
                        EnumSet.of(Permission.VIEW_CHANNEL)
                )
                .addPermissionOverride(
                        member,
                        EnumSet.of(
                                Permission.VIEW_CHANNEL,
                                Permission.MESSAGE_SEND,
                                Permission.MESSAGE_HISTORY,
                                Permission.MESSAGE_ATTACH_FILES,
                                Permission.MESSAGE_EMBED_LINKS,
                                Permission.USE_APPLICATION_COMMANDS
                        ),
                        List.of()
                );

        for (String supportRoleId : resolveSupportRoleIds(panel, option)) {
            Role supportRole = guild.getRoleById(supportRoleId);
            if (supportRole == null) {
                continue;
            }
            action = action.addPermissionOverride(
                    supportRole,
                    EnumSet.of(
                            Permission.VIEW_CHANNEL,
                            Permission.MESSAGE_SEND,
                            Permission.MESSAGE_HISTORY,
                            Permission.MESSAGE_ATTACH_FILES,
                            Permission.MESSAGE_EMBED_LINKS,
                            Permission.USE_APPLICATION_COMMANDS
                    ),
                    List.of()
            );
        }

        TextChannel createdChannel = action.complete();

        GuildModuleSettingsService.ActiveTicket activeTicket = new GuildModuleSettingsService.ActiveTicket();
        activeTicket.setChannelId(createdChannel.getId());
        activeTicket.setOpenerUserId(member.getId());
        activeTicket.setOpenerDisplay(member.getEffectiveName());
        activeTicket.setPanelId(panel.getId());
        activeTicket.setOptionId(option.getId());
        activeTicket.setOptionLabel(option.getLabel());
        activeTicket.setCreatedAt(Instant.now().toString());
        activeTicket.setNummer(nummer);

        String notifyMention = buildRoleMention(guild, panel.getNotifyRoleId());
        Message controlMessage;
        if (notifyMention.isBlank()) {
            controlMessage = createdChannel.sendMessageEmbeds(buildTicketEmbed(guild, panel, activeTicket))
                    .setComponents(buildTicketComponents(guild.getId(), createdChannel.getId(), panel, activeTicket))
                    .complete();
        } else {
            controlMessage = createdChannel.sendMessage(notifyMention)
                    .setEmbeds(buildTicketEmbed(guild, panel, activeTicket))
                    .setComponents(buildTicketComponents(guild.getId(), createdChannel.getId(), panel, activeTicket))
                    .complete();
        }
        activeTicket.setControlMessageId(controlMessage.getId());
        settingsService.addActiveTicket(guild.getId(), activeTicket);

        return new CreateTicketResult(true, "Ticket erstellt: " + createdChannel.getAsMention(), createdChannel.getId());
    }

    public TicketActionResult toggleClaim(Guild guild, TextChannel channel, Member actor) {
        TicketContext context = resolveTicketContext(guild, channel);
        if (context == null) {
            return TicketActionResult.error("Dieses Ticket ist nicht mehr registriert.");
        }
        if (!canManageTicket(actor, context.panel(), context.option())) {
            return TicketActionResult.error("Nur Support-Rollen oder Moderatoren koennen Tickets claimen.");
        }
        if (!context.panel().isAllowClaim()) {
            return TicketActionResult.error("Claim ist fuer dieses Ticket-Panel deaktiviert.");
        }

        GuildModuleSettingsService.ActiveTicket activeTicket = context.activeTicket();
        if (actor.getId().equals(activeTicket.getClaimedByUserId())) {
            activeTicket.setClaimedByUserId("");
            activeTicket.setClaimedByDisplay("");
            settingsService.updateActiveTicket(guild.getId(), activeTicket);
            return TicketActionResult.success(
                    "Claim wurde geloest.",
                    buildTicketEmbed(guild, context.panel(), activeTicket),
                    buildTicketComponents(guild.getId(), channel.getId(), context.panel(), activeTicket)
            );
        }

        activeTicket.setClaimedByUserId(actor.getId());
        activeTicket.setClaimedByDisplay(actor.getEffectiveName());
        settingsService.updateActiveTicket(guild.getId(), activeTicket);
        return TicketActionResult.success(
                "Ticket wurde von " + actor.getAsMention() + " uebernommen.",
                buildTicketEmbed(guild, context.panel(), activeTicket),
                buildTicketComponents(guild.getId(), channel.getId(), context.panel(), activeTicket)
        );
    }

    public TicketActionResult togglePause(Guild guild, TextChannel channel, Member actor) {
        TicketContext context = resolveTicketContext(guild, channel);
        if (context == null) {
            return TicketActionResult.error("Dieses Ticket ist nicht mehr registriert.");
        }
        if (!canManageTicket(actor, context.panel(), context.option())) {
            return TicketActionResult.error("Nur Support-Rollen oder Moderatoren koennen Tickets pausieren.");
        }
        if (!context.panel().isAllowPause()) {
            return TicketActionResult.error("Pause ist fuer dieses Ticket-Panel deaktiviert.");
        }

        GuildModuleSettingsService.ActiveTicket activeTicket = context.activeTicket();
        Member opener = guild.getMemberById(activeTicket.getOpenerUserId());
        if (opener == null) {
            return TicketActionResult.error("Der Ersteller dieses Tickets ist nicht mehr verfuegbar.");
        }

        boolean paused = !activeTicket.isPaused();
        var overrideAction = channel.upsertPermissionOverride(opener);
        if (paused) {
            overrideAction.deny(Permission.MESSAGE_SEND).queue();
        } else {
            overrideAction.clear(Permission.MESSAGE_SEND).queue();
        }

        activeTicket.setPaused(paused);
        settingsService.updateActiveTicket(guild.getId(), activeTicket);

        return TicketActionResult.success(
                paused ? "Ticket wurde pausiert." : "Ticket wurde wieder geoeffnet.",
                buildTicketEmbed(guild, context.panel(), activeTicket),
                buildTicketComponents(guild.getId(), channel.getId(), context.panel(), activeTicket)
        );
    }

    public CloseTicketResult closeTicket(Guild guild, TextChannel channel, Member actor) {
        TicketContext context = resolveTicketContext(guild, channel);
        if (context == null) {
            return new CloseTicketResult(false, "Dieses Ticket ist nicht mehr registriert.", null);
        }

        GuildModuleSettingsService.ActiveTicket activeTicket = context.activeTicket();
        GuildModuleSettingsService.TicketPanel panel = context.panel();
        if (!canCloseTicket(actor, panel, activeTicket, context.option())) {
            return new CloseTicketResult(false, "Nur der Ersteller, eine Support-Rolle oder ein Moderator kann dieses Ticket schliessen.", null);
        }

        try {
            String transcriptText = buildTranscript(channel);
            long transcriptId = transcriptService.saveTranscript(
                    guild.getId(),
                    channel.getId(),
                    activeTicket.getOpenerUserId(),
                    activeTicket.getOpenerDisplay(),
                    activeTicket.getOptionLabel(),
                    transcriptText
            );
            sendTranscriptDm(guild, activeTicket, transcriptId, transcriptText);
            announceTranscript(guild, activeTicket, transcriptId);
            settingsService.removeActiveTicket(guild.getId(), channel.getId());
            channel.delete().queue();
            return new CloseTicketResult(true, "Ticket wurde geschlossen. Transcript #" + transcriptId + " wurde gespeichert.", transcriptId);
        } catch (Exception exception) {
            return new CloseTicketResult(false, "Ticket konnte nicht sauber geschlossen werden: " + exception.getMessage(), null);
        }
    }

    public String extractPanelId(String componentId) {
        return componentId == null || !componentId.startsWith(CREATE_PREFIX)
                ? ""
                : componentId.substring(CREATE_PREFIX.length());
    }

    public boolean isTicketCreateComponent(String componentId) {
        return componentId != null && componentId.startsWith(CREATE_PREFIX);
    }

    public boolean isTicketCreateButtonComponent(String componentId) {
        return componentId != null && componentId.startsWith(CREATE_BUTTON_PREFIX);
    }

    public String extractCreateButtonPanelId(String componentId) {
        String[] parts = componentId == null ? new String[0] : componentId.split(":");
        return parts.length == 4 ? parts[2] : "";
    }

    public String extractCreateButtonOptionId(String componentId) {
        String[] parts = componentId == null ? new String[0] : componentId.split(":");
        return parts.length == 4 ? parts[3] : "";
    }

    public boolean isTicketCloseComponent(String componentId) {
        return componentId != null && componentId.startsWith(CLOSE_PREFIX);
    }

    public boolean isTicketClaimComponent(String componentId) {
        return componentId != null && componentId.startsWith(CLAIM_PREFIX);
    }

    public boolean isTicketPauseComponent(String componentId) {
        return componentId != null && componentId.startsWith(PAUSE_PREFIX);
    }

    public String extractActionChannelId(String componentId) {
        String[] parts = componentId == null ? new String[0] : componentId.split(":");
        return parts.length == 4 ? parts[3] : "";
    }

    public String extractActionGuildId(String componentId) {
        String[] parts = componentId == null ? new String[0] : componentId.split(":");
        return parts.length == 4 ? parts[2] : "";
    }

    private boolean canManageTicket(
            Member actor,
            GuildModuleSettingsService.TicketPanel panel,
            GuildModuleSettingsService.TicketOption option
    ) {
        if (actor == null || panel == null) {
            return false;
        }
        if (actor.hasPermission(Permission.MANAGE_CHANNEL) || actor.hasPermission(Permission.MANAGE_SERVER)) {
            return true;
        }
        List<String> supportRoleIds = resolveSupportRoleIds(panel, option);
        if (supportRoleIds.isEmpty()) {
            return false;
        }
        return actor.getRoles().stream().map(Role::getId).anyMatch(supportRoleIds::contains);
    }

    private boolean canCloseTicket(
            Member actor,
            GuildModuleSettingsService.TicketPanel panel,
            GuildModuleSettingsService.ActiveTicket activeTicket,
            GuildModuleSettingsService.TicketOption option
    ) {
        if (actor == null) {
            return false;
        }
        if (canManageTicket(actor, panel, option)) {
            return true;
        }
        return panel.isAllowCreatorClose() && actor.getId().equals(activeTicket.getOpenerUserId());
    }

    private TicketContext resolveTicketContext(Guild guild, TextChannel channel) {
        Optional<GuildModuleSettingsService.ActiveTicket> activeTicketOptional = settingsService.getActiveTicket(guild.getId(), channel.getId());
        if (activeTicketOptional.isEmpty()) {
            return null;
        }

        GuildModuleSettingsService.ActiveTicket activeTicket = activeTicketOptional.get();
        GuildModuleSettingsService.TicketPanel panel = settingsService.findTicketPanel(guild.getId(), activeTicket.getPanelId()).orElse(null);
        if (panel == null) {
            return null;
        }

        GuildModuleSettingsService.TicketOption option = panel.getOptions().stream()
                .filter(candidate -> activeTicket.getOptionId().equals(candidate.getId()))
                .findFirst()
                .orElse(null);

        return new TicketContext(activeTicket, panel, option);
    }

    private net.dv8tion.jda.api.entities.MessageEmbed buildPanelEmbed(
            Guild guild,
            GuildModuleSettingsService.TicketPanel panel,
            boolean enabled
    ) {
        // Eigene Gestaltung gewinnt - siehe EmbedVorlage. Ist nichts
        // eingetragen, bleibt es beim bisherigen Aussehen; sonst saehe jeder
        // bestehende Server nach dem Update ein leeres Panel.
        EmbedVorlage vorlage = null;
        if (panel.getEmbedVorlageId() != null && !panel.getEmbedVorlageId().isBlank()) {
            vorlage = settingsService.findEmbedVorlage(guild.getId(), panel.getEmbedVorlageId());
        }
        if (vorlage == null || vorlage.istLeer()) {
            vorlage = panel.getEmbed() != null && !panel.getEmbed().istLeer() ? panel.getEmbed() : null;
        }
        if (vorlage != null && enabled) {
            return embedRenderer.baueEmbeds(vorlage,
                    embedRenderer.standardPlatzhalter(guild, null)).get(0);
        }

        String description = panel.getDescription().isBlank()
                ? "Druecke unten auf dein Anliegen. Der Bot legt dafuer sofort einen privaten Kanal an."
                : panel.getDescription();

        // Die Beschreibungen der Anliegen standen frueher in der Auswahlliste.
        // Die gibt es nicht mehr, und auf einem Knopf ist dafuer kein Platz -
        // also hierher, sonst waere die Erklaerung mit der Liste verschwunden.
        StringBuilder erklaerungen = new StringBuilder();
        for (GuildModuleSettingsService.TicketOption option : panel.getOptions()) {
            if (option.getDescription() == null || option.getDescription().isBlank()) {
                continue;
            }
            erklaerungen.append(System.lineSeparator())
                    .append(option.getEmoji().isBlank() ? "•" : option.getEmoji())
                    .append(" **").append(option.getLabel()).append("** — ")
                    .append(option.getDescription());
        }
        if (erklaerungen.length() > 0) {
            description += System.lineSeparator() + erklaerungen;
        }

        if (!enabled) {
            description += "\n\nDieses Ticket-Panel ist gerade nicht verfuegbar.";
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(panel.getTitle().isBlank() ? "Support Tickets" : panel.getTitle())
                .setDescription(description)
                .setColor(resolveColor(panel.getAccentColor(), PANEL_COLOR))
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

    /**
     * Die Platzhalter eines Tickets - Standardwerte plus das, was nur hier
     * bekannt ist.
     */
    private Map<String, String> ticketPlatzhalter(
            Guild guild,
            GuildModuleSettingsService.TicketPanel panel,
            GuildModuleSettingsService.ActiveTicket activeTicket
    ) {
        // Der Eroeffner ist oft im Cache; ist er es nicht, bleiben die
        // nutzerbezogenen Platzhalter weg statt falsch zu sein.
        Member eroeffner = null;
        try {
            eroeffner = guild.getMemberById(activeTicket.getOpenerUserId());
        } catch (RuntimeException ignoriert) {
            // Nicht-numerische Kennung - kann nur aus alten Daten kommen.
        }

        Map<String, String> werte = new LinkedHashMap<>(embedRenderer.standardPlatzhalter(guild, eroeffner));
        werte.put("{ticket}", activeTicket.getOptionLabel());
        werte.put("{anliegen}", activeTicket.getOptionLabel());
        werte.put("{count}", String.valueOf(activeTicket.getNummer()));
        werte.put("{nummer}", String.valueOf(activeTicket.getNummer()));
        werte.put("{status}", activeTicket.isPaused() ? "pausiert" : "offen");
        werte.put("{bearbeiter}", activeTicket.getClaimedByDisplay());
        werte.put("{tafel}", panel.getTitle());
        // Auch ohne Cache-Treffer muss die Erwaehnung stimmen - sie braucht
        // nur die Kennung.
        werte.put("{user}", "<@" + activeTicket.getOpenerUserId() + ">");
        if (eroeffner == null) {
            werte.put("{name}", activeTicket.getOpenerDisplay());
            werte.put("{username}", activeTicket.getOpenerDisplay());
            werte.put("{userid}", activeTicket.getOpenerUserId());
        }
        return werte;
    }

    private net.dv8tion.jda.api.entities.MessageEmbed buildTicketEmbed(
            Guild guild,
            GuildModuleSettingsService.TicketPanel panel,
            GuildModuleSettingsService.ActiveTicket activeTicket
    ) {
        Map<String, String> platzhalter = ticketPlatzhalter(guild, panel, activeTicket);

        // Die eingestellte Gestaltung galt bisher nur fuer die Tafel, nicht
        // fuer das geoeffnete Ticket - dort wurde immer der feste Text unten
        // gebaut. Wer einen Embed gestaltet hat, sah ihn also nie wieder.
        EmbedVorlage vorlage = null;
        if (panel.getEmbedVorlageId() != null && !panel.getEmbedVorlageId().isBlank()) {
            vorlage = settingsService.findEmbedVorlage(guild.getId(), panel.getEmbedVorlageId());
        }
        if (vorlage == null || vorlage.istLeer()) {
            vorlage = panel.getEmbed() != null && !panel.getEmbed().istLeer() ? panel.getEmbed() : null;
        }
        if (vorlage != null) {
            return embedRenderer.baueEmbeds(vorlage, platzhalter).get(0);
        }

        StringBuilder description = new StringBuilder()
                .append("<@")
                .append(activeTicket.getOpenerUserId())
                .append(">, dein Ticket fuer **")
                .append(activeTicket.getOptionLabel())
                .append("** ist jetzt ")
                .append(activeTicket.isPaused() ? "pausiert" : "offen")
                .append(".\n")
                .append(panel.getWelcomeMessage().isBlank()
                        ? "Beschreibe dein Anliegen hier. Das Team meldet sich so schnell wie moeglich."
                        // Hier stand die Begruessung woertlich - "{username}"
                        // blieb "{username}".
                        : embedRenderer.ersetzePlatzhalter(panel.getWelcomeMessage(), platzhalter));
        if (!activeTicket.getClaimedByDisplay().isBlank()) {
            description.append("\n\nBearbeitet von **")
                    .append(activeTicket.getClaimedByDisplay())
                    .append("**.");
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(panel.getTitle().isBlank() ? "Support Ticket" : panel.getTitle())
                .setDescription(description.toString())
                .setColor(resolveColor(panel.getAccentColor(), TICKET_COLOR))
                .setTimestamp(Instant.now());

        if (!panel.getThumbnailUrl().isBlank()) {
            embed.setThumbnail(panel.getThumbnailUrl());
        }
        if (!panel.getImageUrl().isBlank()) {
            embed.setImage(panel.getImageUrl());
        }
        embed.setFooter(guild.getName());
        return embed.build();
    }

    /**
     * Die Knoepfe unter der Tafel.
     *
     * <p>Nur noch Knoepfe. Die Auswahlliste ist raus - sie kostet einen Klick
     * mehr, verbirgt die Anliegen hinter einem Aufklapper und sah auf dem
     * Telefon aus wie ein Formularfeld. Die Grenze ist dieselbe geblieben:
     * fuenf Knoepfe je Reihe, fuenf Reihen, also 25 Anliegen - genau so viele,
     * wie eine Auswahlliste auch fasst.</p>
     *
     * <p>{@code interactionMode} bleibt im Datensatz stehen und wird
     * ignoriert. Es zu entfernen haette bedeutet, jeden gespeicherten Server
     * anzufassen, ohne dass sich etwas daran aendert.</p>
     */
    private List<ActionRow> buildPanelComponents(GuildModuleSettingsService.TicketPanel panel, boolean enabled) {
        if (!enabled || panel.getOptions().isEmpty()) {
            return List.of();
        }

        List<ActionRow> rows = new ArrayList<>();
        List<Button> currentRow = new ArrayList<>();
        for (GuildModuleSettingsService.TicketOption option : panel.getOptions()) {
            currentRow.add(baueAnliegenKnopf(panel, option));
            if (currentRow.size() == 5) {
                rows.add(ActionRow.of(new ArrayList<>(currentRow)));
                currentRow.clear();
            }
            if (rows.size() == 5) {
                break;
            }
        }
        if (!currentRow.isEmpty() && rows.size() < 5) {
            rows.add(ActionRow.of(currentRow));
        }
        return rows;
    }

    /**
     * Ein Knopf je Anliegen - mit dem Emoji im dafuer vorgesehenen Feld.
     *
     * <p>Frueher wurde das Emoji vor die Beschriftung geschrieben. Bei einem
     * Unicode-Zeichen faellt das nicht auf, bei einem Server-Emoji schon: dort
     * stand dann woertlich {@code <:name:123>} auf dem Knopf. Laesst sich die
     * Angabe nicht deuten, bleibt es beim vorangestellten Text - lieber ein
     * schiefes Emoji als ein Knopf, der beim Aufbau der Nachricht platzt.</p>
     */
    private Button baueAnliegenKnopf(
            GuildModuleSettingsService.TicketPanel panel,
            GuildModuleSettingsService.TicketOption option
    ) {
        String id = createButtonComponentId(panel.getId(), option.getId());
        String emoji = option.getEmoji() == null ? "" : option.getEmoji().trim();
        if (emoji.isBlank()) {
            return Button.primary(id, clamp(option.getLabel(), 80));
        }
        try {
            return Button.primary(id, clamp(option.getLabel(), 80))
                    .withEmoji(Emoji.fromFormatted(emoji));
        } catch (RuntimeException nichtDeutbar) {
            return Button.primary(id, clamp(emoji + " " + option.getLabel(), 80));
        }
    }

    private List<ActionRow> buildTicketComponents(
            String guildId,
            String channelId,
            GuildModuleSettingsService.TicketPanel panel,
            GuildModuleSettingsService.ActiveTicket activeTicket
    ) {
        List<Button> buttons = new ArrayList<>();

        if (panel.isAllowClaim()) {
            String claimLabel = activeTicket.getClaimedByDisplay().isBlank()
                    ? "Claim"
                    : activeTicket.getClaimedByDisplay().length() > 40
                    ? "Claim aktualisieren"
                    : "Claim: " + activeTicket.getClaimedByDisplay();
            buttons.add(Button.secondary(claimComponentId(guildId, channelId), clamp(claimLabel, 80)));
        }
        if (panel.isAllowPause()) {
            buttons.add(Button.secondary(pauseComponentId(guildId, channelId), activeTicket.isPaused() ? "Oeffnen" : "Pause"));
        }
        buttons.add(Button.danger(closeComponentId(guildId, channelId), "Schliessen"));

        return List.of(ActionRow.of(buttons));
    }

    // Hier stand buildSelectMenu. Gebaut wird keine Auswahlliste mehr.
    //
    // Der Empfang bleibt aber bestehen (isTicketCreateComponent und
    // CREATE_PREFIX): Tafeln, die vor dieser Aenderung veroeffentlicht wurden,
    // haengen als Nachricht mit Auswahlliste weiterhin in Discord und werden
    // erst beim naechsten Speichern auf Knoepfe umgestellt. Bis dahin muessen
    // Klicks darauf weiter funktionieren - sonst laeuft jemand in eine tote
    // Nachricht, ohne zu wissen warum.

    /**
     * Erkennt den Knopf-Modus.
     *
     * <p>Hier stand {@code "buttons".equals(...)} - die Oberflaeche schickt
     * aber {@code "button"} im Singular. Der Zweig wurde damit nie betreten,
     * und jede Tafel bekam eine Auswahlliste, egal was eingestellt war. Beide
     * Schreibweisen zu akzeptieren ist billiger, als sich auf eine zu einigen
     * und die gespeicherten Daten anzufassen.</p>
     */
    private static boolean istKnopfModus(String modus) {
        String wert = modus == null ? "" : modus.trim().toLowerCase(Locale.ROOT);
        return wert.equals("button") || wert.equals("buttons") || wert.equals("knopf") || wert.equals("knoepfe");
    }

    /**
     * Der Kanalname aus der Vorlage.
     *
     * @param nummer fortlaufende Ticketnummer fuer {@code {count}}
     */
    private String buildChannelName(String template, Member member, Guild guild, String optionLabel, int nummer) {
        String raw = (template == null || template.isBlank() ? "ticket-{label}-{user}" : template)
                .replace("{user}", kanalTauglich(member.getEffectiveName()))
                .replace("{username}", kanalTauglich(member.getUser().getName()))
                .replace("{name}", kanalTauglich(member.getEffectiveName()))
                .replace("{guild}", kanalTauglich(guild.getName()))
                .replace("{server}", kanalTauglich(guild.getName()))
                .replace("{label}", kanalTauglich(optionLabel))
                // {count} gab es bisher gar nicht - "bug-{count}" wurde
                // deshalb zu "bug-count", weil die geschweiften Klammern
                // anschliessend als Sonderzeichen wegfielen.
                .replace("{count}", String.valueOf(nummer))
                .replace("{nummer}", String.valueOf(nummer))
                .replace("{anzahl}", String.valueOf(nummer));
        String name = kanalTauglich(raw);
        return name.isBlank() ? "ticket-" + nummer : name;
    }

    /**
     * Macht aus einem Text einen Discord-tauglichen Kanalnamen.
     *
     * <p>Die alte Fassung ersetzte alles ausser {@code [a-z0-9]} durch einen
     * Bindestrich. Discord ist da deutlich grosszuegiger: Emoji und Zeichen
     * wie {@code │} sind in Kanalnamen ueblich und erlaubt. Aus
     * {@code "│🐞│bug-{count}"} wurde so {@code "bug-count"} - erst fiel die
     * Verzierung weg, dann die Klammern des Platzhalters.</p>
     *
     * <p>Jetzt bleibt alles stehen, was Discord akzeptiert. Entfernt werden
     * nur Steuerzeichen und die Handvoll ASCII-Zeichen, an denen Discord sich
     * tatsaechlich stoert; Leerraum wird - wie in Discord selbst - zum
     * Bindestrich.</p>
     */
    private String kanalTauglich(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        String name = value.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "-")
                .replaceAll("[\\p{Cntrl}@#:,.?%*|\"<>\\\\/]+", "")
                .replaceAll("-{2,}", "-")
                .replaceAll("(^-+|-+$)", "");
        // Discord erlaubt 100 Zeichen; etwas Reserve fuer den Rest der Vorlage.
        return name.length() > 90 ? name.substring(0, 90) : name;
    }

    private String buildTranscript(TextChannel channel) {
        List<Message> messages = channel.getIterableHistory()
                .cache(false)
                .takeAsync(500)
                .join();
        Collections.reverse(messages);

        StringBuilder transcript = new StringBuilder();
        transcript.append("Transcript fuer #").append(channel.getName()).append(System.lineSeparator());
        transcript.append("Erstellt: ").append(Instant.now()).append(System.lineSeparator()).append(System.lineSeparator());

        for (Message message : messages) {
            transcript.append("[")
                    .append(message.getTimeCreated())
                    .append("] ")
                    .append(message.getAuthor().getName());
            if (message.getMember() != null) {
                transcript.append(" (").append(message.getMember().getEffectiveName()).append(")");
            }
            transcript.append(":").append(System.lineSeparator());

            String content = message.getContentDisplay();
            if (content != null && !content.isBlank()) {
                transcript.append(content).append(System.lineSeparator());
            }

            message.getAttachments().forEach(attachment ->
                    transcript.append("[Anhang] ").append(attachment.getFileName()).append(" -> ").append(attachment.getUrl()).append(System.lineSeparator())
            );
            message.getEmbeds().forEach(embed -> {
                if (embed.getTitle() != null && !embed.getTitle().isBlank()) {
                    transcript.append("[Embed] ").append(embed.getTitle()).append(System.lineSeparator());
                }
                if (embed.getDescription() != null && !embed.getDescription().isBlank()) {
                    transcript.append(embed.getDescription()).append(System.lineSeparator());
                }
            });
            transcript.append(System.lineSeparator());
        }

        return transcript.toString();
    }

    private void sendTranscriptDm(
            Guild guild,
            GuildModuleSettingsService.ActiveTicket activeTicket,
            long transcriptId,
            String transcriptText
    ) {
        if (activeTicket.getOpenerUserId().isBlank()) {
            return;
        }

        User user = guild.getJDA().getUserById(activeTicket.getOpenerUserId());
        if (user == null) {
            try {
                user = guild.getJDA().retrieveUserById(activeTicket.getOpenerUserId()).complete();
            } catch (RuntimeException ignored) {
                user = null;
            }
        }
        if (user == null) {
            return;
        }

        String baseUrl = configService.getWebBaseUrl();
        String link = baseUrl == null || baseUrl.isBlank()
                ? ""
                : baseUrl + "/api/dashboard/guilds/" + guild.getId() + "/tickets/transcripts/" + transcriptId;
        String fileName = "ticket-transcript-" + transcriptId + ".txt";

        user.openPrivateChannel()
                .flatMap(privateChannel -> privateChannel.sendMessage(
                                "Dein Ticket **" + activeTicket.getOptionLabel() + "** wurde geschlossen."
                                        + (link.isBlank() ? "" : "\nDownload im Web: " + link)
                        )
                        .addFiles(FileUpload.fromData(transcriptText.getBytes(StandardCharsets.UTF_8), fileName)))
                .queue(
                        success -> {
                        },
                        error -> {
                        }
                );
    }

    private void announceTranscript(Guild guild, GuildModuleSettingsService.ActiveTicket activeTicket, long transcriptId) {
        GuildModuleSettingsService.TicketSystemState state = settingsService.getTicketState(guild.getId());
        if (state.getTranscriptChannelId().isBlank()) {
            return;
        }

        TextChannel transcriptChannel = guild.getTextChannelById(state.getTranscriptChannelId());
        if (transcriptChannel == null) {
            return;
        }

        String baseUrl = configService.getWebBaseUrl();
        String link = baseUrl == null || baseUrl.isBlank()
                ? ""
                : baseUrl + "/api/dashboard/guilds/" + guild.getId() + "/tickets/transcripts/" + transcriptId;

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Ticket geschlossen")
                .setDescription("Transcript #" + transcriptId + " fuer **" + activeTicket.getOptionLabel() + "** wurde gespeichert.")
                .setColor(PANEL_COLOR)
                .setTimestamp(Instant.now());
        embed.addField("Ersteller", activeTicket.getOpenerDisplay().isBlank() ? "Unbekannt" : activeTicket.getOpenerDisplay(), true);
        if (!activeTicket.getClaimedByDisplay().isBlank()) {
            embed.addField("Claim", activeTicket.getClaimedByDisplay(), true);
        }
        if (!link.isBlank()) {
            embed.addField("Download", "[Transcript herunterladen](" + link + ")", false);
        }
        transcriptChannel.sendMessageEmbeds(embed.build()).queue();
    }

    private String buildRoleMention(Guild guild, String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return "";
        }
        Role role = guild.getRoleById(roleId);
        return role == null ? "" : role.getAsMention();
    }

    private List<String> resolveSupportRoleIds(
            GuildModuleSettingsService.TicketPanel panel,
            GuildModuleSettingsService.TicketOption option
    ) {
        if (option != null && option.getSupportRoleIds() != null && !option.getSupportRoleIds().isEmpty()) {
            return option.getSupportRoleIds();
        }
        return panel == null || panel.getSupportRoleIds() == null ? List.of() : panel.getSupportRoleIds();
    }

    private Color resolveColor(String value, Color fallback) {
        try {
            return Color.decode(value == null || value.isBlank() ? "#78D1FF" : value);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private String clamp(String value, int max) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.length() > max ? value.substring(0, max - 3) + "..." : value;
    }

    public static String closeComponentId(String guildId, String channelId) {
        return CLOSE_PREFIX + guildId + ":" + channelId;
    }

    public static String createButtonComponentId(String panelId, String optionId) {
        return CREATE_BUTTON_PREFIX + panelId + ":" + optionId;
    }

    public static String claimComponentId(String guildId, String channelId) {
        return CLAIM_PREFIX + guildId + ":" + channelId;
    }

    public static String pauseComponentId(String guildId, String channelId) {
        return PAUSE_PREFIX + guildId + ":" + channelId;
    }

    public record CreateTicketResult(boolean success, String message, String channelId) {
    }

    public record CloseTicketResult(boolean success, String message, Long transcriptId) {
    }

    public record TicketActionResult(
            boolean success,
            String message,
            net.dv8tion.jda.api.entities.MessageEmbed embed,
            List<ActionRow> components
    ) {
        public static TicketActionResult error(String message) {
            return new TicketActionResult(false, message, null, List.of());
        }

        public static TicketActionResult success(String message, net.dv8tion.jda.api.entities.MessageEmbed embed, List<ActionRow> components) {
            return new TicketActionResult(true, message, embed, components);
        }
    }

    private record TicketContext(
            GuildModuleSettingsService.ActiveTicket activeTicket,
            GuildModuleSettingsService.TicketPanel panel,
            GuildModuleSettingsService.TicketOption option
    ) {
    }
}
