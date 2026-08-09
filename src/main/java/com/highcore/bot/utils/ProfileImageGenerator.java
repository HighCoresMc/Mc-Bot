package com.highcore.bot.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.text.AttributedString;

public class ProfileImageGenerator {
    private static final Logger logger = LoggerFactory.getLogger(ProfileImageGenerator.class);
    private static Font customFont;

    static {
        try {
            InputStream fontStream = ProfileImageGenerator.class.getClassLoader()
                    .getResourceAsStream("Identity/PixelAE-Bold.ttf");
            if (fontStream != null) {
                customFont = Font.createFont(Font.TRUETYPE_FONT, fontStream).deriveFont(32f);
            } else {
                customFont = new Font("Arial", Font.BOLD, 32);
            }
        } catch (Exception e) {
            logger.error("Failed to load PixelAE-Bold.ttf", e);
            customFont = new Font("Arial", Font.BOLD, 32);
        }
    }

    public static byte[] generateProfileImage(String tab, String mcName, String rank, String playTime,
                                              String cmiBalance, String tokens, String kills, String deaths,
                                              String kd, String status, String futureAdd, String avatarUrl) {
        try {
            String templateName;
            switch (tab) {
                case "surv":
                    templateName = "prof_survival.png";
                    break;
                case "pvp":
                    templateName = "prof_pvp.png";
                    break;
                case "side":
                    templateName = "prof_extra.png";
                    break;
                case "general":
                default:
                    templateName = "prof_general.png";
                    break;
            }

            InputStream bgStream = ProfileImageGenerator.class.getClassLoader()
                    .getResourceAsStream("Identity/" + templateName);
            if (bgStream == null) {
                logger.error("Template not found: " + templateName);
                return new byte[0];
            }

            BufferedImage img = ImageIO.read(bgStream);
            Graphics2D g2d = img.createGraphics();

            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            // Draw Avatar
            try {
                BufferedImage avatar = ImageIO.read(new URL(avatarUrl));
                if (avatar != null) {
                    int targetX1 = 106, targetY1 = 255;
                    int targetX2 = 481, targetY2 = 615;
                    int targetWidth = targetX2 - targetX1;
                    int targetHeight = targetY2 - targetY1;
                    
                    double scaleX = (double) targetWidth / avatar.getWidth();
                    double scaleY = (double) targetHeight / avatar.getHeight();
                    
                    AffineTransform at = new AffineTransform();
                    at.translate(targetX1, targetY1);
                    at.scale(scaleX, scaleY);
                    
                    g2d.drawImage(avatar, at, null);
                }
            } catch (Exception e) {
                logger.error("Failed to load avatar from: " + avatarUrl, e);
            }

            // Text Settings
            g2d.setColor(Color.decode("#989FB9"));
            FontRenderContext frc = g2d.getFontRenderContext();

            // Name is common in all tabs: coords 578,146,814,206
            drawTextCentered(g2d, mcName, 578, 146, 814, 206, frc);

            switch (tab) {
                case "general":
                    drawTextCentered(g2d, rank != null ? rank : "بدون رتبة", 901, 334, 1094, 385, frc);
                    drawTextCentered(g2d, playTime, 900, 426, 1034, 472, frc);
                    break;
                case "surv":
                    drawTextCentered(g2d, cmiBalance, 655, 424, 829, 476, frc);
                    drawTextCentered(g2d, tokens, 845, 338, 1021, 387, frc);
                    break;
                case "pvp":
                    drawTextCentered(g2d, kills, 898, 522, 993, 565, frc);
                    drawTextCentered(g2d, deaths, 891, 341, 984, 383, frc);
                    drawTextCentered(g2d, kd, 823, 430, 914, 473, frc);
                    break;
                case "side":
                    drawTextCentered(g2d, status, 762, 344, 880, 376, frc);
                    drawTextCentered(g2d, futureAdd, 869, 433, 992, 468, frc);
                    break;
            }

            g2d.dispose();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            logger.error("Error generating profile image", e);
            return new byte[0];
        }
    }

    private static void drawTextCentered(Graphics2D g2d, String text, int x1, int y1, int x2, int y2, FontRenderContext frc) {
        try {
            if (text == null || text.trim().isEmpty()) return;
            
            String cleanText = text.replaceAll("[\u064B-\u065F]", "")
                    .replaceAll("[\uD83C-\uDBFF\uDC00-\uDFFF]+", "")
                    .replaceAll("[\u2700-\u27BF\u2600-\u26FF\u2B50\u2B55\u2753\u274C]", "")
                    .trim();

            if (cleanText.isEmpty()) return;

            AttributedString as = new AttributedString(cleanText);
            as.addAttribute(TextAttribute.FONT, customFont);
            as.addAttribute(TextAttribute.RUN_DIRECTION, TextAttribute.RUN_DIRECTION_RTL);
            as.addAttribute(TextAttribute.LIGATURES, TextAttribute.LIGATURES_ON);

            TextLayout layout = new TextLayout(as.getIterator(), frc);
            g2d.setFont(customFont);
            int visualWidth = g2d.getFontMetrics().stringWidth(cleanText);

            int boxWidth = x2 - x1;
            int boxHeight = y2 - y1;

            float x = x1 + (boxWidth - visualWidth) / 2f;
            float y = y1 + (boxHeight / 2f) + (layout.getAscent() / 2f) - (layout.getDescent() / 2f);

            layout.draw(g2d, x, y);
        } catch (Exception e) {
            logger.error("Failed to draw text on profile image: " + text, e);
        }
    }
}
