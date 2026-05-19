package com.highcore.bot.listeners;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MinecraftLogListener extends ListenerAdapter {
    public static final Set<String> onlinePlayers = ConcurrentHashMap.newKeySet();
    
    // RegEx patterns to extract Minecraft usernames safely (standard MC username: 3-16 chars, alphanumeric/underscores)
    private static final Pattern JOIN_PATTERN = Pattern.compile("(?i)\\b([a-zA-Z0-9_]{3,16})\\b.*(?:joined|connected|دخل|انضم)");
    private static final Pattern LEAVE_PATTERN = Pattern.compile("(?i)\\b([a-zA-Z0-9_]{3,16})\\b.*(?:left|disconnected|lost connection|خرج|غادر)");

    public static final String LOG_CHANNEL_ID = "1487148944667578368";

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        String channelId = event.getChannel().getId();
        io.github.cdimascio.dotenv.Dotenv dotenv = io.github.cdimascio.dotenv.Dotenv.configure().ignoreIfMissing().load();
        String targetChannelId = dotenv.get("MINECRAFT_LOG_CHANNEL_ID", LOG_CHANNEL_ID);

        if (channelId.equals(targetChannelId)) {
            // Process messages from bots/webhooks (e.g. DiscordSRV) or standard messages in logs
            String content = event.getMessage().getContentRaw();
            if (content.isEmpty() && !event.getMessage().getEmbeds().isEmpty()) {
                MessageEmbed embed = event.getMessage().getEmbeds().get(0);
                if (embed.getDescription() != null) {
                    content = embed.getDescription();
                } else if (embed.getTitle() != null) {
                    content = embed.getTitle();
                }
            }
            handleLogMessage(content);
        }
    }

    public static void handleLogMessage(String content) {
        if (content == null || content.trim().isEmpty()) return;

        // Strip markdown bold/italics markers to ensure raw string comparisons work
        String clean = content.replace("*", "").replace("_", "").trim();

        Matcher joinMatcher = JOIN_PATTERN.matcher(clean);
        if (joinMatcher.find()) {
            String username = joinMatcher.group(1);
            onlinePlayers.add(username);
            System.out.println("[MinecraftLogListener] Player JOINED: " + username + " (Current count: " + onlinePlayers.size() + ")");
            return;
        }

        Matcher leaveMatcher = LEAVE_PATTERN.matcher(clean);
        if (leaveMatcher.find()) {
            String username = leaveMatcher.group(1);
            onlinePlayers.remove(username);
            System.out.println("[MinecraftLogListener] Player LEFT: " + username + " (Current count: " + onlinePlayers.size() + ")");
        }
    }

    /**
     * Scans the log channel history on startup to reconstruct the set of online players.
     */
    public static void initializeOnlinePlayers(JDA jda) {
        onlinePlayers.clear();
        io.github.cdimascio.dotenv.Dotenv dotenv = io.github.cdimascio.dotenv.Dotenv.configure().ignoreIfMissing().load();
        String targetChannelId = dotenv.get("MINECRAFT_LOG_CHANNEL_ID", LOG_CHANNEL_ID);

        TextChannel channel = jda.getTextChannelById(targetChannelId);
        if (channel != null) {
            System.out.println("[MinecraftLogListener] Scanning history of channel: " + channel.getName());
            channel.getHistory().retrievePast(100).queue(messages -> {
                // Reconstruct chronological events (oldest message first)
                for (int i = messages.size() - 1; i >= 0; i--) {
                    Message msg = messages.get(i);
                    String content = msg.getContentRaw();
                    if (content.isEmpty() && !msg.getEmbeds().isEmpty()) {
                        MessageEmbed embed = msg.getEmbeds().get(0);
                        if (embed.getDescription() != null) {
                            content = embed.getDescription();
                        } else if (embed.getTitle() != null) {
                            content = embed.getTitle();
                        }
                    }
                    handleLogMessage(content);
                }
                System.out.println("[MinecraftLogListener] Reconstructed set: " + onlinePlayers + " (" + onlinePlayers.size() + " online)");
            });
        } else {
            System.out.println("[MinecraftLogListener] Log channel not found by ID: " + targetChannelId);
        }
    }
}
