package com.highcore.bot.listeners;

import com.highcore.bot.LeonTrotskyBot;
import com.highcore.bot.services.AIAssistantService;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        new Thread(() -> {
            try {
                String content = event.getMessage().getContentRaw();
                if (content.trim().isEmpty() || !isQuery(content)) {
                    return;
                }

                Message ref = event.getMessage().getReferencedMessage();
                if (ref != null && ref.getAuthor().getId().equals(event.getJDA().getSelfUser().getId())) {
                    String oldThreadId = extractThreadIdFromMentionMessage(ref.getContentRaw());
                    if (oldThreadId != null) {
                        handleFollowUpQuestion(event, oldThreadId);
                        return;
                    }
                }

                runSmartSearch(event);
            } catch (Exception e) {
                logger.error("Error in handleNewQuestion background thread", e);
            }
        }).start();
    }

    // RUN SMART SEARCH
    private void runSmartSearch(MessageReceivedEvent event) {
        String content = event.getMessage().getContentRaw();
        List<AIAssistantService.ThreadInfo> threads = getAllThreadsFromDatabase();

        // Local match check first (case-insensitive, normalized Arabic)
        String localMatchId = findLocalMatch(content, threads);
        if (localMatchId != null) {
            if (threadExistsInJDA(event.getGuild(), localMatchId)) {
                String replyMessage = "هذا السؤال مكرر، يمكنك مراجعة الثريد التالي: <#" + localMatchId + ">";
                event.getMessage().reply(replyMessage).queue();
                return;
            }
        }

        String matchedThreadId = aiService.checkSemanticMatch(content, threads);

        if (matchedThreadId != null && !matchedThreadId.equals("NO")) {
            if (threadExistsInJDA(event.getGuild(), matchedThreadId)) {
                String replyMessage = "هذا السؤال مكرر، يمكنك مراجعة الثريد التالي: <#" + matchedThreadId + ">";
                event.getMessage().reply(replyMessage).queue();
                return;
            }
        }

        createNormalThread(event, content);
    }

    // CREATE NORMAL THREAD
    private void createNormalThread(MessageReceivedEvent event, String content) {
        String threadName = content.length() > 90 ? content.substring(0, 90) + "..." : content;
        if (threadName.isEmpty() && !event.getMessage().getAttachments().isEmpty()) {
            threadName = "صورة مرفقة";
        }

        event.getMessage().createThreadChannel(threadName).queue(thread -> {
            thread.sendTyping().queue();
            new Thread(() -> {
                List<String> imageUrls = new ArrayList<>();
                for (Message.Attachment attachment : event.getMessage().getAttachments()) {
                    if (attachment.isImage()) {
                        imageUrls.add(attachment.getUrl());
                    }
                }

                List<AIAssistantService.ChatMessage> history = new ArrayList<>();
                history.add(new AIAssistantService.ChatMessage(content, false, imageUrls));

                String discordId = event.getAuthor().getId();
                String discordName = event.getAuthor().getName();
                String response = aiService.askGemini(history, discordId, discordName);

                thread.sendMessage(response).queue(msg -> {
                    saveThreadToDatabase(thread.getId(), thread.getName(), content);
                });
            }).start();
        }, error -> logger.error("Failed to create thread for AI assistant", error));
    }

    // HANDLE FOLLOW UP QUESTION
    private void handleFollowUpQuestion(MessageReceivedEvent event, String oldThreadId) {
        String content = event.getMessage().getContentRaw();
        ThreadChannel oldThread = event.getGuild().getThreadChannelById(oldThreadId);

        if (oldThread != null) {
            oldThread.getHistory().retrievePast(20).queue(retrieved -> {
                List<Message> historyMessages = new ArrayList<>(retrieved);
                Collections.reverse(historyMessages);

                String threadName = content.length() > 90 ? content.substring(0, 90) + "..." : content;
                if (threadName.isEmpty() && !event.getMessage().getAttachments().isEmpty()) {
                    threadName = "صورة مرفقة";
                }

                event.getMessage().createThreadChannel(threadName).queue(newThread -> {
                    newThread.sendTyping().queue();
                    new Thread(() -> {
                        List<AIAssistantService.ChatMessage> history = new ArrayList<>();

                        for (Message msg : historyMessages) {
                            if (msg.getAuthor().isBot() && !msg.getAuthor().getId().equals(event.getJDA().getSelfUser().getId())) {
                                continue;
                            }
                            List<String> msgImageUrls = new ArrayList<>();
                            for (Message.Attachment attachment : msg.getAttachments()) {
                                if (attachment.isImage()) {
                                    msgImageUrls.add(attachment.getUrl());
                                }
                            }
                            history.add(new AIAssistantService.ChatMessage(
                                    msg.getContentRaw(),
                                    msg.getAuthor().getId().equals(event.getJDA().getSelfUser().getId()),
                                    msgImageUrls
                            ));
                        }

                        List<String> currentImageUrls = new ArrayList<>();
                        for (Message.Attachment attachment : event.getMessage().getAttachments()) {
                            if (attachment.isImage()) {
                                currentImageUrls.add(attachment.getUrl());
                            }
                        }
                        history.add(new AIAssistantService.ChatMessage(content, false, currentImageUrls));

                        String discordId = event.getAuthor().getId();
                        String discordName = event.getAuthor().getName();
                        String response = aiService.askGemini(history, discordId, discordName);

                        newThread.sendMessage(response).queue(msg -> {
                            saveThreadToDatabase(newThread.getId(), newThread.getName(), content);
                        });
                    }).start();
                }, error -> logger.error("Failed to create thread for follow-up question", error));
            }, error -> {
                logger.error("Failed to retrieve old thread history", error);
                createNormalThread(event, content);
            });
        } else {
            createNormalThread(event, content);
        }
    }

    // HANDLE THREAD MESSAGE
    private void handleThreadMessage(MessageReceivedEvent event, ThreadChannel thread) {
        Message ref = event.getMessage().getReferencedMessage();
        boolean isReplyToBot = (ref != null && ref.getAuthor().getId().equals(event.getJDA().getSelfUser().getId()));
        boolean isMentioned = event.getMessage().getMentions().isMentioned(event.getJDA().getSelfUser());

        if (!isReplyToBot && !isMentioned) {
            return;
        }

        String content = event.getMessage().getContentRaw();
        if (!isQuery(content)) {
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
                    List<String> msgImageUrls = new ArrayList<>();
                    for (Message.Attachment attachment : msg.getAttachments()) {
                        if (attachment.isImage()) {
                            msgImageUrls.add(attachment.getUrl());
                        }
                    }
                    history.add(new AIAssistantService.ChatMessage(msg.getContentRaw(), msg.getAuthor().getId().equals(event.getJDA().getSelfUser().getId()), msgImageUrls));
                }

                List<String> currentImageUrls = new ArrayList<>();
                for (Message.Attachment attachment : event.getMessage().getAttachments()) {
                    if (attachment.isImage()) {
                        currentImageUrls.add(attachment.getUrl());
                    }
                }
                history.add(new AIAssistantService.ChatMessage(event.getMessage().getContentRaw(), false, currentImageUrls));

                String discordId = event.getAuthor().getId();
                String discordName = event.getAuthor().getName();
                String response = aiService.askGemini(history, discordId, discordName);
                thread.sendMessage(response).queue();
            }).start();
        }, error -> logger.error("Failed to get thread history for AI assistant", error));
    }

    // SAVE THREAD TO DATABASE
    private void saveThreadToDatabase(String threadId, String threadName, String question) {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO ai_threads (thread_id, thread_name, original_question) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE thread_name = ?, original_question = ?")) {
            ps.setString(1, threadId);
            ps.setString(2, threadName);
            ps.setString(3, question);
            ps.setString(4, threadName);
            ps.setString(5, question);
            ps.executeUpdate();
        } catch (Exception e) {
            logger.error("Failed to save thread to database", e);
        }
    }

    // GET ALL THREADS FROM DATABASE
    private List<AIAssistantService.ThreadInfo> getAllThreadsFromDatabase() {
        List<AIAssistantService.ThreadInfo> threads = new ArrayList<>();
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT thread_id, thread_name, original_question FROM ai_threads ORDER BY created_at DESC LIMIT 300");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                threads.add(new AIAssistantService.ThreadInfo(
                        rs.getString("thread_id"),
                        rs.getString("thread_name"),
                        rs.getString("original_question")
                ));
            }
        } catch (Exception e) {
            logger.error("Failed to load threads from database", e);
        }
        return threads;
    }

    // THREAD EXISTS IN JDA
    private boolean threadExistsInJDA(net.dv8tion.jda.api.entities.Guild guild, String threadId) {
        ThreadChannel thread = guild.getThreadChannelById(threadId);
        return thread != null;
    }

    // EXTRACT THREAD ID FROM MENTION MESSAGE
    private String extractThreadIdFromMentionMessage(String messageContent) {
        Pattern pattern = Pattern.compile("<#(\\d+)>");
        Matcher matcher = pattern.matcher(messageContent);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    // FIND LOCAL MATCH
    private String findLocalMatch(String query, List<AIAssistantService.ThreadInfo> threads) {
        String normalizedQuery = normalizeArabic(query);
        for (AIAssistantService.ThreadInfo thread : threads) {
            String normalizedThreadName = normalizeArabic(thread.name);
            String normalizedThreadQuestion = normalizeArabic(thread.originalQuestion);
            if (normalizedQuery.equals(normalizedThreadName) || normalizedQuery.equals(normalizedThreadQuestion)) {
                return thread.id;
            }
            if (normalizedQuery.contains(normalizedThreadName) && normalizedThreadName.length() > 5) {
                return thread.id;
            }
        }
        return null;
    }

    // NORMALIZE ARABIC
    private String normalizeArabic(String text) {
        if (text == null) return "";
        return text.toLowerCase()
            .replaceAll("[أإآ]", "ا")
            .replaceAll("ة", "ه")
            .replaceAll("ى", "ي")
            .replaceAll("[؟\\?\\!\\.\\,\\-\\_]", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    // IS QUERY
    private boolean isQuery(String message) {
        if (message == null) return false;
        String clean = message.trim().toLowerCase();
        if (clean.isEmpty()) return false;

        String[] words = clean.split("\\s+");
        if (words.length == 0) return false;

        if (words.length <= 2) {
            boolean hasQuestionMark = clean.contains("؟") || clean.contains("?");
            boolean hasQuestionWord = false;
            String[] questionKeywords = {
                "كيف", "شلون", "وش", "ايش", "ماذا", "هل", "ابي", "ابغى", "وين", "من", "كم", "كيفية", "طريقة", "طريقه", "كيفيه", "ارتفاع", "الارتفاع", "y", "x", "z", "craft", "how", "where", "what", "who", "why"
            };
            for (String kw : questionKeywords) {
                if (clean.contains(kw)) {
                    hasQuestionWord = true;
                    break;
                }
            }
            if (!hasQuestionMark && !hasQuestionWord) {
                return false;
            }
        }

        String[] ignoredWords = {
            "شكرا", "شكرًا", "يسلمو", "تمام", "اوكي", "ok", "حلو", "كفو", "يب", "نعم", "لا", "هلا", "منور", "طيب", "اخيرا", "اخيراا", "finally", "thanks", "thank"
        };
        boolean allIgnored = true;
        for (String w : words) {
            boolean isIgnored = false;
            for (String iw : ignoredWords) {
                if (w.replaceAll("[؟\\?\\!\\.\\,\\-\\_]", "").equals(iw)) {
                    isIgnored = true;
                    break;
                }
            }
            if (!isIgnored) {
                allIgnored = false;
                break;
            }
        }
        if (allIgnored) {
            return false;
        }

        return true;
    }
}
