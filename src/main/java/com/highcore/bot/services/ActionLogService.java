package com.highcore.bot.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

// ACTION LOG SERVICE
public class ActionLogService {

    private static final Logger logger = LoggerFactory.getLogger(ActionLogService.class);

    // Log Channels
    private static final String CHANNEL_COMMANDS    = "1500224210667044965";
    private static final String CHANNEL_GAMES_DAILY = "1500808391633801216";
    private static final String CHANNEL_MAINTENANCE  = "1510585301360181259";

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static String now() {
        return ZonedDateTime.now(ZoneId.of("Asia/Riyadh")).format(FORMATTER);
    }

    // Commands Log
    public static void logCommand(JDA jda, String action, String userId, String userName, String details) {
        send(jda, CHANNEL_COMMANDS, action, userId, userName, details);
    }

    public static void logCommand(JDA jda, String action, String userId, String userName) {
        send(jda, CHANNEL_COMMANDS, action, userId, userName, null);
    }

    // Games + Daily Log
    public static void logGame(JDA jda, String action, String userId, String userName, String details) {
        send(jda, CHANNEL_GAMES_DAILY, action, userId, userName, details);
    }

    public static void logGame(JDA jda, String action, String userId, String userName) {
        send(jda, CHANNEL_GAMES_DAILY, action, userId, userName, null);
    }

    // Maintenance Log
    public static void logMaintenance(JDA jda, String action, String userId, String userName, String details) {
        send(jda, CHANNEL_MAINTENANCE, action, userId, userName, details);
    }

    public static void logMaintenance(JDA jda, String action, String userId, String userName) {
        send(jda, CHANNEL_MAINTENANCE, action, userId, userName, null);
    }

    private static void send(JDA jda, String channelId, String action, String userId, String userName, String details) {
        if (jda == null) return;
        try {
            TextChannel channel = jda.getTextChannelById(channelId);
            if (channel == null) {
                logger.warn("[ActionLog] Channel not found: {}", channelId);
                return;
            }

            String userLine = (userName != null && userId != null)
                    ? "@" + userName + " (<@" + userId + ">)"
                    : userId != null ? "<@" + userId + ">"
                    : "System";

            Container logContainer = Container.of(
                TextDisplay.of("## 📋 ─── Action Executed ───"),
                TextDisplay.of("**► HighCore MC・Activity Log**"),
                Separator.createDivider(Separator.Spacing.SMALL),
                TextDisplay.of(
                    "**Action:**\n`" + action + "`\n\n" +
                    "**User:**\n" + userLine + "\n\n" +
                    (details != null ? "**Details:**\n" + details + "\n\n" : "") +
                    "**Time:** `" + now() + "`"
                )
            );

            channel.sendMessage(
                new MessageCreateBuilder()
                    .setComponents(logContainer)
                    .useComponentsV2(true)
                    .build()
            ).queue(
                success -> {},
                error -> logger.error("[ActionLog] Failed to send log to {}: {}", channelId, error.getMessage())
            );

        } catch (Exception e) {
            logger.error("[ActionLog] Exception while sending log", e);
        }
    }
}
