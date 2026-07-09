package com.highcore.bot.services;

import com.highcore.bot.LeonTrotskyBot;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class DiscordTeamAlertService {

    public static void sendSabotageAlert(String teamName) {
        String query = "SELECT text_channel_id, role_id FROM teams WHERE name = ?";
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(query)) {
            
            ps.setString(1, teamName);
            ResultSet rs = ps.executeQuery();
            
            if (rs.next()) {
                String textChannelId = rs.getString("text_channel_id");
                String roleId = rs.getString("role_id");
                
                if (textChannelId != null && !textChannelId.isEmpty()) {
                    TextChannel channel = LeonTrotskyBot.getJda().getTextChannelById(textChannelId);
                    if (channel != null) {
                        String mention = (roleId != null && !roleId.isEmpty()) ? "<@&" + roleId + "> " : "";
                        String message = mention + "🚨 **تحذير خطير!** المولد الخاص بفريقكم يتعرض للاختراق (Sabotage) الآن! القاعدة الخاصة بكم مكشوفة!";
                        channel.sendMessage(message).queue();
                    }
                }
            }
            TeamLogService.logEvent(teamName, "🚨 اختراق للمولد!", "المولد الخاص بالتيم يتعرض للاختراق الآن!", java.awt.Color.RED);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void sendLevelUpAlert(String teamName, String newLevel, String bonusClaims) {
        String query = "SELECT text_channel_id, role_id FROM teams WHERE name = ?";
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(query)) {
            
            ps.setString(1, teamName);
            ResultSet rs = ps.executeQuery();
            
            if (rs.next()) {
                String textChannelId = rs.getString("text_channel_id");
                String roleId = rs.getString("role_id");
                
                if (textChannelId != null && !textChannelId.isEmpty()) {
                    TextChannel channel = LeonTrotskyBot.getJda().getTextChannelById(textChannelId);
                    if (channel != null) {
                        String mention = (roleId != null && !roleId.isEmpty()) ? "<@&" + roleId + "> " : "";
                        String message = mention + "🎉 **مبروك!** تم رفع مستوى التيم إلى اللفل **" + newLevel + "**!\n" +
                                         "🎁 لقد حصلتم على **" + bonusClaims + "** أراضي (Claims) إضافية!";
                        channel.sendMessage(message).queue();
                    }
                }
            }
            TeamLogService.logEvent(teamName, "⭐ ترقية التيم", "تم رفع مستوى التيم إلى اللفل " + newLevel + " وتمت إضافة " + bonusClaims + " أراضي!", java.awt.Color.CYAN);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void sendTeamChat(String teamName, String playerName, String chatMessage) {
        String query = "SELECT text_channel_id, role_id FROM teams WHERE name = ?";
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(query)) {
            
            ps.setString(1, teamName);
            ResultSet rs = ps.executeQuery();
            
            if (rs.next()) {
                String textChannelId = rs.getString("text_channel_id");
                String roleId = rs.getString("role_id");
                
                if (textChannelId != null && !textChannelId.isEmpty()) {
                    TextChannel channel = LeonTrotskyBot.getJda().getTextChannelById(textChannelId);
                    if (channel != null) {
                        String mention = (roleId != null && !roleId.isEmpty()) ? "<@&" + roleId + "> " : "";
                        String message = mention + "💬 **[لعبة - دردشة الفريق]** " + playerName + ": " + chatMessage;
                        channel.sendMessage(message).queue();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void sendFuelAlert(String teamName) {
        String query = "SELECT text_channel_id, role_id FROM teams WHERE name = ?";
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(query)) {
            
            ps.setString(1, teamName);
            ResultSet rs = ps.executeQuery();
            
            if (rs.next()) {
                String textChannelId = rs.getString("text_channel_id");
                String roleId = rs.getString("role_id");
                
                if (textChannelId != null && !textChannelId.isEmpty()) {
                    TextChannel channel = LeonTrotskyBot.getJda().getTextChannelById(textChannelId);
                    if (channel != null) {
                        String mention = (roleId != null && !roleId.isEmpty()) ? "<@&" + roleId + "> " : "";
                        String message = mention + "⚠️ **تنبيه نقص الوقود!** المولد الخاص بفريقكم قد نفذ منه الوقود (Fuel)! الأراضي قد تصبح مكشوفة للاختراق!";
                        channel.sendMessage(message).queue();
                    }
                }
            }
            TeamLogService.logEvent(teamName, "⚠️ نفاد الوقود", "نفذ الوقود من المولد الخاص بالتيم!", java.awt.Color.ORANGE);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}