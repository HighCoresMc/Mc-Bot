package com.highcore.bot.services;

import com.highcore.bot.utils.MinecraftPing;
import io.github.cdimascio.dotenv.Dotenv;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ServerStatsService {
    private static final Logger logger = LoggerFactory.getLogger(ServerStatsService.class);
    private static final String PERSISTENT_CHANNEL_ID = "1487139736748425236";

    private static int peakPlayers = 0;
    private static long totalChecks = 0;
    private static long successfulChecks = 0;
    private static long onlineSince = -1;

    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public static void startScheduler(JDA jda) {
        // scheduler.scheduleAtFixedRate(() -> updateStats(jda), 0, 1, TimeUnit.MINUTES);
    }

    public static void forceUpdate(JDA jda) {
        // updateStats(jda);
    }

    private static synchronized void updateStats(JDA jda) {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        String host = dotenv.get("MC_SERVER_HOST", "134.255.255.130");
        int port = Integer.parseInt(dotenv.get("MC_SERVER_PORT", "25010"));

        MinecraftPing.StatusResponse response = MinecraftPing.ping(host, port, 3000);
        
        boolean isOnline = response.online;
        int currentPlayers = isOnline ? response.onlinePlayers : 0;
        int maxPlayers = isOnline ? response.maxPlayers : 20;
        
        if (isOnline) {
            successfulChecks++;
            if (onlineSince == -1) onlineSince = System.currentTimeMillis();
            if (currentPlayers > peakPlayers) peakPlayers = currentPlayers;
        } else {
            onlineSince = -1;
        }
        totalChecks++;

        String uptimeStr = (isOnline && onlineSince != -1) ? formatDuration(System.currentTimeMillis() - onlineSince) : "Offline";
        double availability = totalChecks > 0 ? ((double) successfulChecks * 100.0 / totalChecks) : 0.0;

        String content = "### " + (isOnline ? "🟢 Server is Online" : "🔴 Server is Offline") + "\n" +
                         "**📊 Statistics**\n" +
                         "- 👥 Players: `" + currentPlayers + " / " + maxPlayers + "`\n" +
                         "- 📡 Ping: `" + (isOnline ? response.ping + "ms" : "N/A") + "`\n" +
                         "- ⏱️ Uptime: `" + uptimeStr + "`\n" +
                         "- 📈 Availability: `" + String.format("%.1f", availability) + "%`";

        // updateDiscordMessage(jda, content);
    }

    private static String formatDuration(long ms) {
        long sec = ms / 1000;
        return String.format("%dh %dm %ds", sec / 3600, (sec % 3600) / 60, sec % 60);
    }

    private static void updateDiscordMessage(JDA jda, String content) {
        TextChannel channel = jda.getTextChannelById(PERSISTENT_CHANNEL_ID);
        if (channel == null) return;
        
        channel.getHistory().retrievePast(10).queue(msgs -> {
            Message botMsg = msgs.stream().filter(m -> m.getAuthor().getId().equals(jda.getSelfUser().getId())).findFirst().orElse(null);
            if (botMsg != null) {
                botMsg.editMessage(content).queue();
            } else {
                channel.sendMessage(content).queue();
            }
        });
    }
}