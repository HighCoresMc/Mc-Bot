package com.highcore.bot.utils;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class MinecraftPing {
    public static class StatusResponse {
        public boolean online = false;
        public int onlinePlayers = 0;
        public int maxPlayers = 0;
        public long ping = -1;
    }

    public static StatusResponse ping(String host, int port, int timeout) {
        StatusResponse R = new StatusResponse();
        long startTime = System.currentTimeMillis();
        try (Socket S = new Socket()) {
            S.connect(new InetSocketAddress(host, port), timeout);
            OutputStream out = S.getOutputStream();
            DataInputStream in = new DataInputStream(S.getInputStream());

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeByte(0x00);
            dos.writeByte(0x04);
            dos.write(host.getBytes(StandardCharsets.UTF_8).length);
            dos.write(host.getBytes(StandardCharsets.UTF_8));
            dos.writeShort(port);
            dos.writeByte(0x01);

            byte[] handshake = baos.toByteArray();
            out.write(handshake.length);
            out.write(handshake);
            out.write(0x01);
            out.write(0x00);

            in.readInt();
            in.readByte();
            int len = in.readInt();
            byte[] data = new byte[len];
            in.readFully(data);
            String json = new String(data, StandardCharsets.UTF_8);

            R.online = true;
            R.onlinePlayers = Integer.parseInt(extract(json, "\"online\":"));
            R.maxPlayers = Integer.parseInt(extract(json, "\"max\":"));
            R.ping = System.currentTimeMillis() - startTime;
        } catch (Exception e) {
            R.online = false;
        }
        return R;
    }

    private static String extract(String j, String k) {
        int idx = j.indexOf(k);
        if (idx == -1) return "0";
        String val = j.substring(idx + k.length());
        return val.replaceAll("[^0-9]", "").isEmpty() ? "0" : val.replaceAll("[^0-9].*", "");
    }
}