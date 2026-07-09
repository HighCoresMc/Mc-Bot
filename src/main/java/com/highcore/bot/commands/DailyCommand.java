package com.highcore.bot.commands;

import com.highcore.bot.LeonTrotskyBot;
import com.highcore.bot.services.ActionLogService;
import com.highcore.bot.services.PterodactylService;
import com.highcore.bot.utils.EmbedUtil;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.components.container.Container;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;

public class DailyCommand extends ListenerAdapter {

    private final PterodactylService pterodactylService = new PterodactylService();

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("daily")) return;
        event.deferReply().queue();

        String discordId = event.getUser().getId();
        String discordName = event.getUser().getName();

        Optional<String> uuidOpt = getUuidFromDatabase(discordId);
        if (uuidOpt.isEmpty()) {
            Container errorContainer = EmbedUtil.createAlert("حساب غير مسجل", "يجب عليك ربط حسابك في الديسكورد بحساب اللعبة لتتمكن من استلام المكافأة اليومية.");
            event.getHook().editOriginalComponents(errorContainer)
                .setEmbeds(java.util.Collections.emptyList())
                .useComponentsV2(true)
                .queue();
            return;
        }

        String uuid = uuidOpt.get();
        String mcName = getMcNameFromDatabase(uuid, discordId, discordName);
        if (mcName.equals("Unknown")) {
            mcName = discordName; 
        }

        int currentStreak = 0;
        long lastClaimMillis = 0;

        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String checkQuery = "SELECT streak, UNIX_TIMESTAMP(last_claim_time) * 1000 AS last_claim_ms FROM daily_streaks WHERE discord_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(checkQuery)) {
                ps.setString(1, discordId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        currentStreak = rs.getInt("streak");
                        lastClaimMillis = rs.getLong("last_claim_ms");
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            sendDatabaseError(event);
            return;
        }

        long now = System.currentTimeMillis();
        long diff = now - lastClaimMillis;

        if (lastClaimMillis > 0 && diff < 86400000L) {
            long waitMillis = 86400000L - diff;
            long waitHours = waitMillis / 3600000L;
            long waitMins = (waitMillis % 3600000L) / 60000L;
            
            Container cooldownContainer = EmbedUtil.createAlert("المكافأة اليومية غير جاهزة", "لقد قمت باستلام المكافأة مؤخراً!\nيمكنك استلامها مرة أخرى بعد `" + waitHours + "` ساعة و `" + waitMins + "` دقيقة.");
            event.getHook().editOriginalComponents(cooldownContainer)
                .setEmbeds(java.util.Collections.emptyList())
                .useComponentsV2(true)
                .queue();
            return;
        }

        int newStreak = 1;
        if (lastClaimMillis > 0 && diff <= 172800000L) {
            newStreak = currentStreak + 1;
        }

        String rewardText = "";
        String cmd = "";

        if (newStreak == 1) {
            rewardText = "100 رصيد CMI";
            cmd = "cmi money add " + mcName + " 100";
        } else if (newStreak == 2) {
            rewardText = "150 رصيد CMI";
            cmd = "cmi money add " + mcName + " 150";
        } else if (newStreak == 3) {
            rewardText = "200 رصيد CMI";
            cmd = "cmi money add " + mcName + " 200";
        } else if (newStreak == 4) {
            rewardText = "250 رصيد CMI";
            cmd = "cmi money add " + mcName + " 250";
        } else if (newStreak == 5) {
            rewardText = "300 رصيد CMI";
            cmd = "cmi money add " + mcName + " 300";
        } else if (newStreak == 6) {
            rewardText = "350 رصيد CMI";
            cmd = "cmi money add " + mcName + " 350";
        } else if (newStreak == 7) {
            rewardText = "200 خبرة (XP)";
            cmd = "cmi exp " + mcName + " add 200";
        } else if (newStreak == 8) {
            rewardText = "400 رصيد CMI";
            cmd = "cmi money add " + mcName + " 400";
        } else if (newStreak == 9) {
            rewardText = "450 رصيد CMI";
            cmd = "cmi money add " + mcName + " 450";
        } else if (newStreak == 10) {
            rewardText = "15 توكن (Tokens)";
            cmd = "points give " + mcName + " 15";
        } else {
            int cycleIndex = newStreak % 3;
            if (cycleIndex == 1) {
                rewardText = "500 رصيد CMI";
                cmd = "cmi money add " + mcName + " 500";
            } else if (cycleIndex == 2) {
                rewardText = "200 خبرة (XP)";
                cmd = "cmi exp " + mcName + " add 200";
            } else {
                rewardText = "15 توكن (Tokens)";
                cmd = "points give " + mcName + " 15";
            }
        }

        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String replaceQuery = "REPLACE INTO daily_streaks (discord_id, mc_uuid, mc_name, streak, last_claim_time, warning_sent) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, FALSE)";
            try (PreparedStatement ps = conn.prepareStatement(replaceQuery)) {
                ps.setString(1, discordId);
                ps.setString(2, uuid);
                ps.setString(3, mcName);
                ps.setInt(4, newStreak);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            e.printStackTrace();
            sendDatabaseError(event);
            return;
        }

        if (!cmd.isEmpty()) {
            pterodactylService.sendConsoleCommand(cmd);
        }

        String body = "**اللاعب:** `" + mcName + "`\n" +
                      "**سلسلة الأيام (Streak):** `" + newStreak + "` يوم\n" +
                      "**المكافأة المكتسبة:** " + rewardText + "\n\n" +
                      "استمر في استلام الجوائز يومياً لزيادة قيمة المكافأة!";
                      
        Container successContainer = EmbedUtil.createProfilePanel("المكافأة اليومية", body, "https://minotar.net/avatar/" + mcName + "/128");

        ActionLogService.logGame(event.getJDA(), "استلام الجائزة اليومية",
            event.getUser().getId(), event.getUser().getName(),
            "**اللاعب:** `" + mcName + "`\n" +
            "**أيام الستريك:** `" + newStreak + "`\n" +
            "**المكافأة:** " + rewardText + "\n" +
            "**الأمر المنفذ:** `" + cmd + "`");

        event.getHook().editOriginalComponents(successContainer)
            .setEmbeds(java.util.Collections.emptyList())
            .useComponentsV2(true)
            .queue();
    }

    private Optional<String> getUuidFromDatabase(String discordId) {
        String query = "SELECT uuid FROM `discordsrv__accounts` WHERE discord = ?";
        try (Connection conn = LeonTrotskyBot.getDbManager().isCmiPoolReady() ? LeonTrotskyBot.getDbManager().getCmiConnection() : LeonTrotskyBot.getDbManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, discordId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String uuid = rs.getString("uuid");
                    if (uuid != null) {
                        uuid = uuid.trim().toLowerCase();
                        if (uuid.length() == 32 && !uuid.contains("-")) {
                            uuid = uuid.replaceFirst(
                                "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})",
                                "$1-$2-$3-$4-$5"
                            );
                        }
                    }
                    return Optional.ofNullable(uuid);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return Optional.empty();
    }

    private String getMcNameFromDatabase(String uuid, String discordId, String discordName) {
        String uuidDash = uuid.trim().toLowerCase();
        if (uuidDash.length() == 32 && !uuidDash.contains("-")) {
            uuidDash = uuidDash.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5");
        }
        String uuidNoDash = uuidDash.replace("-", "");

        String mcName = null;

        try (Connection conn = LeonTrotskyBot.getDbManager().isCmiPoolReady() ? LeonTrotskyBot.getDbManager().getCmiConnection() : LeonTrotskyBot.getDbManager().getConnection()) {
            String getUsernameQuery = "SELECT username FROM `discordsrv__accounts` WHERE discord = ?";
            try (PreparedStatement psName = conn.prepareStatement(getUsernameQuery)) {
                psName.setString(1, discordId);
                try (ResultSet rsName = psName.executeQuery()) {
                    if (rsName.next()) {
                        mcName = rsName.getString("username");
                    }
                }
            }
        } catch (Exception ignored) {}

        if (mcName != null && !mcName.isEmpty() && !mcName.equalsIgnoreCase("Unknown")) {
            return mcName;
        }

        try {
            Connection conn = LeonTrotskyBot.getDbManager().isCmiPoolReady()
                ? LeonTrotskyBot.getDbManager().getCmiConnection()
                : LeonTrotskyBot.getDbManager().getConnection();
            try (conn) {
                String query = "SELECT username FROM CMI_users WHERE player_uuid = ? OR player_uuid = ? OR username = ? OR username = ?";
                try (PreparedStatement ps = conn.prepareStatement(query)) {
                    ps.setString(1, uuidDash);
                    ps.setString(2, uuidNoDash);
                    ps.setString(3, discordName);
                    ps.setString(4, discordName);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return rs.getString("username");
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        return "Unknown";
    }

    private void sendDatabaseError(SlashCommandInteractionEvent event) {
        Container errorContainer = EmbedUtil.createAlert("خطأ في النظام", "حدث خطأ أثناء الاتصال بقاعدة البيانات. يرجى المحاولة لاحقاً.");
        event.getHook().editOriginalComponents(errorContainer)
            .setEmbeds(java.util.Collections.emptyList())
            .useComponentsV2(true)
            .queue();
    }
}
