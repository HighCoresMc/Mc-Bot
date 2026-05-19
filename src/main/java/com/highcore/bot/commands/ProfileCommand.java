package com.highcore.bot.commands;

import com.highcore.bot.LeonTrotskyBot;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.actionrow.ActionRow;

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
        EmbedBuilder embed = new EmbedBuilder().setColor(Color.decode("#5865F2"));
        String mcName = "Unknown";
        boolean dataFound = false;

        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String query = "SELECT username, Balance, TotalPlayTime, Rank FROM CMI_users WHERE player_uuid = ?";
            try (PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setString(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        dataFound = true;
                        mcName = rs.getString("username");
                        embed.setTitle("👤 ملف اللاعب: " + mcName);
                        embed.setThumbnail("https://mc-heads.net/avatar/" + uuid + "/128");

                        switch (type) {
                            case "general":
                                embed.addField("🌐 المعلومات العامة", "", false);
                                String rank = rs.getString("Rank");
                                embed.addField("الرتبة", rank != null ? rank : "لا توجد", true);
                                embed.addField("وقت اللعب", (rs.getLong("TotalPlayTime") / 60) + " دقيقة", true);
                                break;
                            case "surv":
                                embed.addField("⚔️ إحصائيات السرفايفل", "", false);
                                embed.addField("رصيد CMI", String.format("%,.2f", rs.getDouble("Balance")), true);
                                String ppQuery = "SELECT points FROM playerpoints WHERE uuid = ?";
                                try (PreparedStatement psPP = conn.prepareStatement(ppQuery)) {
                                    psPP.setString(1, uuid);
                                    try (ResultSet rsPP = psPP.executeQuery()) {
                                        if (rsPP.next()) {
                                            embed.addField("Tokens", String.format("%,d", rsPP.getInt("points")), true);
                                        }
                                    }
                                } catch (Exception ignored) {}
                                break;
                        }
                    }
                }
            }
            
            if (!dataFound) {
                embed.setColor(Color.RED);
                embed.setTitle("❌ لم يتم العثور على بيانات اللاعب داخل CMI");
            }
        } catch (Exception E) {
            E.printStackTrace();
            embed.setColor(Color.RED);
            embed.setTitle("⚠️ خطأ في الاتصال بقاعدة البيانات");
        }

        hook.editOriginalEmbeds(embed.build())
                .setComponents(ActionRow.of(
                        Button.primary("prof_general_" + uuid, "🌐 General"),
                        Button.success("prof_surv_" + uuid, "⚔️ Survival"),
                        Button.danger("prof_pvp_" + uuid, "🔫 PvP"),
                        Button.secondary("prof_side_" + uuid, "🌀 Side")
                ))
                .queue();
    }
}