package com.highcore.bot.services;

import com.highcore.bot.LeonTrotskyBot;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

// STREAK EXPIRY REMINDER SERVICE
public class DailyReminderService {
    private static final Logger logger = LoggerFactory.getLogger(DailyReminderService.class);
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // SCHEDULER INITIALIZATION
    public static void startScheduler(JDA jda) {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                checkAndSendWarnings(jda);
            } catch (Exception e) {
                logger.error("Error in DailyReminderService", e);
            }
        }, 5, 60, TimeUnit.MINUTES);
        logger.info("DailyReminderService scheduler started.");
    }

    // CHECK AND SEND WARNINGS
    private static void checkAndSendWarnings(JDA jda) {
        long now = System.currentTimeMillis();

        // DATABASE OPERATIONS
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String query = "SELECT discord_id, streak, last_claim_time FROM daily_streaks WHERE streak >= 5 AND warning_sent = FALSE AND last_claim_time IS NOT NULL";
            try (PreparedStatement ps = conn.prepareStatement(query);
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    String discordId = rs.getString("discord_id");
                    int streak = rs.getInt("streak");
                    Timestamp lastClaimTime = rs.getTimestamp("last_claim_time");

                    if (lastClaimTime != null) {
                        long lastClaimMs = lastClaimTime.getTime();
                        long diffMs = now - lastClaimMs;

                        if (diffMs >= 36 * 60 * 60 * 1000L && diffMs < 48 * 60 * 60 * 1000L) {
                            sendWarningDM(jda, discordId, streak);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to check daily streak warnings", e);
        }
    }

    // DATABASE OPERATIONS
    private static void sendWarningDM(JDA jda, String discordId, int streak) {
        jda.retrieveUserById(discordId).queue(user -> {
            Container warningContainer = Container.of(
                TextDisplay.of("## ⚠️ تنبيه الـ Streak الخاص بك!"),
                TextDisplay.of("لقد مرّت أكثر من 36 ساعة على آخر مكافأة يومية استلمتها.\n\n" +
                               "يرجى استخدام أمر `/daily` في الخادم لتجنب خسارة سلسلة أيامك المتتالية (**" + streak + "** يوم 🔥).")
            );

            user.openPrivateChannel().queue(pc -> {
                pc.sendMessage(new MessageCreateBuilder().setComponents(warningContainer).useComponentsV2(true).build()).queue(
                    success -> updateWarningStatus(discordId),
                    error -> {
                        logger.warn("Failed to send warning DM to user " + discordId);
                        updateWarningStatus(discordId);
                    }
                );
            });
        }, err -> {
            logger.warn("Failed to retrieve user " + discordId + " for warning DM");
            updateWarningStatus(discordId);
        });
    }

    // DATABASE OPERATIONS
    private static void updateWarningStatus(String discordId) {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String updateQuery = "UPDATE daily_streaks SET warning_sent = TRUE WHERE discord_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(updateQuery)) {
                ps.setString(1, discordId);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            logger.error("Failed to update warning status for user " + discordId, e);
        }
    }
}
