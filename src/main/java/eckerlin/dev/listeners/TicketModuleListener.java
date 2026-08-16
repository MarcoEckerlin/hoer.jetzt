package eckerlin.dev.listeners;

import eckerlin.dev.services.TicketModuleService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class TicketModuleListener extends ListenerAdapter {

    private final TicketModuleService ticketModuleService;

    /**
     * Eigener Ausfuehrungsstrang fuer alles, was Discord befragt.
     *
     * <p>Ein Ticket anzulegen sind mehrere Schritte, die aufeinander aufbauen:
     * Kanal erstellen, Rechte setzen, Nachricht senden, deren ID merken. Der
     * Dienst wartet deshalb mit {@code complete()} auf jede Antwort - und genau
     * das verbietet JDA innerhalb seiner Rueckruf-Straenge:</p>
     *
     * <pre>Preventing use of complete() in callback threads!</pre>
     *
     * <p>Zu Recht: der Rueckruf laeuft auf demselben Strang, der die naechste
     * Antwort zustellen muesste. Wer dort wartet, wartet auf sich selbst.</p>
     *
     * <p>Frueher stand die Arbeit direkt im {@code queue()}-Rueckruf des
     * {@code deferReply} - deshalb scheiterte jedes Ticket. Jetzt bestaetigt
     * der Rueckruf nur noch und reicht die Arbeit hier hinein.</p>
     */
    private final ExecutorService arbeiter = Executors.newCachedThreadPool(aufgabe -> {
        Thread strang = new Thread(aufgabe, "ticket-arbeiter");
        strang.setDaemon(true);
        return strang;
    });

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
                success -> arbeiter.execute(() -> {
                    try {
                        var result = ticketModuleService.createTicket(guild, member, panelId, optionId);
                        respondDeferred(event, result.message());
                    } catch (RuntimeException exception) {
                        respondDeferred(event, "Ticket konnte nicht erstellt werden: " + sanitizeMessage(exception));
                    }
                }),
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
                    success -> arbeiter.execute(() -> {
                        try {
                            var result = ticketModuleService.createTicket(guild, member, panelId, optionId);
                            respondDeferred(event, result.message());
                        } catch (RuntimeException exception) {
                            respondDeferred(event, "Ticket konnte nicht erstellt werden: " + sanitizeMessage(exception));
                        }
                    }),
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
                success -> arbeiter.execute(() -> {
                    try {
                        handleTicketAction(event, componentId, targetGuild, textChannel);
                    } catch (RuntimeException exception) {
                        respondDeferred(event, "Ticket-Aktion konnte nicht ausgeführt werden: " + sanitizeMessage(exception));
                    }
                }),
                failure -> respond(event, "Ticket-Aktion konnte nicht ausgeführt werden.")
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
