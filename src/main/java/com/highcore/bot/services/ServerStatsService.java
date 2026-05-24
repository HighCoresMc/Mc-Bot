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
    private static int lastMaxPlayers = 0;
    // Section: Persistent login counter — incremented by MinecraftLogListener on every join
    private static long totalLogins = 0;

    // Section: Load .env once — avoids re-reading the file on every scheduler tick
    private static final Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public static void startScheduler(JDA jda) {
        loadStatsData();
        initPersistentStats();
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
        String host = dotenv.get("MC_SERVER_HOST", "134.255.255.130");
        int port = Integer.parseInt(dotenv.get("MC_SERVER_PORT", "25010"));

        String javaIp = dotenv.get("MC_JAVA_IP", "134.255.255.130:25010");
        String bedrockIp = dotenv.get("MC_BEDROCK_IP", "134.255.255.130:25010");
        
        String[] javaParts = javaIp.split(":");
        String javaHost = javaParts[0];
        String javaPortStr = javaParts.length > 1 ? javaParts[1] : "25565";
        
        String[] bedrockParts = bedrockIp.split(":");
        String bedrockHost = bedrockParts[0];
        String bedrockPortStr = bedrockParts.length > 1 ? bedrockParts[1] : "19132";

        String pteroUrl = dotenv.get("PTERODACTYL_URL", "https://panel.highcores.com");
        String pteroKey = dotenv.get("PTERODACTYL_API_KEY");
        String pteroId = dotenv.get("PTERODACTYL_SERVER_ID", "7bc59359");

        boolean pteroEnabled = pteroKey != null && !pteroKey.trim().isEmpty();
        PterodactylStats ptero = null;
        if (pteroEnabled) {
            ptero = fetchPterodactylStats(pteroUrl, pteroKey, pteroId);
        }

        System.out.println("[ServerStatsService] Pinging Minecraft server at " + host + ":" + port + "...");
        MinecraftPing.StatusResponse response = MinecraftPing.ping(host, port, 3000);
        boolean portOpen    = response.portOpen;
        boolean portRefused = response.portRefused;
        long    tcpPing     = response.ping;

        // Section: Localhost fallback — only when external ping failed and host is not already local
        if (!response.online && !portOpen && !"127.0.0.1".equals(host) && !"localhost".equals(host)) {
            System.out.println("[ServerStatsService] Main ping failed. Attempting fallback to localhost...");
            MinecraftPing.StatusResponse local = MinecraftPing.ping("127.0.0.1", port, 2000);
            if (local.portOpen)    { portOpen    = true; if (tcpPing == 0) tcpPing = local.ping; }
            if (local.portRefused) { portRefused = true; }
            if (local.online || local.portOpen) response = local;
        }

        // Section: Web API fallback — skipped when port was explicitly refused (avoids stale cache)
        if (!response.online) {
            if (portRefused && !portOpen) {
                System.out.println("[ServerStatsService] Port refused on all attempts — server is offline, skipping web API cache.");
            } else {
                System.out.println("[ServerStatsService] " +
                    (portOpen ? "MC protocol rejected (port is open) — using web API." : "TCP uncertain — using web API."));
                MinecraftPing.StatusResponse webResp = queryWebApi(host, port);
                if (webResp.online) {
                    long savedPing = tcpPing > 0 ? tcpPing : webResp.ping;
                    response = webResp;
                    response.ping = savedPing;
                } else if (portOpen) {
                    // TCP handshake succeeded but web API also says offline — trust TCP (port is open)
                    response.online = true;
                    response.ping   = tcpPing;
                }
            }
        }

        System.out.println("[ServerStatsService] Query complete. Online: " + response.online +
                           ", Players: " + response.onlinePlayers + "/" + response.maxPlayers +
                           ", Ping: " + response.ping + "ms, PortOpen: " + portOpen + ", PortRefused: " + portRefused);

        boolean isOnline = response.online;
        if (pteroEnabled && ptero != null && ptero.apiSuccess) {
            if (!ptero.online) {
                isOnline = false;
            } else if (!response.online) {
                isOnline = true;
            }
        }
        
        // Section: Max players — env var takes priority, then API response, then cached value (no hardcoded default)
        int envMax = 0;
        try { envMax = Integer.parseInt(dotenv.get("MC_MAX_PLAYERS", "0")); } catch (Exception ignored) {}

        int currentPlayers = 0;
        int maxPlayers = 0;
        if (isOnline) {
            // Section: Use the real-time log-based player set — accurate, avoids CMI DB timestamp drift
            currentPlayers = com.highcore.bot.listeners.MinecraftLogListener.onlinePlayers.size();
            maxPlayers = envMax > 0 ? envMax
                       : (response.online && response.maxPlayers > 0 ? response.maxPlayers
                       : (lastMaxPlayers > 0 ? lastMaxPlayers : 0));
            if (maxPlayers > 0) {
                lastMaxPlayers = maxPlayers;
            }
        } else {
            maxPlayers = envMax > 0 ? envMax : (lastMaxPlayers > 0 ? lastMaxPlayers : 0);
        }

        long networkPing = isOnline ? (response.ping > 0 ? response.ping : -1) : -1;


        totalChecks++;
        if (isOnline) {
            successfulChecks++;
            if (onlineSince == -1) {
                onlineSince = System.currentTimeMillis();
            }
            if (currentPlayers > peakPlayers) {
                peakPlayers = currentPlayers;
            }
        } else {
            onlineSince = -1;
        }

        saveStatsData();

        double availability = totalChecks > 0 ? ((double) successfulChecks * 100.0 / totalChecks) : 100.0;

        String uptimeStr = "0s";
        if (pteroEnabled && ptero != null && ptero.apiSuccess && ptero.online && ptero.uptimeMs > 0) {
            long secs = ptero.uptimeMs / 1000;
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
        } else if (isOnline && onlineSince != -1) {
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

        String healthPercentage = isOnline ? "100.0%" : "0.0%";

        Container container = Container.of(
            Section.of(
                Thumbnail.fromUrl("https://mc-heads.net/avatar/steve/128"),
                TextDisplay.of("### " + statusEmoji + " " + statusDesc)
            ),
            Separator.createDivider(Separator.Spacing.SMALL),
            TextDisplay.of("### 🏛️ Server Name\n`HighCore MC`"),
            Separator.createDivider(Separator.Spacing.SMALL),
            TextDisplay.of("### 🖥️ Connection Addresses\n" +
                           "**Java IP:** `" + javaHost + "`\n" +
                           "**Java Port:** `" + javaPortStr + "`\n\n" +
                           "**Bedrock IP:** `" + bedrockHost + "`\n" +
                           "**Bedrock Port:** `" + bedrockPortStr + "`"),
            Separator.createDivider(Separator.Spacing.SMALL),
            TextDisplay.of("### 📊 Live Statistics\n" +
                           "👥 **Players Online:** `" + currentPlayers + " / " + maxPlayers + "`\n" +
                           "📈 **Peak Players:** `" + peakPlayers + "`\n" +
                           "📥 **Total Logins:** `" + totalLogins + "`"),
            Separator.createDivider(Separator.Spacing.SMALL),
            TextDisplay.of("### 🚦 Status & Health\n" +
                           "🚦 **Server Status:** `" + (isOnline ? "Open 🔓" : "Closed 🔒") + "`\n" +
                           "📡 **Server Ping:** `" + (networkPing != -1 ? networkPing + "ms" : "N/A") + "`\n" +
                           "🔋 **Health:** `" + healthPercentage + "`\n" +
                           "📈 **Availability:** " + getProgressBar(availability)),
            Separator.createDivider(Separator.Spacing.SMALL),
            TextDisplay.of("### ⏱️ Server Uptime\n" +
                           "⏱️ **Uptime:** `" + uptimeStr + "`\n" +
                           "🔄 **Last Updated:** <t:" + (System.currentTimeMillis() / 1000) + ":R>")
        );

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
    }

    // Section: Called by MinecraftLogListener on every player join to keep the counter accurate
    public static void incrementTotalLogins() {
        totalLogins++;
        saveStatsData();
        saveTotalLoginsToDB();
    }

    // Section: Exposes the log channel ID from the cached Dotenv to MinecraftLogListener
    public static String getLogChannelId() {
        return dotenv.get("MINECRAFT_LOG_CHANNEL_ID", "1487148944667578368");
    }

    // Section: Server lifecycle hooks — called by MinecraftLogListener on stop/start events
    public static void notifyServerStarted() {
        onlineSince = System.currentTimeMillis();
        saveStatsData();
    }

    public static void notifyServerStopped() {
        onlineSince = -1;
        saveStatsData();
    }

    // Section: Called during history scan to restore server uptime from Discord message timestamp
    public static void setOnlineSince(long epochMs) {
        onlineSince = epochMs;
    }

    // Section: MySQL-backed persistence for totalLogins — survives Railway redeployments
    private static void initPersistentStats() {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection();
             PreparedStatement create = conn.prepareStatement(
                 "CREATE TABLE IF NOT EXISTS bot_stats (stat_key VARCHAR(64) PRIMARY KEY, stat_value BIGINT NOT NULL DEFAULT 0)")) {
            create.executeUpdate();
        } catch (Exception e) {
            logger.error("Failed to create bot_stats table", e);
        }
        // Section: Load totalLogins from DB (overwrites file-based value which resets on redeploy)
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO bot_stats (stat_key, stat_value) VALUES ('totalLogins', 0) ON DUPLICATE KEY UPDATE stat_key=stat_key");
             PreparedStatement sel = conn.prepareStatement(
                 "SELECT stat_value FROM bot_stats WHERE stat_key = 'totalLogins'")) {
            ps.executeUpdate();
            try (ResultSet rs = sel.executeQuery()) {
                if (rs.next()) totalLogins = rs.getLong(1);
            }
            System.out.println("[ServerStatsService] Loaded totalLogins from DB: " + totalLogins);
        } catch (Exception e) {
            logger.error("Failed to load totalLogins from DB", e);
        }
    }

    private static void saveTotalLoginsToDB() {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO bot_stats (stat_key, stat_value) VALUES ('totalLogins', ?) ON DUPLICATE KEY UPDATE stat_value = ?")) {
            ps.setLong(1, totalLogins);
            ps.setLong(2, totalLogins);
            ps.executeUpdate();
        } catch (Exception e) {
            logger.error("Failed to save totalLogins to DB", e);
        }
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
            lastMaxPlayers = extractJsonInt(content, "lastMaxPlayers");
            totalLogins = extractJsonLong(content, "totalLogins");
        } catch (Exception e) {
            logger.error("Error loading persistent stats data", e);
        }
    }

    private static void saveStatsData() {
        try (FileWriter writer = new FileWriter(DATA_FILE)) {
            String json = String.format(
                "{\"peakPlayers\":%d,\"totalChecks\":%d,\"successfulChecks\":%d,\"onlineSince\":%d,\"lastMaxPlayers\":%d,\"totalLogins\":%d}",
                peakPlayers, totalChecks, successfulChecks, onlineSince, lastMaxPlayers, totalLogins
            );
            writer.write(json);
        } catch (Exception e) {
            logger.error("Error saving persistent stats data", e);
        }
    }

    private static int extractJsonInt(String json, String key) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)");
        java.util.regex.Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (Exception e) {
                return 0;
            }
        }
        return 0;
    }

    private static long extractJsonLong(String json, String key) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)");
        java.util.regex.Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            try {
                return Long.parseLong(matcher.group(1));
            } catch (Exception e) {
                return 0L;
            }
        }
        return 0L;
    }

    private static class PterodactylStats {
        boolean apiSuccess = false;
        boolean online = false;
        double cpu = 0.0;
        long memoryBytes = 0L;
        long diskBytes = 0L;
        long uptimeMs = 0L;
    }

    private static double extractJsonDouble(String json, String key) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");
        java.util.regex.Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            try {
                return Double.parseDouble(matcher.group(1));
            } catch (Exception e) {
                return 0.0;
            }
        }
        return 0.0;
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
                    stats.apiSuccess = true;
                    stats.online = json.contains("\"current_state\":\"running\"") || json.contains("\"current_state\":\"starting\"");
                    stats.cpu = extractJsonDouble(json, "cpu_absolute");
                    stats.memoryBytes = extractJsonLong(json, "memory_bytes");
                    stats.diskBytes = extractJsonLong(json, "disk_bytes");
                    stats.uptimeMs = extractJsonLong(json, "uptime");
                }
            } else {
                System.out.println("[ServerStatsService] Pterodactyl API responded with error code: " + respCode);
            }
        } catch (Exception e) {
            System.out.println("[ServerStatsService] Exception while querying Pterodactyl API: " + e.getMessage());
        }
        return stats;
    }

    private static MinecraftPing.StatusResponse queryWebApi(String host, int port) {
        MinecraftPing.StatusResponse response = new MinecraftPing.StatusResponse();
        try {
            java.net.URL url = new java.net.URL("https://api.minetools.eu/ping/" + host + "/" + port);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            if (conn.getResponseCode() == 200) {
                try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    java.lang.StringBuilder sb = new java.lang.StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line);
                    }
                    String json = sb.toString();
                    if (!json.contains("\"error\"")) {
                        response.online = true;
                        // Section: minetools uses {"players":{"online":N,"max":N},"latency":F}
                        response.onlinePlayers = extractJsonInt(json, "online");
                        response.maxPlayers    = extractJsonInt(json, "max");
                        response.ping          = extractJsonLong(json, "latency");
                        // Section: leave ping as 0 if not provided — display shows N/A for 0/-1
                        return response;
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[ServerStatsService] Minetools API query failed: " + e.getMessage());
        }

        try {
            java.net.URL url = new java.net.URL("https://api.mcsrvstat.us/2/" + host + ":" + port);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            if (conn.getResponseCode() == 200) {
                try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    java.lang.StringBuilder sb = new java.lang.StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line);
                    }
                    String json = sb.toString();
                    if (json.contains("\"online\":true")) {
                        response.online = true;
                        // Section: mcsrvstat uses {"players":{"online":N,"max":N}}
                        response.onlinePlayers = extractJsonInt(json, "online");
                        response.maxPlayers    = extractJsonInt(json, "max");
                        response.ping          = extractJsonLong(json, "ping");
                        return response;
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[ServerStatsService] mcsrvstat API query failed: " + e.getMessage());
        }
        return response;
    }
}