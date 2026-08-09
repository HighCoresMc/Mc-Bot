import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.font.FontRenderContext;
import java.text.AttributedString;

public class TestDraw {
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        
        Font customFont = Font.createFont(Font.TRUETYPE_FONT, new File("src/main/resources/Identity/PixelAE-Bold.ttf")).deriveFont(Font.BOLD, 32f);
        
        BufferedImage img = new BufferedImage(1920, 1040, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = img.createGraphics();
        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, 0, 1920, 1040);
        g2d.setColor(Color.WHITE);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        
        FontRenderContext frc = g2d.getFontRenderContext();
        
        float rightEdge = 1453; // Target right edge
        g2d.setColor(Color.RED);
        g2d.drawLine((int)rightEdge, 0, (int)rightEdge, 1040);
        
        g2d.setColor(Color.WHITE);
        
        String[] texts = {
            "مجهولة (تكشف عند الفوز)",
            "نذر رايت / كريت قوي (5 خانات 15 ثانية)",
            "بانتظار المتحدي الأول"
        };
        
        int centerY = 300;
        
        for (String text : texts) {
            AttributedString as = new AttributedString(text);
            as.addAttribute(TextAttribute.FONT, customFont);
            as.addAttribute(TextAttribute.RUN_DIRECTION, TextAttribute.RUN_DIRECTION_RTL);
            as.addAttribute(TextAttribute.LIGATURES, TextAttribute.LIGATURES_ON);
            
            TextLayout layout = new TextLayout(as.getIterator(), frc);
            java.awt.geom.Rectangle2D bounds = layout.getBounds();
            
            float x = (float) (rightEdge - bounds.getX() - bounds.getWidth());
            float y = centerY + layout.getAscent() / 2 - layout.getDescent() / 2;
            
            layout.draw(g2d, x, y);
            
            centerY += 100;
        }
        
        g2d.dispose();
        ImageIO.write(img, "png", new File("test_render.png"));
        System.out.println("Done.");
    }
}
