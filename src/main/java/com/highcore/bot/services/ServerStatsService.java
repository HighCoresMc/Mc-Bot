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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
    private static String statsMessageId = null;
    private static long totalChecks = 0;
    private static long successfulChecks = 0;
    private static long onlineSince = -1;
    private static int lastMaxPlayers = 0;
    // Section: Persistent login counter
    private static long totalLogins = 0;

    // Section: Load .env once
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
        String javaHost    = javaParts[0];
        String javaPortStr = javaParts.length > 1 ? javaParts[1] : "25565";
        // Section: Parse Java port as int
        int javaPort = 25565;
        try { javaPort = Integer.parseInt(javaPortStr); } catch (Exception ignored) {}
        
        String[] bedrockParts = bedrockIp.split(":");
        String bedrockHost = bedrockParts[0];
        String bedrockPortStr = bedrockParts.length > 1 ? bedrockParts[1] : "19132";

        String pteroUrl = dotenv.get("PTERODACTYL_URL", "https://panel.highcores.com");
        String pteroKey = dotenv.get("PTERODACTYL_API_KEY");
        String pteroId  = dotenv.get("PTERODACTYL_SERVER_ID", "7bc59359");

        boolean pteroEnabled = pteroKey != null && !pteroKey.trim().isEmpty();
        PterodactylStats ptero = null;
        if (pteroEnabled) {
            ptero = fetchPterodactylStats(pteroUrl, pteroKey, pteroId);
        }

        // Section: Ping Velocity proxy
        System.out.println("[ServerStatsService] Pinging Velocity proxy at " + javaHost + ":" + javaPort + "...");
        MinecraftPing.StatusResponse response = MinecraftPing.ping(javaHost, javaPort, 3000);
        boolean portOpen    = response.portOpen;
        boolean portRefused = response.portRefused;
        long    tcpPing     = response.ping;

        // Section: Localhost fallback
        if (!response.online && !portOpen && !portRefused
                && !"127.0.0.1".equals(javaHost) && !"localhost".equals(javaHost)) {
            System.out.println("[ServerStatsService] Main ping failed. Attempting fallback to localhost...");
            MinecraftPing.StatusResponse local = MinecraftPing.ping("127.0.0.1", javaPort, 2000);
            if (local.portOpen)    { portOpen    = true; if (tcpPing == 0) tcpPing = local.ping; }
            if (local.portRefused) { portRefused = true; }
            if (local.online || local.portOpen) response = local;
        }

        // Section: Web API fallback
        long webPing = 0;
        if (!response.online) {
            if (portRefused && !portOpen) {
                System.out.println("[ServerStatsService] Port refused on all attempts — server is offline, skipping web API cache.");
            } else {
                System.out.println("[ServerStatsService] " +
                    (portOpen ? "MC protocol rejected (port is open) — using web API." : "TCP uncertain — using web API."));
                MinecraftPing.StatusResponse webResp = queryWebApi(javaHost, javaPort);
                webPing = webResp.ping;
                if (webResp.online) {

                    long savedPing = webPing > 0 ? webPing : tcpPing;
                    response = webResp;
                    response.ping = savedPing;
                } else if (portOpen) {
                    response.online = true;
                    response.ping   = tcpPing;
                }
            }
        } else {

            webPing = response.ping;
        }

        System.out.println("[ServerStatsService] Query complete. Online: " + response.online +
                           ", Players: " + response.onlinePlayers + "/" + response.maxPlayers +
                           ", Ping: " + response.ping + "ms, PortOpen: " + portOpen + ", PortRefused: " + portRefused);

        boolean isMaintenance = false;
        long maintenanceReturnTimestamp = 0;
        File maintFile = new File("maintenance_state.json");
        if (maintFile.exists()) {
            try (FileReader reader = new FileReader(maintFile)) {
                java.lang.StringBuilder sb = new java.lang.StringBuilder();
                int ch;
                while ((ch = reader.read()) != -1) {
                    sb.append((char) ch);
                }
                JsonObject json = JsonParser.parseString(sb.toString()).getAsJsonObject();
                maintenanceReturnTimestamp = json.get("returnTimestamp").getAsLong();
                isMaintenance = true;
            } catch (Exception ignored) {}
        }

        boolean isOnline = response.online;
        if (pteroEnabled && ptero != null && ptero.apiSuccess) {
            if (!ptero.online) {
                isOnline = false;
            }
        }

        if (isMaintenance) {
            isOnline = false;
        }

        // Section: Max players
        int envMax = 0;
        try { envMax = Integer.parseInt(dotenv.get("MC_MAX_PLAYERS", "0")); } catch (Exception ignored) {}

        int currentPlayers = 0;
        int maxPlayers = 0;
        if (isOnline) {
            // Section: Real-time player set
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

        // Section: Ping display
        long networkPing = isOnline ? (webPing > 0 ? webPing : tcpPing > 0 ? tcpPing : -1) : -1;
        
        if (!isMaintenance) {
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
        } else {
            onlineSince = -1;
        }

        saveStatsData();
        savePersistentStatsToDB();

        double availability = totalChecks > 0 ? ((double) successfulChecks * 100.0 / totalChecks) : 100.0;

        String uptimeStr = "0s";
        if (!isMaintenance) {
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
        }

        String statusEmoji;
        String statusDesc;
        String serverStatusText = isOnline ? "Open 🔓" : "Closed 🔒";
        String healthPercentage = isOnline ? "100.0%" : "0.0%";

        if (isMaintenance) {
            statusEmoji = "🟡";
            statusDesc = "The server is currently under maintenance. (To know the duration, check the details below)";
        } else {
            statusEmoji = isOnline ? "🟢" : "🔴";
            statusDesc = isOnline 
                ? "Players can join and enjoy the gameplay experience." 
                : "Server is currently offline. Please check back later!";
        }

        Container container = Container.of(
            Section.of(
                Thumbnail.fromUrl("https://minotar.net/avatar/steve/128"),
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
                           "🚦 **Server Status:** `" + serverStatusText + "`\n" +
                           "📡 **Server Ping:** `" + (networkPing != -1 ? networkPing + "ms" : "N/A") + "`\n" +
                           "🔋 **Health:** `" + healthPercentage + "`"),
            Separator.createDivider(Separator.Spacing.SMALL),
            TextDisplay.of("### ⏱️ Server Uptime\n" +
                           "⏱️ **Uptime:** `" + uptimeStr + "`\n" +
                           "🔄 **Last Updated:** <t:" + (System.currentTimeMillis() / 1000) + ":R>")
        );

        TextChannel persistentChannel = jda.getTextChannelById(PERSISTENT_CHANNEL_ID);
        if (persistentChannel != null) {
            if (statsMessageId != null) {
                persistentChannel.retrieveMessageById(statsMessageId).queue(
                    botMessage -> {
                        MessageEditData editData = new MessageEditBuilder()
                                .setContent("")
                                .setComponents(container)
                                .setEmbeds(java.util.Collections.emptyList())
                                .useComponentsV2(true)
                                .build();
                        botMessage.editMessage(editData).queue(
                            success -> {
                                logger.debug("Successfully edited persistent server stats message.");
                                sendOldStatsLog(jda, botMessage);
                            },
                            error -> {
                                logger.error("Failed to edit persistent status message, deleting and recreating...", error);
                                botMessage.delete().queue(
                                    deleted -> {
                                        MessageCreateData createData = new MessageCreateBuilder()
                                                .setComponents(container)
                                                .useComponentsV2(true)
                                                .build();
                                        persistentChannel.sendMessage(createData).queue(
                                            success2 -> {
                                                logger.debug("Successfully recreated persistent server stats message.");
                                                statsMessageId = success2.getId();
                                                saveStatsData();
                                                sendOldStatsLog(jda, botMessage);
                                            },
                                            error2 -> logger.error("Failed to recreate persistent status message", error2)
                                        );
                                    },
                                    delError -> logger.error("Failed to delete legacy persistent status message", delError)
                                );
                            }
                        );
                    },
                    retrieveError -> {
                        statsMessageId = null;
                        updateStats(jda);
                    }
                );
                return;
            }

            persistentChannel.getHistory().retrievePast(10).queue(messages -> {
                Message botMessage = null;
                String maintenanceMsgId = getMaintenanceMessageId();
                for (Message msg : messages) {
                    if (msg.getAuthor().getId().equals(jda.getSelfUser().getId())) {
                        if (maintenanceMsgId == null || !msg.getId().equals(maintenanceMsgId)) {
                            botMessage = msg;
                            break;
                        }
                    }
                }

                if (botMessage != null) {
                    statsMessageId = botMessage.getId();
                    saveStatsData();
                    updateStats(jda);
                } else {
                    MessageCreateData createData = new MessageCreateBuilder()
                            .setComponents(container)
                            .useComponentsV2(true)
                            .build();
                    persistentChannel.sendMessage(createData).queue(
                        success -> {
                            logger.debug("Successfully sent new persistent server stats message.");
                            statsMessageId = success.getId();
                            saveStatsData();
                        },
                        error -> logger.error("Failed to send persistent status message", error)
                    );
                }
            }, error -> {
                logger.error("Failed to fetch history from persistent status channel", error);
            });
        }
    }


    public static void incrementTotalLogins() {
        totalLogins++;
        saveStatsData();
        savePersistentStatsToDB();
    }


    public static String getLogChannelId() {
        return dotenv.get("MINECRAFT_LOG_CHANNEL_ID", "1487148944667578368");
    }

    // Section: Server lifecycle hooks
    public static void notifyServerStarted() {
        onlineSince = System.currentTimeMillis();
        saveStatsData();
    }

    public static void notifyServerStopped() {
        onlineSince = -1;
        saveStatsData();
    }


    public static void setOnlineSince(long epochMs) {
        onlineSince = epochMs;
    }

    // Section: MySQL persistence
    private static void initPersistentStats() {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection();
             PreparedStatement create = conn.prepareStatement(
                 "CREATE TABLE IF NOT EXISTS bot_stats (stat_key VARCHAR(64) PRIMARY KEY, stat_value BIGINT NOT NULL DEFAULT 0)")) {
            create.executeUpdate();
        } catch (Exception e) {
            logger.error("Failed to create bot_stats table", e);
        }
        // Section: Load all persistent counters from DB
        String[] keys = {"totalLogins", "totalChecks", "successfulChecks", "peakPlayers"};
        for (String key : keys) {
            try (Connection conn = LeonTrotskyBot.getDbManager().getConnection();
                 PreparedStatement ins = conn.prepareStatement(
                     "INSERT INTO bot_stats (stat_key, stat_value) VALUES (?, 0) ON DUPLICATE KEY UPDATE stat_key=stat_key");
                 PreparedStatement sel = conn.prepareStatement(
                     "SELECT stat_value FROM bot_stats WHERE stat_key = ?")) {
                ins.setString(1, key);
                ins.executeUpdate();
                sel.setString(1, key);
                try (ResultSet rs = sel.executeQuery()) {
                    if (rs.next()) {
                        long val = rs.getLong(1);
                        switch (key) {
                            case "totalLogins"     -> totalLogins      = val;
                            case "totalChecks"     -> totalChecks      = val;
                            case "successfulChecks"-> successfulChecks = val;
                            case "peakPlayers"     -> peakPlayers      = (int) val;
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("Failed to load {} from DB", key, e);
            }
        }
        System.out.println("[ServerStatsService] Loaded from DB — logins:" + totalLogins +
            ", checks:" + totalChecks + ", ok:" + successfulChecks + ", peak:" + peakPlayers);
    }

    private static void savePersistentStatsToDB() {
        Object[][] rows = {
            {"totalLogins",      totalLogins},
            {"totalChecks",      totalChecks},
            {"successfulChecks", successfulChecks},
            {"peakPlayers",      (long) peakPlayers}
        };
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO bot_stats (stat_key, stat_value) VALUES (?, ?) ON DUPLICATE KEY UPDATE stat_value = ?")) {
            for (Object[] row : rows) {
                ps.setString(1, (String) row[0]);
                ps.setLong(2, (long) row[1]);
                ps.setLong(3, (long) row[1]);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (Exception e) {
            logger.error("Failed to save persistent stats to DB", e);
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
            JsonObject json = JsonParser.parseString(sb.toString()).getAsJsonObject();
            peakPlayers = json.has("peakPlayers") ? json.get("peakPlayers").getAsInt() : 0;
            totalChecks = json.has("totalChecks") ? json.get("totalChecks").getAsLong() : 0;
            successfulChecks = json.has("successfulChecks") ? json.get("successfulChecks").getAsLong() : 0;
            onlineSince = json.has("onlineSince") ? json.get("onlineSince").getAsLong() : -1;
            lastMaxPlayers = json.has("lastMaxPlayers") ? json.get("lastMaxPlayers").getAsInt() : 0;
            totalLogins = json.has("totalLogins") ? json.get("totalLogins").getAsLong() : 0;
            statsMessageId = json.has("statsMessageId") && !json.get("statsMessageId").isJsonNull()
                ? json.get("statsMessageId").getAsString() : null;
        } catch (Exception e) {
            logger.error("Error loading persistent stats data", e);
        }
    }

    private static void saveStatsData() {
        try (FileWriter writer = new FileWriter(DATA_FILE)) {
            JsonObject json = new JsonObject();
            json.addProperty("peakPlayers", peakPlayers);
            json.addProperty("totalChecks", totalChecks);
            json.addProperty("successfulChecks", successfulChecks);
            json.addProperty("onlineSince", onlineSince);
            json.addProperty("lastMaxPlayers", lastMaxPlayers);
            json.addProperty("totalLogins", totalLogins);
            json.addProperty("statsMessageId", statsMessageId);
            writer.write(json.toString());
        } catch (Exception e) {
            logger.error("Error saving persistent stats data", e);
        }
    }

    private static String getMaintenanceMessageId() {
        File file = new File("maintenance_state.json");
        if (!file.exists()) return null;
        try (FileReader reader = new FileReader(file)) {
            java.lang.StringBuilder sb = new java.lang.StringBuilder();
            int ch;
            while ((ch = reader.read()) != -1) {
                sb.append((char) ch);
            }
            JsonObject json = JsonParser.parseString(sb.toString()).getAsJsonObject();
            if (json.has("messageId") && !json.get("messageId").isJsonNull()) {
                return json.get("messageId").getAsString();
            }
        } catch (Exception e) {}
        return null;
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

                        response.onlinePlayers = extractJsonInt(json, "online");
                        response.maxPlayers    = extractJsonInt(json, "max");
                        response.ping          = extractJsonLong(json, "latency");

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

    private static void sendOldStatsLog(JDA jda, Message oldMessage) {
        TextChannel logChannel = jda.getTextChannelById(LOG_CHANNEL_ID);
        if (logChannel != null && oldMessage != null) {
            MessageCreateBuilder builder = new MessageCreateBuilder();
            builder.setContent(oldMessage.getContentRaw());
            if (oldMessage.getEmbeds() != null && !oldMessage.getEmbeds().isEmpty()) {
                builder.setEmbeds(oldMessage.getEmbeds());
            }
            if (oldMessage.getComponents() != null && !oldMessage.getComponents().isEmpty()) {
                builder.setComponents(oldMessage.getComponents());
            }
            builder.useComponentsV2(true);
            logChannel.sendMessage(builder.build()).queue(
                success -> logger.debug("Successfully logged old server stats message."),
                error -> logger.error("Failed to log old server stats message.", error)
            );
        }
    }
}