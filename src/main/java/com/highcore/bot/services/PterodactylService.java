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
import java.util.function.Consumer;

public class PterodactylService {
    private static final Logger logger = LoggerFactory.getLogger(PterodactylService.class);
    private static final Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
    private static final String PANEL_URL = "https://panel.highcores.com";
    private static final String API_KEY = dotenv.get("PTERODACTYL_API_KEY");
    private static final String SERVER_ID = dotenv.get("PTERODACTYL_SERVER_ID", "7bc59359");
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    private WebSocket currentWebSocket = null;

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
        if (API_KEY == null || SERVER_ID == null) {
            return;
        }
        try {
            // First, get the websocket details
            String url = PANEL_URL + "/api/client/servers/" + SERVER_ID + "/websocket";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + API_KEY)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonObject data = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("data");
                String wssUrl = data.get("socket").getAsString();
                String token = data.get("token").getAsString();

                if (currentWebSocket != null) {
                    currentWebSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Reconnecting");
                }

                CompletableFuture<WebSocket> wsFuture = HTTP_CLIENT.newWebSocketBuilder()
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
                                                String line = args.get(0).getAsString().replaceAll("\u001B\\[[;\\d]*m", "");
                                                onMessageReceived.accept(line);
                                            }
                                        }
                                    } catch (Exception e) {
                                        logger.error("Error parsing websocket message", e);
                                    }
                                }
                                return WebSocket.Listener.super.onText(webSocket, data, last);
                            }

                            @Override
                            public void onError(WebSocket webSocket, Throwable error) {
                                logger.error("WebSocket Error", error);
                                WebSocket.Listener.super.onError(webSocket, error);
                            }
                        });
                        
                currentWebSocket = wsFuture.join();
                currentWebSocket.sendText("{\"event\":\"send logs\",\"args\":[]}", true);
            }
        } catch (Exception e) {
            logger.error("Failed to connect to Pterodactyl WebSocket", e);
        }
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
}
