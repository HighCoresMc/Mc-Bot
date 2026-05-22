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

public class ProfileCommand extends ListenerAdapter {

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("profile")) return;
        event.deferReply().queue();

        net.dv8tion.jda.api.entities.User targetUser = event.getOption("user") != null 
                ? event.getOption("user").getAsUser() 
                : event.getUser();

        Optional<String> uuidOpt = getUuidFromDatabase(targetUser.getId());
        if (uuidOpt.isEmpty()) {
            EmbedBuilder errorEmbed = new EmbedBuilder()
                    .setColor(Color.RED)
                    .setDescription("❌ لا يوجد حساب ماينكرافت مربوط بهذا الديسكورد!");
            event.getHook().editOriginalEmbeds(errorEmbed.build()).queue();
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

            String query = "SELECT username, Balance, TotalPlayTime, LastLoginTime, LastLogoffTime, `Rank` FROM CMI_users WHERE player_uuid = ? OR player_uuid = ? OR username = ? OR username = ?";
            try (PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setString(1, uuidDash);
                ps.setString(2, uuidNoDash);
                ps.setString(3, backupUsername);
                ps.setString(4, discordName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        dataFound = true;
                        mcName = rs.getString("username");
                        Thumbnail avatar = Thumbnail.fromUrl("https://mc-heads.net/avatar/" + uuid + "/128");
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
                                        TextDisplay.of("### 🌐 المعلومات العامة"),
                                        TextDisplay.of("**الرتبة:** " + (rank != null && !rank.isEmpty() ? rank : "لا توجد") + "\n**وقت اللعب:** " + timeStr.toString())
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
                                container = Container.of(
                                    Section.of(
                                        avatar,
                                        TextDisplay.of("## 👤 ملف اللاعب: " + mcName),
                                        TextDisplay.of("### 🔫 إحصائيات الـ PvP"),
                                        TextDisplay.of("**القتلات (Kills):** 0\n**الوفيات (Deaths):** 0\n**نسبة K/D:** 0.00")
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
                                container = Container.of(
                                    Section.of(
                                        avatar,
                                        TextDisplay.of("## 👤 ملف اللاعب: " + mcName),
                                        TextDisplay.of("### 🌀 الإحصائيات الجانبية"),
                                        TextDisplay.of("**النقاط الجانبية:** 0\n**الحالة:** نشط (Active)")
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
                Container errorContainer = Container.of(
                    TextDisplay.of("## ❌ لم يتم العثور على بيانات اللاعب"),
                    TextDisplay.of("لم يتم العثور على بيانات اللاعب داخل CMI بالـ UUID أو باسم الحساب المربوط.")
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
}