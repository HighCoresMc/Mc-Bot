package com.highcore.bot.utils;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.mediagallery.MediaGallery;
import net.dv8tion.jda.api.components.mediagallery.MediaGalleryItem;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.section.SectionContentComponent;
import net.dv8tion.jda.api.components.thumbnail.Thumbnail;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

// UI Utilities
public class EmbedUtil {

    public static final Color ACCENT = Color.decode("#C5A059");
    public static final Color SUCCESS = Color.decode("#10b981");
    public static final Color DANGER = Color.decode("#f43f5e");
    public static final Color INFO = Color.decode("#3b82f6");
    public static final Color WARNING = Color.decode("#f59e0b");
    
    // Default banners (Using DC bot's main banners for standard style)
    public static final String BANNER_MAIN = "https://i.imgur.com/RDb9nSh.png";

    public static Container createPanel(String title, String body, ActionRow... rows) {
        return createPanel(title, body, BANNER_MAIN, rows);
    }

    public static Container createPanel(String title, String body, String imageUrl, ActionRow... rows) {
        List<ContainerChildComponent> layout = new ArrayList<>();

        if (imageUrl != null && !imageUrl.isEmpty()) {
            layout.add(MediaGallery.of(MediaGalleryItem.fromUrl(imageUrl)));
        }

        if (title != null && !title.isEmpty()) {
            layout.add(TextDisplay.of("## " + title));
            layout.add(Separator.createDivider(Separator.Spacing.SMALL));
        }

        if (body != null && !body.isEmpty()) {
            String[] parts = body.split("<divider>");
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) {
                    layout.add(Separator.createDivider(Separator.Spacing.SMALL));
                }
                layout.add(TextDisplay.of(parts[i].trim()));
            }
        }

        if (rows != null && rows.length > 0) {
            layout.add(Separator.createDivider(Separator.Spacing.SMALL));
            for (ActionRow row : rows) {
                layout.add(row);
            }
        }

        return Container.of(layout);
    }

    public static Container createAlert(String title, String body) {
        List<ContainerChildComponent> layout = new ArrayList<>();
        layout.add(TextDisplay.of("### " + title + "\n" + body));
        return Container.of(layout);
    }

    public static Container createProfilePanel(String title, String body, String thumbnailUrl, ActionRow... rows) {
        List<ContainerChildComponent> layout = new ArrayList<>();
        
        List<SectionContentComponent> sectionContent = new ArrayList<>();
        if (title != null && !title.isEmpty()) {
            sectionContent.add(TextDisplay.of("## " + title));
        }

        if (body != null && !body.isEmpty()) {
            String[] parts = body.split("<divider>");
            for (int i = 0; i < parts.length; i++) {
                sectionContent.add(TextDisplay.of(parts[i].trim()));
            }
        }
        
        if (thumbnailUrl != null && !thumbnailUrl.isEmpty()) {
            layout.add(Section.of(Thumbnail.fromUrl(thumbnailUrl), sectionContent));
        } else {
            for (SectionContentComponent comp : sectionContent) { layout.add((ContainerChildComponent) comp); }
        }

        if (rows != null && rows.length > 0) {
            layout.add(Separator.createDivider(Separator.Spacing.SMALL));
            for (ActionRow row : rows) {
                layout.add(row);
            }
        }

        return Container.of(layout);
    }
}

