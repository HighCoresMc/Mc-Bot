package com.highcore.bot.commands;

import com.highcore.bot.LeonTrotskyBot;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.thumbnail.Thumbnail;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.separator.Separator;

import java.awt.Color;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;
import com.highcore.bot.services.PterodactylService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
            Container errorContainer = Container.of(
                TextDisplay.of("## ❌ لم يتم العثور على بيانات اللاعب"),
                TextDisplay.of("حساب الديسكورد هذا غير مربوط بحساب ماينكرافت داخل قاعدة البيانات.")
            );
            event.getHook().editOriginalComponents(errorContainer)
                .setEmbeds(java.util.Collections.emptyList())
                .useComponentsV2(true)
                .queue();
            return;
        }
        
        sendProfileEmbed(event.getHook(), uuidOpt.get(), targetUser.getId(), targetUser.getName(), "general");
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith("prof_")) return;
        String[] parts = id.split("_");
        event.deferEdit().queue();
        
        String discordId = parts.length > 3 ? parts[3] : "";
        String effName = parts.length > 4 ? parts[4] : "";
        sendProfileEmbed(event.getHook(), parts[2], discordId, effName, parts[1]);
    }

    private Optional<String> getUuidFromDatabase(String discordId) {
        String query = "SELECT uuid FROM `discordsrv__accounts` WHERE discord = ?";
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection();
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

    private void sendProfileEmbed(net.dv8tion.jda.api.interactions.InteractionHook hook, String uuid, String discordId, String discordName, String type) {
        String mcName = "Unknown";
        boolean dataFound = false;

        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String uuidDash = uuid.trim().toLowerCase();
            if (uuidDash.length() == 32 && !uuidDash.contains("-")) {
                uuidDash = uuidDash.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5");
            }
            String uuidNoDash = uuidDash.replace("-", "");

            String backupUsername = null;
            if (discordId != null && !discordId.isEmpty()) {
                String getUsernameQuery = "SELECT username FROM `discordsrv__accounts` WHERE discord = ?";
                try (PreparedStatement psName = conn.prepareStatement(getUsernameQuery)) {
                    psName.setString(1, discordId);
                    try (ResultSet rsName = psName.executeQuery()) {
                        if (rsName.next()) {
                            backupUsername = rsName.getString("username");
                        }
                    }
                } catch (Exception ignored) {}
            }

            if (backupUsername == null) {
                backupUsername = discordName;
            }
            mcName = backupUsername;

            String query = "SELECT username, Balance, TotalPlayTime, LastLoginTime, LastLogoffTime, `Rank`, Skin FROM CMI_users WHERE player_uuid = ? OR player_uuid = ? OR username = ? OR username = ?";
            try (PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setString(1, uuidDash);
                ps.setString(2, uuidNoDash);
                ps.setString(3, backupUsername);
                ps.setString(4, discordName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        dataFound = true;
                        mcName = rs.getString("username");
                        String skinValue = rs.getString("Skin");
                        Thumbnail avatar = Thumbnail.fromUrl(getAvatarUrl(skinValue, mcName, uuid));
                        Container container = null;

                        switch (type) {
                            case "general": {
                                String rank = rs.getString("Rank");
                                long playtimeMs = rs.getLong("TotalPlayTime");
                                long lastLogin = rs.getLong("LastLoginTime");
                                long lastLogoff = rs.getLong("LastLogoffTime");
                                
                                if (lastLogin > lastLogoff) {
                                    long sessionMs = System.currentTimeMillis() - lastLogin;
                                    if (sessionMs > 0) {
                                        playtimeMs += sessionMs;
                                    }
                                }

                                long playtimeSeconds = playtimeMs / 1000;
                                long days = playtimeSeconds / 86400;
                                long hours = (playtimeSeconds % 86400) / 3600;
                                long minutes = (playtimeSeconds % 3600) / 60;
                                
                                StringBuilder timeStr = new StringBuilder();
                                if (days > 0) timeStr.append(days).append(" يوم و ");
                                if (hours > 0) timeStr.append(hours).append(" ساعة و ");
                                timeStr.append(minutes).append(" دقيقة");

                                container = Container.of(
                                    Section.of(
                                        avatar,
                                        TextDisplay.of("## 👤 ملف اللاعب: " + mcName),
                                        TextDisplay.of("### 🌐 Information"),
                                        TextDisplay.of("**الرتبة:** " + (rank != null && !rank.isEmpty() ? rank : "لا توجد") + "\n**وقت اللعب:** " + timeStr.toString())
                                    ),
                                    Separator.createDivider(Separator.Spacing.SMALL),
                                    ActionRow.of(
                                        Button.primary("prof_general_" + uuid + "_" + discordId + "_" + backupUsername, "🌐 General"),
                                        Button.success("prof_surv_" + uuid + "_" + discordId + "_" + backupUsername, "⚔️ Survival"),
                                        Button.danger("prof_pvp_" + uuid + "_" + uuid + "_" + backupUsername, "🔫 PvP"),
                                        Button.secondary("prof_side_" + uuid + "_" + discordId + "_" + backupUsername, "🌀 Side")
                                    )
                                );
                                break;
                            }
                            case "surv": {
                                double balance = rs.getDouble("Balance");
                                int tokens = 0;
                                String ppQuery = "SELECT points FROM playerpoints WHERE uuid = ? OR uuid = ?";
                                try (PreparedStatement psPP = conn.prepareStatement(ppQuery)) {
                                    psPP.setString(1, uuidDash);
                                    psPP.setString(2, uuidNoDash);
                                    try (ResultSet rsPP = psPP.executeQuery()) {
                                        if (rsPP.next()) {
                                            tokens = rsPP.getInt("points");
                                        }
                                    }
                                } catch (Exception ignored) {}

                                container = Container.of(
                                    Section.of(
                                        avatar,
                                        TextDisplay.of("## 👤 ملف اللاعب: " + mcName),
                                        TextDisplay.of("### ⚔️ إحصائيات السرفايفل"),
                                        TextDisplay.of("**رصيد CMI:** " + String.format("%,.2f", balance) + "$\n**Tokens:** " + String.format("%,d", tokens))
                                    ),
                                    Separator.createDivider(Separator.Spacing.SMALL),
                                    ActionRow.of(
                                        Button.primary("prof_general_" + uuid + "_" + discordId + "_" + backupUsername, "🌐 General"),
                                        Button.success("prof_surv_" + uuid + "_" + discordId + "_" + backupUsername, "⚔️ Survival"),
                                        Button.danger("prof_pvp_" + uuid + "_" + discordId + "_" + backupUsername, "🔫 PvP"),
                                        Button.secondary("prof_side_" + uuid + "_" + discordId + "_" + backupUsername, "🌀 Side")
                                    )
                                );
                                break;
                            }
                            case "pvp": {
                                int kills = 0;
                                int deaths = 0;
                                try {
                                    String statsJsonStr = pterodactylService.getFileContents("world/stats/" + uuidDash + ".json");
                                    if (statsJsonStr != null && !statsJsonStr.isEmpty()) {
                                        JsonObject statsJson = JsonParser.parseString(statsJsonStr).getAsJsonObject();
                                        if (statsJson.has("stats")) {
                                            JsonObject statsObj = statsJson.getAsJsonObject("stats");
                                            if (statsObj.has("minecraft:custom")) {
                                                JsonObject customObj = statsObj.getAsJsonObject("minecraft:custom");
                                                if (customObj.has("minecraft:player_kills")) {
                                                    kills = customObj.get("minecraft:player_kills").getAsInt();
                                                }
                                                if (customObj.has("minecraft:deaths")) {
                                                    deaths = customObj.get("minecraft:deaths").getAsInt();
                                                }
                                            }
                                        }
                                    }
                                } catch (Exception ignored) {}
                                double kd = 0.0;
                                if (deaths > 0) {
                                    kd = (double) kills / deaths;
                                } else if (kills > 0) {
                                    kd = kills;
                                }
                                container = Container.of(
                                    Section.of(
                                        avatar,
                                        TextDisplay.of("## 👤 ملف اللاعب: " + mcName),
                                        TextDisplay.of("### 🔫 إحصائيات الـ PvP"),
                                        TextDisplay.of("**القتلات (Kills):** " + kills + "\n**الوفيات (Deaths):** " + deaths + "\n**نسبة K/D:** " + String.format(java.util.Locale.US, "%.2f", kd))
                                    ),
                                    Separator.createDivider(Separator.Spacing.SMALL),
                                    ActionRow.of(
                                        Button.primary("prof_general_" + uuid + "_" + discordId + "_" + backupUsername, "🌐 General"),
                                        Button.success("prof_surv_" + uuid + "_" + discordId + "_" + backupUsername, "⚔️ Survival"),
                                        Button.danger("prof_pvp_" + uuid + "_" + discordId + "_" + backupUsername, "🔫 PvP"),
                                        Button.secondary("prof_side_" + uuid + "_" + discordId + "_" + backupUsername, "🌀 Side")
                                    )
                                );
                                break;
                            }
                            case "side": {
                                boolean isOnline = false;
                                if (mcName != null && !mcName.trim().isEmpty()) {
                                    String searchName = mcName.trim();
                                    isOnline = com.highcore.bot.listeners.MinecraftLogListener.onlinePlayers.stream()
                                        .anyMatch(name -> name.equalsIgnoreCase(searchName));
                                }
                                container = Container.of(
                                    Section.of(
                                        avatar,
                                        TextDisplay.of("## 👤 ملف اللاعب: " + mcName),
                                        TextDisplay.of("### 🌀 الإحصائيات الجانبية"),
                                        TextDisplay.of("**النقاط الجانبية:** 0\n**الحالة:** " + (isOnline ? "متصل (Online)" : "غير متصل (Offline)"))
                                    ),
                                    Separator.createDivider(Separator.Spacing.SMALL),
                                    ActionRow.of(
                                        Button.primary("prof_general_" + uuid + "_" + discordId + "_" + backupUsername, "🌐 General"),
                                        Button.success("prof_surv_" + uuid + "_" + discordId + "_" + backupUsername, "⚔️ Survival"),
                                        Button.danger("prof_pvp_" + uuid + "_" + discordId + "_" + backupUsername, "🔫 PvP"),
                                        Button.secondary("prof_side_" + uuid + "_" + discordId + "_" + backupUsername, "🌀 Side")
                                    )
                                );
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
            
            if (!dataFound) {
                Thumbnail avatar = Thumbnail.fromUrl(getAvatarUrl(null, mcName, uuid));
                Container errorContainer = Container.of(
                    Section.of(
                        avatar,
                        TextDisplay.of("## 👤 ملف اللاعب: " + mcName),
                        TextDisplay.of("### ❌ لم يتم العثور على بيانات كافية"),
                        TextDisplay.of("الحساب مربوط بنجاح بالديسكورد، ولكن لم يتم تسجيل بيانات له داخل CMI بعد.\nيرجى دخول السيرفر لتوليد ملف اللاعب بالكامل.")
                    ),
                    Separator.createDivider(Separator.Spacing.SMALL),
                    ActionRow.of(
                        Button.primary("prof_general_" + uuid + "_" + discordId + "_" + backupUsername, "🌐 General").withDisabled(true),
                        Button.success("prof_surv_" + uuid + "_" + discordId + "_" + backupUsername, "⚔️ Survival").withDisabled(true),
                        Button.danger("prof_pvp_" + uuid + "_" + discordId + "_" + backupUsername, "🔫 PvP").withDisabled(true),
                        Button.secondary("prof_side_" + uuid + "_" + discordId + "_" + backupUsername, "🌀 Side").withDisabled(true)
                    )
                );
                hook.editOriginalComponents(errorContainer)
                    .setEmbeds(java.util.Collections.emptyList())
                    .useComponentsV2(true)
                    .queue();
            }
        } catch (Exception E) {
            E.printStackTrace();
            Container errorContainer = Container.of(
                TextDisplay.of("## ⚠️ خطأ في الاتصال بقاعدة البيانات"),
                TextDisplay.of("حدث خطأ أثناء محاولة الاتصال بقاعدة البيانات.")
            );
            hook.editOriginalComponents(errorContainer)
                .setEmbeds(java.util.Collections.emptyList())
                .useComponentsV2(true)
                .queue();
        }
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