package eckerlin.dev.listeners;

import eckerlin.dev.services.CommunityModuleService;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionRemoveEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.stereotype.Component;

@Component
public class CommunityModuleListener extends ListenerAdapter {

    private final CommunityModuleService communityModuleService;

    public CommunityModuleListener(CommunityModuleService communityModuleService) {
        this.communityModuleService = communityModuleService;
    }

    @Override
    public void onGuildMemberJoin(GuildMemberJoinEvent event) {
        communityModuleService.handleMemberJoin(event.getMember());
    }

    @Override
    public void onMessageReactionAdd(MessageReactionAddEvent event) {
        communityModuleService.handleReactionRoleAdd(event);
    }

    @Override
    public void onMessageReactionRemove(MessageReactionRemoveEvent event) {
        communityModuleService.handleReactionRoleRemove(event);
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String componentId = event.getComponentId();
        if (communityModuleService.isVerifyStartComponent(componentId)) {
            var prompt = communityModuleService.beginVerification(event.getGuild(), event.getMember());
            if (!prompt.success()) {
                event.reply(prompt.message()).setEphemeral(true).queue();
                return;
            }

            var reply = event.replyEmbeds(prompt.embed()).setEphemeral(true).setComponents(prompt.components());
            if (prompt.image() != null) {
                reply = reply.addFiles(net.dv8tion.jda.api.utils.FileUpload.fromData(prompt.image(), "verify-code.png"));
            }
            reply.queue();
            return;
        }

        if (communityModuleService.isVerifyRefreshComponent(componentId)) {
            var prompt = communityModuleService.refreshVerification(event.getGuild(), event.getMember());
            if (!prompt.success()) {
                event.reply(prompt.message()).setEphemeral(true).queue();
                return;
            }

            var edit = event.editMessageEmbeds(prompt.embed()).setComponents(prompt.components());
            if (prompt.image() != null) {
                edit = edit.setAttachments(net.dv8tion.jda.api.utils.FileUpload.fromData(prompt.image(), "verify-code.png"));
            }
            edit.queue();
            return;
        }

        if (communityModuleService.isVerifySubmitComponent(componentId)) {
            event.replyModal(communityModuleService.buildVerifyModal(
                    communityModuleService.extractVerifyGuildId(componentId)
            )).queue();
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (!communityModuleService.isVerifyModal(event.getModalId())) {
            return;
        }

        String guildId = communityModuleService.extractVerifyGuildId(event.getModalId());
        var guild = event.getGuild() != null ? event.getGuild() : event.getJDA().getGuildById(guildId);
        var result = communityModuleService.submitVerification(guild, event.getMember(), event.getValue("code"));
        event.reply(result.message()).setEphemeral(true).queue();
    }
}
