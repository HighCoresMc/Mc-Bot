import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public class TestFont {
    public static void main(String[] args) throws Exception {
        Font font = Font.createFont(Font.TRUETYPE_FONT, new File("src/main/resources/Identity/PixelAE-Bold.ttf")).deriveFont(Font.BOLD, 32f);
        String text = "مجهولة (تكشف عند الفوز)";
        
        BufferedImage img = new BufferedImage(1000, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = img.createGraphics();
        g2d.setFont(font);
        
        FontMetrics fm = g2d.getFontMetrics();
        int width = fm.stringWidth(text);
        System.out.println("FontMetrics stringWidth: " + width);
        
        java.text.AttributedString as = new java.text.AttributedString(text);
        as.addAttribute(java.awt.font.TextAttribute.FONT, font);
        as.addAttribute(java.awt.font.TextAttribute.RUN_DIRECTION, java.awt.font.TextAttribute.RUN_DIRECTION_RTL);
        java.awt.font.FontRenderContext frc = g2d.getFontRenderContext();
        java.awt.font.TextLayout layout = new java.awt.font.TextLayout(as.getIterator(), frc);
        
        System.out.println("TextLayout advance: " + layout.getAdvance());
        System.out.println("TextLayout bounds width: " + layout.getBounds().getWidth());
        System.out.println("TextLayout bounds x: " + layout.getBounds().getX());
        
        g2d.dispose();
    }
}
