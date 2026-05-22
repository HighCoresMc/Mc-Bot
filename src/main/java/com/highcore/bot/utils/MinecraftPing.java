package com.highcore.bot.utils;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.regex.*;

public class MinecraftPing {
    public static class StatusResponse {
        public boolean online;
        public String motd = "", raw = "";
        public int onlinePlayers, maxPlayers;
        public long ping = -1;
    }

    public static StatusResponse ping(String host, int port, int timeout) {
        StatusResponse r = new StatusResponse();
        long start = System.nanoTime();
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL("https://api.mcstatus.io/v2/status/java/" + host + ":" + port).openConnection();
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);
            
            if (conn.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line; while ((line = br.readLine()) != null) sb.append(line);
                String json = sb.toString();
                r.ping = (System.nanoTime() - start) / 1_000_000;
                r.online = getMatch(json, "\"online\":\\s*(true|false)").equals("true");
                if (r.online) {
                    r.onlinePlayers = Integer.parseInt(getMatch(json, "\"online\":\\s*(-?\\d+)"));
                    r.maxPlayers = Integer.parseInt(getMatch(json, "\"max\":\\s*(-?\\d+)"));
                    r.motd = getMatch(json, "\"clean\":\\s*\"([^\"]*)\"");
                }
            }
        } catch (Exception e) { r.online = false; }
        return r;
    }

    private static String getMatch(String json, String regex) {
        Matcher m = Pattern.compile(regex).matcher(json);
        return m.find() ? m.group(1) : "";
    }
}