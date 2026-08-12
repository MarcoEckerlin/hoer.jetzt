package eckerlin.dev.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import eckerlin.dev.utils.Alert;
import eckerlin.dev.utils.Config;
import eckerlin.dev.utils.DB;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class GuildModuleSettingsService {

    private static final Path LEGACY_SETTINGS_PATH = Paths.get("config", "guild-modules.json");
    private static final int DEFAULT_DELAY_SECONDS = 60;
    private static final int MAX_INVITE_HISTORY = 20;
    private static final String DEFAULT_JOIN_TEMPLATE = "#{counter} - Sprachkanal";

    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, GuildState> cache = new ConcurrentHashMap<>();
    private final int botId = Config.config.optInt("bot_id", 1);

    public GuildModuleSettingsService() {
        this.objectMapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
        importLegacyFileIfPresent();
    }

    public void initializeStorage() {
        importLegacyFileIfPresent();
    }

    public synchronized JoinToCreateState getJoinToCreateState(String guildId) {
        return getOrCreateGuildState(guildId).getJoinToCreate().copy();
    }

    public synchronized Optional<JoinToCreateEntry> findJoinToCreateEntryBySourceChannel(String guildId, String sourceChannelId) {
        if (sourceChannelId == null || sourceChannelId.isBlank()) {
            return Optional.empty();
        }

        return getOrCreateGuildState(guildId).getJoinToCreate().getEntries().stream()
                .filter(entry -> sourceChannelId.equals(entry.getSourceChannelId()))
                .findFirst()
                .map(JoinToCreateEntry::copy);
    }

    public synchronized void saveJoinToCreate(
            String guildId,
            boolean enabled,
            List<JoinToCreateEntry> entries,
            int cleanupDelaySeconds,
            int audioIdleTimeoutSeconds
    ) {
        GuildState guildState = getOrCreateGuildState(guildId);
        JoinToCreateState joinToCreateState = guildState.getJoinToCreate();
        Map<String, JoinToCreateEntry> existingEntriesById = new LinkedHashMap<>();
        for (JoinToCreateEntry existingEntry : joinToCreateState.getEntries()) {
            existingEntriesById.put(existingEntry.getId(), existingEntry);
        }

        List<JoinToCreateEntry> normalizedEntries = entries == null
                ? List.of()
                : entries.stream()
                .filter(Objects::nonNull)
                .map(this::normalizeEntry)
                .map(entry -> {
                    JoinToCreateEntry existingEntry = existingEntriesById.get(entry.getId());
                    if (existingEntry != null) {
                        entry.setNextCounter(Math.max(1, existingEntry.getNextCounter()));
                    }
                    return entry;
                })
                .filter(entry -> !entry.getSourceChannelId().isBlank())
                .toList();

        joinToCreateState.setEnabled(enabled);
        joinToCreateState.setEntries(new ArrayList<>(normalizedEntries));
        joinToCreateState.setCleanupDelaySeconds(clampDelay(cleanupDelaySeconds));
        joinToCreateState.setAudioIdleTimeoutSeconds(clampDelay(audioIdleTimeoutSeconds));

        Set<String> validEntryIds = normalizedEntries.stream()
                .map(JoinToCreateEntry::getId)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);

        joinToCreateState.getManagedChannels().values().forEach(managedChannel -> {
            if (!managedChannel.getSourceEntryId().isBlank() && !validEntryIds.contains(managedChannel.getSourceEntryId())) {
                managedChannel.setSourceEntryId("");
            }
        });

        persistQuietly(guildId, guildState);
    }

    public synchronized int reserveNextJoinToCreateCounter(String guildId, String entryId) {
        if (entryId == null || entryId.isBlank()) {
            return 1;
        }

        GuildState guildState = getOrCreateGuildState(guildId);
        JoinToCreateEntry entry = guildState.getJoinToCreate().getEntries().stream()
                .filter(candidate -> entryId.equals(candidate.getId()))
                .findFirst()
                .orElse(null);

        if (entry == null) {
            return 1;
        }

        int counter = Math.max(1, entry.getNextCounter());
        entry.setNextCounter(counter + 1);
        persistQuietly(guildId, guildState);
        return counter;
    }

    public synchronized void addManagedVoiceChannel(
            String guildId,
            String channelId,
            String ownerId,
            String sourceEntryId
    ) {
        if (channelId == null || channelId.isBlank()) {
            return;
        }

        GuildState guildState = getOrCreateGuildState(guildId);
        guildState.getJoinToCreate().getManagedChannels().put(channelId, new ManagedVoiceChannel(
                channelId,
                ownerId == null ? "" : ownerId,
                sourceEntryId == null ? "" : sourceEntryId,
                Instant.now().toString(),
                new LinkedHashSet<>()
        ));
        persistQuietly(guildId, guildState);
    }

    public synchronized void addManagedVoiceChannelAdmin(String guildId, String channelId, String userId) {
        if (channelId == null || channelId.isBlank() || userId == null || userId.isBlank()) {
            return;
        }

        GuildState guildState = getOrCreateGuildState(guildId);
        ManagedVoiceChannel managedVoiceChannel = guildState.getJoinToCreate().getManagedChannels().get(channelId);
        if (managedVoiceChannel == null) {
            return;
        }

        if (managedVoiceChannel.getAdminUserIds().add(userId)) {
            persistQuietly(guildId, guildState);
        }
    }

    public synchronized boolean isManagedVoiceChannel(String guildId, String channelId) {
        if (channelId == null || channelId.isBlank()) {
            return false;
        }
        return getOrCreateGuildState(guildId).getJoinToCreate().getManagedChannels().containsKey(channelId);
    }

    public synchronized Optional<ManagedVoiceChannel> getManagedVoiceChannel(String guildId, String channelId) {
        if (channelId == null || channelId.isBlank()) {
            return Optional.empty();
        }

        ManagedVoiceChannel managedVoiceChannel = getOrCreateGuildState(guildId)
                .getJoinToCreate()
                .getManagedChannels()
                .get(channelId);

        return managedVoiceChannel == null
                ? Optional.empty()
                : Optional.of(managedVoiceChannel.copy());
    }

    public synchronized Optional<ManagedVoiceChannel> findMostRecentManagedChannelForOwner(String guildId, String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            return Optional.empty();
        }

        return getOrCreateGuildState(guildId).getJoinToCreate().getManagedChannels().values().stream()
                .filter(channel -> ownerId.equals(channel.getOwnerId()))
                .max(Comparator.comparing(ManagedVoiceChannel::getCreatedAt))
                .map(ManagedVoiceChannel::copy);
    }

    public synchronized void removeManagedVoiceChannel(String guildId, String channelId) {
        if (channelId == null || channelId.isBlank()) {
            return;
        }

        GuildState guildState = getOrCreateGuildState(guildId);
        if (guildState.getJoinToCreate().getManagedChannels().remove(channelId) != null) {
            persistQuietly(guildId, guildState);
        }
    }

    public synchronized int getManagedVoiceChannelCount(String guildId) {
        return getOrCreateGuildState(guildId).getJoinToCreate().getManagedChannels().size();
    }

    public synchronized InviteTrackerState getInviteTrackerState(String guildId) {
        return getOrCreateGuildState(guildId).getInviteTracker().copy();
    }

    public synchronized boolean isInviteTrackerEnabled(String guildId) {
        return getOrCreateGuildState(guildId).getInviteTracker().isEnabled();
    }

    public synchronized void saveInviteTracker(String guildId, boolean enabled) {
        GuildState guildState = getOrCreateGuildState(guildId);
        guildState.getInviteTracker().setEnabled(enabled);
        persistQuietly(guildId, guildState);
    }

    public synchronized List<InviteJoinEntry> getRecentInviteJoins(String guildId) {
        return getOrCreateGuildState(guildId).getInviteTracker().getRecentJoins().stream()
                .map(InviteJoinEntry::copy)
                .toList();
    }

    public synchronized void recordInviteJoin(
            String guildId,
            String memberId,
            String memberDisplay,
            String inviteCode,
            String inviterDisplay,
            Integer uses
    ) {
        GuildState guildState = getOrCreateGuildState(guildId);
        InviteTrackerState state = guildState.getInviteTracker();
        state.getRecentJoins().add(0, new InviteJoinEntry(
                memberId == null ? "" : memberId,
                memberDisplay == null ? "Unbekannt" : memberDisplay,
                inviteCode == null ? "unbekannt" : inviteCode,
                inviterDisplay == null ? "Unbekannt" : inviterDisplay,
                uses,
                Instant.now().toString()
        ));

        while (state.getRecentJoins().size() > MAX_INVITE_HISTORY) {
            state.getRecentJoins().remove(state.getRecentJoins().size() - 1);
        }

        persistQuietly(guildId, guildState);
    }

    public synchronized WelcomeState getWelcomeState(String guildId) {
        return getOrCreateGuildState(guildId).getWelcome().copy();
    }

    public synchronized void saveWelcomeState(
            String guildId,
            boolean enabled,
            List<String> roleIds,
            String channelId,
            String welcomeText,
            boolean sendImage,
            String backgroundImageUrl,
            String accentColor
    ) {
        GuildState guildState = getOrCreateGuildState(guildId);
        WelcomeState welcomeState = guildState.getWelcome();
        welcomeState.setEnabled(enabled);
        welcomeState.setRoleIds(normalizeStringList(roleIds));
        welcomeState.setChannelId(blankToEmpty(channelId));
        welcomeState.setWelcomeText(blankToEmpty(welcomeText));
        welcomeState.setSendImage(sendImage);
        welcomeState.setBackgroundImageUrl(normalizeUrl(backgroundImageUrl));
        welcomeState.setAccentColor(normalizeColor(accentColor));
        persistQuietly(guildId, guildState);
    }

    public synchronized ReactionRoleState getReactionRoleState(String guildId) {
        return getOrCreateGuildState(guildId).getReactionRoles().copy();
    }

    public synchronized void saveReactionRoleState(
            String guildId,
            boolean enabled,
            List<ReactionRolePanel> panels
    ) {
        GuildState guildState = getOrCreateGuildState(guildId);
        ReactionRoleState reactionRoleState = guildState.getReactionRoles();
        reactionRoleState.setEnabled(enabled);
        reactionRoleState.setPanels((panels == null ? List.<ReactionRolePanel>of() : panels).stream()
                .filter(Objects::nonNull)
                .map(this::normalizeReactionRolePanel)
                .filter(panel -> !panel.getPublishChannelId().isBlank())
                .toList());
        persistQuietly(guildId, guildState);
    }

    public synchronized void updateReactionRoleMessage(String guildId, String panelId, String messageId) {
        GuildState guildState = getOrCreateGuildState(guildId);
        guildState.getReactionRoles().getPanels().stream()
                .filter(panel -> Objects.equals(panel.getId(), panelId))
                .findFirst()
                .ifPresent(panel -> panel.setMessageId(blankToEmpty(messageId)));
        persistQuietly(guildId, guildState);
    }

    public synchronized VerifyState getVerifyState(String guildId) {
        return getOrCreateGuildState(guildId).getVerify().copy();
    }

    public synchronized void saveVerifyState(
            String guildId,
            boolean enabled,
            String publishChannelId,
            List<String> verifiedRoleIds,
            String title,
            String description,
            String imageUrl,
            String thumbnailUrl,
            String accentColor
    ) {
        GuildState guildState = getOrCreateGuildState(guildId);
        VerifyState verifyState = guildState.getVerify();
        verifyState.setEnabled(enabled);
        verifyState.setPublishChannelId(blankToEmpty(publishChannelId));
        verifyState.setVerifiedRoleIds(normalizeStringList(verifiedRoleIds));
        verifyState.setTitle(blankToEmpty(title));
        verifyState.setDescription(blankToEmpty(description));
        verifyState.setImageUrl(normalizeUrl(imageUrl));
        verifyState.setThumbnailUrl(normalizeUrl(thumbnailUrl));
        verifyState.setAccentColor(normalizeColor(accentColor));
        persistQuietly(guildId, guildState);
    }

    public synchronized void updateVerifyMessage(String guildId, String messageId) {
        GuildState guildState = getOrCreateGuildState(guildId);
        guildState.getVerify().setMessageId(blankToEmpty(messageId));
        persistQuietly(guildId, guildState);
    }

    public synchronized LlmState getLlmState(String guildId) {
        return getOrCreateGuildState(guildId).getLlm().copy();
    }

    public synchronized void saveLlmState(
            String guildId,
            boolean enabled,
            String textChannelId,
            String model
    ) {
        GuildState guildState = getOrCreateGuildState(guildId);
        LlmState llmState = guildState.getLlm();
        llmState.setEnabled(enabled);
        llmState.setTextChannelId(blankToEmpty(textChannelId));
        llmState.setModel(blankToEmpty(model));
        persistQuietly(guildId, guildState);
    }

    public synchronized DiscordLogState getDiscordLogState(String guildId) {
        return getOrCreateGuildState(guildId).getDiscordLogs().copy();
    }

    public synchronized void saveDiscordLogState(
            String guildId,
            boolean enabled,
            String textChannelId,
            boolean memberJoin,
            boolean memberLeave,
            boolean voiceJoin,
            boolean voiceLeave,
            boolean music,
            boolean moderation,
            boolean roleUpdates,
            boolean nicknameUpdates,
            boolean timeouts,
            boolean kicks,
            boolean bans,
            boolean messageDeletes,
            boolean voiceModeration,
            boolean commands
    ) {
        GuildState guildState = getOrCreateGuildState(guildId);
        DiscordLogState logState = guildState.getDiscordLogs();
        logState.setEnabled(enabled);
        logState.setTextChannelId(blankToEmpty(textChannelId));
        logState.setMemberJoin(memberJoin);
        logState.setMemberLeave(memberLeave);
        logState.setVoiceJoin(voiceJoin);
        logState.setVoiceLeave(voiceLeave);
        logState.setMusic(music);
        logState.setModeration(moderation);
        logState.setRoleUpdates(roleUpdates);
        logState.setNicknameUpdates(nicknameUpdates);
        logState.setTimeouts(timeouts);
        logState.setKicks(kicks);
        logState.setBans(bans);
        logState.setMessageDeletes(messageDeletes);
        logState.setVoiceModeration(voiceModeration);
        logState.setCommands(commands);
        persistQuietly(guildId, guildState);
    }

    public synchronized TicketSystemState getTicketState(String guildId) {
        return getOrCreateGuildState(guildId).getTickets().copy();
    }

    public synchronized void saveTicketState(
            String guildId,
            boolean enabled,
            String transcriptChannelId,
            List<TicketPanel> panels
    ) {
        GuildState guildState = getOrCreateGuildState(guildId);
        TicketSystemState ticketState = guildState.getTickets();

        List<TicketPanel> normalizedPanels = panels == null
                ? List.of()
                : panels.stream()
                .filter(Objects::nonNull)
                .map(this::normalizeTicketPanel)
                .filter(panel -> !panel.getPublishChannelId().isBlank())
                .toList();

        ticketState.setEnabled(enabled);
        ticketState.setTranscriptChannelId(blankToEmpty(transcriptChannelId));
        ticketState.setPanels(new ArrayList<>(normalizedPanels));

        Set<String> validPanelIds = normalizedPanels.stream()
                .map(TicketPanel::getId)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);

        ticketState.getActiveTickets().entrySet().removeIf(entry -> !validPanelIds.contains(entry.getValue().getPanelId()));
        persistQuietly(guildId, guildState);
    }

    public synchronized Optional<TicketPanel> findTicketPanel(String guildId, String panelId) {
        if (panelId == null || panelId.isBlank()) {
            return Optional.empty();
        }

        return getOrCreateGuildState(guildId).getTickets().getPanels().stream()
                .filter(panel -> panelId.equals(panel.getId()))
                .findFirst()
                .map(TicketPanel::copy);
    }

    public synchronized void updateTicketPanelMessage(String guildId, String panelId, String messageId) {
        if (panelId == null || panelId.isBlank()) {
            return;
        }

        GuildState guildState = getOrCreateGuildState(guildId);
        guildState.getTickets().getPanels().stream()
                .filter(panel -> panelId.equals(panel.getId()))
                .findFirst()
                .ifPresent(panel -> panel.setMessageId(blankToEmpty(messageId)));
        persistQuietly(guildId, guildState);
    }

    public synchronized Optional<ActiveTicket> getActiveTicket(String guildId, String channelId) {
        if (channelId == null || channelId.isBlank()) {
            return Optional.empty();
        }

        ActiveTicket activeTicket = getOrCreateGuildState(guildId).getTickets().getActiveTickets().get(channelId);
        return activeTicket == null ? Optional.empty() : Optional.of(activeTicket.copy());
    }

    public synchronized Optional<ActiveTicket> findActiveTicketForUser(String guildId, String panelId, String openerUserId) {
        if (openerUserId == null || openerUserId.isBlank()) {
            return Optional.empty();
        }

        return getOrCreateGuildState(guildId).getTickets().getActiveTickets().values().stream()
                .filter(ticket -> openerUserId.equals(ticket.getOpenerUserId()))
                .filter(ticket -> panelId == null || panelId.isBlank() || panelId.equals(ticket.getPanelId()))
                .findFirst()
                .map(ActiveTicket::copy);
    }

    public synchronized void addActiveTicket(String guildId, ActiveTicket activeTicket) {
        if (activeTicket == null || activeTicket.getChannelId().isBlank()) {
            return;
        }

        GuildState guildState = getOrCreateGuildState(guildId);
        guildState.getTickets().getActiveTickets().put(activeTicket.getChannelId(), activeTicket.copy());
        persistQuietly(guildId, guildState);
    }

    public synchronized void updateActiveTicket(String guildId, ActiveTicket activeTicket) {
        if (activeTicket == null || activeTicket.getChannelId().isBlank()) {
            return;
        }

        GuildState guildState = getOrCreateGuildState(guildId);
        guildState.getTickets().getActiveTickets().put(activeTicket.getChannelId(), activeTicket.copy());
        persistQuietly(guildId, guildState);
    }

    public synchronized void removeActiveTicket(String guildId, String channelId) {
        if (channelId == null || channelId.isBlank()) {
            return;
        }

        GuildState guildState = getOrCreateGuildState(guildId);
        if (guildState.getTickets().getActiveTickets().remove(channelId) != null) {
            persistQuietly(guildId, guildState);
        }
    }

    public synchronized boolean isCommandEnabled(String guildId, String commandName) {
        if (commandName == null || commandName.isBlank()) {
            return true;
        }
        return getOrCreateGuildState(guildId).getCommands().getOrDefault(commandName, Boolean.TRUE);
    }

    public synchronized void saveCommandState(String guildId, String commandName, boolean enabled) {
        if (commandName == null || commandName.isBlank()) {
            return;
        }

        GuildState guildState = getOrCreateGuildState(guildId);
        guildState.getCommands().put(commandName, enabled);
        persistQuietly(guildId, guildState);
    }

    private JoinToCreateEntry normalizeEntry(JoinToCreateEntry entry) {
        return new JoinToCreateEntry(
                entry.getId() == null || entry.getId().isBlank() ? UUID.randomUUID().toString() : entry.getId(),
                blankToEmpty(entry.getSourceChannelId()),
                blankToEmpty(entry.getCategoryId()),
                entry.getNameTemplate() == null || entry.getNameTemplate().isBlank() ? DEFAULT_JOIN_TEMPLATE : entry.getNameTemplate().trim(),
                Math.max(0, Math.min(99, entry.getUserLimit())),
                clampBitrate(entry.getBitrateKbps()),
                Math.max(1, entry.getNextCounter()),
                entry.isSendConfigPrompt()
        );
    }

    private TicketPanel normalizeTicketPanel(TicketPanel panel) {
        TicketPanel normalized = new TicketPanel();
        normalized.setId(panel.getId() == null || panel.getId().isBlank() ? UUID.randomUUID().toString() : panel.getId());
        normalized.setTitle(panel.getTitle() == null || panel.getTitle().isBlank() ? "Support Tickets" : panel.getTitle().trim());
        normalized.setDescription(blankToEmpty(panel.getDescription()));
        normalized.setInteractionMode(normalizeTicketInteractionMode(panel.getInteractionMode()));
        normalized.setPublishChannelId(blankToEmpty(panel.getPublishChannelId()));
        normalized.setCategoryId(blankToEmpty(panel.getCategoryId()));
        normalized.setPlaceholder(panel.getPlaceholder() == null || panel.getPlaceholder().isBlank() ? "Waehle dein Anliegen" : panel.getPlaceholder().trim());
        normalized.setWelcomeMessage(panel.getWelcomeMessage() == null || panel.getWelcomeMessage().isBlank()
                ? "Beschreibe dein Anliegen hier so genau wie moeglich. Ein Teammitglied kuemmert sich zeitnah darum."
                : panel.getWelcomeMessage().trim());
        normalized.setImageUrl(normalizeUrl(panel.getImageUrl()));
        normalized.setThumbnailUrl(normalizeUrl(panel.getThumbnailUrl()));
        normalized.setAccentColor(normalizeColor(panel.getAccentColor()));
        normalized.setNotifyRoleId(blankToEmpty(panel.getNotifyRoleId()));
        normalized.setSupportRoleIds((panel.getSupportRoleIds() == null ? List.<String>of() : panel.getSupportRoleIds()).stream()
                .map(this::blankToEmpty)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList());
        normalized.setAllowClaim(panel.isAllowClaim());
        normalized.setAllowPause(panel.isAllowPause());
        normalized.setAllowCreatorClose(panel.isAllowCreatorClose());
        normalized.setOneTicketPerUser(panel.isOneTicketPerUser());
        normalized.setMessageId(blankToEmpty(panel.getMessageId()));
        normalized.setOptions((panel.getOptions() == null ? List.<TicketOption>of() : panel.getOptions()).stream()
                .filter(Objects::nonNull)
                .map(this::normalizeTicketOption)
                .filter(option -> !option.getLabel().isBlank())
                .toList());
        return normalized;
    }

    private TicketOption normalizeTicketOption(TicketOption option) {
        TicketOption normalized = new TicketOption();
        normalized.setId(option.getId() == null || option.getId().isBlank() ? UUID.randomUUID().toString() : option.getId());
        normalized.setLabel(blankToEmpty(option.getLabel()));
        normalized.setDescription(blankToEmpty(option.getDescription()));
        normalized.setEmoji(blankToEmpty(option.getEmoji()));
        normalized.setChannelNameTemplate(option.getChannelNameTemplate() == null || option.getChannelNameTemplate().isBlank()
                ? "ticket-{label}-{user}"
                : option.getChannelNameTemplate().trim());
        normalized.setSupportRoleIds((option.getSupportRoleIds() == null ? List.<String>of() : option.getSupportRoleIds()).stream()
                .map(this::blankToEmpty)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList());
        return normalized;
    }

    private ReactionRolePanel normalizeReactionRolePanel(ReactionRolePanel panel) {
        ReactionRolePanel normalized = new ReactionRolePanel();
        normalized.setId(panel.getId() == null || panel.getId().isBlank() ? UUID.randomUUID().toString() : panel.getId());
        normalized.setPublishChannelId(blankToEmpty(panel.getPublishChannelId()));
        normalized.setTitle(blankToEmpty(panel.getTitle()));
        normalized.setDescription(blankToEmpty(panel.getDescription()));
        normalized.setImageUrl(normalizeUrl(panel.getImageUrl()));
        normalized.setThumbnailUrl(normalizeUrl(panel.getThumbnailUrl()));
        normalized.setAccentColor(normalizeColor(panel.getAccentColor()));
        normalized.setMessageId(blankToEmpty(panel.getMessageId()));
        normalized.setEntries((panel.getEntries() == null ? List.<ReactionRoleEntry>of() : panel.getEntries()).stream()
                .filter(Objects::nonNull)
                .map(this::normalizeReactionRoleEntry)
                .filter(entry -> !entry.getEmoji().isBlank() && !entry.getRoleIds().isEmpty())
                .toList());
        return normalized;
    }

    private ReactionRoleEntry normalizeReactionRoleEntry(ReactionRoleEntry entry) {
        ReactionRoleEntry normalized = new ReactionRoleEntry();
        normalized.setId(entry.getId() == null || entry.getId().isBlank() ? UUID.randomUUID().toString() : entry.getId());
        normalized.setEmoji(blankToEmpty(entry.getEmoji()));
        normalized.setRoleIds(normalizeStringList(entry.getRoleIds().isEmpty() ? List.of(entry.getRoleId()) : entry.getRoleIds()));
        normalized.setLabel(blankToEmpty(entry.getLabel()));
        normalized.setDescription(blankToEmpty(entry.getDescription()));
        return normalized;
    }

    private ReactionRolePanel parseReactionRolePanel(JsonNode node) {
        ReactionRolePanel panel = new ReactionRolePanel();
        panel.setId(text(node, "id"));
        panel.setPublishChannelId(text(node, "publishChannelId"));
        panel.setTitle(text(node, "title"));
        panel.setDescription(text(node, "description"));
        panel.setImageUrl(text(node, "imageUrl"));
        panel.setThumbnailUrl(text(node, "thumbnailUrl"));
        panel.setAccentColor(text(node, "accentColor"));
        panel.setMessageId(text(node, "messageId"));

        JsonNode entriesNode = node.path("entries");
        if (entriesNode.isArray()) {
            entriesNode.forEach(entryNode -> panel.getEntries().add(parseReactionRoleEntry(entryNode)));
        }
        return normalizeReactionRolePanel(panel);
    }

    private ReactionRoleEntry parseReactionRoleEntry(JsonNode node) {
        ReactionRoleEntry entry = new ReactionRoleEntry();
        entry.setId(text(node, "id"));
        entry.setEmoji(text(node, "emoji"));
        entry.setRoleIds(readStringList(node, "roleIds", text(node, "roleId")));
        entry.setLabel(text(node, "label"));
        entry.setDescription(text(node, "description"));
        return normalizeReactionRoleEntry(entry);
    }

    private List<String> normalizeStringList(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values == null ? List.<String>of() : values) {
            String item = blankToEmpty(value);
            if (!item.isBlank()) {
                normalized.add(item);
            }
        }
        return new ArrayList<>(normalized);
    }

    private List<String> readStringList(JsonNode node, String fieldName, String legacyValue) {
        List<String> values = new ArrayList<>();
        JsonNode arrayNode = node.path(fieldName);
        if (arrayNode.isArray()) {
            arrayNode.forEach(item -> {
                if (item != null && !item.isNull()) {
                    values.add(item.asText(""));
                }
            });
        } else if (legacyValue != null && !legacyValue.isBlank()) {
            values.add(legacyValue);
        }
        return normalizeStringList(values);
    }

    private String normalizeMode(String mode) {
        String normalized = blankToEmpty(mode).toLowerCase();
        return switch (normalized) {
            case "toggle" -> "toggle";
            default -> "mention";
        };
    }

    private int clampDelay(int value) {
        return Math.max(10, Math.min(3600, value <= 0 ? DEFAULT_DELAY_SECONDS : value));
    }

    private int clampBitrate(int value) {
        return Math.max(0, Math.min(384, value));
    }

    private String normalizeUrl(String value) {
        String normalized = blankToEmpty(value);
        return normalized.length() > 500 ? normalized.substring(0, 500) : normalized;
    }

    private String normalizeColor(String value) {
        String normalized = blankToEmpty(value).replace(" ", "");
        if (normalized.isBlank()) {
            return "#78D1FF";
        }
        if (!normalized.startsWith("#")) {
            normalized = "#" + normalized;
        }
        return normalized.matches("^#[0-9a-fA-F]{6}$") ? normalized.toUpperCase() : "#78D1FF";
    }

    private String normalizeTicketInteractionMode(String value) {
        return "buttons".equals(blankToEmpty(value).toLowerCase()) ? "buttons" : "dropdown";
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private synchronized GuildState getOrCreateGuildState(String guildId) {
        return cache.computeIfAbsent(guildId, this::loadGuildState);
    }

    private GuildState loadGuildState(String guildId) {
        if (guildId == null || guildId.isBlank()) {
            return new GuildState();
        }

        if (DB.isAvailable()) {
            String sql = """
                    SELECT settings_json
                    FROM guild_module_settings
                    WHERE bot_id = ? AND guild_id = ?
                    LIMIT 1
                    """;

            try (Connection connection = DB.connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, botId);
                statement.setString(2, guildId);

                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        String json = resultSet.getString("settings_json");
                        if (json != null && !json.isBlank()) {
                            return parseGuildState(objectMapper.readTree(json));
                        }
                    }
                }
            } catch (IOException | SQLException exception) {
                Alert.send("WARN", "MODULES", "Konnte Guild-Settings aus der Datenbank nicht laden: " + exception.getMessage());
            }
        }

        return loadGuildStateFromLegacyFile(guildId).orElseGet(GuildState::new);
    }

    private Optional<GuildState> loadGuildStateFromLegacyFile(String guildId) {
        if (!Files.exists(LEGACY_SETTINGS_PATH)) {
            return Optional.empty();
        }

        try {
            JsonNode rootNode = objectMapper.readTree(Files.readString(LEGACY_SETTINGS_PATH));
            JsonNode guildNode = rootNode.path("guilds").path(guildId);
            if (guildNode.isMissingNode() || guildNode.isNull()) {
                return Optional.empty();
            }
            return Optional.of(parseGuildState(guildNode));
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private void importLegacyFileIfPresent() {
        if (!DB.isAvailable() || !Files.exists(LEGACY_SETTINGS_PATH)) {
            return;
        }

        try {
            JsonNode rootNode = objectMapper.readTree(Files.readString(LEGACY_SETTINGS_PATH));
            JsonNode guildsNode = rootNode.path("guilds");
            if (!guildsNode.isObject()) {
                return;
            }

            guildsNode.fields().forEachRemaining(entry -> {
                String guildId = entry.getKey();
                if (guildId == null || guildId.isBlank() || hasDatabaseState(guildId)) {
                    return;
                }

                GuildState guildState = parseGuildState(entry.getValue());
                cache.put(guildId, guildState);
                persistQuietly(guildId, guildState);
            });
        } catch (IOException exception) {
            Alert.send("WARN", "MODULES", "Legacy guild-modules.json konnte nicht importiert werden.");
        }
    }

    private boolean hasDatabaseState(String guildId) {
        String sql = """
                SELECT 1
                FROM guild_module_settings
                WHERE bot_id = ? AND guild_id = ?
                LIMIT 1
                """;

        try (Connection connection = DB.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, botId);
            statement.setString(2, guildId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException exception) {
            return false;
        }
    }

    private GuildState parseGuildState(JsonNode node) {
        GuildState guildState = new GuildState();
        guildState.setJoinToCreate(parseJoinToCreate(node.path("joinToCreate")));
        guildState.setInviteTracker(parseInviteTracker(node.path("inviteTracker")));
        guildState.setWelcome(parseWelcome(node.path("welcome")));
        guildState.setReactionRoles(parseReactionRoles(node.path("reactionRoles")));
        guildState.setVerify(parseVerify(node.path("verify")));
        guildState.setLlm(parseLlm(node.path("llm")));
        guildState.setTickets(parseTickets(node.path("tickets")));
        guildState.setDiscordLogs(parseDiscordLogs(node.path("discordLogs")));

        JsonNode commandsNode = node.path("commands");
        if (commandsNode.isObject()) {
            commandsNode.fields().forEachRemaining(command -> guildState.getCommands().put(command.getKey(), command.getValue().asBoolean(true)));
        }

        return guildState;
    }

    private JoinToCreateState parseJoinToCreate(JsonNode node) {
        JoinToCreateState state = new JoinToCreateState();
        state.setEnabled(node.path("enabled").asBoolean(false));
        state.setCleanupDelaySeconds(clampDelay(node.path("cleanupDelaySeconds").asInt(DEFAULT_DELAY_SECONDS)));
        state.setAudioIdleTimeoutSeconds(clampDelay(node.path("audioIdleTimeoutSeconds").asInt(DEFAULT_DELAY_SECONDS)));

        JsonNode entriesNode = node.path("entries");
        if (entriesNode.isArray()) {
            entriesNode.forEach(entryNode -> state.getEntries().add(normalizeEntry(new JoinToCreateEntry(
                    text(entryNode, "id"),
                    text(entryNode, "sourceChannelId"),
                    text(entryNode, "categoryId"),
                    text(entryNode, "nameTemplate"),
                    entryNode.path("userLimit").asInt(0),
                    entryNode.path("bitrateKbps").asInt(0),
                    entryNode.path("nextCounter").asInt(1),
                    !entryNode.has("sendConfigPrompt") || entryNode.path("sendConfigPrompt").asBoolean(true)
            ))));
        }

        if (state.getEntries().isEmpty()) {
            String legacySourceChannelId = text(node, "sourceChannelId");
            if (!legacySourceChannelId.isBlank()) {
                state.getEntries().add(normalizeEntry(new JoinToCreateEntry(
                        UUID.randomUUID().toString(),
                        legacySourceChannelId,
                        text(node, "categoryId"),
                        text(node, "nameTemplate"),
                        node.path("userLimit").asInt(0),
                        0,
                        1,
                        true
                )));
            }
        }

        JsonNode managedChannelsNode = node.path("managedChannels");
        if (managedChannelsNode.isObject()) {
            managedChannelsNode.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                ManagedVoiceChannel managedChannel = new ManagedVoiceChannel(
                        entry.getKey(),
                        text(value, "ownerId"),
                        text(value, "sourceEntryId"),
                        text(value, "createdAt").isBlank() ? Instant.now().toString() : text(value, "createdAt"),
                        parseStringSet(value.path("adminUserIds"))
                );
                state.getManagedChannels().put(entry.getKey(), managedChannel);
            });
        }

        JsonNode managedChannelIdsNode = node.path("managedChannelIds");
        if (managedChannelIdsNode.isArray()) {
            managedChannelIdsNode.forEach(channelIdNode -> {
                String channelId = channelIdNode.asText("");
                if (!channelId.isBlank() && !state.getManagedChannels().containsKey(channelId)) {
                    state.getManagedChannels().put(channelId, new ManagedVoiceChannel(channelId, "", "", Instant.now().toString(), new LinkedHashSet<>()));
                }
            });
        }

        return state;
    }

    private InviteTrackerState parseInviteTracker(JsonNode node) {
        InviteTrackerState state = new InviteTrackerState();
        state.setEnabled(node.path("enabled").asBoolean(false));

        JsonNode recentJoinsNode = node.path("recentJoins");
        if (recentJoinsNode.isArray()) {
            recentJoinsNode.forEach(joinNode -> state.getRecentJoins().add(new InviteJoinEntry(
                    text(joinNode, "memberId"),
                    text(joinNode, "memberDisplay"),
                    text(joinNode, "inviteCode"),
                    text(joinNode, "inviterDisplay"),
                    joinNode.has("uses") && !joinNode.get("uses").isNull() ? joinNode.get("uses").asInt() : null,
                    text(joinNode, "joinedAt").isBlank() ? Instant.now().toString() : text(joinNode, "joinedAt")
            )));
        }

        return state;
    }

    private WelcomeState parseWelcome(JsonNode node) {
        WelcomeState state = new WelcomeState();
        state.setEnabled(node.path("enabled").asBoolean(false));
        state.setRoleIds(readStringList(node, "roleIds", text(node, "roleId")));
        state.setChannelId(text(node, "channelId"));
        state.setWelcomeText(text(node, "welcomeText"));
        state.setSendImage(node.path("sendImage").asBoolean(false));
        state.setBackgroundImageUrl(text(node, "backgroundImageUrl"));
        state.setAccentColor(text(node, "accentColor"));
        return state;
    }

    private ReactionRoleState parseReactionRoles(JsonNode node) {
        ReactionRoleState state = new ReactionRoleState();
        state.setEnabled(node.path("enabled").asBoolean(false));
        JsonNode panelsNode = node.path("panels");
        if (panelsNode.isArray()) {
            panelsNode.forEach(panelNode -> state.getPanels().add(parseReactionRolePanel(panelNode)));
        } else {
            ReactionRolePanel legacyPanel = new ReactionRolePanel();
            legacyPanel.setPublishChannelId(text(node, "publishChannelId"));
            legacyPanel.setTitle(text(node, "title"));
            legacyPanel.setDescription(text(node, "description"));
            legacyPanel.setImageUrl(text(node, "imageUrl"));
            legacyPanel.setThumbnailUrl(text(node, "thumbnailUrl"));
            legacyPanel.setAccentColor(text(node, "accentColor"));
            legacyPanel.setMessageId(text(node, "messageId"));

            JsonNode entriesNode = node.path("entries");
            if (entriesNode.isArray()) {
                entriesNode.forEach(entryNode -> legacyPanel.getEntries().add(parseReactionRoleEntry(entryNode)));
            }

            if (!legacyPanel.getPublishChannelId().isBlank() || !legacyPanel.getEntries().isEmpty()) {
                state.getPanels().add(normalizeReactionRolePanel(legacyPanel));
            }
        }
        return state;
    }

    private VerifyState parseVerify(JsonNode node) {
        VerifyState state = new VerifyState();
        state.setEnabled(node.path("enabled").asBoolean(false));
        state.setPublishChannelId(text(node, "publishChannelId"));
        state.setVerifiedRoleIds(readStringList(node, "verifiedRoleIds", text(node, "verifiedRoleId")));
        state.setTitle(text(node, "title"));
        state.setDescription(text(node, "description"));
        state.setImageUrl(text(node, "imageUrl"));
        state.setThumbnailUrl(text(node, "thumbnailUrl"));
        state.setAccentColor(text(node, "accentColor"));
        state.setMessageId(text(node, "messageId"));
        return state;
    }

    private LlmState parseLlm(JsonNode node) {
        LlmState state = new LlmState();
        state.setEnabled(node.path("enabled").asBoolean(false));
        state.setTextChannelId(text(node, "textChannelId"));
        state.setModel(text(node, "model"));
        state.setMode(normalizeMode(text(node, "mode")));
        state.setSystemPrompt(text(node, "systemPrompt"));
        state.setMaxReplyChars(Math.max(80, Math.min(1800, node.path("maxReplyChars").asInt(260))));
        return state;
    }

    private DiscordLogState parseDiscordLogs(JsonNode node) {
        DiscordLogState state = new DiscordLogState();
        state.setEnabled(node.path("enabled").asBoolean(false));
        state.setTextChannelId(text(node, "textChannelId"));
        state.setMemberJoin(!node.has("memberJoin") || node.path("memberJoin").asBoolean(true));
        state.setMemberLeave(!node.has("memberLeave") || node.path("memberLeave").asBoolean(true));
        state.setVoiceJoin(node.path("voiceJoin").asBoolean(false));
        state.setVoiceLeave(node.path("voiceLeave").asBoolean(false));
        state.setMusic(!node.has("music") || node.path("music").asBoolean(true));
        state.setModeration(node.path("moderation").asBoolean(false));
        state.setRoleUpdates(node.path("roleUpdates").asBoolean(false));
        state.setNicknameUpdates(node.path("nicknameUpdates").asBoolean(false));
        state.setTimeouts(node.path("timeouts").asBoolean(false));
        state.setKicks(node.path("kicks").asBoolean(false));
        state.setBans(node.path("bans").asBoolean(false));
        state.setMessageDeletes(node.path("messageDeletes").asBoolean(false));
        state.setVoiceModeration(node.path("voiceModeration").asBoolean(false));
        state.setCommands(node.path("commands").asBoolean(false));
        return state;
    }

    private TicketSystemState parseTickets(JsonNode node) {
        TicketSystemState state = new TicketSystemState();
        state.setEnabled(node.path("enabled").asBoolean(false));
        state.setTranscriptChannelId(text(node, "transcriptChannelId"));

        JsonNode panelsNode = node.path("panels");
        if (panelsNode.isArray()) {
            panelsNode.forEach(panelNode -> {
                TicketPanel panel = new TicketPanel();
                panel.setId(text(panelNode, "id"));
                panel.setTitle(text(panelNode, "title"));
                panel.setDescription(text(panelNode, "description"));
                panel.setInteractionMode(text(panelNode, "interactionMode"));
                panel.setPublishChannelId(text(panelNode, "publishChannelId"));
                panel.setCategoryId(text(panelNode, "categoryId"));
                panel.setPlaceholder(text(panelNode, "placeholder"));
                panel.setWelcomeMessage(text(panelNode, "welcomeMessage"));
                panel.setImageUrl(text(panelNode, "imageUrl"));
                panel.setThumbnailUrl(text(panelNode, "thumbnailUrl"));
                panel.setAccentColor(text(panelNode, "accentColor"));
                panel.setNotifyRoleId(text(panelNode, "notifyRoleId"));
                panel.setSupportRoleIds(parseStringList(panelNode.path("supportRoleIds")));
                panel.setAllowClaim(!panelNode.has("allowClaim") || panelNode.path("allowClaim").asBoolean(true));
                panel.setAllowPause(!panelNode.has("allowPause") || panelNode.path("allowPause").asBoolean(true));
                panel.setAllowCreatorClose(!panelNode.has("allowCreatorClose") || panelNode.path("allowCreatorClose").asBoolean(true));
                panel.setOneTicketPerUser(panelNode.path("oneTicketPerUser").asBoolean(false));
                panel.setMessageId(text(panelNode, "messageId"));

                JsonNode optionsNode = panelNode.path("options");
                if (optionsNode.isArray()) {
                    optionsNode.forEach(optionNode -> {
                        TicketOption option = new TicketOption();
                        option.setId(text(optionNode, "id"));
                        option.setLabel(text(optionNode, "label"));
                        option.setDescription(text(optionNode, "description"));
                        option.setEmoji(text(optionNode, "emoji"));
                        option.setChannelNameTemplate(text(optionNode, "channelNameTemplate"));
                        option.setSupportRoleIds(parseStringList(optionNode.path("supportRoleIds")));
                        panel.getOptions().add(normalizeTicketOption(option));
                    });
                }

                state.getPanels().add(normalizeTicketPanel(panel));
            });
        }

        JsonNode activeTicketsNode = node.path("activeTickets");
        if (activeTicketsNode.isObject()) {
            activeTicketsNode.fields().forEachRemaining(entry -> {
                JsonNode ticketNode = entry.getValue();
                ActiveTicket ticket = new ActiveTicket();
                ticket.setChannelId(entry.getKey());
                ticket.setOpenerUserId(text(ticketNode, "openerUserId"));
                ticket.setOpenerDisplay(text(ticketNode, "openerDisplay"));
                ticket.setPanelId(text(ticketNode, "panelId"));
                ticket.setOptionId(text(ticketNode, "optionId"));
                ticket.setOptionLabel(text(ticketNode, "optionLabel"));
                ticket.setClaimedByUserId(text(ticketNode, "claimedByUserId"));
                ticket.setClaimedByDisplay(text(ticketNode, "claimedByDisplay"));
                ticket.setPaused(ticketNode.path("paused").asBoolean(false));
                ticket.setControlMessageId(text(ticketNode, "controlMessageId"));
                ticket.setCreatedAt(text(ticketNode, "createdAt").isBlank() ? Instant.now().toString() : text(ticketNode, "createdAt"));
                state.getActiveTickets().put(entry.getKey(), ticket);
            });
        }

        return state;
    }

    private String text(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private Set<String> parseStringSet(JsonNode node) {
        Set<String> values = new LinkedHashSet<>();
        if (!node.isArray()) {
            return values;
        }

        node.forEach(value -> {
            String text = value == null || value.isNull() ? "" : value.asText("");
            if (!text.isBlank()) {
                values.add(text);
            }
        });
        return values;
    }

    private List<String> parseStringList(JsonNode node) {
        return new ArrayList<>(parseStringSet(node));
    }

    private void persistQuietly(String guildId, GuildState state) {
        cache.put(guildId, state);

        if (!DB.isAvailable()) {
            Alert.send("WARN", "MODULES", "Datenbank nicht erreichbar, Guild-Settings fuer " + guildId + " nur im Speicher gehalten.");
            return;
        }

        String sql = """
                INSERT INTO guild_module_settings (bot_id, guild_id, settings_json)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE settings_json = VALUES(settings_json), updated_at = current_timestamp()
                """;

        try (Connection connection = DB.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, botId);
            statement.setString(2, guildId);
            statement.setString(3, objectMapper.writeValueAsString(state));
            statement.executeUpdate();
        } catch (IOException | SQLException exception) {
            Alert.send("WARN", "MODULES", "Konnte Guild-Settings nicht speichern: " + exception.getMessage());
        }
    }

    public static class GuildState {

        private JoinToCreateState joinToCreate = new JoinToCreateState();
        private InviteTrackerState inviteTracker = new InviteTrackerState();
        private WelcomeState welcome = new WelcomeState();
        private ReactionRoleState reactionRoles = new ReactionRoleState();
        private VerifyState verify = new VerifyState();
        private LlmState llm = new LlmState();
        private TicketSystemState tickets = new TicketSystemState();
        private DiscordLogState discordLogs = new DiscordLogState();
        private Map<String, Boolean> commands = new LinkedHashMap<>();

        public JoinToCreateState getJoinToCreate() {
            return joinToCreate;
        }

        public void setJoinToCreate(JoinToCreateState joinToCreate) {
            this.joinToCreate = joinToCreate == null ? new JoinToCreateState() : joinToCreate;
        }

        public InviteTrackerState getInviteTracker() {
            return inviteTracker;
        }

        public void setInviteTracker(InviteTrackerState inviteTracker) {
            this.inviteTracker = inviteTracker == null ? new InviteTrackerState() : inviteTracker;
        }

        public WelcomeState getWelcome() {
            return welcome;
        }

        public void setWelcome(WelcomeState welcome) {
            this.welcome = welcome == null ? new WelcomeState() : welcome;
        }

        public ReactionRoleState getReactionRoles() {
            return reactionRoles;
        }

        public void setReactionRoles(ReactionRoleState reactionRoles) {
            this.reactionRoles = reactionRoles == null ? new ReactionRoleState() : reactionRoles;
        }

        public VerifyState getVerify() {
            return verify;
        }

        public void setVerify(VerifyState verify) {
            this.verify = verify == null ? new VerifyState() : verify;
        }

        public LlmState getLlm() {
            return llm;
        }

        public void setLlm(LlmState llm) {
            this.llm = llm == null ? new LlmState() : llm;
        }

        public TicketSystemState getTickets() {
            return tickets;
        }

        public void setTickets(TicketSystemState tickets) {
            this.tickets = tickets == null ? new TicketSystemState() : tickets;
        }

        public DiscordLogState getDiscordLogs() {
            return discordLogs;
        }

        public void setDiscordLogs(DiscordLogState discordLogs) {
            this.discordLogs = discordLogs == null ? new DiscordLogState() : discordLogs;
        }

        public Map<String, Boolean> getCommands() {
            return commands;
        }

        public void setCommands(Map<String, Boolean> commands) {
            this.commands = commands == null ? new LinkedHashMap<>() : commands;
        }
    }

    public static class JoinToCreateState {

        private boolean enabled;
        private int cleanupDelaySeconds = DEFAULT_DELAY_SECONDS;
        private int audioIdleTimeoutSeconds = DEFAULT_DELAY_SECONDS;
        private List<JoinToCreateEntry> entries = new ArrayList<>();
        private Map<String, ManagedVoiceChannel> managedChannels = new LinkedHashMap<>();

        public JoinToCreateState copy() {
            JoinToCreateState copy = new JoinToCreateState();
            copy.setEnabled(enabled);
            copy.setCleanupDelaySeconds(cleanupDelaySeconds);
            copy.setAudioIdleTimeoutSeconds(audioIdleTimeoutSeconds);
            copy.setEntries(entries.stream().map(JoinToCreateEntry::copy).toList());
            Map<String, ManagedVoiceChannel> managedCopies = new LinkedHashMap<>();
            managedChannels.forEach((channelId, managedChannel) -> managedCopies.put(channelId, managedChannel.copy()));
            copy.setManagedChannels(managedCopies);
            return copy;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getCleanupDelaySeconds() {
            return cleanupDelaySeconds;
        }

        public void setCleanupDelaySeconds(int cleanupDelaySeconds) {
            this.cleanupDelaySeconds = cleanupDelaySeconds;
        }

        public int getAudioIdleTimeoutSeconds() {
            return audioIdleTimeoutSeconds;
        }

        public void setAudioIdleTimeoutSeconds(int audioIdleTimeoutSeconds) {
            this.audioIdleTimeoutSeconds = audioIdleTimeoutSeconds;
        }

        public List<JoinToCreateEntry> getEntries() {
            return entries;
        }

        public void setEntries(List<JoinToCreateEntry> entries) {
            this.entries = entries == null ? new ArrayList<>() : entries;
        }

        public Map<String, ManagedVoiceChannel> getManagedChannels() {
            return managedChannels;
        }

        public void setManagedChannels(Map<String, ManagedVoiceChannel> managedChannels) {
            this.managedChannels = managedChannels == null ? new LinkedHashMap<>() : managedChannels;
        }
    }

    public static class JoinToCreateEntry {

        private String id = "";
        private String sourceChannelId = "";
        private String categoryId = "";
        private String nameTemplate = DEFAULT_JOIN_TEMPLATE;
        private int userLimit;
        private int bitrateKbps;
        private int nextCounter = 1;
        private boolean sendConfigPrompt = true;

        public JoinToCreateEntry() {
        }

        public JoinToCreateEntry(
                String id,
                String sourceChannelId,
                String categoryId,
                String nameTemplate,
                int userLimit,
                int bitrateKbps,
                int nextCounter,
                boolean sendConfigPrompt
        ) {
            this.id = id == null ? "" : id;
            this.sourceChannelId = sourceChannelId == null ? "" : sourceChannelId;
            this.categoryId = categoryId == null ? "" : categoryId;
            this.nameTemplate = nameTemplate == null ? DEFAULT_JOIN_TEMPLATE : nameTemplate;
            this.userLimit = userLimit;
            this.bitrateKbps = bitrateKbps;
            this.nextCounter = nextCounter;
            this.sendConfigPrompt = sendConfigPrompt;
        }

        public JoinToCreateEntry copy() {
            return new JoinToCreateEntry(id, sourceChannelId, categoryId, nameTemplate, userLimit, bitrateKbps, nextCounter, sendConfigPrompt);
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getSourceChannelId() {
            return sourceChannelId;
        }

        public void setSourceChannelId(String sourceChannelId) {
            this.sourceChannelId = sourceChannelId;
        }

        public String getCategoryId() {
            return categoryId;
        }

        public void setCategoryId(String categoryId) {
            this.categoryId = categoryId;
        }

        public String getNameTemplate() {
            return nameTemplate;
        }

        public void setNameTemplate(String nameTemplate) {
            this.nameTemplate = nameTemplate;
        }

        public int getUserLimit() {
            return userLimit;
        }

        public void setUserLimit(int userLimit) {
            this.userLimit = userLimit;
        }

        public int getBitrateKbps() {
            return bitrateKbps;
        }

        public void setBitrateKbps(int bitrateKbps) {
            this.bitrateKbps = bitrateKbps;
        }

        public int getNextCounter() {
            return nextCounter;
        }

        public void setNextCounter(int nextCounter) {
            this.nextCounter = nextCounter;
        }

        public boolean isSendConfigPrompt() {
            return sendConfigPrompt;
        }

        public void setSendConfigPrompt(boolean sendConfigPrompt) {
            this.sendConfigPrompt = sendConfigPrompt;
        }
    }

    public static class ManagedVoiceChannel {

        private String channelId = "";
        private String ownerId = "";
        private String sourceEntryId = "";
        private String createdAt = Instant.now().toString();
        private Set<String> adminUserIds = new LinkedHashSet<>();

        public ManagedVoiceChannel() {
        }

        public ManagedVoiceChannel(String channelId, String ownerId, String sourceEntryId, String createdAt, Set<String> adminUserIds) {
            this.channelId = channelId == null ? "" : channelId;
            this.ownerId = ownerId == null ? "" : ownerId;
            this.sourceEntryId = sourceEntryId == null ? "" : sourceEntryId;
            this.createdAt = createdAt == null || createdAt.isBlank() ? Instant.now().toString() : createdAt;
            this.adminUserIds = adminUserIds == null ? new LinkedHashSet<>() : new LinkedHashSet<>(adminUserIds);
        }

        public ManagedVoiceChannel copy() {
            return new ManagedVoiceChannel(channelId, ownerId, sourceEntryId, createdAt, adminUserIds);
        }

        public String getChannelId() {
            return channelId;
        }

        public void setChannelId(String channelId) {
            this.channelId = channelId;
        }

        public String getOwnerId() {
            return ownerId;
        }

        public void setOwnerId(String ownerId) {
            this.ownerId = ownerId;
        }

        public String getSourceEntryId() {
            return sourceEntryId;
        }

        public void setSourceEntryId(String sourceEntryId) {
            this.sourceEntryId = sourceEntryId;
        }

        public String getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(String createdAt) {
            this.createdAt = createdAt;
        }

        public Set<String> getAdminUserIds() {
            return adminUserIds;
        }

        public void setAdminUserIds(Set<String> adminUserIds) {
            this.adminUserIds = adminUserIds == null ? new LinkedHashSet<>() : new LinkedHashSet<>(adminUserIds);
        }
    }

    public static class InviteTrackerState {

        private boolean enabled;
        private List<InviteJoinEntry> recentJoins = new ArrayList<>();

        public InviteTrackerState copy() {
            InviteTrackerState copy = new InviteTrackerState();
            copy.setEnabled(enabled);
            copy.setRecentJoins(recentJoins.stream().map(InviteJoinEntry::copy).toList());
            return copy;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<InviteJoinEntry> getRecentJoins() {
            return recentJoins;
        }

        public void setRecentJoins(List<InviteJoinEntry> recentJoins) {
            this.recentJoins = recentJoins == null ? new ArrayList<>() : recentJoins;
        }
    }

    public static class InviteJoinEntry {

        private String memberId = "";
        private String memberDisplay = "Unbekannt";
        private String inviteCode = "unbekannt";
        private String inviterDisplay = "Unbekannt";
        private Integer uses;
        private String joinedAt = Instant.now().toString();

        public InviteJoinEntry() {
        }

        public InviteJoinEntry(
                String memberId,
                String memberDisplay,
                String inviteCode,
                String inviterDisplay,
                Integer uses,
                String joinedAt
        ) {
            this.memberId = memberId == null ? "" : memberId;
            this.memberDisplay = memberDisplay == null ? "Unbekannt" : memberDisplay;
            this.inviteCode = inviteCode == null ? "unbekannt" : inviteCode;
            this.inviterDisplay = inviterDisplay == null ? "Unbekannt" : inviterDisplay;
            this.uses = uses;
            this.joinedAt = joinedAt == null || joinedAt.isBlank() ? Instant.now().toString() : joinedAt;
        }

        public InviteJoinEntry copy() {
            return new InviteJoinEntry(memberId, memberDisplay, inviteCode, inviterDisplay, uses, joinedAt);
        }

        public String getMemberId() {
            return memberId;
        }

        public void setMemberId(String memberId) {
            this.memberId = memberId;
        }

        public String getMemberDisplay() {
            return memberDisplay;
        }

        public void setMemberDisplay(String memberDisplay) {
            this.memberDisplay = memberDisplay;
        }

        public String getInviteCode() {
            return inviteCode;
        }

        public void setInviteCode(String inviteCode) {
            this.inviteCode = inviteCode;
        }

        public String getInviterDisplay() {
            return inviterDisplay;
        }

        public void setInviterDisplay(String inviterDisplay) {
            this.inviterDisplay = inviterDisplay;
        }

        public Integer getUses() {
            return uses;
        }

        public void setUses(Integer uses) {
            this.uses = uses;
        }

        public String getJoinedAt() {
            return joinedAt;
        }

        public void setJoinedAt(String joinedAt) {
            this.joinedAt = joinedAt;
        }
    }

    public static class WelcomeState {

        private boolean enabled;
        private List<String> roleIds = new ArrayList<>();
        private String channelId = "";
        private String welcomeText = "Willkommen {user} auf **{guild}**.";
        private boolean sendImage;
        private String backgroundImageUrl = "";
        private String accentColor = "#78D1FF";

        public WelcomeState copy() {
            WelcomeState copy = new WelcomeState();
            copy.setEnabled(enabled);
            copy.setRoleIds(roleIds);
            copy.setChannelId(channelId);
            copy.setWelcomeText(welcomeText);
            copy.setSendImage(sendImage);
            copy.setBackgroundImageUrl(backgroundImageUrl);
            copy.setAccentColor(accentColor);
            return copy;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getRoleIds() {
            return roleIds;
        }

        public void setRoleIds(List<String> roleIds) {
            this.roleIds = roleIds == null ? new ArrayList<>() : new ArrayList<>(roleIds);
        }

        public String getChannelId() {
            return channelId;
        }

        public void setChannelId(String channelId) {
            this.channelId = channelId == null ? "" : channelId;
        }

        public String getWelcomeText() {
            return welcomeText;
        }

        public void setWelcomeText(String welcomeText) {
            this.welcomeText = welcomeText == null || welcomeText.isBlank()
                    ? "Willkommen {user} auf **{guild}**."
                    : welcomeText;
        }

        public boolean isSendImage() {
            return sendImage;
        }

        public void setSendImage(boolean sendImage) {
            this.sendImage = sendImage;
        }

        public String getBackgroundImageUrl() {
            return backgroundImageUrl;
        }

        public void setBackgroundImageUrl(String backgroundImageUrl) {
            this.backgroundImageUrl = backgroundImageUrl == null ? "" : backgroundImageUrl;
        }

        public String getAccentColor() {
            return accentColor;
        }

        public void setAccentColor(String accentColor) {
            this.accentColor = accentColor == null || accentColor.isBlank() ? "#78D1FF" : accentColor;
        }
    }

    public static class ReactionRoleState {

        private boolean enabled;
        private List<ReactionRolePanel> panels = new ArrayList<>();

        public ReactionRoleState copy() {
            ReactionRoleState copy = new ReactionRoleState();
            copy.setEnabled(enabled);
            copy.setPanels(panels.stream().map(ReactionRolePanel::copy).toList());
            return copy;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<ReactionRolePanel> getPanels() {
            return panels;
        }

        public void setPanels(List<ReactionRolePanel> panels) {
            this.panels = panels == null ? new ArrayList<>() : new ArrayList<>(panels);
        }
    }

    public static class ReactionRolePanel {

        private String id = "";
        private String publishChannelId = "";
        private String title = "Reaction Roles";
        private String description = "Reagiere mit dem passenden Emoji, um deine Rolle zu erhalten.";
        private String imageUrl = "";
        private String thumbnailUrl = "";
        private String accentColor = "#78D1FF";
        private String messageId = "";
        private List<ReactionRoleEntry> entries = new ArrayList<>();

        public ReactionRolePanel copy() {
            ReactionRolePanel copy = new ReactionRolePanel();
            copy.setId(id);
            copy.setPublishChannelId(publishChannelId);
            copy.setTitle(title);
            copy.setDescription(description);
            copy.setImageUrl(imageUrl);
            copy.setThumbnailUrl(thumbnailUrl);
            copy.setAccentColor(accentColor);
            copy.setMessageId(messageId);
            copy.setEntries(entries.stream().map(ReactionRoleEntry::copy).toList());
            return copy;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id == null ? "" : id;
        }

        public String getPublishChannelId() {
            return publishChannelId;
        }

        public void setPublishChannelId(String publishChannelId) {
            this.publishChannelId = publishChannelId == null ? "" : publishChannelId;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title == null || title.isBlank() ? "Reaction Roles" : title;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description == null ? "" : description;
        }

        public String getImageUrl() {
            return imageUrl;
        }

        public void setImageUrl(String imageUrl) {
            this.imageUrl = imageUrl == null ? "" : imageUrl;
        }

        public String getThumbnailUrl() {
            return thumbnailUrl;
        }

        public void setThumbnailUrl(String thumbnailUrl) {
            this.thumbnailUrl = thumbnailUrl == null ? "" : thumbnailUrl;
        }

        public String getAccentColor() {
            return accentColor;
        }

        public void setAccentColor(String accentColor) {
            this.accentColor = accentColor == null || accentColor.isBlank() ? "#78D1FF" : accentColor;
        }

        public String getMessageId() {
            return messageId;
        }

        public void setMessageId(String messageId) {
            this.messageId = messageId == null ? "" : messageId;
        }

        public List<ReactionRoleEntry> getEntries() {
            return entries;
        }

        public void setEntries(List<ReactionRoleEntry> entries) {
            this.entries = entries == null ? new ArrayList<>() : new ArrayList<>(entries);
        }
    }

    public static class ReactionRoleEntry {

        private String id = "";
        private String emoji = "";
        private String roleId = "";
        private List<String> roleIds = new ArrayList<>();
        private String label = "";
        private String description = "";

        public ReactionRoleEntry copy() {
            ReactionRoleEntry copy = new ReactionRoleEntry();
            copy.setId(id);
            copy.setEmoji(emoji);
            copy.setRoleId(roleId);
            copy.setRoleIds(roleIds);
            copy.setLabel(label);
            copy.setDescription(description);
            return copy;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id == null ? "" : id;
        }

        public String getEmoji() {
            return emoji;
        }

        public void setEmoji(String emoji) {
            this.emoji = emoji == null ? "" : emoji;
        }

        public String getRoleId() {
            return roleId;
        }

        public void setRoleId(String roleId) {
            this.roleId = roleId == null ? "" : roleId;
        }

        public List<String> getRoleIds() {
            return roleIds;
        }

        public void setRoleIds(List<String> roleIds) {
            this.roleIds = roleIds == null ? new ArrayList<>() : new ArrayList<>(roleIds);
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label == null ? "" : label;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description == null ? "" : description;
        }
    }

    public static class VerifyState {

        private boolean enabled;
        private String publishChannelId = "";
        private List<String> verifiedRoleIds = new ArrayList<>();
        private String title = "Server-Verifizierung";
        private String description = "Klicke auf den Button und gib den Code ein, um dich zu verifizieren.";
        private String imageUrl = "";
        private String thumbnailUrl = "";
        private String accentColor = "#78D1FF";
        private String messageId = "";

        public VerifyState copy() {
            VerifyState copy = new VerifyState();
            copy.setEnabled(enabled);
            copy.setPublishChannelId(publishChannelId);
            copy.setVerifiedRoleIds(verifiedRoleIds);
            copy.setTitle(title);
            copy.setDescription(description);
            copy.setImageUrl(imageUrl);
            copy.setThumbnailUrl(thumbnailUrl);
            copy.setAccentColor(accentColor);
            copy.setMessageId(messageId);
            return copy;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getPublishChannelId() {
            return publishChannelId;
        }

        public void setPublishChannelId(String publishChannelId) {
            this.publishChannelId = publishChannelId == null ? "" : publishChannelId;
        }

        public List<String> getVerifiedRoleIds() {
            return verifiedRoleIds;
        }

        public void setVerifiedRoleIds(List<String> verifiedRoleIds) {
            this.verifiedRoleIds = verifiedRoleIds == null ? new ArrayList<>() : new ArrayList<>(verifiedRoleIds);
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title == null || title.isBlank() ? "Server-Verifizierung" : title;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description == null || description.isBlank()
                    ? "Klicke auf den Button und gib den Code ein, um dich zu verifizieren."
                    : description;
        }

        public String getImageUrl() {
            return imageUrl;
        }

        public void setImageUrl(String imageUrl) {
            this.imageUrl = imageUrl == null ? "" : imageUrl;
        }

        public String getThumbnailUrl() {
            return thumbnailUrl;
        }

        public void setThumbnailUrl(String thumbnailUrl) {
            this.thumbnailUrl = thumbnailUrl == null ? "" : thumbnailUrl;
        }

        public String getAccentColor() {
            return accentColor;
        }

        public void setAccentColor(String accentColor) {
            this.accentColor = accentColor == null || accentColor.isBlank() ? "#78D1FF" : accentColor;
        }

        public String getMessageId() {
            return messageId;
        }

        public void setMessageId(String messageId) {
            this.messageId = messageId == null ? "" : messageId;
        }
    }

    public static class LlmState {

        private boolean enabled;
        private String textChannelId = "";
        private String model = "";
        private String mode = "mention";
        private String systemPrompt = "";
        private int maxReplyChars = 260;

        public LlmState copy() {
            LlmState copy = new LlmState();
            copy.setEnabled(enabled);
            copy.setTextChannelId(textChannelId);
            copy.setModel(model);
            copy.setMode(mode);
            copy.setSystemPrompt(systemPrompt);
            copy.setMaxReplyChars(maxReplyChars);
            return copy;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getTextChannelId() {
            return textChannelId;
        }

        public void setTextChannelId(String textChannelId) {
            this.textChannelId = textChannelId == null ? "" : textChannelId;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model == null ? "" : model.trim();
        }

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode == null || mode.isBlank() ? "mention" : mode;
        }

        public String getSystemPrompt() {
            return systemPrompt;
        }

        public void setSystemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
        }

        public int getMaxReplyChars() {
            return maxReplyChars;
        }

        public void setMaxReplyChars(int maxReplyChars) {
            this.maxReplyChars = maxReplyChars;
        }
    }

    public static class TicketSystemState {

        private boolean enabled;
        private String transcriptChannelId = "";
        private List<TicketPanel> panels = new ArrayList<>();
        private Map<String, ActiveTicket> activeTickets = new LinkedHashMap<>();

        public TicketSystemState copy() {
            TicketSystemState copy = new TicketSystemState();
            copy.setEnabled(enabled);
            copy.setTranscriptChannelId(transcriptChannelId);
            copy.setPanels(panels.stream().map(TicketPanel::copy).toList());
            Map<String, ActiveTicket> activeCopies = new LinkedHashMap<>();
            activeTickets.forEach((channelId, ticket) -> activeCopies.put(channelId, ticket.copy()));
            copy.setActiveTickets(activeCopies);
            return copy;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getTranscriptChannelId() {
            return transcriptChannelId;
        }

        public void setTranscriptChannelId(String transcriptChannelId) {
            this.transcriptChannelId = transcriptChannelId == null ? "" : transcriptChannelId;
        }

        public List<TicketPanel> getPanels() {
            return panels;
        }

        public void setPanels(List<TicketPanel> panels) {
            this.panels = panels == null ? new ArrayList<>() : panels;
        }

        public Map<String, ActiveTicket> getActiveTickets() {
            return activeTickets;
        }

        public void setActiveTickets(Map<String, ActiveTicket> activeTickets) {
            this.activeTickets = activeTickets == null ? new LinkedHashMap<>() : activeTickets;
        }
    }

    public static class TicketPanel {

        private String id = "";
        private String title = "Support Tickets";
        private String description = "";
        private String interactionMode = "dropdown";
        private String publishChannelId = "";
        private String categoryId = "";
        private String placeholder = "Waehle dein Anliegen";
        private String welcomeMessage = "Beschreibe dein Anliegen hier so genau wie moeglich. Ein Teammitglied kuemmert sich zeitnah darum.";
        private String imageUrl = "";
        private String thumbnailUrl = "";
        private String accentColor = "#78D1FF";
        private String notifyRoleId = "";
        private List<String> supportRoleIds = new ArrayList<>();
        private boolean allowClaim = true;
        private boolean allowPause = true;
        private boolean allowCreatorClose = true;
        private boolean oneTicketPerUser;
        private String messageId = "";
        private List<TicketOption> options = new ArrayList<>();

        public TicketPanel copy() {
            TicketPanel copy = new TicketPanel();
            copy.setId(id);
            copy.setTitle(title);
            copy.setDescription(description);
            copy.setInteractionMode(interactionMode);
            copy.setPublishChannelId(publishChannelId);
            copy.setCategoryId(categoryId);
            copy.setPlaceholder(placeholder);
            copy.setWelcomeMessage(welcomeMessage);
            copy.setImageUrl(imageUrl);
            copy.setThumbnailUrl(thumbnailUrl);
            copy.setAccentColor(accentColor);
            copy.setNotifyRoleId(notifyRoleId);
            copy.setSupportRoleIds(supportRoleIds);
            copy.setAllowClaim(allowClaim);
            copy.setAllowPause(allowPause);
            copy.setAllowCreatorClose(allowCreatorClose);
            copy.setOneTicketPerUser(oneTicketPerUser);
            copy.setMessageId(messageId);
            copy.setOptions(options.stream().map(TicketOption::copy).toList());
            return copy;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id == null ? "" : id;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title == null ? "Support Tickets" : title;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description == null ? "" : description;
        }

        public String getInteractionMode() {
            return interactionMode;
        }

        public void setInteractionMode(String interactionMode) {
            this.interactionMode = interactionMode == null ? "dropdown" : interactionMode;
        }

        public String getPublishChannelId() {
            return publishChannelId;
        }

        public void setPublishChannelId(String publishChannelId) {
            this.publishChannelId = publishChannelId == null ? "" : publishChannelId;
        }

        public String getCategoryId() {
            return categoryId;
        }

        public void setCategoryId(String categoryId) {
            this.categoryId = categoryId == null ? "" : categoryId;
        }

        public String getPlaceholder() {
            return placeholder;
        }

        public void setPlaceholder(String placeholder) {
            this.placeholder = placeholder == null ? "Waehle dein Anliegen" : placeholder;
        }

        public String getWelcomeMessage() {
            return welcomeMessage;
        }

        public void setWelcomeMessage(String welcomeMessage) {
            this.welcomeMessage = welcomeMessage == null ? "" : welcomeMessage;
        }

        public String getImageUrl() {
            return imageUrl;
        }

        public void setImageUrl(String imageUrl) {
            this.imageUrl = imageUrl == null ? "" : imageUrl;
        }

        public String getThumbnailUrl() {
            return thumbnailUrl;
        }

        public void setThumbnailUrl(String thumbnailUrl) {
            this.thumbnailUrl = thumbnailUrl == null ? "" : thumbnailUrl;
        }

        public String getAccentColor() {
            return accentColor;
        }

        public void setAccentColor(String accentColor) {
            this.accentColor = accentColor == null ? "#78D1FF" : accentColor;
        }

        public String getNotifyRoleId() {
            return notifyRoleId;
        }

        public void setNotifyRoleId(String notifyRoleId) {
            this.notifyRoleId = notifyRoleId == null ? "" : notifyRoleId;
        }

        public List<String> getSupportRoleIds() {
            return supportRoleIds;
        }

        public void setSupportRoleIds(List<String> supportRoleIds) {
            this.supportRoleIds = supportRoleIds == null ? new ArrayList<>() : new ArrayList<>(supportRoleIds);
        }

        public boolean isAllowClaim() {
            return allowClaim;
        }

        public void setAllowClaim(boolean allowClaim) {
            this.allowClaim = allowClaim;
        }

        public boolean isAllowPause() {
            return allowPause;
        }

        public void setAllowPause(boolean allowPause) {
            this.allowPause = allowPause;
        }

        public boolean isAllowCreatorClose() {
            return allowCreatorClose;
        }

        public void setAllowCreatorClose(boolean allowCreatorClose) {
            this.allowCreatorClose = allowCreatorClose;
        }

        public boolean isOneTicketPerUser() {
            return oneTicketPerUser;
        }

        public void setOneTicketPerUser(boolean oneTicketPerUser) {
            this.oneTicketPerUser = oneTicketPerUser;
        }

        public String getMessageId() {
            return messageId;
        }

        public void setMessageId(String messageId) {
            this.messageId = messageId == null ? "" : messageId;
        }

        public List<TicketOption> getOptions() {
            return options;
        }

        public void setOptions(List<TicketOption> options) {
            this.options = options == null ? new ArrayList<>() : options;
        }
    }

    public static class TicketOption {

        private String id = "";
        private String label = "";
        private String description = "";
        private String emoji = "";
        private String channelNameTemplate = "ticket-{label}-{user}";
        private List<String> supportRoleIds = new ArrayList<>();

        public TicketOption copy() {
            TicketOption copy = new TicketOption();
            copy.setId(id);
            copy.setLabel(label);
            copy.setDescription(description);
            copy.setEmoji(emoji);
            copy.setChannelNameTemplate(channelNameTemplate);
            copy.setSupportRoleIds(supportRoleIds);
            return copy;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id == null ? "" : id;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label == null ? "" : label;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description == null ? "" : description;
        }

        public String getEmoji() {
            return emoji;
        }

        public void setEmoji(String emoji) {
            this.emoji = emoji == null ? "" : emoji;
        }

        public String getChannelNameTemplate() {
            return channelNameTemplate;
        }

        public void setChannelNameTemplate(String channelNameTemplate) {
            this.channelNameTemplate = channelNameTemplate == null ? "ticket-{label}-{user}" : channelNameTemplate;
        }

        public List<String> getSupportRoleIds() {
            return supportRoleIds;
        }

        public void setSupportRoleIds(List<String> supportRoleIds) {
            this.supportRoleIds = supportRoleIds == null ? new ArrayList<>() : new ArrayList<>(supportRoleIds);
        }
    }

    public static class ActiveTicket {

        private String channelId = "";
        private String openerUserId = "";
        private String openerDisplay = "";
        private String panelId = "";
        private String optionId = "";
        private String optionLabel = "";
        private String claimedByUserId = "";
        private String claimedByDisplay = "";
        private boolean paused;
        private String controlMessageId = "";
        private String createdAt = Instant.now().toString();

        public ActiveTicket copy() {
            ActiveTicket copy = new ActiveTicket();
            copy.setChannelId(channelId);
            copy.setOpenerUserId(openerUserId);
            copy.setOpenerDisplay(openerDisplay);
            copy.setPanelId(panelId);
            copy.setOptionId(optionId);
            copy.setOptionLabel(optionLabel);
            copy.setClaimedByUserId(claimedByUserId);
            copy.setClaimedByDisplay(claimedByDisplay);
            copy.setPaused(paused);
            copy.setControlMessageId(controlMessageId);
            copy.setCreatedAt(createdAt);
            return copy;
        }

        public String getChannelId() {
            return channelId;
        }

        public void setChannelId(String channelId) {
            this.channelId = channelId == null ? "" : channelId;
        }

        public String getOpenerUserId() {
            return openerUserId;
        }

        public void setOpenerUserId(String openerUserId) {
            this.openerUserId = openerUserId == null ? "" : openerUserId;
        }

        public String getOpenerDisplay() {
            return openerDisplay;
        }

        public void setOpenerDisplay(String openerDisplay) {
            this.openerDisplay = openerDisplay == null ? "" : openerDisplay;
        }

        public String getPanelId() {
            return panelId;
        }

        public void setPanelId(String panelId) {
            this.panelId = panelId == null ? "" : panelId;
        }

        public String getOptionId() {
            return optionId;
        }

        public void setOptionId(String optionId) {
            this.optionId = optionId == null ? "" : optionId;
        }

        public String getOptionLabel() {
            return optionLabel;
        }

        public void setOptionLabel(String optionLabel) {
            this.optionLabel = optionLabel == null ? "" : optionLabel;
        }

        public String getClaimedByUserId() {
            return claimedByUserId;
        }

        public void setClaimedByUserId(String claimedByUserId) {
            this.claimedByUserId = claimedByUserId == null ? "" : claimedByUserId;
        }

        public String getClaimedByDisplay() {
            return claimedByDisplay;
        }

        public void setClaimedByDisplay(String claimedByDisplay) {
            this.claimedByDisplay = claimedByDisplay == null ? "" : claimedByDisplay;
        }

        public boolean isPaused() {
            return paused;
        }

        public void setPaused(boolean paused) {
            this.paused = paused;
        }

        public String getControlMessageId() {
            return controlMessageId;
        }

        public void setControlMessageId(String controlMessageId) {
            this.controlMessageId = controlMessageId == null ? "" : controlMessageId;
        }

        public String getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(String createdAt) {
            this.createdAt = createdAt == null || createdAt.isBlank() ? Instant.now().toString() : createdAt;
        }
    }

    public static class DiscordLogState {

        private boolean enabled;
        private String textChannelId = "";
        private boolean memberJoin = true;
        private boolean memberLeave = true;
        private boolean voiceJoin;
        private boolean voiceLeave;
        private boolean music = true;
        private boolean moderation;
        private boolean roleUpdates;
        private boolean nicknameUpdates;
        private boolean timeouts;
        private boolean kicks;
        private boolean bans;
        private boolean messageDeletes;
        private boolean voiceModeration;
        private boolean commands;

        public DiscordLogState copy() {
            DiscordLogState copy = new DiscordLogState();
            copy.setEnabled(enabled);
            copy.setTextChannelId(textChannelId);
            copy.setMemberJoin(memberJoin);
            copy.setMemberLeave(memberLeave);
            copy.setVoiceJoin(voiceJoin);
            copy.setVoiceLeave(voiceLeave);
            copy.setMusic(music);
            copy.setModeration(moderation);
            copy.setRoleUpdates(roleUpdates);
            copy.setNicknameUpdates(nicknameUpdates);
            copy.setTimeouts(timeouts);
            copy.setKicks(kicks);
            copy.setBans(bans);
            copy.setMessageDeletes(messageDeletes);
            copy.setVoiceModeration(voiceModeration);
            copy.setCommands(commands);
            return copy;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getTextChannelId() {
            return textChannelId;
        }

        public void setTextChannelId(String textChannelId) {
            this.textChannelId = textChannelId == null ? "" : textChannelId;
        }

        public boolean isMemberJoin() {
            return memberJoin;
        }

        public void setMemberJoin(boolean memberJoin) {
            this.memberJoin = memberJoin;
        }

        public boolean isMemberLeave() {
            return memberLeave;
        }

        public void setMemberLeave(boolean memberLeave) {
            this.memberLeave = memberLeave;
        }

        public boolean isVoiceJoin() {
            return voiceJoin;
        }

        public void setVoiceJoin(boolean voiceJoin) {
            this.voiceJoin = voiceJoin;
        }

        public boolean isVoiceLeave() {
            return voiceLeave;
        }

        public void setVoiceLeave(boolean voiceLeave) {
            this.voiceLeave = voiceLeave;
        }

        public boolean isMusic() {
            return music;
        }

        public void setMusic(boolean music) {
            this.music = music;
        }

        public boolean isModeration() {
            return moderation;
        }

        public void setModeration(boolean moderation) {
            this.moderation = moderation;
        }

        public boolean isRoleUpdates() {
            return roleUpdates;
        }

        public void setRoleUpdates(boolean roleUpdates) {
            this.roleUpdates = roleUpdates;
        }

        public boolean isNicknameUpdates() {
            return nicknameUpdates;
        }

        public void setNicknameUpdates(boolean nicknameUpdates) {
            this.nicknameUpdates = nicknameUpdates;
        }

        public boolean isTimeouts() {
            return timeouts;
        }

        public void setTimeouts(boolean timeouts) {
            this.timeouts = timeouts;
        }

        public boolean isKicks() {
            return kicks;
        }

        public void setKicks(boolean kicks) {
            this.kicks = kicks;
        }

        public boolean isBans() {
            return bans;
        }

        public void setBans(boolean bans) {
            this.bans = bans;
        }

        public boolean isMessageDeletes() {
            return messageDeletes;
        }

        public void setMessageDeletes(boolean messageDeletes) {
            this.messageDeletes = messageDeletes;
        }

        public boolean isVoiceModeration() {
            return voiceModeration;
        }

        public void setVoiceModeration(boolean voiceModeration) {
            this.voiceModeration = voiceModeration;
        }

        public boolean isCommands() {
            return commands;
        }

        public void setCommands(boolean commands) {
            this.commands = commands;
        }
    }
}
