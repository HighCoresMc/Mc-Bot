package com.highcore.bot.services;

import com.highcore.bot.LeonTrotskyBot;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.awt.Color;

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
                        EmbedBuilder embed = new EmbedBuilder();
                        embed.setTitle(title);
                        embed.setDescription(description);
                        embed.setColor(color);
                        channel.sendMessageEmbeds(embed.build()).queue();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}