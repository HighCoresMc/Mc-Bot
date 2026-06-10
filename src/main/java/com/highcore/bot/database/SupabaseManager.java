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

    public void upsertTeam(String name, String color, String leader, String member2,
                            String member3, String member4, String tag) {
        try {
            String json = String.format(
                "{\"admin\":\"HighCoreMc Bot\",\"name\":%s,\"team_name\":%s,\"color\":%s," +
                "\"leader\":%s,\"member2\":%s,\"member3\":%s,\"member4\":%s,\"tag\":%s}",
                jsonStr(name), jsonStr(name), jsonStr(color),
                jsonStr(leader), jsonStr(member2), jsonStr(member3), jsonStr(member4),
                jsonStr(tag)
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(supabaseUrl + "/rest/v1/teams"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("apikey", supabaseKey)
                    .header("Authorization", "Bearer " + supabaseKey)
                    .header("Prefer", "return=minimal,resolution=merge-duplicates")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            logger.info("Team '{}' synced to Supabase successfully.", name);
                        } else {
                            logger.warn("Supabase returned {} for team '{}'. Body: {}",
                                response.statusCode(), name, response.body());
                        }
                    })
                    .exceptionally(e -> {
                        logger.error("Failed to sync team to Supabase", e);
                        return null;
                    });
        } catch (Exception e) {
            logger.error("Error sending team to Supabase", e);
        }
    }

    public void updateTeam(String name, String color, String leader, String member2,
                            String member3, String member4, String tag) {
        try {
            String encodedName = java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8);
            String json = String.format(
                "{\"admin\":\"HighCoreMc Bot\",\"name\":%s,\"team_name\":%s,\"color\":%s," +
                "\"leader\":%s,\"member2\":%s,\"member3\":%s,\"member4\":%s,\"tag\":%s}",
                jsonStr(name), jsonStr(name), jsonStr(color),
                jsonStr(leader), jsonStr(member2), jsonStr(member3), jsonStr(member4),
                jsonStr(tag)
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(supabaseUrl + "/rest/v1/teams?name=eq." + encodedName))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("apikey", supabaseKey)
                    .header("Authorization", "Bearer " + supabaseKey)
                    .header("Prefer", "return=minimal")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(json))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            logger.info("Team '{}' updated in Supabase successfully.", name);
                        } else {
                            logger.warn("Supabase returned {} when updating team '{}'. Body: {}",
                                response.statusCode(), name, response.body());
                        }
                    })
                    .exceptionally(e -> {
                        logger.error("Failed to update team in Supabase", e);
                        return null;
                    });
        } catch (Exception e) {
            logger.error("Error updating team in Supabase", e);
        }
    }

    public void deleteTeam(String name) {
        try {
            String encodedName = java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(supabaseUrl + "/rest/v1/teams?name=eq." + encodedName))
                    .timeout(Duration.ofSeconds(15))
                    .header("apikey", supabaseKey)
                    .header("Authorization", "Bearer " + supabaseKey)
                    .DELETE()
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            logger.info("Team '{}' deleted from Supabase.", name);
                        } else {
                            logger.warn("Supabase returned {} when deleting team '{}'.",
                                response.statusCode(), name);
                        }
                    })
                    .exceptionally(e -> {
                        logger.error("Failed to delete team from Supabase", e);
                        return null;
                    });
        } catch (Exception e) {
            logger.error("Error deleting team from Supabase", e);
        }
    }

    public void updateTeamTag(String name, String tag) {
        try {
            String encodedName = java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8);
            String json = "{\"tag\":" + jsonStr(tag) + "}";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(supabaseUrl + "/rest/v1/teams?name=eq." + encodedName))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("apikey", supabaseKey)
                    .header("Authorization", "Bearer " + supabaseKey)
                    .header("Prefer", "return=minimal")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(json))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            logger.info("Team '{}' tag updated to '{}' in Supabase.", name, tag);
                        } else {
                            logger.warn("Supabase returned {} when updating tag for '{}'.",
                                response.statusCode(), name);
                        }
                    })
                    .exceptionally(e -> {
                        logger.error("Failed to update team tag in Supabase", e);
                        return null;
                    });
        } catch (Exception e) {
            logger.error("Error updating team tag in Supabase", e);
        }
    }

    public String getSupabaseUrl() {
        return supabaseUrl;
    }

    public String getSupabaseKey() {
        return supabaseKey;
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }

    public void logDcEvent(int eventId, String title, String type, String description, String eventDate, int points, int maxSupervisors) {
        try {
            String json = String.format(
                "{\"id\":%d,\"title\":%s,\"event_type\":%s,\"description\":%s,\"event_date\":%s,\"points\":%d,\"max_supervisors\":%d,\"section\":\"dc\",\"created_by\":\"HighCoreMc Bot\"}",
                eventId,
                jsonStr(title),
                jsonStr(type),
                jsonStr(description),
                jsonStr(eventDate),
                points,
                maxSupervisors
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(supabaseUrl + "/rest/v1/events"))
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
                            logger.info("DC Event logged to Supabase successfully. ID: {}", eventId);
                        } else {
                            logger.warn("Supabase returned status {} for DC event ID {}. Body: {}", response.statusCode(), eventId, response.body());
                        }
                    })
                    .exceptionally(e -> {
                        logger.error("Failed to log DC event to Supabase", e);
                        return null;
                    });

        } catch (Exception e) {
            logger.error("Error sending DC event to Supabase", e);
        }
    }

    private String jsonStr(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
}
