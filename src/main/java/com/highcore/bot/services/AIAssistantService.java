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
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final PterodactylService pteroService;
    private String cachedPluginsContext = "";
    private String customConfigsContext = "";

    // INIT
    public AIAssistantService(PterodactylService pteroService) {
        this.pteroService = pteroService;
        loadPluginsContextAsync();
        loadCustomConfigsAsync();
    }

    // LOAD CUSTOM CONFIGS
    private void loadCustomConfigsAsync() {
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            String[] targetFiles = {
                "plugins/CoreClaims/config.yml",
                "plugins/CoreClaims/messages.yml",
                "plugins/BetterTeams/config.yml",
                "plugins/AthisAirdrops/config.yml"
            };
            for (String file : targetFiles) {
                try {
                    String content = pteroService.getFileContents(file);
                    if (content != null && !content.trim().isEmpty()) {
                        sb.append("FILE: ").append(file).append("\n");
                        sb.append(content).append("\n---\n");
                    }
                } catch (Exception e) {
                    logger.error("Failed to load custom config: " + file, e);
                }
            }
            customConfigsContext = sb.toString();
            logger.info("Loaded custom configs context length: " + customConfigsContext.length());
        }).start();
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
        try {
            String systemInstruction = "You are Leon Trotsky, a legendary helpful AI assistant for the HighCore Minecraft server.\n" +
                    "Your goal is to answer the players' questions using the provided server context and Minecraft Wiki/Fandom knowledge.\n\n" +
                    "SERVER CONTEXT:\n" +
                    "- Active Plugins: " + cachedPluginsContext + "\n" +
                    "- Custom Plugin Configs/Rules:\n" + customConfigsContext + "\n\n" +
                    "GAMEPLAY RULES TO EXPLAIN TO PLAYERS:\n" +
                    "1. For the Claims system (نظام الحماية): Players must use the in-game command `/cc claim` to receive their claim tools (Claim Wand and Unclaim Wand) and a Power Generator.\n" +
                    "   - To claim a chunk: Hold the Claim Wand (عصا الحماية) and right-click on the ground.\n" +
                    "   - The claim requires a Power Generator (مولد طاقة) placed and fueled in the chunk to remain active.\n" +
                    "   - The Power Generator FUEL is: Coal Block (بلوك فحم), Coal/Charcoal (فحم), and Wood/Logs (خشب). DO NOT say Lodestone is the fuel or generator (it is just a custom item design).\n" +
                    "   - To unclaim: Hold the Unclaim Wand (عصا إلغاء الحماية) and do Sneak + Left-Click (شيفت + كليك يسار).\n" +
                    "2. For Teams (نظام الفرق): Players MUST use the Discord bot command `/team panel` for EVERYTHING related to teams (creating a team, inviting, joining, managing, declaring war, etc.). Absolutely DO NOT give them in-game Minecraft commands like '/team create', '/team invite', '/team war', or any other '/team' command. Always direct them to use the Discord bot.\n" +
                    "3. Translation rules for Arabic: Use 'تخريب' for griefing, 'ريد / سرقة' for raiding. Do NOT use translations like 'شرحه', 'تعويضه', 'الخنقّ', or 'السحّار'. Use clean, native Arabic Minecraft terminology.\n\n" +
                    "STRICT RULES:\n" +
                    "1. Respond directly, simply, and with no praise, flattery, or wordy pleasantries.\n" +
                    "2. Support all languages. Detect the player's language and reply in the same language.\n" +
                    "3. Absolutely DO NOT reveal configuration file contents verbatim, database structures, server architecture, or any internal/programmatic details.\n" +
                    "4. Absolutely DO NOT mention or disclose technical plugin names (e.g., 'CoreClaims', 'BetterTeams', 'AthisAirdrops') to the player under any circumstances. Instead, refer to them by their gameplay terms (e.g. 'نظام الحماية' or 'كليمز', 'نظام الفرق', 'الدروبات').\n" +
                    "5. Absolutely DO NOT share any other player's private data or database info.\n" +
                    "6. Absolutely DO NOT help with cheats, hacks, exploits, or malicious activities.\n" +
                    "7. Absolutely DO NOT tell players to contact administration, open a ticket, or ask support. You are the AI Assistant; you must answer their questions directly based on the provided info. If you don't know something, tell them you don't have that specific information right now.\n" +
                    "8. Absolutely DO NOT invent or hallucinate mechanics that do not exist in Vanilla Minecraft (like thirst, temperature, or sitting on blocks) unless they are in the SERVER CONTEXT. If a player asks about something impossible, clearly state it's not a feature.\n" +
                    "9. Act professional, legendary, and straight to the point.";

            JsonObject requestBody = new JsonObject();
            JsonArray messages = new JsonArray();

            JsonObject systemMsg = new JsonObject();
            systemMsg.addProperty("role", "system");
            systemMsg.addProperty("content", systemInstruction);
            messages.add(systemMsg);

            for (ChatMessage msg : history) {
                JsonObject turn = new JsonObject();
                turn.addProperty("role", msg.isBot ? "assistant" : "user");
                turn.addProperty("content", msg.content);
                messages.add(turn);
            }
            requestBody.add("messages", messages);
            requestBody.addProperty("model", "openai");
            requestBody.addProperty("jsonMode", false);

            String url = "https://text.pollinations.ai/";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(25))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return response.body();
            } else {
                logger.error("Pollinations AI error (Status {}): {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            logger.error("Error communicating with Pollinations AI", e);
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
