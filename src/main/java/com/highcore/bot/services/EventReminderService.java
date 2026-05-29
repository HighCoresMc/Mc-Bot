package com.highcore.bot.services;

import com.highcore.bot.LeonTrotskyBot;
import com.highcore.bot.utils.TimeUtils;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class EventReminderService {
    private static final Logger logger = LoggerFactory.getLogger(EventReminderService.class);
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private static final String EVENT_ROLE_ID = "1509885693818699776";

    public static void startScheduler(JDA jda) {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                checkAndSendReminders(jda);
            } catch (Exception e) {
                logger.error("Error in EventReminderService", e);
            }
        }, 1, 1, TimeUnit.MINUTES);
        logger.info("EventReminderService scheduler started.");
    }

    private static void checkAndSendReminders(JDA jda) {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String q = "SELECT id, name, event_date, channel_id FROM events WHERE status != 'CANCELLED' AND status != 'FINISHED' AND reminder_sent = FALSE";
            try (PreparedStatement ps = conn.prepareStatement(q);
                 ResultSet rs = ps.executeQuery()) {
                
                long now = System.currentTimeMillis() / 1000; // current time in seconds

                while (rs.next()) {
                    int eventId = rs.getInt("id");
                    String name = rs.getString("name");
                    String dateStr = rs.getString("event_date");
                    String channelId = rs.getString("channel_id");

                    long eventUnixTime = TimeUtils.parseToUnixTimestamp(dateStr);
                    if (eventUnixTime == -1) continue;

                    // If the event is exactly 30 minutes (1800 seconds) or less away
                    if (eventUnixTime - now <= 1800 && eventUnixTime > now) {
                        sendReminder(jda, eventId, name, channelId, conn);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error checking reminders", e);
        }
    }

    private static void sendReminder(JDA jda, int eventId, String name, String channelId, Connection conn) throws Exception {
        if (channelId != null && !channelId.isEmpty()) {
            TextChannel channel = jda.getTextChannelById(channelId);
            if (channel != null) {
                channel.sendMessage("🔔 تذكير: فعالية **" + name + "** ستبدأ بعد أقل من 30 دقيقة! <@&" + EVENT_ROLE_ID + ">").queue();
            }
        }

        // Send DMs to those who requested
        String q2 = "SELECT discord_id FROM event_participants WHERE event_id = ? AND wants_reminder = TRUE";
        try (PreparedStatement ps2 = conn.prepareStatement(q2)) {
            ps2.setInt(1, eventId);
            ResultSet rs2 = ps2.executeQuery();
            while (rs2.next()) {
                String uid = rs2.getString("discord_id");
                User u = jda.getUserById(uid);
                if (u != null) {
                    u.openPrivateChannel().queue(pc -> pc.sendMessage("تذكير: فعاليتك " + name + " ستبدأ بعد أقل من 30 دقيقة!").queue(s -> {}, e -> {}));
                }
            }
        }

        // Mark reminder as sent
        String mark = "UPDATE events SET reminder_sent = TRUE WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(mark)) {
            ps.setInt(1, eventId);
            ps.executeUpdate();
        }
        logger.info("Sent automatic 30-min reminder for event " + eventId);
    }
}
