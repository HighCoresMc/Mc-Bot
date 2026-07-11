package com.highcore.bot.listeners;

import com.highcore.bot.services.AIAssistantService;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AIAssistantListener extends ListenerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(AIAssistantListener.class);
    private static final String TARGET_CHANNEL_ID = "1510962014120579254";
    private final AIAssistantService aiService;

    // INIT
    public AIAssistantListener(AIAssistantService aiService) {
        this.aiService = aiService;
    }

    // ON MESSAGE RECEIVED
    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) {
            return;
        }

        String channelId = event.getChannel().getId();
        boolean isThread = event.getChannelType().isThread();

        if (isThread) {
            ThreadChannel thread = event.getChannel().asThreadChannel();
            if (TARGET_CHANNEL_ID.equals(thread.getParentChannel().getId())) {
                handleThreadMessage(event, thread);
            }
        } else if (TARGET_CHANNEL_ID.equals(channelId)) {
            handleNewQuestion(event);
        }
    }

    // HANDLE NEW QUESTION
    private void handleNewQuestion(MessageReceivedEvent event) {
        String content = event.getMessage().getContentRaw();
        if (content.trim().isEmpty()) {
            return;
        }

        String threadName = content.length() > 90 ? content.substring(0, 90) + "..." : content;
        event.getMessage().createThreadChannel(threadName).queue(thread -> {
            thread.sendTyping().queue();
            new Thread(() -> {
                List<AIAssistantService.ChatMessage> history = new ArrayList<>();
                history.add(new AIAssistantService.ChatMessage(content, false));
                String response = aiService.askGemini(history);
                thread.sendMessage(response).queue();
            }).start();
        }, error -> logger.error("Failed to create thread for AI assistant", error));
    }

    // HANDLE THREAD MESSAGE
    private void handleThreadMessage(MessageReceivedEvent event, ThreadChannel thread) {
        Message ref = event.getMessage().getReferencedMessage();
        boolean isReplyToBot = (ref != null && ref.getAuthor().getId().equals(event.getJDA().getSelfUser().getId()));
        boolean isMentioned = event.getMessage().getMentions().isMentioned(event.getJDA().getSelfUser());
        
        if (!isReplyToBot && !isMentioned) {
            return;
        }

        thread.sendTyping().queue();
        thread.getHistoryBefore(event.getMessageIdLong(), 20).queue(historyObj -> {
            new Thread(() -> {
                List<Message> retrieved = new ArrayList<>(historyObj.getRetrievedHistory());
                Collections.reverse(retrieved);

                List<AIAssistantService.ChatMessage> history = new ArrayList<>();
                for (Message msg : retrieved) {
                    if (msg.getAuthor().isBot() && !msg.getAuthor().getId().equals(event.getJDA().getSelfUser().getId())) {
                        continue;
                    }
                    history.add(new AIAssistantService.ChatMessage(msg.getContentRaw(), msg.getAuthor().getId().equals(event.getJDA().getSelfUser().getId())));
                }
                history.add(new AIAssistantService.ChatMessage(event.getMessage().getContentRaw(), false));

                String response = aiService.askGemini(history);
                thread.sendMessage(response).queue();
            }).start();
        }, error -> logger.error("Failed to get thread history for AI assistant", error));
    }
}
