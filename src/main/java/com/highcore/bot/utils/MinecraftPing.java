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
        public int maxPlayers = 0;
        public long ping = -1;
    }

    public static StatusResponse ping(String host, int port, int timeout) {
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<StatusResponse> future = executor.submit(() -> pingRaw(host, port, timeout));
        try {
            return future.get(timeout + 500, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            future.cancel(true);
            StatusResponse response = new StatusResponse();
            response.online = false;
            return response;
        } finally {
            executor.shutdownNow();
        }
    }

    private static StatusResponse pingRaw(String host, int port, int timeout) {
        StatusResponse response = new StatusResponse();
        long start = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.setSoTimeout(timeout);
            socket.connect(new InetSocketAddress(host, port), timeout);
            
            response.ping = (System.nanoTime() - start) / 1_000_000;

            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            DataInputStream dataIn = new DataInputStream(in);
            DataOutputStream dataOut = new DataOutputStream(out);

            ByteArrayOutputStream handshakeBytes = new ByteArrayOutputStream();
            DataOutputStream handshakeOut = new DataOutputStream(handshakeBytes);
            
            writeVarInt(0x00, handshakeOut);
            writeVarInt(-1, handshakeOut);
            writeString(host, handshakeOut);
            handshakeOut.writeShort(port);
            writeVarInt(1, handshakeOut);

            writeVarInt(handshakeBytes.size(), dataOut);
            dataOut.write(handshakeBytes.toByteArray());

            dataOut.writeByte(1);
            dataOut.writeByte(0x00);
            dataOut.flush();

            int size = readVarInt(dataIn);
            int id = readVarInt(dataIn);
            if (id == 0x00) {
                int length = readVarInt(dataIn);
                byte[] data = new byte[length];
                dataIn.readFully(data);
                String json = new String(data, StandardCharsets.UTF_8);
                
                response.online = true;
                
                int playersIdx = json.indexOf("\"players\"");
                if (playersIdx != -1) {
                    String playersPart = json.substring(playersIdx);
                    response.onlinePlayers = extractJsonInt(playersPart, "online");
                    response.maxPlayers = extractJsonInt(playersPart, "max");
                } else {
                    response.onlinePlayers = extractJsonInt(json, "online");
                    response.maxPlayers = extractJsonInt(json, "max");
                }
                
                response.motd = extractJsonString(json, "text");
            }
        } catch (Exception e) {
            System.out.println("[MinecraftPing] TCP Ping raw error: " + e.toString());
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