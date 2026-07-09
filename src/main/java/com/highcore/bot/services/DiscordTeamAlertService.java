package com.highcore.bot.services;

import com.highcore.bot.LeonTrotskyBot;
import com.highcore.bot.utils.EmbedUtil;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
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
                        String title = "حالة طوارئ: هجوم على المولد!";
                        String body = mention + "\n> **المولد الخاص بكم يتعرض لهجوم حالياً (Sabotage)!**\n> يرجى التوجه للدفاع عنه فوراً!";
                        channel.sendMessage(new MessageCreateBuilder().setComponents(EmbedUtil.createPanel(title, body)).useComponentsV2(true).build()).queue();
                    }
                }
            }
            TeamLogService.logEvent(teamName, "تنبيه هجوم", "تم رصد محاولة تدمير (Sabotage) لأحد المولدات!", java.awt.Color.RED);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void sendSabotageSuccessAlert(String teamName, String duration) {
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
                        String title = "تم اختراق المولد!";
                        String body = mention + "\n> **تم اختراق المولد بنجاح من قبل الأعداء!**\n> أراضيكم الآن مكشوفة وقابلة للتدمير لمدة `" + duration + "` دقائق!";
                        channel.sendMessage(new MessageCreateBuilder().setComponents(EmbedUtil.createPanel(title, body)).useComponentsV2(true).build()).queue();
                    }
                }
            }
            TeamLogService.logEvent(teamName, "تم الاختراق", "تم اختراق المولد وأراضيكم الآن مكشوفة للهجوم!", java.awt.Color.RED);
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
                        String title = "ترقية مستوى التيم";
                        String body = mention + "\n> **تم رفع مستوى التيم إلى اللفل `" + newLevel + "` بنجاح!**\n> حصلتم على **`" + bonusClaims + "`** أراضي إضافية!";
                        channel.sendMessage(new MessageCreateBuilder().setComponents(EmbedUtil.createPanel(title, body)).useComponentsV2(true).build()).queue();
                    }
                }
            }
            TeamLogService.logEvent(teamName, "ترقية التيم", "تم رفع المستوى إلى " + newLevel + " مع إضافة " + bonusClaims + " مساحات إضافية.", java.awt.Color.CYAN);
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
                        channel.sendMessage("### **[" + playerName + "]**\n> " + chatMessage).queue();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void sendFuelAlert(String teamName, String percent) {
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
                        String title = percent.equals("0") ? "تحذير: نفاذ طاقة المولد!" : "تنبيه: انخفاض طاقة المولد";
                        String body;
                        if (percent.equals("0")) {
                            body = mention + "\n> **أحد مولداتكم قد نفذت منه الطاقة (Fuel) تماماً!**\n> المولد متوقف حالياً، يرجى تزويده بالطاقة لاستمرار الحماية.";
                        } else {
                            body = mention + "\n> **طاقة أحد المولدات وصلت إلى " + percent + "% !**\n> يرجى تزويده بالطاقة قبل توقفه.";
                        }
                        channel.sendMessage(new net.dv8tion.jda.api.utils.messages.MessageCreateBuilder().setComponents(com.highcore.bot.utils.EmbedUtil.createPanel(title, body)).useComponentsV2(true).build()).queue();
                    }
                }
            }
            TeamLogService.logEvent(teamName, "تنبيه طاقة", "نفذت الطاقة من أحد المولدات.", java.awt.Color.ORANGE);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
