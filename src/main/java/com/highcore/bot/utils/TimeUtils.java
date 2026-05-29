package com.highcore.bot.utils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public class TimeUtils {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public static long parseToUnixTimestamp(String dateString) {
        try {
            LocalDateTime dateTime = LocalDateTime.parse(dateString, FORMATTER);
            // Assuming KSA time (UTC+3) for the bot operations
            return dateTime.atZone(ZoneId.of("Asia/Riyadh")).toEpochSecond();
        } catch (DateTimeParseException e) {
            return -1;
        }
    }

    public static boolean isValidFormat(String dateString) {
        try {
            LocalDateTime.parse(dateString, FORMATTER);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
