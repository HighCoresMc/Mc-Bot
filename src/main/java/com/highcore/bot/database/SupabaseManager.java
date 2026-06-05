package com.highcore.bot.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

// Supabase
public class SupabaseManager {
    private static final Logger logger = LoggerFactory.getLogger(SupabaseManager.class);

    private final String supabaseUrl;
    private final String supabaseKey;
    private final HttpClient httpClient;

    public SupabaseManager(String supabaseUrl, String supabaseKey) {
        this.supabaseUrl = supabaseUrl.endsWith("/") ? supabaseUrl.substring(0, supabaseUrl.length() - 1) : supabaseUrl;
        this.supabaseKey = supabaseKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public void logEvent(int eventId, String title, String type, String description, String eventDate, int points, int maxSupervisors) {
        try {
            String json = String.format(
                "{\"id\":%d,\"title\":%s,\"type\":%s,\"description\":%s,\"event_date\":%s,\"points\":%d,\"max_supervisors\":%d}",
                eventId,
                jsonStr(title),
                jsonStr(type),
                jsonStr(description),
                jsonStr(eventDate),
                points,
                maxSupervisors
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(supabaseUrl + "/rest/v1/mc_events"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("apikey", supabaseKey)
                    .header("Authorization", "Bearer " + supabaseKey)
                    .header("Prefer", "return=minimal")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            logger.info("Event logged to Supabase successfully. ID: {}", eventId);
                        } else {
                            logger.warn("Supabase returned status {} for event ID {}. Body: {}", response.statusCode(), eventId, response.body());
                        }
                    })
                    .exceptionally(e -> {
                        logger.error("Failed to log event to Supabase", e);
                        return null;
                    });

        } catch (Exception e) {
            logger.error("Error sending event to Supabase", e);
        }
    }

    private String jsonStr(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
}
