import re

with open('src/main/java/com/highcore/bot/commands/CrateDropCommand.java', 'r', encoding='utf-8') as f:
    content = f.read()

target = """    private static byte[] generateDropImage(String level, String prizeText, String statusText) {"""

# We need to replace the body of generateDropImage
# Let's find the method end
start_idx = content.find(target)
end_idx = content.find("    private void handleClaimClick", start_idx)

if start_idx != -1 and end_idx != -1:
    new_method = """    private static byte[] generateDropImage(String level, String prizeText, String statusText) {
        try {
            String bgFileName = "level_1.png";
            if ("RARE".equalsIgnoreCase(level))
                bgFileName = "level_2.png";
            else if ("EPIC".equalsIgnoreCase(level))
                bgFileName = "level_3.png";
            else if ("NETHERITE".equalsIgnoreCase(level))
                bgFileName = "level_4.png";

            java.io.InputStream bgStream = CrateDropCommand.class.getClassLoader()
                    .getResourceAsStream("Identity/" + bgFileName);
            if (bgStream == null)
                bgStream = CrateDropCommand.class.getClassLoader().getResourceAsStream("Identity/level_1.png");

            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(bgStream);
            java.awt.Graphics2D g2d = img.createGraphics();

            java.awt.Font tempFont;
            try {
                tempFont = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT,
                        CrateDropCommand.class.getClassLoader().getResourceAsStream("Identity/PixelAE-Bold.ttf"))
                        .deriveFont(32f);
            } catch (Exception e) {
                tempFont = new java.awt.Font("Arial", java.awt.Font.BOLD, 32);
            }
            final java.awt.Font customFont = tempFont;

            g2d.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2d.setColor(java.awt.Color.decode("#989FB9"));
            java.awt.font.FontRenderContext frc = g2d.getFontRenderContext();

            java.util.function.BiConsumer<String, Integer> drawTextRightAligned = (text, centerY) -> {
                try {
                    com.ibm.icu.text.ArabicShaping shaper = new com.ibm.icu.text.ArabicShaping(
                            com.ibm.icu.text.ArabicShaping.LETTERS_SHAPE | com.ibm.icu.text.ArabicShaping.LENGTH_GROW_SHRINK);
                    String shapedText = shaper.shape(text);

                    java.text.AttributedString as = new java.text.AttributedString(shapedText);
                    as.addAttribute(java.awt.font.TextAttribute.FONT, customFont);
                    as.addAttribute(java.awt.font.TextAttribute.RUN_DIRECTION,
                            java.awt.font.TextAttribute.RUN_DIRECTION_RTL);

                    java.awt.font.TextLayout layout = new java.awt.font.TextLayout(as.getIterator(), frc);
                    
                    float rightEdge = 1400; // Alignment point right before the titles
                    float x = rightEdge - layout.getAdvance();
                    float y = centerY + layout.getAscent() / 2 - layout.getDescent() / 2;

                    layout.draw(g2d, x, y);
                } catch (Exception e) {
                    // fallback if ICU4J fails or anything goes wrong
                    try {
                        java.text.AttributedString as = new java.text.AttributedString(text);
                        as.addAttribute(java.awt.font.TextAttribute.FONT, customFont);
                        as.addAttribute(java.awt.font.TextAttribute.RUN_DIRECTION,
                                java.awt.font.TextAttribute.RUN_DIRECTION_RTL);
                        java.awt.font.TextLayout layout = new java.awt.font.TextLayout(as.getIterator(), frc);
                        float rightEdge = 1400;
                        float x = rightEdge - layout.getAdvance();
                        float y = centerY + layout.getAscent() / 2 - layout.getDescent() / 2;
                        layout.draw(g2d, x, y);
                    } catch (Exception ex) {}
                }
            };

            // Prize at Top, Level at Middle, Status at Bottom
            drawTextRightAligned.accept(prizeText, 484);
            drawTextRightAligned.accept(getLevelText(level), 591);
            drawTextRightAligned.accept(statusText, 698);

            g2d.dispose();
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

"""
    content = content[:start_idx] + new_method + content[end_idx:]
    with open('src/main/java/com/highcore/bot/commands/CrateDropCommand.java', 'w', encoding='utf-8') as f:
        f.write(content)
    print("Replaced method successfully")
else:
    print("Method bounds not found")
