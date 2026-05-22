package com.highcore.bot.utils;

import java.io.*;
import java.net.*;
import java.nio.*;

public class MinecraftPing {
    public static class StatusResponse {
        public boolean online;
        public int onlinePlayers, maxPlayers;
        public long ping;
    }

    public static StatusResponse ping(String host, int port, int timeout) {
        StatusResponse r = new StatusResponse();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeout);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            
            // Handshake
            DataOutputStream dos = new DataOutputStream(out);
            dos.write(new byte[]{0x06, 0x00, 0x00, (byte) host.length()});
            dos.write(host.getBytes());
            dos.writeShort(port);
            dos.writeByte(0x01);
            dos.writeByte(0x00);
            
            // Ping
            dos.writeByte(0x01);
            dos.writeByte(0x00);

            DataInputStream dis = new DataInputStream(in);
            dis.readInt(); // packet length
            dis.readByte(); // packet id
            int len = dis.readInt(); // json length
            byte[] b = new byte[len];
            dis.readFully(b);
            String json = new String(b);

            r.online = true;
            r.onlinePlayers = Integer.parseInt(extract(json, "online"));
            r.maxPlayers = Integer.parseInt(extract(json, "max"));
            r.ping = 0; 
        } catch (Exception e) { r.online = false; }
        return r;
    }

    private static String extract(String j, String k) {
        int idx = j.indexOf(k);
        if (idx == -1) return "0";
        String sub = j.substring(idx + k.length() + 2);
        return sub.split(",")[0].replaceAll("[^0-9]", "");
    }
}