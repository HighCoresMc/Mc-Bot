package com.highcore.bot.services;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.cdimascio.dotenv.Dotenv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class AIAssistantService {
    private static final Logger logger = LoggerFactory.getLogger(AIAssistantService.class);
    private static final Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
    private static final String GEMINI_API_KEY = dotenv.get("GEMINI_API_KEY");
    private static final String GEMINI_MODEL = dotenv.get("GEMINI_MODEL", "gemini-2.0-flash");
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final PterodactylService pteroService;
    private String cachedPluginsContext = "";

    // INIT
    public AIAssistantService(PterodactylService pteroService) {
        this.pteroService = pteroService;
        loadPluginsContextAsync();
    }

    // LOAD PLUGINS
    private void loadPluginsContextAsync() {
        new Thread(() -> {
            try {
                JsonArray files = pteroService.listFiles("plugins");
                if (files != null) {
                    List<String> pluginNames = new ArrayList<>();
                    for (JsonElement el : files) {
                        JsonObject fileObj = el.getAsJsonObject();
                        if (fileObj.has("attributes")) {
                            JsonObject attr = fileObj.getAsJsonObject("attributes");
                            String name = attr.has("name") ? attr.get("name").getAsString() : "";
                            boolean isFile = attr.has("is_file") && attr.get("is_file").getAsBoolean();
                            if (!isFile || name.endsWith(".jar")) {
                                pluginNames.add(name.replace(".jar", ""));
                            }
                        }
                    }
                    cachedPluginsContext = String.join(", ", pluginNames);
                    logger.info("AI Assistant loaded plugins context: " + cachedPluginsContext);
                }
            } catch (Exception e) {
                logger.error("Failed to load plugins context for AI Assistant", e);
            }
        }).start();
    }

    // ASK GEMINI
    public String askGemini(List<ChatMessage> history) {
        if (GEMINI_API_KEY == null || GEMINI_API_KEY.isEmpty()) {
            return "AI API key is missing. Contact admin.";
        }

        try {
            String systemInstruction = "You are Leon Trotsky, a legendary helpful AI assistant for the HighCore Minecraft server.\n" +
                    "Your goal is to answer the players' questions using the provided server context and Minecraft Wiki/Fandom knowledge.\n\n" +
                    "SERVER CONTEXT:\n" +
                    "- Active Plugins: " + cachedPluginsContext + "\n\n" +
                    "STRICT RULES:\n" +
                    "1. Respond directly, simply, and with no praise, flattery, or wordy pleasantries.\n" +
                    "2. Support all languages. Detect the player's language and reply in the same language.\n" +
                    "3. Absolutely DO NOT reveal configuration file contents, exact plugin lists (unless explaining a gameplay feature that uses them), database structures, server architecture, or any internal/programmatic details that could help players clone the server or find security exploits.\n" +
                    "4. Absolutely DO NOT share any other player's private data or database info.\n" +
                    "5. Absolutely DO NOT help with cheats, hacks, exploits, or malicious activities.\n" +
                    "6. Act professional, legendary, and straight to the point.";

            JsonObject requestBody = new JsonObject();
            
            JsonObject systemObj = new JsonObject();
            JsonObject systemParts = new JsonObject();
            systemParts.addProperty("text", systemInstruction);
            JsonArray systemPartsArr = new JsonArray();
            systemPartsArr.add(systemParts);
            systemObj.add("parts", systemPartsArr);
            requestBody.add("systemInstruction", systemObj);

            JsonArray contents = new JsonArray();
            for (ChatMessage msg : history) {
                JsonObject turn = new JsonObject();
                turn.addProperty("role", msg.isBot ? "model" : "user");
                JsonObject part = new JsonObject();
                part.addProperty("text", msg.content);
                JsonArray parts = new JsonArray();
                parts.add(part);
                turn.add("parts", parts);
                contents.add(turn);
            }
            requestBody.add("contents", contents);

            JsonObject safetySetting = new JsonObject();
            safetySetting.addProperty("category", "HARM_CATEGORY_SEXUALLY_EXPLICIT");
            safetySetting.addProperty("threshold", "BLOCK_NONE");
            JsonArray safetySettings = new JsonArray();
            safetySettings.add(safetySetting);
            requestBody.add("safetySettings", safetySettings);

            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + GEMINI_MODEL + ":generateContent?key=" + GEMINI_API_KEY;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(6))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonObject resObj = JsonParser.parseString(response.body()).getAsJsonObject();
                JsonArray candidates = resObj.getAsJsonArray("candidates");
                if (candidates != null && candidates.size() > 0) {
                    JsonObject candidate = candidates.get(0).getAsJsonObject();
                    JsonObject content = candidate.getAsJsonObject("content");
                    JsonArray parts = content.getAsJsonArray("parts");
                    if (parts != null && parts.size() > 0) {
                        return parts.get(0).getAsJsonObject().get("text").getAsString();
                    }
                }
            } else {
                logger.error("Gemini API error (Status {}): {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            logger.error("Error communicating with Gemini API", e);
        }
        return "عذراً، لم أتمكن من معالجة الطلب في الوقت الحالي.";
    }

    // CHAT MESSAGE CLASS
    public static class ChatMessage {
        public final String content;
        public final boolean isBot;

        public ChatMessage(String content, boolean isBot) {
            this.content = content;
            this.isBot = isBot;
        }
    }
}
