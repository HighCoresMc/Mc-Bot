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
    private static final String LOG_CHANNEL_ID = "1506315801295061063";
    private static final String DATA_FILE = "stats_data.json";

    private static int peakPlayers = 0;
    private static long totalChecks = 0;
    private static long successfulChecks = 0;
    private static long onlineSince = -1;

    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public static void startScheduler(JDA jda) {
        loadStatsData();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                updateStats(jda);
            } catch (Exception e) {
                logger.error("Error updating server stats", e);
            }
        }, 5, 30, TimeUnit.SECONDS);
        logger.info("Server stats updater scheduler started (updates every 30 seconds).");
    }

    public static void forceUpdate(JDA jda) {
        Executors.newSingleThreadExecutor().submit(() -> {
            try {
                updateStats(jda);
            } catch (Exception e) {
                logger.error("Error force updating server stats", e);
            }
        });
    }

    private static void updateStats(JDA jda) {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        String host = dotenv.get("MC_SERVER_HOST", "play.highcore.net");
        int port = Integer.parseInt(dotenv.get("MC_SERVER_PORT", "25565"));

        MinecraftPing.StatusResponse response = MinecraftPing.ping(host, port, 3000);

        totalChecks++;
        if (response.online) {
            successfulChecks++;
            if (onlineSince == -1) {
                onlineSince = System.currentTimeMillis();
            }
            if (response.onlinePlayers > peakPlayers) {
                peakPlayers = response.onlinePlayers;
            }
        } else {
            onlineSince = -1;
        }

        saveStatsData();

        int totalLogins = getTotalLogins();
        double availability = totalChecks > 0 ? ((double) successfulChecks * 100.0 / totalChecks) : 100.0;

        String uptimeStr = "0s";
        if (response.online && onlineSince != -1) {
            long diff = System.currentTimeMillis() - onlineSince;
            long secs = diff / 1000;
            long days = secs / 86400;
            long hours = (secs % 86400) / 3600;
            long minutes = (secs % 3600) / 60;
            long remainingSecs = secs % 60;

            java.lang.StringBuilder sb = new java.lang.StringBuilder();
            if (days > 0) sb.append(days).append("d ");
            if (hours > 0) sb.append(hours).append("h ");
            if (minutes > 0) sb.append(minutes).append("m ");
            sb.append(remainingSecs).append("s");
            uptimeStr = sb.toString();
        }

        // Build V2 Container
        Container container = Container.of(
            Section.of(
                Thumbnail.fromUrl("https://mc-heads.net/avatar/steve/128"),
                TextDisplay.of("## 🕹️ HighCore MC | حالة السيرفر"),
                TextDisplay.of("### Players can join and enjoy the gameplay experience.")
            ),
            Separator.createDivider(Separator.Spacing.SMALL),
            TextDisplay.of("### 🖥️ عناوين الاتصال (Server IP)"),
            TextDisplay.of("**Java IP:** `play.highcore.net:25565`\n**Bedrock IP:** `play.highcore.net:19132`"),
            Separator.createDivider(Separator.Spacing.SMALL),
            TextDisplay.of("### 📊 إحصائيات اللاعبين"),
            TextDisplay.of("**👥 Players Online:** " + (response.online ? response.onlinePlayers : 0) + " / " + (response.online ? response.maxPlayers : 50) + 
                           "\n**📈 Peak Players:** " + peakPlayers + 
                           "\n**📥 Total Logins:** " + totalLogins),
            Separator.createDivider(Separator.Spacing.SMALL),
            TextDisplay.of("### ⚙️ حالة النظام"),
            TextDisplay.of("**🚦 Server Status:** " + (response.online ? "Open 🔓" : "Closed 🔒") + 
                           "\n**📡 Server Ping:** " + (response.online ? response.ping + "ms" : "N/A") + 
                           "\n**🔋 Health:** " + (response.online ? "100.0%" : "0.0%")),
            Separator.createDivider(Separator.Spacing.SMALL),
            TextDisplay.of("### ⏱️ الأوقات والتوفر"),
            TextDisplay.of("**⏱️ Uptime:** " + uptimeStr + 
                           "\n**📊 Availability:** " + String.format("%.2f", availability) + "%" + 
                           "\n**🔄 Last Updated:** <t:" + (System.currentTimeMillis() / 1000) + ":R>")
        );

        // 1. Persistent Message update in PERSISTENT_CHANNEL_ID
        TextChannel persistentChannel = jda.getTextChannelById(PERSISTENT_CHANNEL_ID);
        if (persistentChannel != null) {
            persistentChannel.getHistory().retrievePast(10).queue(messages -> {
                Message botMessage = null;
                for (Message msg : messages) {
                    if (msg.getAuthor().getId().equals(jda.getSelfUser().getId())) {
                        botMessage = msg;
                        break;
                    }
                }

                if (botMessage != null) {
                    MessageEditData editData = new MessageEditBuilder()
                            .setComponents(container)
                            .setEmbeds(java.util.Collections.emptyList())
                            .useComponentsV2(true)
                            .build();
                    botMessage.editMessage(editData).queue(
                        success -> logger.debug("Successfully edited persistent server stats message."),
                        error -> logger.error("Failed to edit persistent status message", error)
                    );
                } else {
                    MessageCreateData createData = new MessageCreateBuilder()
                            .setComponents(container)
                            .useComponentsV2(true)
                            .build();
                    persistentChannel.sendMessage(createData).queue(
                        success -> logger.debug("Successfully sent new persistent server stats message."),
                        error -> logger.error("Failed to send persistent status message", error)
                    );
                }
            }, error -> {
                logger.error("Failed to fetch history from persistent status channel", error);
            });
        }

        // 2. Logging updates in LOG_CHANNEL_ID (sends a new message every update)
        TextChannel logChannel = jda.getTextChannelById(LOG_CHANNEL_ID);
        if (logChannel != null) {
            MessageCreateData createData = new MessageCreateBuilder()
                    .setComponents(container)
                    .useComponentsV2(true)
                    .build();
            logChannel.sendMessage(createData).queue(
                success -> logger.debug("Successfully logged server stats update to history channel."),
                error -> logger.error("Failed to log status update to history channel", error)
            );
        }
    }

    private static int getTotalLogins() {
        String query = "SELECT COUNT(*) FROM CMI_users";
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(query);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (Exception e) {
            logger.error("Error executing total logins query", e);
        }
        return 0;
    }

    private static void loadStatsData() {
        File file = new File(DATA_FILE);
        if (!file.exists()) return;
        try (FileReader reader = new FileReader(file)) {
            java.lang.StringBuilder sb = new java.lang.StringBuilder();
            int ch;
            while ((ch = reader.read()) != -1) {
                sb.append((char) ch);
            }
            String content = sb.toString();
            peakPlayers = extractJsonInt(content, "peakPlayers");
            totalChecks = extractJsonLong(content, "totalChecks");
            successfulChecks = extractJsonLong(content, "successfulChecks");
            onlineSince = extractJsonLong(content, "onlineSince");
        } catch (Exception e) {
            logger.error("Error loading persistent stats data", e);
        }
    }

    private static void saveStatsData() {
        try (FileWriter writer = new FileWriter(DATA_FILE)) {
            String json = String.format(
                "{\"peakPlayers\":%d,\"totalChecks\":%d,\"successfulChecks\":%d,\"onlineSince\":%d}",
                peakPlayers, totalChecks, successfulChecks, onlineSince
            );
            writer.write(json);
        } catch (Exception e) {
            logger.error("Error saving persistent stats data", e);
        }
    }

    private static int extractJsonInt(String json, String key) {
        String pattern = "\"" + key + "\":";
        int idx = json.indexOf(pattern);
        if (idx == -1) return 0;
        idx += pattern.length();
        int end = idx;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        try {
            return Integer.parseInt(json.substring(idx, end));
        } catch (Exception e) {
            return 0;
        }
    }

    private static long extractJsonLong(String json, String key) {
        String pattern = "\"" + key + "\":";
        int idx = json.indexOf(pattern);
        if (idx == -1) return 0L;
        idx += pattern.length();
        int end = idx;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        try {
            return Long.parseLong(json.substring(idx, end));
        } catch (Exception e) {
            return 0L;
        }
    }
}
