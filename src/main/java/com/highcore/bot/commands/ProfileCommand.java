package com.highcore.bot.commands;

import com.highcore.bot.LeonTrotskyBot;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
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

        event.deferReply().queue(); // Because DB queries might take a second

        // Try to get target user from arguments, otherwise use the sender
        net.dv8tion.jda.api.entities.User targetUser = event.getOption("user") != null 
                ? event.getOption("user").getAsUser() 
                : event.getUser();

        // 1. Get Minecraft UUID from DiscordSRV
        Optional<String> uuidOpt = LeonTrotskyBot.getDiscordSRVManager().getUuidByDiscordId(targetUser.getId());
        
        if (uuidOpt.isEmpty()) {
            event.getHook().sendMessage("❌ لا يوجد حساب ماينكرافت مربوط بهذا الديسكورد!").queue();
            return;
        }
        
        String uuid = uuidOpt.get();

        // 2. Fetch CMI Data
        String mcName = "Unknown";
        double balance = 0.0;
        long playTime = 0;
        
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection("CMI")) {
            // Note: We will adjust the exact table name based on CMI's schema
            String query = "SELECT username, Balance, PlayTime FROM cmi_users WHERE player_uuid = ?";
            try (PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setString(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        mcName = rs.getString("username");
                        balance = rs.getDouble("Balance");
                        playTime = rs.getLong("PlayTime");
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            event.getHook().sendMessage("❌ حدث خطأ أثناء الاتصال بقاعدة البيانات.").queue();
            return;
        }

        // 3. Build Embed
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("🌐 ملف اللاعب: " + mcName)
                .setColor(Color.decode("#5865F2"))
                .setThumbnail("https://mc-heads.net/avatar/" + uuid + "/100")
                .addField("💰 الرصيد (CMI)", String.format("%,.2f", balance), true)
                .addField("⏱️ وقت اللعب", (playTime / 60) + " دقيقة", true)
                // We will add more stats here from other plugins
                .setFooter("HighCore MC - Leon Trotsky Bot");

        // 4. Send Message with Buttons
        event.getHook().sendMessageEmbeds(embed.build())
                .addActionRow(
                        Button.primary("prof_gen_" + uuid, "🌐 General Info"),
                        Button.secondary("prof_surv_" + uuid, "⚔️ Survival"),
                        Button.danger("prof_pvp_" + uuid, "🔫 PvP"),
                        Button.success("prof_side_" + uuid, "🌀 Side Modes")
                ).queue();
    }
}
