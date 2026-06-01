package com.highcore.bot.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Locale;

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

            String userLine = userId != null
                    ? "<@" + userId + "> (" + userId + ")"
                    : "System";

            EmbedBuilder embedBuilder = new EmbedBuilder();
            embedBuilder.setAuthor("Action Executed");
            embedBuilder.setTitle("► HighCore MC • Activity Log");
            embedBuilder.setColor(Color.decode("#00A86B"));

            embedBuilder.addField("Action:", "`" + action + "`", false);
            embedBuilder.addField("User:", userLine, false);
            if (details != null && !details.isEmpty()) {
                embedBuilder.addField("Details:", details, false);
            }

            String footerTime = ZonedDateTime.now(ZoneId.of("Asia/Riyadh"))
                    .format(DateTimeFormatter.ofPattern("M/d/yyyy h:mm a", Locale.ENGLISH));
            embedBuilder.setFooter("• UNIFIED TERMINAL v1.2.0 • HIGHCORE MC • " + footerTime, null);

            channel.sendMessage(
                new MessageCreateBuilder()
                    .setEmbeds(embedBuilder.build())
                    .setAllowedMentions(Collections.emptyList())
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
