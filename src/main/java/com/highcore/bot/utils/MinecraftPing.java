package com.highcore.bot.utils;

import java.io.*;
import java.net.*;

public class MinecraftPing {
    public static class StatusResponse {
        public boolean online;
        public int onlinePlayers, maxPlayers;
        public long ping = -1;
    }

    public static StatusResponse ping(String host, int port, int timeout) {
        StatusResponse r = new StatusResponse();
        try (Socket s = new Socket()) {
            long start = System.nanoTime();
            s.connect(new InetSocketAddress(host, port), timeout);
            r.ping = (System.nanoTime() - start) / 1_000_000;
            r.online = true;
            // هنا نجبر البوت على قراءة البيانات من البفر مباشرة بدون API
            r.onlinePlayers = 100; // عدلها برمجياً لتقرأ من Packet الـ Status
            r.maxPlayers = 100;    // عدلها برمجياً لتقرأ من Packet الـ Status
        } catch (Exception e) { 
            r.online = false; 
        }
        return r;
    }
}