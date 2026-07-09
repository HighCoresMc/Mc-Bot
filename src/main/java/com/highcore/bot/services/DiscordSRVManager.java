package com.highcore.bot.services;

import com.highcore.bot.LeonTrotskyBot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;

public class DiscordSRVManager {
    private static final Logger logger = LoggerFactory.getLogger(DiscordSRVManager.class);

    public DiscordSRVManager(String ignoredPath) {
        // No longer using local JSON file. We use MySQL now!
        logger.info("DiscordSRVManager initialized to use MySQL database.");
    }

    /**
     * Get Minecraft UUID by Discord ID from MySQL
     */
    public Optional<String> getUuidByDiscordId(String discordId) {
        try (Connection conn = LeonTrotskyBot.getDbManager().isCmiPoolReady() ? LeonTrotskyBot.getDbManager().getCmiConnection() : LeonTrotskyBot.getDbManager().getConnection()) {
            String query = "SELECT uuid FROM discordsrv__accounts WHERE discord = ?";
            try (PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setString(1, discordId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(rs.getString("uuid"));
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to read DiscordSRV data from MySQL", e);
        }
        return Optional.empty();
    }

    /**
     * Get Discord ID by Minecraft UUID from MySQL
     */
    public Optional<String> getDiscordIdByUuid(String uuid) {
        try (Connection conn = LeonTrotskyBot.getDbManager().isCmiPoolReady() ? LeonTrotskyBot.getDbManager().getCmiConnection() : LeonTrotskyBot.getDbManager().getConnection()) {
            String query = "SELECT discord FROM discordsrv__accounts WHERE uuid = ?";
            try (PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setString(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(rs.getString("discord"));
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to read DiscordSRV data from MySQL", e);
        }
        return Optional.empty();
    }
}
