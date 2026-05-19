package com.highcore.bot.commands;

import com.highcore.bot.services.ServerStatsService;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

public class StatsCommand extends ListenerAdapter {

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("stats")) return;
        
        event.deferReply(true).queue(); // Ephemeral reply to avoid channel cluttering
        
        try {
            ServerStatsService.forceUpdate(event.getJDA());
            event.getHook().sendMessage("✅ تم تحديث حالة السيرفر بنجاح وجاري المراقبة المستمرة!").queue();
        } catch (Exception e) {
            e.printStackTrace();
            event.getHook().sendMessage("⚠️ حدث خطأ أثناء تحديث حالة السيرفر.").queue();
        }
    }
}
