import java.awt.*;
import java.awt.font.*;
import java.awt.image.*;
import java.io.*;
import java.text.*;
import javax.imageio.*;

public class test_image {
    public static void main(String[] args) throws Exception {
        File bgFile = new File("Identity/مستوى الاول.png");
        BufferedImage img = ImageIO.read(bgFile);
        Graphics2D g2d = img.createGraphics();
        Font customFont = Font.createFont(Font.TRUETYPE_FONT, new File("Identity/PixelAE-Regular.ttf")).deriveFont(24f);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2d.setColor(Color.decode("#989FB9"));
        FontRenderContext frc = g2d.getFontRenderContext();
        
        String[] texts = {"مَجْهُولَة (تُكْشَفُ عِنْدَ الْفَوْز)", "بسيطة (2 خانات - 22 ثانية)", "بانتظار المتحدي الأول"};
        Rectangle[] bounds = {new Rectangle(1447, 676, 205, 45), new Rectangle(1451, 464, 203, 41), new Rectangle(1398, 571, 204, 41)};
        
        for (int i=0; i<texts.length; i++) {
            AttributedString as = new AttributedString(texts[i]);
            as.addAttribute(TextAttribute.FONT, customFont);
            as.addAttribute(TextAttribute.RUN_DIRECTION, TextAttribute.RUN_DIRECTION_RTL);
            TextLayout layout = new TextLayout(as.getIterator(), frc);
            Rectangle2D textBounds = layout.getBounds();
            float x = (float) (bounds[i].x + bounds[i].width - textBounds.getWidth());
            float y = (float) (bounds[i].y + (bounds[i].height - textBounds.getHeight()) / 2 + layout.getAscent());
            layout.draw(g2d, x, y);
            System.out.println("Text: " + texts[i] + " Width: " + textBounds.getWidth());
        }
        g2d.dispose();
        ImageIO.write(img, "png", new File("test_output.png"));
    }
}
