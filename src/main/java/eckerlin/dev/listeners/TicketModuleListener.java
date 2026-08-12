package eckerlin.dev.listeners;

import eckerlin.dev.services.TicketModuleService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.stereotype.Component;

@Component
public class TicketModuleListener extends ListenerAdapter {

    private final TicketModuleService ticketModuleService;

    public TicketModuleListener(TicketModuleService ticketModuleService) {
        this.ticketModuleService = ticketModuleService;
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        String componentId = event.getComponentId();
        if (!ticketModuleService.isTicketCreateComponent(componentId)) {
            return;
        }

        Guild guild = event.getGuild();
        Member member = event.getMember();
        if (guild == null || member == null || event.getValues().isEmpty()) {
            event.reply("Ticket konnte nicht erstellt werden.").setEphemeral(true).queue();
            return;
        }

        String panelId = ticketModuleService.extractPanelId(componentId);
        String optionId = event.getValues().get(0);
        event.deferReply(true).queue(
                success -> {
                    try {
                        var result = ticketModuleService.createTicket(guild, member, panelId, optionId);
                        respondDeferred(event, result.message());
                    } catch (RuntimeException exception) {
                        respondDeferred(event, "Ticket konnte nicht erstellt werden: " + sanitizeMessage(exception));
                    }
                },
                failure -> respond(event, "Ticket konnte nicht erstellt werden.")
        );
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String componentId = event.getComponentId();
        if (ticketModuleService.isTicketCreateButtonComponent(componentId)) {
            Guild guild = event.getGuild();
            Member member = event.getMember();
            if (guild == null || member == null) {
                event.reply("Ticket konnte nicht erstellt werden.").setEphemeral(true).queue();
                return;
            }

            String panelId = ticketModuleService.extractCreateButtonPanelId(componentId);
            String optionId = ticketModuleService.extractCreateButtonOptionId(componentId);
            event.deferReply(true).queue(
                    success -> {
                        try {
                            var result = ticketModuleService.createTicket(guild, member, panelId, optionId);
                            respondDeferred(event, result.message());
                        } catch (RuntimeException exception) {
                            respondDeferred(event, "Ticket konnte nicht erstellt werden: " + sanitizeMessage(exception));
                        }
                    },
                    failure -> respond(event, "Ticket konnte nicht erstellt werden.")
            );
            return;
        }

        if (!ticketModuleService.isTicketCloseComponent(componentId)
                && !ticketModuleService.isTicketClaimComponent(componentId)
                && !ticketModuleService.isTicketPauseComponent(componentId)) {
            return;
        }

        Guild guild = event.getGuild();
        if (guild == null) {
            String guildId = ticketModuleService.extractActionGuildId(componentId);
            guild = event.getJDA().getGuildById(guildId);
        }

        if (guild == null || event.getChannel() == null) {
            event.reply("Ticket konnte nicht geschlossen werden.").setEphemeral(true).queue();
            return;
        }

        if (!(event.getChannel() instanceof TextChannel textChannel)) {
            event.reply("Dieses Ticket ist kein Text-Channel.").setEphemeral(true).queue();
            return;
        }

        Guild targetGuild = guild;
        event.deferReply(true).queue(
                success -> {
                    try {
                        handleTicketAction(event, componentId, targetGuild, textChannel);
                    } catch (RuntimeException exception) {
                        respondDeferred(event, "Ticket-Aktion konnte nicht ausgefuehrt werden: " + sanitizeMessage(exception));
                    }
                },
                failure -> respond(event, "Ticket-Aktion konnte nicht ausgefuehrt werden.")
        );
    }

    private void handleTicketAction(ButtonInteractionEvent event, String componentId, Guild guild, TextChannel textChannel) {
        if (ticketModuleService.isTicketClaimComponent(componentId)) {
            var result = ticketModuleService.toggleClaim(guild, textChannel, event.getMember());
            respondDeferred(event, result.message());
            if (result.success() && result.embed() != null) {
                event.getMessage().editMessageEmbeds(result.embed()).setComponents(result.components()).queue(null, ignored -> {
                });
            }
            return;
        }

        if (ticketModuleService.isTicketPauseComponent(componentId)) {
            var result = ticketModuleService.togglePause(guild, textChannel, event.getMember());
            respondDeferred(event, result.message());
            if (result.success() && result.embed() != null) {
                event.getMessage().editMessageEmbeds(result.embed()).setComponents(result.components()).queue(null, ignored -> {
                });
            }
            return;
        }

        var result = ticketModuleService.closeTicket(guild, textChannel, event.getMember());
        respondDeferred(event, result.message());
    }

    private void respondDeferred(StringSelectInteractionEvent event, String message) {
        event.getHook().editOriginal(message).queue(
                success -> {
                },
                failure -> event.getHook().sendMessage(message).setEphemeral(true).queue(null, ignored -> {
                })
        );
    }

    private void respondDeferred(ButtonInteractionEvent event, String message) {
        event.getHook().editOriginal(message).queue(
                success -> {
                },
                failure -> event.getHook().sendMessage(message).setEphemeral(true).queue(null, ignored -> {
                })
        );
    }

    private void respond(StringSelectInteractionEvent event, String message) {
        event.reply(message).setEphemeral(true).queue(null, ignored -> {
        });
    }

    private void respond(ButtonInteractionEvent event, String message) {
        event.reply(message).setEphemeral(true).queue(null, ignored -> {
        });
    }

    private String sanitizeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "Unbekannter Fehler";
        }
        return message;
    }
}
