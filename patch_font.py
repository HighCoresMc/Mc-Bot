with open('src/main/java/com/highcore/bot/commands/CrateDropCommand.java', 'r', encoding='utf-8') as f:
    content = f.read()

target = """                    // Add a fallback font for Arabic if PixelAE fails completely
                    // We assign SansSerif to Arabic characters, while keeping PixelAE for numbers/punctuation
                    java.awt.Font fallbackFont = new java.awt.Font("SansSerif", java.awt.Font.BOLD, 32);
                    for (int i = 0; i < cleanText.length(); i++) {
                        char c = cleanText.charAt(i);
                        // If it's an Arabic letter (0600-06FF), we use SansSerif to guarantee it connects!
                        if (c >= '\\u0600' && c <= '\\u06FF') {
                            as.addAttribute(java.awt.font.TextAttribute.FONT, fallbackFont, i, i + 1);
                        }
                    }"""

replacement = """                    // Use PixelAE entirely. Removed SansSerif fallback to keep the legendary font intact."""

if target in content:
    content = content.replace(target, replacement)
    with open('src/main/java/com/highcore/bot/commands/CrateDropCommand.java', 'w', encoding='utf-8') as f:
        f.write(content)
    print("Replaced successfully")
else:
    print("Target not found")
