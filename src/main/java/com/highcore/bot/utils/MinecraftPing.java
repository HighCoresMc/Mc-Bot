package com.highcore.bot.utils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MinecraftPing {
    public static class StatusResponse {
        public boolean online;
        public String motd = "";
        public int onlinePlayers = 0;
        public int maxPlayers = 0;
        public long ping = -1;
    }

    public static StatusResponse ping(String host, int port, int timeout) {
        StatusResponse response = new StatusResponse();
        long start = System.nanoTime();
        try {

            String apiUrl = "http://api-mcstatus.railway.internal:8080/status/java/" + host + ":" + port;
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);

            response.ping = (System.nanoTime() - start) / 1_000_000;

            if (conn.getResponseCode() == 200) {
                BufferedReader rd = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder result = new StringBuilder();
                String line;
                while ((line = rd.readLine()) != null) {
                    result.append(line);
                }
                rd.close();

                String json = result.toString();
                response.online = extractJsonBool(json, "online");
                
                if (response.online) {
                    response.onlinePlayers = extractJsonInt(json, "online");
                    response.maxPlayers = extractJsonInt(json, "max");
                    response.motd = extractJsonString(json, "clean");
                    if (response.motd.isEmpty()) {
                        response.motd = extractJsonString(json, "raw");
                    }
                }
            } else {
                response.online = false;
            }
        } catch (Exception e) {
            response.online = false;
        }
        return response;
    }

    private static boolean extractJsonBool(String json, String key) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*(true|false)");
        java.util.regex.Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return Boolean.parseBoolean(matcher.group(1));
        }
        return false;
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

    private static String extractJsonString(String json, String key) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"");
        java.util.regex.Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }
}