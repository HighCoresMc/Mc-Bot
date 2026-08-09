with open('src/main/java/com/highcore/bot/commands/CrateDropCommand.java', 'r', encoding='utf-8') as f:
    content = f.read()

target = """            java.util.function.BiConsumer<String, Integer> drawTextRightAligned = (text, centerY) -> {
                try {
                    java.text.AttributedString as = new java.text.AttributedString(text);
                    as.addAttribute(java.awt.font.TextAttribute.FONT, customFont);
                    as.addAttribute(java.awt.font.TextAttribute.RUN_DIRECTION,
                            java.awt.font.TextAttribute.RUN_DIRECTION_RTL);

                    java.awt.font.TextLayout layout = new java.awt.font.TextLayout(as.getIterator(), frc);
                    
                    // Coordinates fixed: X=1420 is slightly closer to the title to ensure it aligns perfectly
                    float rightEdge = 1420; 
                    float x = rightEdge - layout.getAdvance();
                    float y = centerY + layout.getAscent() / 2 - layout.getDescent() / 2;

                    layout.draw(g2d, x, y);
                } catch (Exception e) {
                    logger.error("Failed to draw text on drop image: " + text, e);
                }
            };

            // Prize at Top, Level at Middle, Status at Bottom
            drawTextRightAligned.accept(prizeText, 484);
            drawTextRightAligned.accept(getLevelText(level), 591);
            drawTextRightAligned.accept(statusText, 698);"""

replacement = """            @FunctionalInterface
            interface DrawTextAligned {
                void draw(String text, float rightEdge, float centerY);
            }
            DrawTextAligned drawTextRightAligned = (text, rightEdge, centerY) -> {
                try {
                    // Remove Arabic diacritics (harakat) which might cause missing glyphs
                    String cleanText = text.replaceAll("[\\u064B-\\u065F]", "");
                    
                    java.text.AttributedString as = new java.text.AttributedString(cleanText);
                    as.addAttribute(java.awt.font.TextAttribute.FONT, customFont);
                    as.addAttribute(java.awt.font.TextAttribute.RUN_DIRECTION,
                            java.awt.font.TextAttribute.RUN_DIRECTION_RTL);
                    
                    // Force ligatures to trigger GSUB for PixelAE if it supports it
                    as.addAttribute(java.awt.font.TextAttribute.LIGATURES, java.awt.font.TextAttribute.LIGATURES_ON);
                    
                    // Add a fallback font for Arabic if PixelAE fails completely
                    // We assign SansSerif to Arabic characters, while keeping PixelAE for numbers/punctuation
                    java.awt.Font fallbackFont = new java.awt.Font("SansSerif", java.awt.Font.BOLD, 32);
                    for (int i = 0; i < cleanText.length(); i++) {
                        char c = cleanText.charAt(i);
                        // If it's an Arabic letter (0600-06FF), we use SansSerif to guarantee it connects!
                        if (c >= '\u0600' && c <= '\u06FF') {
                            as.addAttribute(java.awt.font.TextAttribute.FONT, fallbackFont, i, i + 1);
                        }
                    }

                    java.awt.font.TextLayout layout = new java.awt.font.TextLayout(as.getIterator(), frc);
                    
                    float x = rightEdge - layout.getAdvance();
                    float y = centerY + layout.getAscent() / 2 - layout.getDescent() / 2;

                    layout.draw(g2d, x, y);
                } catch (Exception e) {
                    logger.error("Failed to draw text on drop image: " + text, e);
                }
            };

            // Prize, Level, Status (using user's exact X right edges)
            drawTextRightAligned.draw(prizeText, 1445, 484);
            drawTextRightAligned.draw(getLevelText(level), 1390, 588);
            drawTextRightAligned.draw(statusText, 1455, 695);"""

if target in content:
    content = content.replace(target, replacement)
    with open('src/main/java/com/highcore/bot/commands/CrateDropCommand.java', 'w', encoding='utf-8') as f:
        f.write(content)
    print("Replaced successfully")
else:
    print("Target not found. Doing substring search...")
    idx = content.find("java.util.function.BiConsumer<String, Integer> drawTextRightAligned")
    end_idx = content.find("g2d.dispose();", idx)
    if idx != -1 and end_idx != -1:
        content = content[:idx] + replacement + "\n\n            " + content[end_idx:]
        with open('src/main/java/com/highcore/bot/commands/CrateDropCommand.java', 'w', encoding='utf-8') as f:
            f.write(content)
        print("Replaced with substring successfully")
    else:
        print("Fallback failed")
