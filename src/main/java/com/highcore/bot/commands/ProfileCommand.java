package com.highcore.bot.commands;

import com.highcore.bot.LeonTrotskyBot;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.buttons.Button;

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

        Optional<String> uuidOpt = LeonTrotskyBot.getDiscordSRVManager().getUuidByDiscordId(targetUser.getId());
        
        if (uuidOpt.isEmpty()) {
            event.getHook().sendMessage("❌ لا يوجد حساب ماينكرافت مربوط بهذا الديسكورد!").queue();
            return;
        }
        
        String uuid = uuidOpt.get();
        sendProfileEmbed(event.getHook(), uuid, "general");
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith("prof_")) return;

        String[] parts = id.split("_");
        String type = parts[1]; // gen, surv, pvp, side
        String uuid = parts[2];

        event.deferEdit().queue();
        sendProfileEmbed(event.getHook(), uuid, type);
    }

    private void sendProfileEmbed(net.dv8tion.jda.api.interactions.InteractionHook hook, String uuid, String type) {
        EmbedBuilder embed = new EmbedBuilder().setColor(Color.decode("#5865F2"));
        String mcName = "Unknown";

        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection("CMI")) {
            // Get base data
            String query = "SELECT username, Balance, PlayTime, Rank FROM cmi_users WHERE player_uuid = ?";
            try (PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setString(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        mcName = rs.getString("username");
                        embed.setTitle("👤 ملف اللاعب: " + mcName);
                        embed.setThumbnail("https://mc-heads.net/avatar/" + uuid + "/128");

                        switch (type) {
                            case "general":
                                embed.addField("🌐 المعلومات العامة", "", false);
                                embed.addField("الرتبة", rs.getString("Rank"), true);
                                embed.addField("وقت اللعب", (rs.getLong("PlayTime") / 60) + " دقيقة", true);
                                embed.addField("UUID", uuid, false);
                                break;
                            case "surv":
                                embed.addField("⚔️ إحصائيات السرفايفل", "", false);
                                embed.addField("رصيد CMI", String.format("%,.2f", rs.getDouble("Balance")), true);
                                
                                // Fetch PlayerPoints
                                try (Connection connPP = LeonTrotskyBot.getDbManager().getConnection("PlayerPoints")) {
                                    String ppQuery = "SELECT points FROM playerpoints WHERE uuid = ?";
                                    try (PreparedStatement psPP = connPP.prepareStatement(ppQuery)) {
                                        psPP.setString(1, uuid);
                                        try (ResultSet rsPP = psPP.executeQuery()) {
                                            if (rsPP.next()) {
                                                embed.addField("Tokens", String.format("%,d", rsPP.getInt("points")), true);
                                            }
                                        }
                                    }
                                } catch (Exception ignored) {}
                                break;
                            case "pvp":
                                embed.addField("🔫 إحصائيات القتال", "سيتم سحب البيانات قريباً...", false);
                                break;
                            case "side":
                                embed.addField("🌀 الأطوار الجانبية", "قريباً...", false);
                                break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            hook.sendMessage("❌ خطأ في قاعدة البيانات").queue();
            return;
        }

        hook.editOriginalEmbeds(embed.build())
                .setActionRow(
                        Button.primary("prof_general_" + uuid, "🌐 General"),
                        Button.success("prof_surv_" + uuid, "⚔️ Survival"),
                        Button.danger("prof_pvp_" + uuid, "🔫 PvP"),
                        Button.secondary("prof_side_" + uuid, "🌀 Side")
                ).queue();
    }
}
