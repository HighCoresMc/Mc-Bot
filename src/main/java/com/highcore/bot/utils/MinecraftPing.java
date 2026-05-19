package com.highcore.bot.utils;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class MinecraftPing {
    public static class StatusResponse {
        public boolean online;
        public String motd = "";
        public int onlinePlayers = 0;
        public int maxPlayers = 50;
        public long ping = 0;
    }

    public static StatusResponse ping(String host, int port, int timeout) {
        StatusResponse response = new StatusResponse();
        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.setSoTimeout(timeout);
            socket.connect(new InetSocketAddress(host, port), timeout);
            response.ping = System.currentTimeMillis() - start;

            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            DataInputStream dataIn = new DataInputStream(in);
            DataOutputStream dataOut = new DataOutputStream(out);

            // Handshake Packet
            ByteArrayOutputStream handshakeBytes = new ByteArrayOutputStream();
            DataOutputStream handshakeOut = new DataOutputStream(handshakeBytes);
            
            writeVarInt(0x00, handshakeOut); // Packet ID
            writeVarInt(-1, handshakeOut); // Protocol version (-1 handles any)
            writeString(host, handshakeOut);
            handshakeOut.writeShort(port);
            writeVarInt(1, handshakeOut); // Next state: status

            // Write Handshake Packet Length + Bytes
            writeVarInt(handshakeBytes.size(), dataOut);
            dataOut.write(handshakeBytes.toByteArray());

            // Status Request Packet
            dataOut.writeByte(1); // Size of packet
            dataOut.writeByte(0x00); // Packet ID

            // Read Response Length + Packet ID
            int size = readVarInt(dataIn);
            int id = readVarInt(dataIn);
            if (id == 0x00) {
                int length = readVarInt(dataIn);
                byte[] data = new byte[length];
                dataIn.readFully(data);
                String json = new String(data, StandardCharsets.UTF_8);
                
                response.online = true;
                response.onlinePlayers = extractJsonInt(json, "online");
                response.maxPlayers = extractJsonInt(json, "max");
                response.motd = extractJsonString(json, "text");
            }
        } catch (Exception e) {
            response.online = false;
        }
        return response;
    }

    private static void writeVarInt(int value, DataOutputStream out) throws IOException {
        while ((value & 0xFFFFFF80) != 0L) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value & 0x7F);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int numRead = 0;
        int result = 0;
        byte read;
        do {
            read = in.readByte();
            result |= (read & 0x7F) << (numRead++ * 7);
            if (numRead > 5) throw new RuntimeException("VarInt is too big");
        } while ((read & 0x80) != 0);
        return result;
    }

    private static void writeString(String s, DataOutputStream out) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(bytes.length, out);
        out.write(bytes);
    }

    private static int extractJsonInt(String json, String key) {
        String pattern = "\"" + key + "\":";
        int idx = json.indexOf(pattern);
        if (idx == -1) return 0;
        idx += pattern.length();
        int end = idx;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        try {
            return Integer.parseInt(json.substring(idx, end));
        } catch (Exception e) {
            return 0;
        }
    }

    private static String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\":\"";
        int idx = json.indexOf(pattern);
        if (idx == -1) return "";
        idx += pattern.length();
        int end = json.indexOf("\"", idx);
        if (end == -1) return "";
        return json.substring(idx, end);
    }
}
