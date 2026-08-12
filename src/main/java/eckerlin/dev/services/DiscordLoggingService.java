package eckerlin.dev.services;

import eckerlin.dev.web.dto.DiscordLogView;
import net.dv8tion.jda.api.audit.ActionType;
import net.dv8tion.jda.api.audit.AuditLogChange;
import net.dv8tion.jda.api.audit.AuditLogEntry;
import net.dv8tion.jda.api.audit.AuditLogKey;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class DiscordLoggingService {

    private final GuildModuleSettingsService settingsService;

    public DiscordLoggingService(GuildModuleSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    public DiscordLogView buildView(Guild guild) {
        GuildModuleSettingsService.DiscordLogState state = settingsService.getDiscordLogState(guild.getId());
        String notice;

        if (state.getTextChannelId().isBlank()) {
            notice = "Waehle einen Text-Channel fuer die Discord-Logs aus.";
        } else if (guild.getTextChannelById(state.getTextChannelId()) == null) {
            notice = "Der gespeicherte Log-Channel existiert nicht mehr.";
        } else {
            notice = "Logs werden in #" + guild.getTextChannelById(state.getTextChannelId()).getName() + " geschrieben.";
        }

        return new DiscordLogView(
                state.isEnabled(),
                state.getTextChannelId(),
                state.isMemberJoin(),
                state.isMemberLeave(),
                state.isVoiceJoin(),
                state.isVoiceLeave(),
                state.isMusic(),
                state.isModeration(),
                state.isRoleUpdates(),
                state.isNicknameUpdates(),
                state.isTimeouts(),
                state.isKicks(),
                state.isBans(),
                state.isMessageDeletes(),
                state.isVoiceModeration(),
                state.isCommands(),
                notice
        );
    }

    public void logMemberJoin(Member member) {
        if (member == null || member.getUser().isBot()) {
            return;
        }
        Guild guild = member.getGuild();
        GuildModuleSettingsService.DiscordLogState state = settingsService.getDiscordLogState(guild.getId());
        if (!state.isEnabled() || !state.isMemberJoin()) {
            return;
        }

        send(guild, state, "Mitglied beigetreten", member.getAsMention() + " ist dem Server beigetreten.", new Color(88, 166, 255));
    }

    public void logMemberLeave(Guild guild, User user) {
        if (guild == null || user == null || user.isBot()) {
            return;
        }

        GuildModuleSettingsService.DiscordLogState state = settingsService.getDiscordLogState(guild.getId());
        if (!state.isEnabled() || !state.isMemberLeave()) {
            return;
        }

        send(guild, state, "Mitglied verlassen", user.getAsMention() + " hat den Server verlassen.", new Color(255, 151, 163));
    }

    public void logVoiceJoin(Member member, String channelName) {
        if (member == null || member.getUser().isBot()) {
            return;
        }

        Guild guild = member.getGuild();
        GuildModuleSettingsService.DiscordLogState state = settingsService.getDiscordLogState(guild.getId());
        if (!state.isEnabled() || !state.isVoiceJoin()) {
            return;
        }

        send(guild, state, "Voice Join", member.getAsMention() + " hat `" + safe(channelName) + "` betreten.", new Color(121, 200, 255));
    }

    public void logVoiceLeave(Member member, String channelName) {
        if (member == null || member.getUser().isBot()) {
            return;
        }

        Guild guild = member.getGuild();
        GuildModuleSettingsService.DiscordLogState state = settingsService.getDiscordLogState(guild.getId());
        if (!state.isEnabled() || !state.isVoiceLeave()) {
            return;
        }

        send(guild, state, "Voice Leave", member.getAsMention() + " hat `" + safe(channelName) + "` verlassen.", new Color(255, 190, 92));
    }

    public void logMusicEvent(Guild guild, String title, String text) {
        if (guild == null) {
            return;
        }

        GuildModuleSettingsService.DiscordLogState state = settingsService.getDiscordLogState(guild.getId());
        if (!state.isEnabled() || !state.isMusic()) {
            return;
        }

        send(guild, state, title, text, new Color(84, 200, 149));
    }

    public void logCommand(SlashCommandInteractionEvent event) {
        if (event == null || event.getGuild() == null || event.getUser().isBot()) {
            return;
        }

        GuildModuleSettingsService.DiscordLogState state = settingsService.getDiscordLogState(event.getGuild().getId());
        if (!state.isEnabled() || !state.isCommands()) {
            return;
        }

        String text = """
                Ausgefuehrt von: %s
                Channel: %s
                Command: `/%s`
                Optionen: %s
                """.formatted(
                event.getUser().getAsMention(),
                describeChannel(event.getChannel()),
                safe(event.getCommandString()),
                describeOptions(event)
        );
        send(event.getGuild(), state, "Slash Command", text, new Color(171, 136, 255));
    }

    public void logModerationAudit(AuditLogEntry entry) {
        if (entry == null || entry.getGuild() == null) {
            return;
        }

        if (!isModerationAction(entry.getType())) {
            return;
        }

        Guild guild = entry.getGuild();
        GuildModuleSettingsService.DiscordLogState state = settingsService.getDiscordLogState(guild.getId());
        if (!state.isEnabled() || !state.isModeration()) {
            return;
        }

        if (!shouldLogModerationType(state, entry.getType())) {
            return;
        }

        ModerationLog moderationLog = describeModeration(entry);
        if (moderationLog == null) {
            return;
        }

        send(guild, state, moderationLog.title(), moderationLog.description(), moderationLog.color());
    }

    private void send(Guild guild, GuildModuleSettingsService.DiscordLogState state, String title, String text, Color color) {
        TextChannel textChannel = guild.getTextChannelById(state.getTextChannelId());
        if (textChannel == null) {
            return;
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(safe(title))
                .setDescription(safe(text))
                .setColor(color)
                .setTimestamp(Instant.now());

        textChannel.sendMessageEmbeds(embed.build()).queue();
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) {
            return "Unbekannt";
        }
        return value.length() > 1800 ? value.substring(0, 1797) + "..." : value;
    }

    private ModerationLog describeModeration(AuditLogEntry entry) {
        ActionType type = entry.getType();
        return switch (type) {
            case KICK -> new ModerationLog(
                    "Moderation | Kick",
                    moderationDetails(entry, "Kick", List.of()),
                    new Color(255, 176, 92)
            );
            case BAN -> new ModerationLog(
                    "Moderation | Ban",
                    moderationDetails(entry, "Ban", List.of()),
                    new Color(255, 120, 120)
            );
            case UNBAN -> new ModerationLog(
                    "Moderation | Unban",
                    moderationDetails(entry, "Unban", List.of()),
                    new Color(121, 200, 255)
            );
            case MEMBER_UPDATE -> describeMemberUpdate(entry);
            case MEMBER_ROLE_UPDATE -> describeRoleUpdate(entry);
            case MEMBER_VOICE_MOVE -> new ModerationLog(
                    "Moderation | Voice Move",
                    moderationDetails(entry, "Voice Move", List.of(describeAuditOptions(entry))),
                    new Color(121, 200, 255)
            );
            case MEMBER_VOICE_KICK -> new ModerationLog(
                    "Moderation | Voice Kick",
                    moderationDetails(entry, "Voice Kick", List.of()),
                    new Color(255, 190, 92)
            );
            case MESSAGE_DELETE -> new ModerationLog(
                    "Moderation | Nachricht geloescht",
                    moderationDetails(entry, "Message Delete", List.of(describeAuditOptions(entry))),
                    new Color(255, 151, 163)
            );
            case MESSAGE_BULK_DELETE -> new ModerationLog(
                    "Moderation | Nachrichten geloescht",
                    moderationDetails(entry, "Bulk Delete", List.of(describeAuditOptions(entry))),
                    new Color(255, 151, 163)
            );
            case PRUNE -> new ModerationLog(
                    "Moderation | Prune",
                    moderationDetails(entry, "Prune", List.of(describeAuditOptions(entry))),
                    new Color(255, 176, 92)
            );
            case AUTO_MODERATION_MEMBER_TIMEOUT -> new ModerationLog(
                    "Moderation | AutoMod Timeout",
                    moderationDetails(entry, "AutoMod Timeout", List.of(describeAuditOptions(entry))),
                    new Color(255, 151, 163)
            );
            default -> new ModerationLog(
                    "Moderation | " + humanizeActionType(type),
                    moderationDetails(entry, humanizeActionType(type), List.of(
                            describeChanges(entry),
                            describeAuditOptions(entry)
                    )),
                    new Color(171, 136, 255)
            );
        };
    }

    private ModerationLog describeMemberUpdate(AuditLogEntry entry) {
        AuditLogChange timeoutChange = entry.getChangeByKey(AuditLogKey.MEMBER_TIME_OUT);
        AuditLogChange muteChange = entry.getChangeByKey(AuditLogKey.MEMBER_MUTE);
        AuditLogChange deafChange = entry.getChangeByKey(AuditLogKey.MEMBER_DEAF);
        AuditLogChange nickChange = entry.getChangeByKey(AuditLogKey.MEMBER_NICK);

        if (timeoutChange != null) {
            String newValue = formatAuditDate(timeoutChange.getNewValue());
            boolean removed = newValue.isBlank() || "Keine".equals(newValue);
            return new ModerationLog(
                    removed ? "Moderation | Timeout aufgehoben" : "Moderation | Timeout gesetzt",
                    moderationDetails(entry, removed ? "Timeout aufgehoben" : "Timeout gesetzt", List.of(
                            "Bis: " + (removed ? "Sofort aufgehoben" : newValue)
                    )),
                    new Color(255, 151, 163)
            );
        }

        if (muteChange != null) {
            boolean muted = Boolean.parseBoolean(String.valueOf(muteChange.getNewValue()));
            return new ModerationLog(
                    muted ? "Moderation | Servermute gesetzt" : "Moderation | Servermute entfernt",
                    moderationDetails(entry, muted ? "Servermute gesetzt" : "Servermute entfernt", List.of(
                            "Status: " + (muted ? "Stummgeschaltet" : "Entstummt")
                    )),
                    new Color(255, 176, 92)
            );
        }

        if (deafChange != null) {
            boolean deafened = Boolean.parseBoolean(String.valueOf(deafChange.getNewValue()));
            return new ModerationLog(
                    deafened ? "Moderation | Serverdeaf gesetzt" : "Moderation | Serverdeaf entfernt",
                    moderationDetails(entry, deafened ? "Serverdeaf gesetzt" : "Serverdeaf entfernt", List.of(
                            "Status: " + (deafened ? "Taubgeschaltet" : "Nicht mehr taub")
                    )),
                    new Color(255, 190, 92)
            );
        }

        if (nickChange != null) {
            return new ModerationLog(
                    "Moderation | Nickname aktualisiert",
                    moderationDetails(entry, "Nickname aktualisiert", List.of(
                            "Vorher: " + describeAuditValue(nickChange.getOldValue()),
                            "Neu: " + describeAuditValue(nickChange.getNewValue())
                    )),
                    new Color(171, 136, 255)
            );
        }

        return new ModerationLog(
                "Moderation | Mitglied aktualisiert",
                moderationDetails(entry, "Mitglied aktualisiert", List.of(describeChanges(entry))),
                new Color(171, 136, 255)
        );
    }

    private ModerationLog describeRoleUpdate(AuditLogEntry entry) {
        AuditLogChange addChange = entry.getChangeByKey(AuditLogKey.MEMBER_ROLES_ADD);
        AuditLogChange removeChange = entry.getChangeByKey(AuditLogKey.MEMBER_ROLES_REMOVE);
        if (addChange == null && removeChange == null) {
            return null;
        }

        String added = describeAuditValue(addChange == null ? null : addChange.getNewValue());
        String removed = describeAuditValue(removeChange == null ? null : removeChange.getNewValue());
        return new ModerationLog(
                "Moderation | Rollen aktualisiert",
                moderationDetails(entry, "Rollen aktualisiert", List.of(
                        "Hinzugefuegt: " + added,
                        "Entfernt: " + removed
                )),
                new Color(171, 136, 255)
        );
    }

    private boolean isModerationAction(ActionType type) {
        return switch (type) {
            case KICK,
                    PRUNE,
                    BAN,
                    UNBAN,
                    MEMBER_UPDATE,
                    MEMBER_ROLE_UPDATE,
                    MEMBER_VOICE_MOVE,
                    MEMBER_VOICE_KICK,
                    MESSAGE_DELETE,
                    MESSAGE_BULK_DELETE,
                    AUTO_MODERATION_MEMBER_TIMEOUT -> true;
            default -> false;
        };
    }

    private boolean shouldLogModerationType(GuildModuleSettingsService.DiscordLogState state, ActionType type) {
        boolean detailed = state.isRoleUpdates()
                || state.isNicknameUpdates()
                || state.isTimeouts()
                || state.isKicks()
                || state.isBans()
                || state.isMessageDeletes()
                || state.isVoiceModeration();
        if (!detailed) {
            return true;
        }

        return switch (type) {
            case KICK -> state.isKicks();
            case BAN, UNBAN -> state.isBans();
            case MEMBER_ROLE_UPDATE -> state.isRoleUpdates();
            case MEMBER_UPDATE -> state.isNicknameUpdates() || state.isTimeouts();
            case MESSAGE_DELETE, MESSAGE_BULK_DELETE -> state.isMessageDeletes();
            case MEMBER_VOICE_MOVE, MEMBER_VOICE_KICK -> state.isVoiceModeration();
            case PRUNE, AUTO_MODERATION_MEMBER_TIMEOUT -> state.isTimeouts();
            default -> true;
        };
    }

    private String moderationDetails(AuditLogEntry entry, String actionLabel, List<String> extraLines) {
        List<String> lines = new ArrayList<>();
        lines.add("Aktion: " + safe(actionLabel));
        lines.add("Moderator: " + mentionUser(entry.getUserId()));
        if (entry.getTargetId() != null && !entry.getTargetId().isBlank() && !"0".equals(entry.getTargetId())) {
            lines.add("Ziel: " + mentionUser(entry.getTargetId()));
        }
        lines.add("Zeit: " + formatEntryTime(entry));
        for (String extraLine : extraLines) {
            if (extraLine != null && !extraLine.isBlank()) {
                lines.add(extraLine);
            }
        }
        lines.add("Grund: " + reasonText(entry.getReason()));
        return lines.stream()
                .filter(line -> line != null && !line.isBlank())
                .collect(Collectors.joining("\n"));
    }

    private String describeChanges(AuditLogEntry entry) {
        if (entry.getChanges().isEmpty()) {
            return "";
        }

        return "Aenderungen: " + entry.getChanges().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(change -> change.getKey() + " [" + describeAuditValue(change.getValue().getOldValue()) + " -> " + describeAuditValue(change.getValue().getNewValue()) + "]")
                .collect(Collectors.joining(" | "));
    }

    private String describeAuditOptions(AuditLogEntry entry) {
        if (entry.getOptions().isEmpty()) {
            return "";
        }

        return "Kontext: " + entry.getOptions().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(option -> option.getKey() + "=" + describeAuditValue(option.getValue()))
                .collect(Collectors.joining(" | "));
    }

    private String formatEntryTime(AuditLogEntry entry) {
        return DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.GERMANY)
                .withZone(ZoneId.systemDefault())
                .format(entry.getTimeCreated().toInstant());
    }

    private String humanizeActionType(ActionType type) {
        if (type == null) {
            return "Unbekannte Aktion";
        }
        String raw = type.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        String[] parts = raw.split(" ");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.toString();
    }

    private String mentionUser(String userId) {
        if (userId == null || userId.isBlank() || "0".equals(userId)) {
            return "Unbekannt";
        }
        return "<@" + userId + ">";
    }

    private String reasonText(String reason) {
        return reason == null || reason.isBlank() ? "Kein Grund angegeben" : safe(reason);
    }

    private String describeChannel(Channel channel) {
        if (channel == null) {
            return "Unbekannt";
        }
        return "#" + safe(channel.getName());
    }

    private String describeOptions(SlashCommandInteractionEvent event) {
        if (event.getOptions().isEmpty()) {
            return "Keine";
        }

        return event.getOptions().stream()
                .sorted(Comparator.comparing(OptionMapping::getName))
                .map(this::formatOption)
                .collect(Collectors.joining(" | "));
    }

    private String formatOption(OptionMapping option) {
        String value = switch (option.getType()) {
            case USER, MENTIONABLE -> mentionUser(option.getAsString());
            case CHANNEL -> "#" + safe(option.getAsChannel().getName());
            case ROLE -> "@" + safe(option.getAsRole().getName());
            case BOOLEAN, INTEGER, NUMBER, STRING -> safe(option.getAsString());
            default -> safe(option.getAsString());
        };
        return option.getName() + "=" + value;
    }

    private String describeAuditValue(Object value) {
        if (value == null) {
            return "Keine";
        }
        if (value instanceof AuditLogChange change) {
            return describeAuditValue(change.getNewValue());
        }
        if (value instanceof Iterable<?> iterable) {
            List<String> parts = new ArrayList<>();
            for (Object item : iterable) {
                parts.add(describeAuditValue(item));
            }
            String joined = String.join(", ", parts);
            return joined.isBlank() ? "Keine" : joined;
        }
        if (value instanceof Map<?, ?> map) {
            Object roleName = map.get("name");
            Object roleId = map.get("id");
            if (roleName != null) {
                return "@" + safe(String.valueOf(roleName)) + (roleId == null ? "" : " (" + safe(String.valueOf(roleId)) + ")");
            }
            return map.entrySet().stream()
                    .sorted((left, right) -> String.valueOf(left.getKey()).compareTo(String.valueOf(right.getKey())))
                    .map(entry -> safe(String.valueOf(entry.getKey())) + "=" + safe(String.valueOf(entry.getValue())))
                    .collect(Collectors.joining(", "));
        }
        String raw = String.valueOf(value).trim();
        return raw.isBlank() ? "Keine" : safe(raw);
    }

    private String formatAuditDate(Object value) {
        if (value == null) {
            return "Keine";
        }
        try {
            OffsetDateTime dateTime = OffsetDateTime.parse(String.valueOf(value));
            return DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.GERMANY)
                    .withZone(ZoneId.systemDefault())
                    .format(dateTime.toInstant());
        } catch (RuntimeException ignored) {
            return safe(String.valueOf(value));
        }
    }

    private record ModerationLog(String title, String description, Color color) {
    }
}
