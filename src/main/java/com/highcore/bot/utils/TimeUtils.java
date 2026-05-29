package com.highcore.bot.utils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public class TimeUtils {
    private static final String[] PATTERNS = {
        "yyyy-MM-dd HH:mm",
        "yyyy/MM/dd HH:mm",
        "dd-MM-yyyy HH:mm",
        "dd/MM/yyyy HH:mm",
        "yyyy-MM-dd hh:mm a",
        "yyyy/MM/dd hh:mm a",
        "dd-MM-yyyy hh:mm a",
        "dd/MM/yyyy hh:mm a",
        "yyyy-MM-dd h:mm a",
        "dd/MM/yyyy h:mm a",
        "dd-MM-yyyy h:mm a"
    };

    public static long parseToUnixTimestamp(String dateString) {
        LocalDateTime dateTime = parse(dateString);
        if (dateTime != null) {
            return dateTime.atZone(ZoneId.of("Asia/Riyadh")).toEpochSecond();
        }
        return -1;
    }

    public static boolean isValidFormat(String dateString) {
        return parse(dateString) != null;
    }
    
    public static String getStandardFormat(String dateString) {
        LocalDateTime dateTime = parse(dateString);
        if (dateTime != null) {
            return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        }
        return dateString;
    }
    
    private static LocalDateTime parse(String dateString) {
        String input = dateString.trim().toUpperCase();
        for (String pattern : PATTERNS) {
            try {
                return LocalDateTime.parse(input, DateTimeFormatter.ofPattern(pattern));
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }
}
