package com.highcore.bot.utils;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class MinecraftPing {

    public static class StatusResponse {
        public boolean online;       // Full protocol exchange succeeded
        public boolean portOpen;     // TCP connect succeeded — server IS listening on this port
        public boolean portRefused;  // Connection explicitly refused — server is definitely offline
        public int onlinePlayers, maxPlayers;
        public long ping;            // Round-trip time in ms (0 = unknown)
    }

    // Section: VarInt encoding — required by the Minecraft 1.7+ status protocol
    private static void writeVarInt(OutputStream out, int value) throws IOException {
        while ((value & 0xFFFFFF80) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value & 0x7F);
    }

    private static int readVarInt(InputStream in) throws IOException {
        int result = 0, shift = 0, b;
        do {
            b = in.read();
            if (b == -1) throw new EOFException("Stream ended while reading VarInt");
            result |= (b & 0x7F) << shift;
            shift += 7;
            if (shift > 35) throw new IOException("VarInt too large");
        } while ((b & 0x80) != 0);
        return result;
    }

    public static StatusResponse ping(String host, int port, int timeout) {
        StatusResponse R = new StatusResponse();
        try (Socket S = new Socket()) {
            // Section: Attempt TCP connect with a hard deadline — catch refused separately
            long connectStart = System.nanoTime();
            try {
                S.connect(new InetSocketAddress(host, port), timeout);
            } catch (ConnectException e) {
                // Port explicitly refused — server is definitely not running on this port
                R.portRefused = true;
                return R;
            }
            // Section: TCP handshake succeeded — server IS listening
            R.portOpen = true;
            // Use nanoTime for sub-millisecond accuracy — ensures at least 1ms when TCP is instant
            R.ping = Math.max(1L, (System.nanoTime() - connectStart) / 1_000_000L);
            S.setSoTimeout(timeout); // Cap each individual read at `timeout` ms

            OutputStream rawOut = S.getOutputStream();
            InputStream  rawIn  = S.getInputStream();

            // Section: Build Minecraft handshake packet body
            ByteArrayOutputStream packetBody = new ByteArrayOutputStream();
            DataOutputStream      body       = new DataOutputStream(packetBody);
            writeVarInt(packetBody, 0x00);                                // Packet ID: Handshake
            writeVarInt(packetBody, 765);                                 // Protocol version (1.20.4)
            byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
            writeVarInt(packetBody, hostBytes.length);
            packetBody.write(hostBytes);
            body.writeShort(port);
            writeVarInt(packetBody, 1);                                   // Next state: Status

            // Section: Send handshake then status request
            writeVarInt(rawOut, packetBody.size());
            rawOut.write(packetBody.toByteArray());
            writeVarInt(rawOut, 1);  // Status request length
            writeVarInt(rawOut, 0x00); // Packet ID: Status Request
            rawOut.flush();

            // Section: Read status response and measure RTT
            long rttStart = System.nanoTime();
            readVarInt(rawIn);              // Packet length
            readVarInt(rawIn);              // Packet ID (0x00)
            int    jsonLen = readVarInt(rawIn);
            byte[] data    = new byte[jsonLen];
            new DataInputStream(rawIn).readFully(data);
            R.ping = Math.max(1L, (System.nanoTime() - rttStart) / 1_000_000L);

            String json     = new String(data, StandardCharsets.UTF_8);
            R.online        = true;
            R.onlinePlayers = Integer.parseInt(extract(json, "\"online\":"));
            R.maxPlayers    = Integer.parseInt(extract(json, "\"max\":"));

        } catch (Exception e) {
            R.online = false;
            // portOpen remains true if connect succeeded but protocol exchange failed
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