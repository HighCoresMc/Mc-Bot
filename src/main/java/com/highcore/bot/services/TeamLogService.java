package com.highcore.bot.services;

import com.highcore.bot.LeonTrotskyBot;
import com.highcore.bot.utils.EmbedUtil;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.awt.Color;
import java.time.Instant;

// Team Log Service
public class TeamLogService {

    public static void logEvent(String teamName, String title, String description, Color color) {
        String query = "SELECT log_channel_id FROM teams WHERE name = ?";
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(query)) {
            
            ps.setString(1, teamName);
            ResultSet rs = ps.executeQuery();
            
            if (rs.next()) {
                String logChannelId = rs.getString("log_channel_id");
                if (logChannelId != null && !logChannelId.isEmpty()) {
                    TextChannel channel = LeonTrotskyBot.getJda().getTextChannelById(logChannelId);
                    if (channel != null) {
                        String fullTitle = "► HighCore MC ・ " + title;
                        String body = "### 📋 سجل عمليات التيم\n" +
                                      "> **الإجراء:** `" + title.replace("►", "").replace("⭐", "").trim() + "`\n" +
                                      "> **التفاصيل:** " + description + "\n" +
                                      "> **التاريخ:** <t:" + Instant.now().getEpochSecond() + ":F>";

                        channel.sendMessage(new MessageCreateBuilder()
                                .setComponents(EmbedUtil.createPanel(fullTitle, body))
                                .useComponentsV2(true).build()).queue();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}