package com.highcore.bot.commands;

import com.highcore.bot.LeonTrotskyBot;
import com.highcore.bot.services.ActionLogService;
import com.highcore.bot.services.PterodactylService;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.thumbnail.Thumbnail;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.Optional;

// DAILY COMMAND IMPLEMENTATION
public class DailyCommand extends ListenerAdapter {
    private final PterodactylService pterodactylService = new PterodactylService();

    // SLASH COMMAND EVENT
    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("daily")) return;
        ActionLogService.logCommand(event.getJDA(), "/daily", event.getUser().getId(), event.getUser().getName(), "طلب استلام المكافأة اليومية");
        event.deferReply(true).queue();

        String discordId = event.getUser().getId();
        Optional<String> uuidOpt = getUuidFromDatabase(discordId);

        if (uuidOpt.isEmpty()) {
            Container errorContainer = Container.of(
                TextDisplay.of("## ❌ لم يتم العثور على بيانات اللاعب"),
                TextDisplay.of("حساب الديسكورد هذا غير مربوط بحساب ماينكرافت داخل قاعدة البيانات.\nيرجى ربط حسابك أولاً باستخدام DiscordSRV داخل اللعبة.")
            );
            event.getHook().editOriginalComponents(errorContainer)
                .setEmbeds(java.util.Collections.emptyList())
                .useComponentsV2(true)
                .queue();
            return;
        }

        String uuid = uuidOpt.get();
        String mcName = getMcNameFromDatabase(uuid, discordId, event.getUser().getName());

        if (mcName.equals("Unknown")) {
            Container errorContainer = Container.of(
                TextDisplay.of("## ❌ لم يتم العثور على بيانات اللاعب في اللعبة"),
                TextDisplay.of("حسابك مربوط بالديسكورد، ولكن لم يتم تسجيل اسم اللاعب الخاص بك في السيرفر بعد.\nيرجى دخول السيرفر أولاً ثم المحاولة مجدداً.")
            );
            event.getHook().editOriginalComponents(errorContainer)
                .setEmbeds(java.util.Collections.emptyList())
                .useComponentsV2(true)
                .queue();
            return;
        }

        long now = System.currentTimeMillis();
        int currentStreak = 0;
        Timestamp lastClaimTime = null;

        // DATABASE OPERATIONS
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String selectQuery = "SELECT streak, last_claim_time FROM daily_streaks WHERE discord_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(selectQuery)) {
                ps.setString(1, discordId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        currentStreak = rs.getInt("streak");
                        lastClaimTime = rs.getTimestamp("last_claim_time");
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            sendDatabaseError(event);
            return;
        }

        int newStreak = 1;
        if (lastClaimTime != null) {
            long lastClaimMs = lastClaimTime.getTime();
            long diffMs = now - lastClaimMs;

            if (diffMs < 22 * 60 * 60 * 1000L) {
                long nextClaimTimeMs = lastClaimMs + 22 * 60 * 60 * 1000L;
                Container cooldownContainer = Container.of(
                    Section.of(
                        Thumbnail.fromUrl("https://minotar.net/avatar/" + mcName + "/128"),
                        TextDisplay.of("## ⏱️ المكافأة اليومية | Cooldown"),
                        TextDisplay.of("لقد قمت باستلام المكافأة اليومية بالفعل.\n\n" +
                                       "يمكنك الاستلام مجدداً بعد: <t:" + (nextClaimTimeMs / 1000) + ":R> (<t:" + (nextClaimTimeMs / 1000) + ":t>)")
                    )
                );
                event.getHook().editOriginalComponents(cooldownContainer)
                    .setEmbeds(java.util.Collections.emptyList())
                    .useComponentsV2(true)
                    .queue();
                return;
            } else if (diffMs <= 48 * 60 * 60 * 1000L) {
                newStreak = currentStreak + 1;
            }
        }

        // REWARDS LOGIC
        String rewardText = "";
        String cmd = "";

        if (newStreak == 1) {
            rewardText = "100 فلوس CMI 💵";
            cmd = "cmi money add " + mcName + " 100";
        } else if (newStreak == 2) {
            rewardText = "150 فلوس CMI 💵";
            cmd = "cmi money add " + mcName + " 150";
        } else if (newStreak == 3) {
            rewardText = "200 فلوس CMI 💵";
            cmd = "cmi money add " + mcName + " 200";
        } else if (newStreak == 4) {
            rewardText = "250 فلوس CMI 💵";
            cmd = "cmi money add " + mcName + " 250";
        } else if (newStreak == 5) {
            rewardText = "300 فلوس CMI 💵";
            cmd = "cmi money add " + mcName + " 300";
        } else if (newStreak == 6) {
            rewardText = "350 فلوس CMI 💵";
            cmd = "cmi money add " + mcName + " 350";
        } else if (newStreak == 7) {
            rewardText = "200 خبرة (XP) 🧪";
            cmd = "cmi exp " + mcName + " add 200";
        } else if (newStreak == 8) {
            rewardText = "400 فلوس CMI 💵";
            cmd = "cmi money add " + mcName + " 400";
        } else if (newStreak == 9) {
            rewardText = "450 فلوس CMI 💵";
            cmd = "cmi money add " + mcName + " 450";
        } else if (newStreak == 10) {
            rewardText = "15 Tokens 🌀";
            cmd = "points give " + mcName + " 15";
        } else {
            int cycleIndex = newStreak % 3;
            if (cycleIndex == 1) {
                rewardText = "500 فلوس CMI 💵";
                cmd = "cmi money add " + mcName + " 500";
            } else if (cycleIndex == 2) {
                rewardText = "200 خبرة (XP) 🧪";
                cmd = "cmi exp " + mcName + " add 200";
            } else {
                rewardText = "15 Tokens 🌀";
                cmd = "points give " + mcName + " 15";
            }
        }

        // DATABASE OPERATIONS
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

        Container successContainer = Container.of(
            Section.of(
                Thumbnail.fromUrl("https://minotar.net/avatar/" + mcName + "/128"),
                TextDisplay.of("## 🎁 المكافأة اليومية | Daily Reward"),
                TextDisplay.of("### 🎉 تم استلام الجائزة بنجاح!"),
                TextDisplay.of("**اللاعب:** `" + mcName + "`\n" +
                               "**الـ Streak الحالي:** `" + newStreak + "` أيام 🔥\n" +
                               "**الجائزة:** " + rewardText + "\n\n" +
                               "تذكر استلام جائزتك غداً للحفاظ على سلسلة أيامك المتتالية!")
            )
        );

        ActionLogService.logGame(event.getJDA(), "🎁 Daily Reward Claimed",
            event.getUser().getId(), event.getUser().getName(),
            "**اللاعب:** `" + mcName + "`\n" +
            "**السلسلة (Streak):** `" + newStreak + "` أيام 🔥\n" +
            "**الجائزة:** " + rewardText + "\n" +
            "**الأمر المُنفَّذ:** `" + cmd + "`");

        event.getHook().editOriginalComponents(successContainer)
            .setEmbeds(java.util.Collections.emptyList())
            .useComponentsV2(true)
            .queue();
    }

    // DATABASE OPERATIONS
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

    // DATABASE OPERATIONS
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

    // DATABASE OPERATIONS
    private void sendDatabaseError(SlashCommandInteractionEvent event) {
        Container errorContainer = Container.of(
            TextDisplay.of("## ⚠️ خطأ في الاتصال بقاعدة البيانات"),
            TextDisplay.of("حدث خطأ أثناء محاولة الاتصال بقاعدة البيانات لمعالجة المكافأة اليومية.")
        );
        event.getHook().editOriginalComponents(errorContainer)
            .setEmbeds(java.util.Collections.emptyList())
            .useComponentsV2(true)
            .queue();
    }
}
