package com.highcore.bot.utils;

import java.io.*;
import java.net.*;
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
        StatusResponse r = new StatusResponse();
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(timeout);
            InetAddress addr = InetAddress.getByName(host);
            long start = System.nanoTime();
            byte[] h = {(byte) 0xFE, (byte) 0xFD, 0x09, 0x01, 0x02, 0x03, 0x04};
            s.send(new DatagramPacket(h, h.length, addr, port));
            byte[] b = new byte[2048];
            DatagramPacket p = new DatagramPacket(b, b.length);
            s.receive(p);
            byte[] req = {(byte) 0xFE, (byte) 0xFD, 0x00, 0x01, 0x02, 0x03, 0x04, 0x00, 0x00, 0x00, 0x00};
            s.send(new DatagramPacket(req, req.length, addr, port));
            s.receive(p);
            r.ping = (System.nanoTime() - start) / 1_000_000;
            r.online = true;
            String d = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
            r.onlinePlayers = Integer.parseInt(extract(d, "numplayers"));
            r.maxPlayers = Integer.parseInt(extract(d, "maxplayers"));
            r.motd = extract(d, "hostname");
        } catch (Exception e) { r.online = false; }
        return r;
    }

    private static String extract(String d, String k) {
        try {
            int s = d.indexOf(k) + k.length() + 1;
            int e = d.indexOf(0x00, s);
            return d.substring(s, e);
        } catch (Exception x) { return "0"; }
    }
}