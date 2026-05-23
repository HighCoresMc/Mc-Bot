package com.highcore.bot.services;

import com.highcore.bot.LeonTrotskyBot;
import com.highcore.bot.utils.MinecraftPing;
import io.github.cdimascio.dotenv.Dotenv;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.thumbnail.Thumbnail;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ServerStatsService {
    private static final Logger logger = LoggerFactory.getLogger(ServerStatsService.class);
    
    private static final String PERSISTENT_CHANNEL_ID = "1487139736748425236";
    private static final String DATA_FILE = "stats_data.json";

    private static int peakPlayers = 0;
    private static long totalChecks = 0;
    private static long successfulChecks = 0;
    private static long onlineSince = -1;
    private static int lastMaxPlayers = 0;

    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public static void startScheduler(JDA jda) {
        loadStatsData();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                updateStats(jda);
            } catch (Exception e) {
                logger.error("Error updating server stats", e);
            }
        }, 5, 60, TimeUnit.SECONDS); 
    }

    private static void updateStats(JDA jda) {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        String host = dotenv.get("MC_SERVER_HOST", "134.255.255.130");
        int port = Integer.parseInt(dotenv.get("MC_SERVER_PORT", "25010"));

        MinecraftPing.StatusResponse response = MinecraftPing.ping(host, port, 5000);
        
        boolean isOnline = response.online;
        int currentPlayers = isOnline ? response.onlinePlayers : 0;
        int maxPlayers = isOnline ? response.maxPlayers : (lastMaxPlayers > 0 ? lastMaxPlayers : 20);
        
        if (isOnline) {
            lastMaxPlayers = maxPlayers;
            successfulChecks++;
            if (onlineSince == -1) onlineSince = System.currentTimeMillis();
            if (currentPlayers > peakPlayers) peakPlayers = currentPlayers;
        } else {
            onlineSince = -1;
        }
        totalChecks++;
        saveStatsData();

        String uptimeStr = (isOnline && onlineSince != -1) ? formatDuration(System.currentTimeMillis() - onlineSince) : "Offline";
        double availability = totalChecks > 0 ? ((double) successfulChecks * 100.0 / totalChecks) : 0.0;

        Container container = Container.of(
            Section.of(Thumbnail.fromUrl("https://mc-heads.net/avatar/steve/128"), 
                       TextDisplay.of("### " + (isOnline ? "🟢 Server is Online" : "🔴 Server is Offline"))),
            Separator.createDivider(Separator.Spacing.SMALL),
            TextDisplay.of("### 📊 Statistics\n" +
                           "👥 **Players:** `" + currentPlayers + " / " + maxPlayers + "`\n" +
                           "📡 **Ping:** `" + (isOnline ? response.ping + "ms" : "N/A") + "`\n" +
                           "⏱️ **Uptime:** `" + uptimeStr + "`\n" +
                           "📈 **Availability:** `" + String.format("%.1f", availability) + "%`")
        );

        updateDiscordMessage(jda, container);
    }

    private static String formatDuration(long ms) {
        long sec = ms / 1000;
        return String.format("%dh %dm %ds", sec / 3600, (sec % 3600) / 60, sec % 60);
    }

    private static void updateDiscordMessage(JDA jda, Container container) {
        TextChannel channel = jda.getTextChannelById(PERSISTENT_CHANNEL_ID);
        if (channel == null) return;
        channel.getHistory().retrievePast(5).queue(msgs -> {
            Message botMsg = msgs.stream().filter(m -> m.getAuthor().getId().equals(jda.getSelfUser().getId())).findFirst().orElse(null);
            MessageEditData data = new MessageEditBuilder().setComponents(container).build();
            if (botMsg != null) botMsg.editMessage(data).queue();
            else channel.sendMessage(new MessageCreateBuilder().setComponents(container).build()).queue();
        });
    }

    private static void loadStatsData() { /* ... */ }
    private static void saveStatsData() { /* ... */ }
    private static int extractJsonInt(String j, String k) { return 0; }
    private static long extractJsonLong(String j, String k) { return 0L; }
}