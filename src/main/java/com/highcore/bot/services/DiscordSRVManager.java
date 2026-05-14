package com.highcore.bot.services;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.Optional;

public class DiscordSRVManager {
    private static final Logger logger = LoggerFactory.getLogger(DiscordSRVManager.class);
    private final String linkedAccountsFilePath;
    private final Gson gson;

    public DiscordSRVManager(String linkedAccountsFilePath) {
        this.linkedAccountsFilePath = linkedAccountsFilePath;
        this.gson = new Gson();
    }

    /**
     * Reads the linkedaccounts.json file and returns a map of DiscordID -> UUID
     */
    public Map<String, String> getLinkedAccounts() {
        try (FileReader reader = new FileReader(linkedAccountsFilePath)) {
            Type type = new TypeToken<Map<String, String>>() {}.getType();
            return gson.fromJson(reader, type);
        } catch (IOException e) {
            logger.error("Failed to read DiscordSRV linkedaccounts.json from path: " + linkedAccountsFilePath, e);
            return Map.of();
        }
    }

    /**
     * Get Minecraft UUID by Discord ID
     */
    public Optional<String> getUuidByDiscordId(String discordId) {
        Map<String, String> accounts = getLinkedAccounts();
        return Optional.ofNullable(accounts.get(discordId));
    }

    /**
     * Get Discord ID by Minecraft UUID
     */
    public Optional<String> getDiscordIdByUuid(String uuid) {
        return getLinkedAccounts().entrySet().stream()
                .filter(entry -> entry.getValue().equals(uuid))
                .map(Map.Entry::getKey)
                .findFirst();
    }
}
