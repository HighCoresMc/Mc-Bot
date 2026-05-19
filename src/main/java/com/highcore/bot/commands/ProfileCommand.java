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
        
        sendProfileEmbed(event.getHook(), uuidOpt.get(), "general");
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith("prof_")) return;
        String[] parts = id.split("_");
        event.deferEdit().queue();
        sendProfileEmbed(event.getHook(), parts[2], parts[1]);
    }

    private Optional<String> getUuidFromDatabase(String discordId) {
        String query = "SELECT uuid FROM `discordsrv__accounts` WHERE discord = ?";
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, discordId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.ofNullable(rs.getString("uuid"));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return Optional.empty();
    }

    private void sendProfileEmbed(net.dv8tion.jda.api.interactions.InteractionHook hook, String uuid, String type) {
        String mcName = "Unknown";
        boolean dataFound = false;

        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String query = "SELECT username, Balance, TotalPlayTime, `Rank` FROM CMI_users WHERE player_uuid = ?";
            try (PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setString(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        dataFound = true;
                        mcName = rs.getString("username");
                        Thumbnail avatar = Thumbnail.fromUrl("https://mc-heads.net/avatar/" + uuid + "/128");
                        Container container = null;

                        switch (type) {
                            case "general": {
                                String rank = rs.getString("Rank");
                                long playtimeSeconds = rs.getLong("TotalPlayTime");
                                long hours = playtimeSeconds / 3600;
                                long minutes = (playtimeSeconds % 3600) / 60;
                                String playtimeStr = hours > 0 ? (hours + " ساعة و " + minutes + " دقيقة") : (minutes + " دقيقة");

                                container = Container.of(
                                    Section.of(
                                        avatar,
                                        TextDisplay.of("## 👤 ملف اللاعب: " + mcName),
                                        TextDisplay.of("### 🌐 المعلومات العامة"),
                                        TextDisplay.of("**الرتبة:** " + (rank != null && !rank.isEmpty() ? rank : "لا توجد") + "\n**وقت اللعب:** " + playtimeStr)
                                    ),
                                    Separator.createDivider(Separator.Spacing.SMALL),
                                    ActionRow.of(
                                        Button.primary("prof_general_" + uuid, "🌐 General"),
                                        Button.success("prof_surv_" + uuid, "⚔️ Survival"),
                                        Button.danger("prof_pvp_" + uuid, "🔫 PvP"),
                                        Button.secondary("prof_side_" + uuid, "🌀 Side")
                                    )
                                );
                                break;
                            }
                            case "surv": {
                                double balance = rs.getDouble("Balance");
                                int tokens = 0;
                                String ppQuery = "SELECT points FROM playerpoints WHERE uuid = ?";
                                try (PreparedStatement psPP = conn.prepareStatement(ppQuery)) {
                                    psPP.setString(1, uuid);
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
                                        Button.primary("prof_general_" + uuid, "🌐 General"),
                                        Button.success("prof_surv_" + uuid, "⚔️ Survival"),
                                        Button.danger("prof_pvp_" + uuid, "🔫 PvP"),
                                        Button.secondary("prof_side_" + uuid, "🌀 Side")
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
                                        Button.primary("prof_general_" + uuid, "🌐 General"),
                                        Button.success("prof_surv_" + uuid, "⚔️ Survival"),
                                        Button.danger("prof_pvp_" + uuid, "🔫 PvP"),
                                        Button.secondary("prof_side_" + uuid, "🌀 Side")
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
                                        Button.primary("prof_general_" + uuid, "🌐 General"),
                                        Button.success("prof_surv_" + uuid, "⚔️ Survival"),
                                        Button.danger("prof_pvp_" + uuid, "🔫 PvP"),
                                        Button.secondary("prof_side_" + uuid, "🌀 Side")
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
                    TextDisplay.of("لم يتم العثور على بيانات اللاعب داخل CMI.")
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