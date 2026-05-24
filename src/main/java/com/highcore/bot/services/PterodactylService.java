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
    private boolean isReconnecting = false;

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
    public void connectToConsole(Consumer<String> onMessageReceived) {
        if (onMessageReceived != null) {
            this.messageListener = onMessageReceived;
        }
        if (API_KEY == null || SERVER_ID == null) {
            return;
        }
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
                            logger.error("Failed to fetch WebSocket details from Pterodactyl", err);
                            scheduleReconnect();
                            return;
                        }
                        if (response.statusCode() == 200) {
                            try {
                                JsonObject data = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("data");
                                String wssUrl = data.get("socket").getAsString();
                                String token = data.get("token").getAsString();

                                if (currentWebSocket != null) {
                                    try {
                                        currentWebSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Reconnecting");
                                    } catch (Exception ignored) {}
                                }

                                HTTP_CLIENT.newWebSocketBuilder()
                                        .header("Origin", PANEL_URL)
                                        .buildAsync(URI.create(wssUrl), new WebSocket.Listener() {
                                            StringBuilder messageBuffer = new StringBuilder();

                                            @Override
                                            public void onOpen(WebSocket webSocket) {
                                                logger.info("Connected to Pterodactyl Console WebSocket");
                                                // AUTH
                                                String authMessage = "{\"event\":\"auth\",\"args\":[\"" + token + "\"]}";
                                                webSocket.sendText(authMessage, true);
                                                WebSocket.Listener.super.onOpen(webSocket);
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
                                                         if ("console output".equals(event)) {
                                                             JsonArray args = json.getAsJsonArray("args");
                                                             if (args != null && args.size() > 0) {
                                                                 String cleanLog = cleanAnsiForDiscord(args.get(0).getAsString());
                                                                 if (cleanLog != null) {
                                                                     String[] lines = cleanLog.split("\\r?\\n");
                                                                     for (String singleLine : lines) {
                                                                         if (messageListener != null) {
                                                                             messageListener.accept(singleLine);
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
                                                scheduleReconnect();
                                                return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
                                            }

                                            @Override
                                            public void onError(WebSocket webSocket, Throwable error) {
                                                if (pingFuture != null) {
                                                    pingFuture.cancel(false);
                                                    pingFuture = null;
                                                }
                                                scheduleReconnect();
                                                WebSocket.Listener.super.onError(webSocket, error);
                                            }
                                        }).whenComplete((ws, err2) -> {
                                            if (err2 != null) {
                                                logger.error("WebSocket connection failed asynchronously", err2);
                                                scheduleReconnect();
                                            } else {
                                                currentWebSocket = ws;
                                                currentWebSocket.sendText("{\"event\":\"send logs\",\"args\":[]}", true);

                                                if (pingFuture != null) {
                                                    pingFuture.cancel(false);
                                                }
                                                pingFuture = scheduler.scheduleAtFixedRate(() -> {
                                                    if (currentWebSocket != null && !currentWebSocket.isInputClosed() && !currentWebSocket.isOutputClosed()) {
                                                        currentWebSocket.sendPing(java.nio.ByteBuffer.allocate(0)).whenComplete((pingWs, pingErr) -> {
                                                            if (pingErr != null) {
                                                                logger.warn("Failed to send WebSocket keep-alive ping, reconnecting...", pingErr);
                                                                if (pingFuture != null) {
                                                                    pingFuture.cancel(false);
                                                                    pingFuture = null;
                                                                }
                                                                scheduleReconnect();
                                                            }
                                                        });
                                                    }
                                                }, 30, 30, TimeUnit.SECONDS);
                                            }
                                        });
                            } catch (Exception e) {
                                logger.error("Failed to parse WebSocket details response", e);
                                scheduleReconnect();
                            }
                        } else {
                            logger.error("Failed to fetch WebSocket details, HTTP status code: {}", response.statusCode());
                            scheduleReconnect();
                        }
                    });
        } catch (Exception e) {
            logger.error("Failed to initiate async request for WebSocket details", e);
            scheduleReconnect();
        }
    }

    private synchronized void scheduleReconnect() {
        if (isReconnecting) return;
        isReconnecting = true;
        scheduler.schedule(() -> {
            synchronized (this) {
                isReconnecting = false;
            }
            connectToConsole(this.messageListener);
        }, 5, TimeUnit.SECONDS);
    }

    // CLOSE
    public void closeConsole() {
        if (currentWebSocket != null) {
            currentWebSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Closing console");
            currentWebSocket = null;
        }
    }
    
    // SEND CMD
    public void sendCommand(String command) {
        if (currentWebSocket != null) {
            String msg = "{\"event\":\"send command\",\"args\":[\"" + command.replace("\"", "\\\"") + "\"]}";
            currentWebSocket.sendText(msg, true);
        }
    }

    private String cleanAnsiForDiscord(String input) {
        if (input == null) return null;
        String mapped = input.replaceAll("\u001B\\[(?:0;)?9([0-7])m", "\u001B[1;3$1m");
        mapped = mapped.replaceAll("\u001B\\[1;9([0-7])m", "\u001B[1;3$1m");
        mapped = mapped.replaceAll("\u001B\\[(?:0;)?10([0-7])m", "\u001B[4$1m");
        
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\u001B\\[([0-9;]*)m");
        java.util.regex.Matcher matcher = pattern.matcher(mapped);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String content = matcher.group(1);
            String[] parts = content.split(";");
            java.util.List<String> validParts = new java.util.ArrayList<>();
            boolean hasForeground = false;
            for (String part : parts) {
                if (part.isEmpty()) continue;
                try {
                    int val = Integer.parseInt(part);
                    if (val == 0 || val == 1 || val == 4 || (val >= 30 && val <= 37) || (val >= 40 && val <= 47)) {
                        if (val >= 30 && val <= 37) {
                            hasForeground = true;
                            if (val == 34) {
                                val = 36;
                                validParts.add(String.valueOf(val));
                            } else if (val == 31) {
                                validParts.add("37");
                                validParts.add("41");
                            } else if (val == 33) {
                                validParts.add("30");
                                validParts.add("43");
                            } else {
                                validParts.add(String.valueOf(val));
                            }
                        } else {
                            validParts.add(String.valueOf(val));
                        }
                    }
                } catch (NumberFormatException ignored) {}
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
}
