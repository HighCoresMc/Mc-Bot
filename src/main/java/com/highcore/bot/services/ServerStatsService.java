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
        }, 5, 20, TimeUnit.SECONDS);
        logger.info("Server stats updater scheduler started (updates every 20 seconds).");
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

    private static String getProgressBar(double percentage) {
        int totalBars = 20;
        int filled = (int) Math.round((percentage / 100.0) * totalBars);
        java.lang.StringBuilder sb = new java.lang.StringBuilder();
        sb.append(String.format("%.2f", percentage)).append("%\n`");
        for (int i = 0; i < totalBars; i++) {
            if (i < filled) {
                sb.append("█");
            } else {
                sb.append("░");
            }
        }
        sb.append("`");
        return sb.toString();
    }

    private static void updateStats(JDA jda) {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        String host = dotenv.get("MC_SERVER_HOST", "134.255.255.130");
        int port = Integer.parseInt(dotenv.get("MC_SERVER_PORT", "25010"));

        String javaIp = dotenv.get("MC_JAVA_IP", "134.255.255.130:25010");
        String bedrockIp = dotenv.get("MC_BEDROCK_IP", "134.255.255.130:25010");

        String pteroUrl = dotenv.get("PTERODACTYL_URL", "https://panel.highcores.com");
        String pteroKey = dotenv.get("PTERODACTYL_API_KEY");
        String pteroId = dotenv.get("PTERODACTYL_SERVER_ID", "7bc59359");

        boolean pteroEnabled = pteroKey != null && !pteroKey.trim().isEmpty();
        PterodactylStats ptero = null;
        if (pteroEnabled) {
            ptero = fetchPterodactylStats(pteroUrl, pteroKey, pteroId);
        }

        // Dual-ping mechanism: try configured host, fallback to localhost Spigot port
        System.out.println("[ServerStatsService] Pinging Minecraft server at " + host + ":" + port + "...");
        MinecraftPing.StatusResponse response = MinecraftPing.ping(host, port, 3000);
        if (!response.online && !"127.0.0.1".equals(host) && !"localhost".equals(host)) {
            System.out.println("[ServerStatsService] Main ping failed. Attempting fallback to localhost...");
            response = MinecraftPing.ping("127.0.0.1", port, 2000);
        }
        System.out.println("[ServerStatsService] Query complete. Online: " + response.online + 
                           ", Players: " + response.onlinePlayers + "/" + response.maxPlayers + 
                           ", Ping: " + response.ping + "ms");

        boolean isOnline = response.online || (pteroEnabled && ptero != null && ptero.online);

        totalChecks++;
        if (isOnline) {
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
        if (isOnline && onlineSince != -1) {
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

        String statusEmoji = isOnline ? "🟢" : "🔴";
        String statusDesc = isOnline 
            ? "Players can join and enjoy the gameplay experience." 
            : "Server is currently offline. Please check back later!";

        String healthStr = isOnline ? "100.0%" : "0.0%";
        if (pteroEnabled && ptero != null && ptero.online) {
            double ramGiB = (double) ptero.memoryBytes / (1024.0 * 1024.0 * 1024.0);
            healthStr = String.format("CPU: %.1f%% | RAM: %.2f GiB", ptero.cpu, ramGiB);
        }

        // Build V2 Container exactly like the SA-MP dashboard template
        Container container = Container.of(
            Section.of(
                Thumbnail.fromUrl("https://mc-heads.net/avatar/steve/128"),
                TextDisplay.of("### " + statusEmoji + " " + statusDesc)
            ),
            Separator.createDivider(Separator.Spacing.SMALL),
            TextDisplay.of("### 🏛️ Server Name"),
            TextDisplay.of("`HighCore MC`"),
            Separator.createDivider(Separator.Spacing.SMALL),
            TextDisplay.of("### 🖥️ Connection Addresses"),
            TextDisplay.of("**Java IP:** `" + javaIp + "`\n**Bedrock IP:** `" + bedrockIp + "`"),
            Separator.createDivider(Separator.Spacing.SMALL),
            TextDisplay.of("### 👥 Players Online  |  📈 Peak Players  |  📥 Total Logins\n" +
                           "`" + (isOnline ? response.onlinePlayers : 0) + " / " + (isOnline ? response.maxPlayers : 20) + "`  |  `" + peakPlayers + "`  |  `" + totalLogins + "`"),
            Separator.createDivider(Separator.Spacing.SMALL),
            TextDisplay.of("### 🚦 Server Status  |  📡 Server Ping  |  🔋 Health\n" +
                           "`" + (isOnline ? "Open 🔓" : "Closed 🔒") + "`  |  `" + (isOnline ? response.ping + "ms" : "N/A") + "`  |  `" + healthStr + "`"),
            Separator.createDivider(Separator.Spacing.SMALL),
            TextDisplay.of("### ⏱️ Uptime  |  📊 Availability  |  🔄 Last Updated\n" +
                           "`" + uptimeStr + "`  |  " + getProgressBar(availability) + "  |  <t:" + (System.currentTimeMillis() / 1000) + ":R>")
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

    private static class PterodactylStats {
        boolean online = false;
        double cpu = 0.0;
        long memoryBytes = 0L;
        long diskBytes = 0L;
    }

    private static double extractJsonDouble(String json, String key) {
        String pattern = "\"" + key + "\":";
        int idx = json.indexOf(pattern);
        if (idx == -1) return 0.0;
        idx += pattern.length();
        int end = idx;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.' || json.charAt(end) == '-')) {
            end++;
        }
        try {
            return Double.parseDouble(json.substring(idx, end));
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static PterodactylStats fetchPterodactylStats(String url, String apiKey, String serverId) {
        PterodactylStats stats = new PterodactylStats();
        try {
            java.net.URL apiURL = new java.net.URL(url + "/api/client/servers/" + serverId + "/resources");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) apiURL.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);

            int respCode = conn.getResponseCode();
            if (respCode == 200) {
                try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    java.lang.StringBuilder sb = new java.lang.StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line);
                    }
                    String json = sb.toString();
                    stats.online = json.contains("\"current_state\":\"running\"") || json.contains("\"current_state\":\"starting\"");
                    stats.cpu = extractJsonDouble(json, "cpu_absolute");
                    stats.memoryBytes = extractJsonLong(json, "memory_bytes");
                    stats.diskBytes = extractJsonLong(json, "disk_bytes");
                }
            } else {
                System.out.println("[ServerStatsService] Pterodactyl API responded with error code: " + respCode);
            }
        } catch (Exception e) {
            System.out.println("[ServerStatsService] Exception while querying Pterodactyl API: " + e.getMessage());
        }
        return stats;
    }
}
