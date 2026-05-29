package com.highcore.bot.utils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public class TimeUtils {
    private static final String[] PATTERNS = {
        "yyyy-M-d H:m",
        "yyyy/M/d H:m",
        "d-M-yyyy H:m",
        "d/M/yyyy H:m",
        "yyyy-M-d h:m a",
        "yyyy/M/d h:m a",
        "d-M-yyyy h:m a",
        "d/M/yyyy h:m a",
        "H:m yyyy-M-d",
        "H:m yyyy/M/d",
        "H:m d-M-yyyy",
        "H:m d/M/yyyy",
        "h:m a yyyy-M-d",
        "h:m a yyyy/M/d",
        "h:m a d-M-yyyy",
        "h:m a d/M/yyyy"
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
        String input = dateString.trim().toUpperCase()
            .replace("م", "PM")
            .replace("ص", "AM")
            .replace("مساء", "PM")
            .replace("صباحا", "AM")
            .replace("صباحاً", "AM")
            .replace("مساءً", "PM")
            .replace(".", "-");
            
        // Reduce multiple spaces to a single space
        input = input.replaceAll("\\s+", " ").trim();
        
        for (String pattern : PATTERNS) {
            try {
                return LocalDateTime.parse(input, DateTimeFormatter.ofPattern(pattern));
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }
}
