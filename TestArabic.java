import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;
import java.io.File;
import java.text.AttributedString;
import javax.imageio.ImageIO;

public class TestArabic {
    public static void main(String[] args) throws Exception {
        Font customFont = Font.createFont(Font.TRUETYPE_FONT, new File("src/main/resources/Identity/PixelAE-Bold.ttf")).deriveFont(32f);
        BufferedImage img = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = img.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2d.setColor(java.awt.Color.WHITE);
        FontRenderContext frc = g2d.getFontRenderContext();
        
        String text = "الجائزة: كريت قوي (5 خانات - 15 ثانية)";
        
        // Shape it with ICU4J
        com.ibm.icu.text.ArabicShaping shaper = new com.ibm.icu.text.ArabicShaping(
            com.ibm.icu.text.ArabicShaping.LETTERS_SHAPE | com.ibm.icu.text.ArabicShaping.LENGTH_GROW_SHRINK
        );
        String shapedText = shaper.shape(text);
        
        AttributedString as = new AttributedString(shapedText);
        as.addAttribute(TextAttribute.FONT, customFont);
        as.addAttribute(TextAttribute.RUN_DIRECTION, TextAttribute.RUN_DIRECTION_RTL);
        
        TextLayout layout = new TextLayout(as.getIterator(), frc);
        layout.draw(g2d, 1000, 500);
        g2d.dispose();
        ImageIO.write(img, "png", new File("test_arabic.png"));
    }
}
