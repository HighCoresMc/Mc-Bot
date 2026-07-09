package com.highcore.bot.commands;

import com.highcore.bot.LeonTrotskyBot;
import com.highcore.bot.utils.EmbedUtil;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.container.Container;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;
import com.highcore.bot.services.PterodactylService;

public class ProfileCommand extends ListenerAdapter {
    private final PterodactylService pterodactylService = new PterodactylService();

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("profile")) return;
        event.deferReply().queue();

        net.dv8tion.jda.api.entities.User targetUser = event.getOption("user") != null 
                ? event.getOption("user").getAsUser() 
                : event.getUser();

        Optional<String> uuidOpt = getUuidFromDatabase(targetUser.getId());
        if (uuidOpt.isEmpty()) {
            Container errorContainer = EmbedUtil.createAlert("لم يتم العثور على حساب", "هذا اللاعب غير مسجل أو لم يقم بربط حسابه في خادم الديسكورد.");
            event.getHook().editOriginalComponents(errorContainer)
                .setEmbeds(java.util.Collections.emptyList())
                .useComponentsV2(true)
                .queue();
            return;
        }

        String uuid = uuidOpt.get();
        String mcName = getMcNameFromDatabase(uuid, targetUser.getId(), targetUser.getName());
        String backupUsername = mcName.equalsIgnoreCase("Unknown") ? targetUser.getName() : mcName;
        
        loadAndSendProfile(event.getHook(), uuid, targetUser.getId(), backupUsername, mcName, "general");
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String componentId = event.getComponentId();
        if (componentId.startsWith("prof_")) {
            event.deferEdit().queue();
            
            String[] parts = componentId.split("_");
            if (parts.length >= 5) {
                String tab = parts[1];
                String uuid = parts[2];
                String discordId = parts[3];
                String backupUsername = parts[4];
                String mcName = getMcNameFromDatabase(uuid, discordId, backupUsername);
                
                loadAndSendProfile(event.getHook(), uuid, discordId, backupUsername, mcName, tab);
            }
        }
    }

    private void loadAndSendProfile(net.dv8tion.jda.api.interactions.InteractionHook hook, String uuid, String discordId, String backupUsername, String mcName, String tab) {
        try {
            boolean dataFound = false;
            String uuidDash = uuid.trim().toLowerCase();
            if (uuidDash.length() == 32 && !uuidDash.contains("-")) {
                uuidDash = uuidDash.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5");
            }

            try (Connection conn = LeonTrotskyBot.getDbManager().isCmiPoolReady() ? LeonTrotskyBot.getDbManager().getCmiConnection() : LeonTrotskyBot.getDbManager().getConnection()) {
                String query = "SELECT * FROM CMI_users WHERE player_uuid = ?";
                try (PreparedStatement ps = conn.prepareStatement(query)) {
                    ps.setString(1, uuidDash);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            dataFound = true;
                            if (mcName.equalsIgnoreCase("Unknown")) {
                                mcName = rs.getString("username");
                            }

                            String rank = rs.getString("rank");
                            double balance = rs.getDouble("Balance");
                            long totalPlayTimeSecs = rs.getLong("TotalPlayTime");

                            int tokens = 0;
                            try (Connection lpConn = LeonTrotskyBot.getDbManager().getConnection()) {
                                String pointsQuery = "SELECT points FROM player_points WHERE uuid = ?";
                                try (PreparedStatement psPoints = lpConn.prepareStatement(pointsQuery)) {
                                    psPoints.setString(1, uuidDash);
                                    try (ResultSet rsPoints = psPoints.executeQuery()) {
                                        if (rsPoints.next()) {
                                            tokens = rsPoints.getInt("points");
                                        }
                                    }
                                }
                            } catch (Exception ignored) {}

                            int kills = 0;
                            int deaths = 0;
                            try (Connection statConn = LeonTrotskyBot.getDbManager().getConnection()) {
                                String statQuery = "SELECT kills, deaths FROM player_stats WHERE uuid = ?";
                                try (PreparedStatement psStat = statConn.prepareStatement(statQuery)) {
                                    psStat.setString(1, uuidDash);
                                    try (ResultSet rsStat = psStat.executeQuery()) {
                                        if (rsStat.next()) {
                                            kills = rsStat.getInt("kills");
                                            deaths = rsStat.getInt("deaths");
                                        }
                                    }
                                }
                            } catch (Exception ignored) {}

                            String skinValue = null;
                            try (Connection skinConn = LeonTrotskyBot.getDbManager().getConnection()) {
                                String skinQuery = "SELECT Value FROM Skins WHERE Nick = ?";
                                try (PreparedStatement psSkin = skinConn.prepareStatement(skinQuery)) {
                                    psSkin.setString(1, mcName);
                                    try (ResultSet rsSkin = psSkin.executeQuery()) {
                                        if (rsSkin.next()) {
                                            skinValue = rsSkin.getString("Value");
                                        }
                                    }
                                }
                            } catch (Exception ignored) {}

                            String avatarUrl = getAvatarUrl(skinValue, mcName, uuid);
                            
                            ActionRow buttons = ActionRow.of(
                                Button.primary("prof_general_" + uuid + "_" + discordId + "_" + backupUsername, "عام").withDisabled(tab.equals("general")),
                                Button.success("prof_surv_" + uuid + "_" + discordId + "_" + backupUsername, "سيرفايفل").withDisabled(tab.equals("surv")),
                                Button.danger("prof_pvp_" + uuid + "_" + discordId + "_" + backupUsername, "القتال (PvP)").withDisabled(tab.equals("pvp")),
                                Button.secondary("prof_side_" + uuid + "_" + discordId + "_" + backupUsername, "إضافي").withDisabled(tab.equals("side"))
                            );

                            Container container = null;
                            String title = "ملف اللاعب: " + mcName;

                            switch (tab) {
                                case "general": {
                                    long days = totalPlayTimeSecs / 86400;
                                    long hours = (totalPlayTimeSecs % 86400) / 3600;
                                    long minutes = ((totalPlayTimeSecs % 86400) % 3600) / 60;
                                    StringBuilder timeStr = new StringBuilder();
                                    if (days > 0) timeStr.append(days).append(" أيام ");
                                    if (hours > 0) timeStr.append(hours).append(" ساعات ");
                                    timeStr.append(minutes).append(" دقائق");

                                    String body = "**الرتبة:** " + (rank != null && !rank.isEmpty() ? rank : "بدون رتبة") + "\n" +
                                                  "**وقت اللعب:** " + timeStr.toString();
                                    container = EmbedUtil.createProfilePanel(title, "المعلومات العامة<divider>" + body, avatarUrl, buttons);
                                    break;
                                }
                                case "surv": {
                                    String body = "**رصيد CMI:** " + String.format("%,.2f", balance) + "$\n" +
                                                  "**عملات (Tokens):** " + String.format("%,d", tokens);
                                    container = EmbedUtil.createProfilePanel(title, "إحصائيات السيرفايفل<divider>" + body, avatarUrl, buttons);
                                    break;
                                }
                                case "pvp": {
                                    double kd = 0.0;
                                    if (deaths > 0) {
                                        kd = (double) kills / deaths;
                                    } else if (kills > 0) {
                                        kd = kills;
                                    }
                                    String body = "**القتلات (Kills):** " + kills + "\n" +
                                                  "**الوفيات (Deaths):** " + deaths + "\n" +
                                                  "**معدل (K/D):** " + String.format(java.util.Locale.US, "%.2f", kd);
                                    container = EmbedUtil.createProfilePanel(title, "إحصائيات القتال (PvP)<divider>" + body, avatarUrl, buttons);
                                    break;
                                }
                                case "side": {
                                    boolean isOnline = false;
                                    if (mcName != null && !mcName.trim().isEmpty()) {
                                        String searchName = mcName.trim();
                                        isOnline = com.highcore.bot.listeners.MinecraftLogListener.onlinePlayers.stream()
                                            .anyMatch(name -> name.equalsIgnoreCase(searchName));
                                    }
                                    String body = "**تم إضافته مستقبلاً:** 0\n" +
                                                  "**حالة الاتصال:** " + (isOnline ? "متصل (Online)" : "غير متصل (Offline)");
                                    container = EmbedUtil.createProfilePanel(title, "إحصائيات إضافية<divider>" + body, avatarUrl, buttons);
                                    break;
                                }
                            }

                            if (container != null) {
                                hook.editOriginalComponents(container)
                                    .setEmbeds(java.util.Collections.emptyList())
                                    .useComponentsV2(true)
                                    .queue();
                            }
                        }
                    }
                }
            }
            
            if (!dataFound) {
                String avatarUrl = getAvatarUrl(null, mcName, uuid);
                ActionRow disabledButtons = ActionRow.of(
                    Button.primary("prof_general_" + uuid + "_" + discordId + "_" + backupUsername, "عام").withDisabled(true),
                    Button.success("prof_surv_" + uuid + "_" + discordId + "_" + backupUsername, "سيرفايفل").withDisabled(true),
                    Button.danger("prof_pvp_" + uuid + "_" + discordId + "_" + backupUsername, "القتال (PvP)").withDisabled(true),
                    Button.secondary("prof_side_" + uuid + "_" + discordId + "_" + backupUsername, "إضافي").withDisabled(true)
                );
                Container errorContainer = EmbedUtil.createProfilePanel("ملف اللاعب: " + mcName, "لم يتم العثور على بيانات<divider>تعذر العثور على اللاعب في قاعدة بيانات السيرفر، ربما لم يدخل السيرفر بعد.", avatarUrl, disabledButtons);
                hook.editOriginalComponents(errorContainer)
                    .setEmbeds(java.util.Collections.emptyList())
                    .useComponentsV2(true)
                    .queue();
            }
        } catch (Exception E) {
            E.printStackTrace();
            Container errorContainer = EmbedUtil.createAlert("خطأ في النظام", "حدث خطأ غير متوقع أثناء محاولة استرجاع البيانات.");
            hook.editOriginalComponents(errorContainer)
                .setEmbeds(java.util.Collections.emptyList())
                .useComponentsV2(true)
                .queue();
        }
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

    private String getAvatarUrl(String skinValue, String mcName, String uuid) {
        if (skinValue != null && !skinValue.trim().isEmpty()) {
            skinValue = skinValue.trim();
            if (isBase64(skinValue)) {
                try {
                    String decoded = new String(java.util.Base64.getDecoder().decode(skinValue), java.nio.charset.StandardCharsets.UTF_8);
                    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("texture/([a-fA-F0-9]+)").matcher(decoded);
                    if (matcher.find()) {
                        return "https://mc-heads.net/avatar/" + matcher.group(1) + "/128";
                    }
                } catch (Exception ignored) {}
            }
            if (skinValue.matches("^[a-zA-Z0-9_\\-]+$")) {
                return "https://minotar.net/avatar/" + skinValue + "/128";
            }
        }
        if (mcName != null && !mcName.trim().isEmpty()) {
            return "https://minotar.net/avatar/" + mcName.trim() + "/128";
        }
        return "https://minotar.net/avatar/" + uuid + "/128";
    }

    private boolean isBase64(String str) {
        try {
            java.util.Base64.getDecoder().decode(str);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
