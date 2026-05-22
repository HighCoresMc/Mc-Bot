package com.highcore.bot.utils;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class MinecraftPing {
    public static class StatusResponse {
        public boolean online;
        public int onlinePlayers, maxPlayers;
    }

    public static StatusResponse ping(String host, int port, int timeout) {
        StatusResponse r = new StatusResponse();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeout);
            OutputStream out = s.getOutputStream();
            DataInputStream in = new DataInputStream(s.getInputStream());

            // Handshake (السر هنا في البايتات)
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeByte(0x00); // Handshake
            dos.writeByte(0x04); // Protocol version
            dos.write(host.getBytes(StandardCharsets.UTF_8).length);
            dos.write(host.getBytes(StandardCharsets.UTF_8));
            dos.writeShort(port);
            dos.writeByte(0x01); // Status request

            byte[] handshake = baos.toByteArray();
            out.write(handshake.length);
            out.write(handshake);
            out.write(0x01); // Ping request
            out.write(0x00);

            in.readInt(); // Length
            in.readByte(); // Packet ID
            int len = in.readInt();
            byte[] data = new byte[len];
            in.readFully(data);
            String json = new String(data, StandardCharsets.UTF_8);

            r.online = true;
            r.onlinePlayers = Integer.parseInt(extract(json, "\"online\":"));
            r.maxPlayers = Integer.parseInt(extract(json, "\"max\":"));
        } catch (Exception e) { 
            r.online = false; 
        }
        return r;
    }

    private static String extract(String j, String k) {
        int idx = j.indexOf(k);
        if (idx == -1) return "0";
        String val = j.substring(idx + k.length());
        return val.replaceAll("[^0-9]", "").split("")[0].equals("") ? "0" : val.replaceAll("[^0-9].*", "");
    }
}