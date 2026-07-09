package com.highcore.bot.listeners;

import net.dv8tion.jda.api.JDA;
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
    private static final Pattern JOIN_PATTERN  = Pattern.compile("(?i)\\b([a-zA-Z0-9_]{3,16})\\b.*(?:joined|connected|دخل|انضم)");
    private static final Pattern LEAVE_PATTERN = Pattern.compile("(?i)\\b([a-zA-Z0-9_]{3,16})\\b.*(?:left|disconnected|lost connection|خرج|غادر)");

    // Section: Server lifecycle patterns — used to clear stale player set on stop/start
    private static final Pattern SERVER_STOP_PATTERN  = Pattern.compile("(?i)(server|السيرفر).*(stopped|shutdown|offline|توقف|أغلق)|(stopped|shutdown).*(server|السيرفر)");
    private static final Pattern SERVER_START_PATTERN = Pattern.compile("(?i)(server|السيرفر).*(started|online|running|شغّل|بدأ)|(started|running).*(server|السيرفر)");

    // Section: Team Logs
    private static final Pattern SABOTAGE_PATTERN = Pattern.compile("\\[CoreClaims-Sabotage\\] (.*)");
    private static final Pattern SABOTAGE_SUCCESS_PATTERN = Pattern.compile("\\[CoreClaims-SabotageSuccess\\] (.*?) (\\d+)");
    private static final Pattern TEAM_CHAT_PATTERN = Pattern.compile("\\[CoreClaims-TeamChat\\] \\[(.*?)\\] (.*?): (.*)");
    private static final Pattern FUEL_PATTERN = Pattern.compile("(?i)Generator #\\d+ for team (.*?) has run out of fuel!");
    private static final Pattern LEVELUP_PATTERN = Pattern.compile("\\[CoreClaims-LevelUp\\] \\[(.*?)\\] (\\d+):(\\d+)");

    public static final String LOG_CHANNEL_ID = "1487148944667578368";

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        String channelId    = event.getChannel().getId();
        String targetChannel = com.highcore.bot.services.ServerStatsService.getLogChannelId();

        if (channelId.equals(targetChannel)) {
            String content = event.getMessage().getContentRaw();
            if (content.isEmpty() && !event.getMessage().getEmbeds().isEmpty()) {
                content = extractEmbedText(event.getMessage().getEmbeds().get(0));
            } else if (!event.getMessage().getEmbeds().isEmpty()) {
                content = content + " " + extractEmbedText(event.getMessage().getEmbeds().get(0));
            }
            handleLogMessage(content, false);
        }
    }

    private static String extractEmbedText(MessageEmbed embed) {
        if (embed == null) return "";
        StringBuilder sb = new StringBuilder();
        if (embed.getAuthor() != null && embed.getAuthor().getName() != null) {
            sb.append(embed.getAuthor().getName()).append(" ");
        }
        if (embed.getTitle() != null) {
            sb.append(embed.getTitle()).append(" ");
        }
        if (embed.getDescription() != null) {
            sb.append(embed.getDescription()).append(" ");
        }
        return sb.toString().trim();
    }

    public static void handleLogMessage(String content, boolean isInit) {
        if (content == null || content.trim().isEmpty()) return;

        // Strip markdown bold/italics markers to ensure raw string comparisons work
        String clean = content.replace("*", "").trim();

        // Section: Server lifecycle — clear stale players on stop or start
        if (SERVER_STOP_PATTERN.matcher(clean).find() || clean.toLowerCase().contains("has stopped")) {
            onlinePlayers.clear();
            if (!isInit) {
                System.out.println("[MinecraftLogListener] Server STOPPED detected — cleared online players set.");
                com.highcore.bot.services.ServerStatsService.notifyServerStopped();
            }
            return;
        }
        if (SERVER_START_PATTERN.matcher(clean).find() || clean.toLowerCase().contains("has started")) {
            onlinePlayers.clear();
            if (!isInit) {
                System.out.println("[MinecraftLogListener] Server STARTED detected — cleared online players set.");
                com.highcore.bot.services.ServerStatsService.notifyServerStarted();
            }
            return;
        }

        Matcher joinMatcher = JOIN_PATTERN.matcher(clean);
        if (joinMatcher.find()) {
            String username = joinMatcher.group(1);
            onlinePlayers.add(username);
            if (!isInit) {
                System.out.println("[MinecraftLogListener] Player JOINED: " + username + " (Current count: " + onlinePlayers.size() + ")");
                // Section: Increment persistent login counter on every confirmed join
                com.highcore.bot.services.ServerStatsService.incrementTotalLogins();
            }
            return;
        }

        Matcher leaveMatcher = LEAVE_PATTERN.matcher(clean);
        if (leaveMatcher.find()) {
            String username = leaveMatcher.group(1);
            onlinePlayers.remove(username);
            if (!isInit) System.out.println("[MinecraftLogListener] Player LEFT: " + username + " (Current count: " + onlinePlayers.size() + ")");
            return;
        }

        // Section: Check for Sabotage
        Matcher saboMatcher = SABOTAGE_PATTERN.matcher(clean);
        if (saboMatcher.find()) {
            String teamName = saboMatcher.group(1).trim();
            if (!isInit) {
                com.highcore.bot.services.DiscordTeamAlertService.sendSabotageAlert(teamName);
            }
            return;
        }

        Matcher saboSuccessMatcher = SABOTAGE_SUCCESS_PATTERN.matcher(clean);
        if (saboSuccessMatcher.find()) {
            String teamName = saboSuccessMatcher.group(1).trim();
            String duration = saboSuccessMatcher.group(2).trim();
            if (!isInit) {
                com.highcore.bot.services.DiscordTeamAlertService.sendSabotageSuccessAlert(teamName, duration);
            }
            return;
        }

        // Section: Check for Fuel Out
        Matcher fuelMatcher = FUEL_PATTERN.matcher(clean);
        if (fuelMatcher.find()) {
            String teamName = fuelMatcher.group(1).trim();
            if (!isInit) {
                com.highcore.bot.services.DiscordTeamAlertService.sendFuelAlert(teamName);
            }
            return;
        }

        Matcher levelMatcher = LEVELUP_PATTERN.matcher(clean);
        if (levelMatcher.find()) {
            String teamName = levelMatcher.group(1).trim();
            String newLevel = levelMatcher.group(2).trim();
            String bonus = levelMatcher.group(3).trim();
            if (!isInit) {
                com.highcore.bot.services.DiscordTeamAlertService.sendLevelUpAlert(teamName, newLevel, bonus);
            }
            return;
        }

        // Section: Check for Team Chat
        Matcher teamChatMatcher = TEAM_CHAT_PATTERN.matcher(clean);
        if (teamChatMatcher.find()) {
            String teamName = teamChatMatcher.group(1).trim();
            String playerName = teamChatMatcher.group(2).trim();
            String chatMessage = teamChatMatcher.group(3).trim();
            if (!isInit) {
                com.highcore.bot.services.DiscordTeamAlertService.sendTeamChat(teamName, playerName, chatMessage);
            }
            return;
        }
    }

    /**
     * Scans the log channel history on startup to reconstruct the set of online players.
     * Respects server stop/start events — only players from the latest server session are counted.
     */
    public static void initializeOnlinePlayers(JDA jda) {
        onlinePlayers.clear();
        String targetChannelId = com.highcore.bot.services.ServerStatsService.getLogChannelId();

        TextChannel channel = jda.getTextChannelById(targetChannelId);
        if (channel != null) {
            System.out.println("[MinecraftLogListener] Scanning history of channel: " + channel.getName());
            channel.getHistory().retrievePast(100).queue(messages -> {
                // Section: Reconstruct chronological events (oldest message first)
                // Respects server lifecycle — stop/start events reset the player set
                for (int i = messages.size() - 1; i >= 0; i--) {
                    Message msg = messages.get(i);
                    String content = msg.getContentRaw();
                    if (content.isEmpty() && !msg.getEmbeds().isEmpty()) {
                        content = extractEmbedText(msg.getEmbeds().get(0));
                    } else if (!msg.getEmbeds().isEmpty()) {
                        content = content + " " + extractEmbedText(msg.getEmbeds().get(0));
                    }

                    // Section: Track the timestamp of the last "Server has started" for uptime accuracy
                    String clean = content.replace("*", "").trim();
                    if (SERVER_START_PATTERN.matcher(clean).find() || clean.toLowerCase().contains("has started")) {
                        onlinePlayers.clear();
                        long startedAt = msg.getTimeCreated().toInstant().toEpochMilli();
                        com.highcore.bot.services.ServerStatsService.setOnlineSince(startedAt);
                        continue;
                    }
                    if (SERVER_STOP_PATTERN.matcher(clean).find() || clean.toLowerCase().contains("has stopped")) {
                        onlinePlayers.clear();
                        com.highcore.bot.services.ServerStatsService.setOnlineSince(-1);
                        continue;
                    }

                    handleLogMessage(content, true);
                }
                System.out.println("[MinecraftLogListener] Reconstructed set: " + onlinePlayers + " (" + onlinePlayers.size() + " online)");
            });
        } else {
            System.out.println("[MinecraftLogListener] Log channel not found by ID: " + targetChannelId);
        }
    }
}
