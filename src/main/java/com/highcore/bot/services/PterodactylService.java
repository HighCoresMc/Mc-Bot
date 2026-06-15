package com.highcore.bot.services;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.cdimascio.dotenv.Dotenv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class PterodactylService {
    private static final Logger logger = LoggerFactory.getLogger(PterodactylService.class);
    private static final Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
    private static final String PANEL_URL = "https://panel.highcores.com";
    private static final String API_KEY = dotenv.get("PTERODACTYL_API_KEY");
    private static final String SERVER_ID = dotenv.get("PTERODACTYL_SERVER_ID", "7bc59359");
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    private WebSocket currentWebSocket = null;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });
    private java.util.concurrent.ScheduledFuture<?> pingFuture = null;
    private Consumer<String> messageListener = null;
    private enum ConnectionState {
        DISCONNECTED,
        SCHEDULING,
        CONNECTING,
        CONNECTED
    }
    private ConnectionState connectionState = ConnectionState.DISCONNECTED;
    private volatile long lastPongTime = 0;

    // INIT
    public boolean sendPowerSignal(String signal) {
        if (API_KEY == null || SERVER_ID == null) {
            logger.error("Pterodactyl API Key or Server ID is missing.");
            return false;
        }
        try {
            String url = PANEL_URL + "/api/client/servers/" + SERVER_ID + "/power";
            String jsonBody = "{\"signal\": \"" + signal + "\"}";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + API_KEY)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .timeout(java.time.Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 204;
        } catch (Exception e) {
            logger.error("Failed to send power signal to Pterodactyl", e);
            return false;
        }
    }

    // RESOURCES
    public JsonObject getServerResources() {
        if (API_KEY == null || SERVER_ID == null) {
            return null;
        }
        try {
            String url = PANEL_URL + "/api/client/servers/" + SERVER_ID + "/resources";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + API_KEY)
                    .header("Accept", "application/json")
                    .timeout(java.time.Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("attributes");
            }
        } catch (Exception e) {
            logger.error("Failed to fetch server resources from Pterodactyl", e);
        }
        return null;
    }

    // WEBSOCKET CONNECTION
    public synchronized void connectToConsole(Consumer<String> onMessageReceived) {
        if (onMessageReceived != null) {
            this.messageListener = onMessageReceived;
        }
        if (API_KEY == null || SERVER_ID == null) {
            return;
        }
        if (connectionState == ConnectionState.CONNECTED || connectionState == ConnectionState.CONNECTING || connectionState == ConnectionState.SCHEDULING) {
            return;
        }
        connectionState = ConnectionState.CONNECTING;
        connectToConsoleInternal();
    }

    private void connectToConsoleInternal() {
        try {
            String url = PANEL_URL + "/api/client/servers/" + SERVER_ID + "/websocket";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + API_KEY)
                    .header("Accept", "application/json")
                    .timeout(java.time.Duration.ofSeconds(10))
                    .GET()
                    .build();

            HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((response, err) -> {
                        if (err != null) {
                            if (err.getCause() instanceof java.net.ConnectException || err instanceof java.net.ConnectException) {
                                logger.error("Failed to connect to Pterodactyl API: Connection refused. Retrying...");
                            } else {
                                logger.error("Failed to fetch WebSocket details from Pterodactyl", err);
                            }
                            synchronized (PterodactylService.this) {
                                connectionState = ConnectionState.DISCONNECTED;
                                scheduleReconnect();
                            }
                            return;
                        }
                        if (response.statusCode() == 200) {
                            try {
                                JsonObject data = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("data");
                                String wssUrl = data.get("socket").getAsString();
                                String token = data.get("token").getAsString();

                                synchronized (PterodactylService.this) {
                                    if (currentWebSocket != null) {
                                        WebSocket wsToClose = currentWebSocket;
                                        currentWebSocket = null;
                                        try {
                                            wsToClose.sendClose(WebSocket.NORMAL_CLOSURE, "Reconnecting");
                                        } catch (Exception ignored) {}
                                    }
                                }

                                HTTP_CLIENT.newWebSocketBuilder()
                                        .header("Origin", PANEL_URL)
                                        .buildAsync(URI.create(wssUrl), new WebSocket.Listener() {
                                            StringBuilder messageBuffer = new StringBuilder();

                                            @Override
                                            public void onOpen(WebSocket webSocket) {
                                                logger.info("Connected to Pterodactyl Console WebSocket");
                                                lastPongTime = System.currentTimeMillis();
                                                String authMessage = "{\"event\":\"auth\",\"args\":[\"" + token + "\"]}";
                                                webSocket.sendText(authMessage, true);
                                                WebSocket.Listener.super.onOpen(webSocket);
                                            }

                                            @Override
                                            public CompletionStage<?> onPong(WebSocket webSocket, java.nio.ByteBuffer message) {
                                                lastPongTime = System.currentTimeMillis();
                                                return WebSocket.Listener.super.onPong(webSocket, message);
                                            }

                                            @Override
                                            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                                                messageBuffer.append(data);
                                                if (last) {
                                                    String completeMessage = messageBuffer.toString();
                                                    messageBuffer.setLength(0);
                                                    
                                                    try {
                                                        JsonObject json = JsonParser.parseString(completeMessage).getAsJsonObject();
                                                        String event = json.get("event").getAsString();
                                                        if ("auth success".equals(event)) {
                                                            webSocket.sendText("{\"event\":\"send logs\",\"args\":[]}", true);
                                                        } else if ("console output".equals(event)) {
                                                            JsonArray args = json.getAsJsonArray("args");
                                                            if (args != null && args.size() > 0) {
                                                                String cleanLog = cleanAnsiForDiscord(args.get(0).getAsString());
                                                                if (cleanLog != null) {
                                                                    String[] lines = cleanLog.split("\\r?\\n");
                                                                    for (String singleLine : lines) {
                                                                        if (messageListener != null) {
                                                                            messageListener.accept(colorizeLogLine(singleLine));
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    } catch (Exception e) {
                                                        logger.error("Error parsing websocket message", e);
                                                    }
                                                }
                                                return WebSocket.Listener.super.onText(webSocket, data, last);
                                            }

                                            @Override
                                            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                                                if (pingFuture != null) {
                                                    pingFuture.cancel(false);
                                                    pingFuture = null;
                                                }
                                                synchronized (PterodactylService.this) {
                                                    if (webSocket == currentWebSocket) {
                                                        currentWebSocket = null;
                                                        connectionState = ConnectionState.DISCONNECTED;
                                                        scheduleReconnect();
                                                    }
                                                }
                                                return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
                                            }

                                            @Override
                                            public void onError(WebSocket webSocket, Throwable error) {
                                                if (pingFuture != null) {
                                                    pingFuture.cancel(false);
                                                    pingFuture = null;
                                                }
                                                synchronized (PterodactylService.this) {
                                                    if (webSocket == currentWebSocket) {
                                                        currentWebSocket = null;
                                                        connectionState = ConnectionState.DISCONNECTED;
                                                        scheduleReconnect();
                                                    }
                                                }
                                                WebSocket.Listener.super.onError(webSocket, error);
                                            }
                                        }).whenComplete((ws, err2) -> {
                                            if (err2 != null) {
                                                logger.error("WebSocket connection failed asynchronously", err2);
                                                synchronized (PterodactylService.this) {
                                                    connectionState = ConnectionState.DISCONNECTED;
                                                    scheduleReconnect();
                                                }
                                            } else {
                                                synchronized (PterodactylService.this) {
                                                    currentWebSocket = ws;
                                                    connectionState = ConnectionState.CONNECTED;
                                                }

                                                if (pingFuture != null) {
                                                    pingFuture.cancel(false);
                                                }
                                                pingFuture = scheduler.scheduleAtFixedRate(() -> {
                                                    synchronized (PterodactylService.this) {
                                                        if (currentWebSocket != null && !currentWebSocket.isInputClosed() && !currentWebSocket.isOutputClosed()) {
                                                            long now = System.currentTimeMillis();
                                                            if (now - lastPongTime > 20000) {
                                                                logger.warn("WebSocket connection is dead (no pong received), reconnecting...");
                                                                WebSocket wsToClose = currentWebSocket;
                                                                currentWebSocket = null;
                                                                connectionState = ConnectionState.DISCONNECTED;
                                                                try {
                                                                    wsToClose.abort();
                                                                } catch (Exception ignored) {}
                                                                scheduleReconnect();
                                                                return;
                                                            }
                                                            currentWebSocket.sendPing(java.nio.ByteBuffer.allocate(0)).whenComplete((pingWs, pingErr) -> {
                                                                if (pingErr != null) {
                                                                    logger.warn("Failed to send WebSocket keep-alive ping, reconnecting...", pingErr);
                                                                    synchronized (PterodactylService.this) {
                                                                        if (pingFuture != null) {
                                                                            pingFuture.cancel(false);
                                                                            pingFuture = null;
                                                                        }
                                                                        if (currentWebSocket == ws) {
                                                                            currentWebSocket = null;
                                                                            connectionState = ConnectionState.DISCONNECTED;
                                                                            scheduleReconnect();
                                                                        }
                                                                    }
                                                                }
                                                            });
                                                        }
                                                    }
                                                }, 10, 10, TimeUnit.SECONDS);
                                            }
                                        });
                            } catch (Exception e) {
                                logger.error("Failed to parse WebSocket details response", e);
                                synchronized (PterodactylService.this) {
                                    connectionState = ConnectionState.DISCONNECTED;
                                    scheduleReconnect();
                                }
                            }
                        } else {
                            logger.error("Failed to fetch WebSocket details, HTTP status code: {}", response.statusCode());
                            synchronized (PterodactylService.this) {
                                connectionState = ConnectionState.DISCONNECTED;
                                scheduleReconnect();
                            }
                        }
                    });
        } catch (Exception e) {
            logger.error("Failed to initiate async request for WebSocket details", e);
            synchronized (PterodactylService.this) {
                connectionState = ConnectionState.DISCONNECTED;
                scheduleReconnect();
            }
        }
    }

    private synchronized void scheduleReconnect() {
        if (connectionState == ConnectionState.SCHEDULING || connectionState == ConnectionState.CONNECTING || connectionState == ConnectionState.CONNECTED) {
            return;
        }
        connectionState = ConnectionState.SCHEDULING;
        scheduler.schedule(() -> {
            synchronized (PterodactylService.this) {
                if (connectionState != ConnectionState.SCHEDULING) {
                    return;
                }
                connectionState = ConnectionState.CONNECTING;
            }
            connectToConsoleInternal();
        }, 5, TimeUnit.SECONDS);
    }

    // CLOSE
    public void closeConsole() {
        synchronized (this) {
            if (currentWebSocket != null) {
                WebSocket wsToClose = currentWebSocket;
                currentWebSocket = null;
                try {
                    wsToClose.sendClose(WebSocket.NORMAL_CLOSURE, "Closing console");
                } catch (Exception ignored) {}
            }
            connectionState = ConnectionState.DISCONNECTED;
        }
    }
    
    // SEND CMD
    public void sendCommand(String command) {
        synchronized (this) {
            if (currentWebSocket != null) {
                String msg = "{\"event\":\"send command\",\"args\":[\"" + command.replace("\"", "\\\"") + "\"]}";
                currentWebSocket.sendText(msg, true);
            }
        }
    }

    public boolean sendConsoleCommand(String command) {
        if (API_KEY == null || SERVER_ID == null) {
            return false;
        }
        try {
            String url = PANEL_URL + "/api/client/servers/" + SERVER_ID + "/command";
            String jsonBody = "{\"command\": \"" + command.replace("\"", "\\\"") + "\"}";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + API_KEY)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .timeout(java.time.Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 204;
        } catch (Exception e) {
            logger.error("Failed to send console command to Pterodactyl", e);
            return false;
        }
    }

    public void reconnectConsole() {
        synchronized (this) {
            if (currentWebSocket != null) {
                WebSocket wsToClose = currentWebSocket;
                currentWebSocket = null;
                try {
                    wsToClose.abort();
                } catch (Exception ignored) {}
            }
            connectionState = ConnectionState.CONNECTING;
        }
        connectToConsoleInternal();
    }

    private String cleanAnsiForDiscord(String input) {
        if (input == null) return null;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\u001B\\[([0-9;]*)m");
        java.util.regex.Matcher matcher = pattern.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String content = matcher.group(1);
            if (content.isEmpty()) {
                matcher.appendReplacement(sb, "\u001B[0m");
                continue;
            }
            String[] parts = content.split(";");
            java.util.List<String> validParts = new java.util.ArrayList<>();
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                if (part.isEmpty()) continue;
                try {
                    int val = Integer.parseInt(part);
                    if ((val == 38 || val == 48) && i + 2 < parts.length) {
                        int type = Integer.parseInt(parts[i + 1]);
                        if (type == 5) {
                            int colorIndex = Integer.parseInt(parts[i + 2]);
                            int basicColor = convert256ToBasic(colorIndex);
                            int targetVal = (val == 38) ? (30 + basicColor) : (40 + basicColor);
                            validParts.add(String.valueOf(targetVal));
                            i += 2;
                        } else if (type == 2 && i + 4 < parts.length) {
                            int r = Integer.parseInt(parts[i + 2]);
                            int g = Integer.parseInt(parts[i + 3]);
                            int b = Integer.parseInt(parts[i + 4]);
                            int basicColor = convertRgbToBasic(r, g, b);
                            int targetVal = (val == 38) ? (30 + basicColor) : (40 + basicColor);
                            validParts.add(String.valueOf(targetVal));
                            i += 4;
                        }
                    } else if (val == 0 || val == 1 || val == 4) {
                        validParts.add(String.valueOf(val));
                    } else if (val >= 30 && val <= 37) {
                        validParts.add(String.valueOf(val));
                    } else if (val >= 40 && val <= 47) {
                        validParts.add(String.valueOf(val));
                    } else if (val >= 90 && val <= 97) {
                        validParts.add(String.valueOf(val - 60));
                    } else if (val >= 100 && val <= 107) {
                        validParts.add(String.valueOf(val - 60));
                    }
                } catch (Exception ignored) {}
            }
            for (int k = 0; k < validParts.size(); k++) {
                String p = validParts.get(k);
                if (p.equals("34")) {
                    validParts.set(k, "36");
                }
            }
            boolean hasForeground = false;
            for (String p : validParts) {
                try {
                    int val = Integer.parseInt(p);
                    if (val >= 30 && val <= 37) {
                        hasForeground = true;
                        break;
                    }
                } catch (Exception ignored) {}
            }
            if (hasForeground) {
                validParts.remove("0");
                if (!validParts.contains("1")) {
                    validParts.add(0, "1");
                }
            }
            if (validParts.isEmpty()) {
                matcher.appendReplacement(sb, "");
            } else {
                matcher.appendReplacement(sb, "\u001B[" + String.join(";", validParts) + "m");
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private int convert256ToBasic(int index) {
        if (index < 8) return index;
        if (index < 16) return index - 8;
        if (index >= 16 && index <= 231) {
            int adjusted = index - 16;
            int r = (adjusted / 36) * 51;
            int g = ((adjusted % 36) / 6) * 51;
            int b = (adjusted % 6) * 51;
            return convertRgbToBasic(r, g, b);
        }
        int gray = 8 + (index - 232) * 10;
        return convertRgbToBasic(gray, gray, gray);
    }

    private int convertRgbToBasic(int r, int g, int b) {
        int[][] basicColors = {
            {0, 0, 0},
            {205, 0, 0},
            {0, 205, 0},
            {205, 205, 0},
            {0, 0, 238},
            {205, 0, 205},
            {0, 205, 205},
            {229, 229, 229}
        };
        int bestIndex = 7;
        double minDistance = Double.MAX_VALUE;
        for (int i = 0; i < basicColors.length; i++) {
            int dr = r - basicColors[i][0];
            int dg = g - basicColors[i][1];
            int db = b - basicColors[i][2];
            double dist = dr * dr + dg * dg + db * db;
            if (dist < minDistance) {
                minDistance = dist;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    public boolean isConsoleDisconnected() {
        synchronized (this) {
            return currentWebSocket == null || currentWebSocket.isInputClosed() || currentWebSocket.isOutputClosed();
        }
    }

    public String getFileContents(String path) {
        if (API_KEY == null || SERVER_ID == null) {
            return null;
        }
        try {
            String encodedPath = java.net.URLEncoder.encode(path, java.nio.charset.StandardCharsets.UTF_8);
            String url = PANEL_URL + "/api/client/servers/" + SERVER_ID + "/files/contents?file=" + encodedPath;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + API_KEY)
                    .header("Accept", "application/json")
                    .timeout(java.time.Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return response.body();
            }
        } catch (Exception e) {
            logger.error("Failed to fetch file contents from Pterodactyl: " + path, e);
        }
        return null;
    }

    public java.util.List<String> getLatestLogs(int maxLines) {
        String content = getFileContents("logs/latest.log");
        java.util.List<String> result = new java.util.ArrayList<>();
        if (content != null && !content.trim().isEmpty()) {
            String clean = cleanAnsiForDiscord(content);
            String[] lines = clean.split("\\r?\\n");
            int start = Math.max(0, lines.length - maxLines);
            for (int i = start; i < lines.length; i++) {
                result.add(colorizeLogLine(lines[i]));
            }
        }
        return result;
    }

    private String colorizeLogLine(String line) {
        if (line == null) return null;
        line = line.replaceFirst("^\\[(\\d{2}:\\d{2}:\\d{2})\\]\\s*\\[[^\\]]+/([A-Z]+)\\]:", "[$1 $2]:");
        if (line.contains("\u001B[")) {
            return line;
        }
        String clean = line.replaceAll("\u001B\\[[0-9;]*m", "");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\[?\\d{2}:\\d{2}:\\d{2}\\]?\\s*(?:\\[[^\\]]+\\/)?(?:INFO|WARN|ERROR|DEBUG|FATAL|WARNING)\\]?:?)\\s*(.*)$").matcher(clean);
        if (m.find()) {
            String prefix = m.group(1);
            String rest = m.group(2);
            String coloredPrefix = prefix;
            boolean isError = prefix.contains("ERROR") || prefix.contains("FATAL") || rest.toLowerCase().contains("exception") || rest.toLowerCase().contains("error") || rest.toLowerCase().contains("invalid") || rest.toLowerCase().contains("not valid");
            boolean isWarn = prefix.contains("WARN") || prefix.contains("WARNING");
            if (prefix.contains("WARN")) {
                coloredPrefix = prefix.replace("WARN", "\u001B[1;33mWARN\u001B[0m");
            } else if (prefix.contains("WARNING")) {
                coloredPrefix = prefix.replace("WARNING", "\u001B[1;33mWARNING\u001B[0m");
            } else if (prefix.contains("ERROR")) {
                coloredPrefix = prefix.replace("ERROR", "\u001B[1;31mERROR\u001B[0m");
            } else if (prefix.contains("FATAL")) {
                coloredPrefix = prefix.replace("FATAL", "\u001B[1;31mFATAL\u001B[0m");
            } else if (prefix.contains("DEBUG")) {
                coloredPrefix = prefix.replace("DEBUG", "\u001B[1;36mDEBUG\u001B[0m");
            }
            String coloredRest = rest;
            if (isError) {
                coloredRest = "\u001B[1;31m" + rest + "\u001B[0m";
            } else if (isWarn) {
                coloredRest = "\u001B[1;33m" + rest + "\u001B[0m";
            } else {
                coloredRest = colorizeInfoRest(rest);
            }
            return coloredPrefix + " " + coloredRest;
        }
        String cleanLower = clean.toLowerCase();
        if (clean.contains(" ERROR]:") || cleanLower.contains("exception") || cleanLower.contains("error") || clean.contains("<--[HERE]") || clean.startsWith("\tat ") || clean.contains("Caused by:")) {
            return "\u001B[1;31m" + clean + "\u001B[0m";
        }
        if (clean.contains(" WARN]:") || clean.contains(" WARNING]:") || cleanLower.contains("warning") || cleanLower.contains("warn")) {
            return "\u001B[1;33m" + clean + "\u001B[0m";
        }
        return colorizeInfoRest(clean);
    }

    private String colorizeInfoRest(String rest) {
        if (rest == null || rest.isEmpty()) {
            return rest;
        }
        rest = rest.replaceAll("https?:\\/\\/[^\\s]+", "\u001B[1;36m$0\u001B[0m");
        rest = rest.replace("Oraxen | No broken models or textures were found in the resourcepack",
                "\u001B[1;36mOraxen |\u001B[0m \u001B[1;32mNo broken models or textures were found in the resourcepack\u001B[0m");
        rest = rest.replace("Oraxen |", "\u001B[1;36mOraxen |\u001B[0m");
        rest = rest.replace("Duels Optimised » Registering post listeners...",
                "\u001B[1;36mDuels Optimised » Registering post listeners...\u001B[0m");
        rest = rest.replace("Duels Optimised »", "\u001B[1;36mDuels Optimised »\u001B[0m");
        rest = rest.replace("Successfully registered listeners after plugin startup in",
                "\u001B[1;35mSuccessfully registered listeners after plugin startup in\u001B[0m");
        rest = rest.replace("DiscordSRV found. (Loaded: true)",
                "\u001B[1;33mDiscordSRV\u001B[0m \u001B[1;36mfound. (Loaded: true)\u001B[0m");
        rest = rest.replaceAll("\\[([a-zA-Z]+)\\]", "\u001B[1;32m[$1]\u001B[0m");
        rest = rest.replaceAll("\\[(\\d+ms)\\]", "\u001B[1;32m[$1]\u001B[0m");
        rest = rest.replaceAll("\\((\\d+(?:\\.\\d+)?s)\\)", "\u001B[1;32m($1)\u001B[0m");
        rest = rest.replaceAll("\\b(\\d+ms)\\b", "\u001B[1;32m$1\u001B[0m");
        rest = rest.replaceAll("\\b(\\d+)\\s+(NPCs|plugin\\(s\\)|cancelled)\\b", "\u001B[1;32m$1\u001B[0m $2");
        rest = rest.replaceAll("\\b(\\d+/\\d+)\\s+guilds\\b", "\u001B[1;32m$1\u001B[0m guilds");
        rest = rest.replaceAll("\\b(\\d+)\\s+placeholder hook\\(s\\) registered!", "\u001B[1;32m$1 placeholder hook(s) registered!\u001B[0m");
        rest = rest.replaceAll("\\bDone\\b", "\u001B[1;32mDone\u001B[0m");
        return rest;
    }
}
