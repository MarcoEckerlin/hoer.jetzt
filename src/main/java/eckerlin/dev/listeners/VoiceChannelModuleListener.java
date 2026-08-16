package eckerlin.dev.listeners;

import eckerlin.dev.audio.AudioService;
import eckerlin.dev.services.GuildModuleSettingsService;
import eckerlin.dev.utils.Alert;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.attribute.ICategorizableChannel;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Component
public class VoiceChannelModuleListener extends ListenerAdapter {

    private static final String COMPONENT_PREFIX = "jtc:";
    private static final String MODAL_PREFIX = COMPONENT_PREFIX + "modal:";
    private static final String ACTION_SETTINGS = "settings";
    private static final String ACTION_LOCK = "lock";
    private static final String ACTION_UNLOCK = "unlock";
    private static final String ACTION_ADMIN = "admin";
    private static final String ACTION_KICK = "kick";
    private static final EnumSet<Permission> CHANNEL_ADMIN_PERMISSIONS = EnumSet.of(
            Permission.VIEW_CHANNEL,
            Permission.MESSAGE_SEND,
            Permission.MESSAGE_HISTORY,
            Permission.MESSAGE_EMBED_LINKS,
            Permission.USE_APPLICATION_COMMANDS,
            Permission.VOICE_CONNECT,
            Permission.VOICE_SPEAK,
            Permission.VOICE_MOVE_OTHERS,
            Permission.VOICE_MUTE_OTHERS,
            Permission.VOICE_DEAF_OTHERS,
            Permission.MANAGE_CHANNEL
    );

    private final GuildModuleSettingsService settingsService;
    private final AudioService audioService;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<String, ScheduledFuture<?>> cleanupTasks = new ConcurrentHashMap<>();

    public VoiceChannelModuleListener(GuildModuleSettingsService settingsService, AudioService audioService) {
        this.settingsService = settingsService;
        this.audioService = audioService;
    }

    @Override
    public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
        Guild guild = event.getGuild();
        GuildModuleSettingsService.JoinToCreateState joinState = settingsService.getJoinToCreateState(guild.getId());
        audioService.refreshVoiceChannelStatus(guild);

        if (event.getChannelJoined() instanceof VoiceChannel joinedChannel) {
            cancelCleanupTask(taskKey(guild.getId(), joinedChannel.getId()));
            audioService.syncAudienceState(guild);
        }

        if (event.getChannelLeft() instanceof VoiceChannel leftChannel) {
            refreshManagedChannelLifecycle(guild, leftChannel, joinState);
            audioService.syncAudienceState(guild);
        }

        if (event.getMember().getUser().isBot() || !joinState.isEnabled()) {
            return;
        }

        if (event.getChannelJoined() instanceof VoiceChannel joinedChannel) {
            joinState.getEntries().stream()
                    .filter(entry -> joinedChannel.getId().equals(entry.getSourceChannelId()))
                    .findFirst()
                    .ifPresent(entry -> createPersonalVoiceChannel(guild, event.getMember(), entry));
        }
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String componentId = event.getComponentId();
        if (componentId == null || !componentId.startsWith(COMPONENT_PREFIX)) {
            return;
        }

        String[] parts = componentId.split(":");
        if (parts.length != 4) {
            event.reply("Die Sprachkanal-Steuerung ist ungültig.").setEphemeral(true).queue();
            return;
        }

        String action = normalizeAction(parts[1]);
        if (!List.of(ACTION_SETTINGS, ACTION_LOCK, ACTION_UNLOCK).contains(action)) {
            return;
        }

        ManagedChannelContext context = resolveManagedChannel(event.getJDA(), parts[2], parts[3]);
        if (context == null) {
            event.reply("Dieser automatische Sprachkanal existiert nicht mehr.").setEphemeral(true).queue();
            return;
        }

        if (!canManageChannel(event.getMember(), context.managedChannel())) {
            event.reply("Du darfst diesen Sprachkanal nicht steuern.").setEphemeral(true).queue();
            return;
        }

        if (ACTION_SETTINGS.equals(action)) {
            openSettingsModal(event, context);
            return;
        }

        event.deferReply(true).queue(
                success -> updateLockState(event, context, ACTION_LOCK.equals(action)),
                failure -> event.reply("Die Sprachkanal-Steuerung ist abgelaufen.").setEphemeral(true).queue()
        );
    }

    @Override
    public void onEntitySelectInteraction(EntitySelectInteractionEvent event) {
        String componentId = event.getComponentId();
        if (componentId == null || !componentId.startsWith(COMPONENT_PREFIX)) {
            return;
        }

        String[] parts = componentId.split(":");
        if (parts.length != 4) {
            event.reply("Die Auswahl für den Sprachkanal ist ungültig.").setEphemeral(true).queue();
            return;
        }

        String action = normalizeAction(parts[1]);
        if (!List.of(ACTION_ADMIN, ACTION_KICK).contains(action)) {
            return;
        }

        ManagedChannelContext context = resolveManagedChannel(event.getJDA(), parts[2], parts[3]);
        if (context == null) {
            event.reply("Dieser automatische Sprachkanal existiert nicht mehr.").setEphemeral(true).queue();
            return;
        }

        if (!canManageChannel(event.getMember(), context.managedChannel())) {
            event.reply("Du darfst diesen Sprachkanal nicht steuern.").setEphemeral(true).queue();
            return;
        }

        Member targetMember = event.getMentions().getMembers().isEmpty()
                ? null
                : event.getMentions().getMembers().get(0);
        if (targetMember == null) {
            event.reply("Bitte wähle ein Mitglied aus.").setEphemeral(true).queue();
            return;
        }

        event.deferReply(true).queue(
                success -> {
                    if (ACTION_ADMIN.equals(action)) {
                        appointChannelAdmin(event, context, targetMember);
                    } else {
                        kickFromVoiceChannel(event, context, targetMember);
                    }
                },
                failure -> event.reply("Die Sprachkanal-Steuerung ist abgelaufen.").setEphemeral(true).queue()
        );
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (event.getModalId() == null || !event.getModalId().startsWith(MODAL_PREFIX)) {
            return;
        }

        String[] parts = event.getModalId().split(":");
        if (parts.length != 4) {
            event.reply("Die Sprachkanal-Konfiguration ist ungültig.").setEphemeral(true).queue();
            return;
        }

        ManagedChannelContext context = resolveManagedChannel(event.getJDA(), parts[2], parts[3]);
        if (context == null) {
            event.reply("Der automatische Sprachkanal ist nicht mehr verfügbar.").setEphemeral(true).queue();
            return;
        }

        if (!canManageChannel(event.getMember(), context.managedChannel())) {
            event.reply("Du darfst diesen Sprachkanal nicht steuern.").setEphemeral(true).queue();
            return;
        }

        String channelName = sanitizeChannelName(event.getValue("name") == null ? "" : event.getValue("name").getAsString());
        int userLimit = parseUserLimit(event.getValue("limit") == null ? "" : event.getValue("limit").getAsString());

        event.deferReply(true).queue(
                success -> context.channel().getManager()
                        .setName(channelName)
                        .setUserLimit(userLimit)
                        .queue(
                                ignored -> event.getHook().editOriginal("Sprachkanal aktualisiert: `" + channelName + "` mit Limit `" + userLimit + "`.").queue(),
                                throwable -> event.getHook().editOriginal("Der Sprachkanal konnte nicht aktualisiert werden: " + throwable.getMessage()).queue()
                        ),
                failure -> event.reply("Die Sprachkanal-Steuerung ist abgelaufen.").setEphemeral(true).queue()
        );
    }

    private void createPersonalVoiceChannel(
            Guild guild,
            Member member,
            GuildModuleSettingsService.JoinToCreateEntry entry
    ) {
        Category category = entry.getCategoryId().isBlank() ? null : guild.getCategoryById(entry.getCategoryId());
        int counter = settingsService.reserveNextJoinToCreateCounter(guild.getId(), entry.getId());
        VoiceChannel quellkanal = entry.getSourceChannelId().isBlank()
                ? null
                : guild.getVoiceChannelById(entry.getSourceChannelId());
        String channelName = buildChannelName(entry.getNameTemplate(), guild, member, quellkanal, category, counter);

        var action = category == null
                ? guild.createVoiceChannel(channelName)
                : guild.createVoiceChannel(channelName, category);

        // Ohne explizite Vorgabe wurde bisher die Bitrate des Quellkanals
        // kopiert - das sind fast immer die 64 kbps aus Discords Standard und
        // damit die groesste einzelne Ursache fuer dumpfen Musik-Sound.
        // Neuer Fallback: die hoechste Bitrate, die der Server hergibt.
        int bitrate = entry.getBitrateKbps() > 0
                ? entry.getBitrateKbps() * 1000
                : guild.getMaxBitrate();
        if (bitrate > 0) {
            action = action.setBitrate(Math.min(guild.getMaxBitrate(), bitrate));
        }

        if (entry.getUserLimit() > 0) {
            action = action.setUserlimit(entry.getUserLimit());
        }

        action.queue(createdChannel -> grantChannelAdminPermissions(createdChannel, member).queue(
                permissionSuccess -> {
                    // Die Nummer wandert mit: nur so kann sie spaeter wieder
                    // frei werden, statt fuer immer verbraucht zu sein.
                    settingsService.addManagedVoiceChannel(
                            guild.getId(), createdChannel.getId(), member.getId(), entry.getId(), counter);
                    guild.moveVoiceMember(member, createdChannel).queue(
                            success -> {
                                if (entry.isSendConfigPrompt()) {
                                    sendConfigurationPanel(member.getUser(), guild, createdChannel);
                                }
                                Alert.send("INFO", "VOICE", "Automatischer Sprachkanal für " + member.getEffectiveName() + " erstellt.");
                            },
                            error -> {
                                settingsService.removeManagedVoiceChannel(guild.getId(), createdChannel.getId());
                                createdChannel.delete().queue();
                                Alert.send("ERROR", "VOICE", "Konnte Nutzer nicht in automatischen Sprachkanal verschieben: " + error.getMessage());
                            }
                    );
                },
                permissionError -> {
                    createdChannel.delete().queue();
                    Alert.send("ERROR", "VOICE", "Konnte Rechte für automatischen Sprachkanal nicht setzen: " + permissionError.getMessage());
                }
        ), error -> Alert.send("ERROR", "VOICE", "Automatischer Sprachkanal konnte nicht erstellt werden: " + error.getMessage()));
    }

    private void refreshManagedChannelLifecycle(
            Guild guild,
            ICategorizableChannel channel,
            GuildModuleSettingsService.JoinToCreateState joinState
    ) {
        if (!(channel instanceof VoiceChannel voiceChannel)) {
            return;
        }

        if (!settingsService.isManagedVoiceChannel(guild.getId(), voiceChannel.getId())) {
            return;
        }

        String taskKey = taskKey(guild.getId(), voiceChannel.getId());
        if (!voiceChannel.getMembers().isEmpty()) {
            cancelCleanupTask(taskKey);
            return;
        }

        cancelCleanupTask(taskKey);
        cleanupTasks.put(taskKey, scheduler.schedule(() -> {
            VoiceChannel currentChannel = guild.getVoiceChannelById(voiceChannel.getId());
            cleanupTasks.remove(taskKey);

            if (currentChannel == null) {
                settingsService.removeManagedVoiceChannel(guild.getId(), voiceChannel.getId());
                return;
            }

            if (!settingsService.isManagedVoiceChannel(guild.getId(), currentChannel.getId()) || !currentChannel.getMembers().isEmpty()) {
                return;
            }

            currentChannel.delete().queue(
                    success -> settingsService.removeManagedVoiceChannel(guild.getId(), currentChannel.getId()),
                    error -> Alert.send("WARN", "VOICE", "Automatischer Sprachkanal konnte nicht gelöscht werden: " + error.getMessage())
            );
        }, joinState.getCleanupDelaySeconds(), TimeUnit.SECONDS));
    }

    private void openSettingsModal(ButtonInteractionEvent event, ManagedChannelContext context) {
        TextInput nameInput = TextInput.create("name", TextInputStyle.SHORT)
                .setRequired(true)
                .setRequiredRange(1, 90)
                .setPlaceholder("Kanalname")
                .setValue(context.channel().getName())
                .build();

        TextInput userLimitInput = TextInput.create("limit", TextInputStyle.SHORT)
                .setRequired(true)
                .setRequiredRange(1, 2)
                .setPlaceholder("0 bis 99")
                .setValue(String.valueOf(context.channel().getUserLimit()))
                .build();

        Modal modal = Modal.create(MODAL_PREFIX + context.guild().getId() + ":" + context.channel().getId(), "Sprachkanal einstellen")
                .addComponents(
                        Label.of("Kanalname", nameInput),
                        Label.of("Maximale Nutzer (0-99)", userLimitInput)
                )
                .build();

        event.replyModal(modal).queue();
    }

    private void updateLockState(ButtonInteractionEvent event, ManagedChannelContext context, boolean locked) {
        var action = context.channel()
                .upsertPermissionOverride(context.guild().getPublicRole());

        if (locked) {
            action.deny(Permission.VOICE_CONNECT).queue(
                    success -> event.getHook().editOriginal("Der Sprachkanal ist jetzt gesperrt.").queue(),
                    error -> event.getHook().editOriginal("Der Sprachkanal konnte nicht gesperrt werden: " + error.getMessage()).queue()
            );
            return;
        }

        action.clear(Permission.VOICE_CONNECT).queue(
                success -> event.getHook().editOriginal("Der Sprachkanal ist wieder offen.").queue(),
                error -> event.getHook().editOriginal("Der Sprachkanal konnte nicht entsperrt werden: " + error.getMessage()).queue()
        );
    }

    private void appointChannelAdmin(EntitySelectInteractionEvent event, ManagedChannelContext context, Member targetMember) {
        if (targetMember.getUser().isBot()) {
            event.getHook().editOriginal("Bots können hier nicht als Kanal-Admin gesetzt werden.").queue();
            return;
        }

        if (targetMember.getId().equals(context.managedChannel().getOwnerId())
                || context.managedChannel().getAdminUserIds().contains(targetMember.getId())) {
            event.getHook().editOriginal(targetMember.getAsMention() + " verwaltet diesen Sprachkanal bereits.").queue();
            return;
        }

        grantChannelAdminPermissions(context.channel(), targetMember).queue(
                success -> {
                    settingsService.addManagedVoiceChannelAdmin(context.guild().getId(), context.channel().getId(), targetMember.getId());
                    event.getHook().editOriginal(targetMember.getAsMention() + " ist jetzt Kanal-Admin.").queue();
                },
                error -> event.getHook().editOriginal("Kanal-Admin konnte nicht gesetzt werden: " + error.getMessage()).queue()
        );
    }

    private void kickFromVoiceChannel(EntitySelectInteractionEvent event, ManagedChannelContext context, Member targetMember) {
        if (targetMember.getUser().isBot()) {
            event.getHook().editOriginal("Bots können über dieses Panel nicht gekickt werden.").queue();
            return;
        }

        if (targetMember.getId().equals(context.managedChannel().getOwnerId())) {
            event.getHook().editOriginal("Den Ersteller dieses Sprachkanals kannst du hier nicht kicken.").queue();
            return;
        }

        if (targetMember.getVoiceState() == null
                || targetMember.getVoiceState().getChannel() == null
                || !targetMember.getVoiceState().getChannel().getId().equals(context.channel().getId())) {
            event.getHook().editOriginal("Dieses Mitglied ist gerade nicht in diesem Sprachkanal.").queue();
            return;
        }

        context.guild().kickVoiceMember(targetMember).queue(
                success -> event.getHook().editOriginal(targetMember.getAsMention() + " wurde aus dem Sprachkanal entfernt.").queue(),
                error -> event.getHook().editOriginal("Mitglied konnte nicht entfernt werden: " + error.getMessage()).queue()
        );
    }

    private boolean canManageChannel(Member member, GuildModuleSettingsService.ManagedVoiceChannel managedChannel) {
        if (member == null || managedChannel == null) {
            return false;
        }

        if (member.hasPermission(Permission.MANAGE_SERVER) || member.hasPermission(Permission.MANAGE_CHANNEL)) {
            return true;
        }

        return member.getId().equals(managedChannel.getOwnerId())
                || managedChannel.getAdminUserIds().contains(member.getId());
    }

    private ManagedChannelContext resolveManagedChannel(net.dv8tion.jda.api.JDA jda, String guildId, String channelId) {
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            return null;
        }

        VoiceChannel channel = guild.getVoiceChannelById(channelId);
        Optional<GuildModuleSettingsService.ManagedVoiceChannel> managedChannel = settingsService.getManagedVoiceChannel(guildId, channelId);
        if (channel == null || managedChannel.isEmpty()) {
            return null;
        }

        return new ManagedChannelContext(guild, channel, managedChannel.get());
    }

    private String normalizeAction(String action) {
        if ("config".equalsIgnoreCase(action)) {
            return ACTION_SETTINGS;
        }
        return action == null ? "" : action.toLowerCase();
    }

    private void sendConfigurationPanel(User user, Guild guild, VoiceChannel channel) {
        EmbedBuilder embed = new EmbedBuilder()
                .setColor(new Color(101, 164, 255))
                .setTitle("Sprachkanal-Steuerung")
                .setDescription("Dieser Sprachkanal gehört " + user.getAsMention() + ". Hier kannst du den Kanal sperren, Admins vergeben, das Limit ändern und Mitglieder aus dem VC entfernen.")
                .addField("Kanal", channel.getAsMention(), true)
                .addField("Aktuelles Limit", channel.getUserLimit() <= 0 ? "unbegrenzt" : String.valueOf(channel.getUserLimit()), true);

        channel.sendMessageEmbeds(embed.build())
                .setComponents(
                        ActionRow.of(
                                Button.secondary(componentId(ACTION_SETTINGS, guild.getId(), channel.getId()), "Name & Limit"),
                                Button.secondary(componentId(ACTION_LOCK, guild.getId(), channel.getId()), "Sperren"),
                                Button.secondary(componentId(ACTION_UNLOCK, guild.getId(), channel.getId()), "Entsperren")
                        ),
                        ActionRow.of(EntitySelectMenu.create(componentId(ACTION_ADMIN, guild.getId(), channel.getId()), EntitySelectMenu.SelectTarget.USER)
                                .setPlaceholder("Kanal-Admin ernennen")
                                .setRequiredRange(1, 1)
                                .build()),
                        ActionRow.of(EntitySelectMenu.create(componentId(ACTION_KICK, guild.getId(), channel.getId()), EntitySelectMenu.SelectTarget.USER)
                                .setPlaceholder("Mitglied aus dem Sprachkanal kicken")
                                .setRequiredRange(1, 1)
                                .build())
                )
                .queue(
                        success -> {
                        },
                        error -> Alert.send("WARN", "VOICE", "Steuerpanel für automatischen Sprachkanal konnte nicht gesendet werden: " + error.getMessage())
                );
    }

    private String componentId(String action, String guildId, String channelId) {
        return COMPONENT_PREFIX + action + ":" + guildId + ":" + channelId;
    }

    private net.dv8tion.jda.api.requests.restaction.PermissionOverrideAction grantChannelAdminPermissions(VoiceChannel channel, Member member) {
        return channel.upsertPermissionOverride(member).grant(CHANNEL_ADMIN_PERMISSIONS);
    }

    private void cancelCleanupTask(String taskKey) {
        ScheduledFuture<?> task = cleanupTasks.remove(taskKey);
        if (task != null) {
            task.cancel(false);
        }
    }

    private String taskKey(String guildId, String channelId) {
        return guildId + ":" + channelId;
    }

    private String sanitizeChannelName(String input) {
        String sanitized = input == null ? "" : input.trim();
        if (sanitized.isBlank()) {
            sanitized = "Sprachkanal";
        }
        return sanitized.length() > 90 ? sanitized.substring(0, 90) : sanitized;
    }

    private int parseUserLimit(String input) {
        try {
            return Math.max(0, Math.min(99, Integer.parseInt(input.trim())));
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    /**
     * Setzt die Platzhalter im Kanalnamen ein.
     *
     * <p>Die Liste steht wortgleich im Dashboard unter dem Eingabefeld - wer
     * hier etwas ergaenzt, ergaenzt es dort mit, sonst kennt sie niemand.
     * Unbekannte Platzhalter bleiben absichtlich stehen: ein Tippfehler soll
     * sichtbar sein und nicht als Leerstelle verschwinden.</p>
     */
    private String buildChannelName(
            String template,
            Guild guild,
            Member member,
            VoiceChannel quellkanal,
            Category kategorie,
            int counter
    ) {
        String guildName = guild == null ? "" : guild.getName();
        String userName = member == null ? "" : member.getEffectiveName();
        ZonedDateTime jetzt = ZonedDateTime.now(ZoneId.of("Europe/Berlin"));

        String name = (template == null ? "#{counter} - Sprachkanal" : template)
                // Wer
                .replace("{user}", userName)
                .replace("{nutzer}", userName)
                .replace("{username}", member == null ? "" : member.getUser().getName())
                .replace("{tag}", member == null ? "" : member.getUser().getName())
                .replace("{userid}", member == null ? "" : member.getId())
                // Wo
                .replace("{guild}", guildName)
                .replace("{server}", guildName)
                .replace("{kanal}", quellkanal == null ? "" : quellkanal.getName())
                .replace("{channel}", quellkanal == null ? "" : quellkanal.getName())
                .replace("{kategorie}", kategorie == null ? "" : kategorie.getName())
                .replace("{category}", kategorie == null ? "" : kategorie.getName())
                // Wie viele
                .replace("{counter}", String.valueOf(counter))
                .replace("{count}", String.valueOf(counter))
                .replace("{nummer}", String.valueOf(counter))
                .replace("{n}", String.valueOf(counter))
                .replace("{mitglieder}", guild == null ? "0" : String.valueOf(guild.getMemberCount()))
                // Wann
                .replace("{datum}", jetzt.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")))
                .replace("{zeit}", jetzt.format(DateTimeFormatter.ofPattern("HH:mm")))
                .replace("{wochentag}", jetzt.getDayOfWeek()
                        .getDisplayName(TextStyle.FULL, Locale.GERMAN))
                .trim();

        if (name.isBlank()) {
            name = "#" + counter + " - Sprachkanal";
        }

        return name.length() > 90 ? name.substring(0, 90) : name;
    }

    private record ManagedChannelContext(
            Guild guild,
            VoiceChannel channel,
            GuildModuleSettingsService.ManagedVoiceChannel managedChannel
    ) {
    }
}
