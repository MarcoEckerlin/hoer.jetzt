package eckerlin.dev.listeners;

import eckerlin.dev.services.LlmService;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.stereotype.Component;

@Component
public class LlmModuleListener extends ListenerAdapter {

    private final LlmService llmService;

    public LlmModuleListener(LlmService llmService) {
        this.llmService = llmService;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        llmService.handleMessage(event);
    }
}
