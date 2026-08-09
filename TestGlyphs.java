import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.font.GlyphVector;
import java.io.File;
import java.text.AttributedString;

public class TestGlyphs {
    public static void main(String[] args) throws Exception {
        Font customFont = Font.createFont(Font.TRUETYPE_FONT, new File("src/main/resources/Identity/PixelAE-Bold.ttf")).deriveFont(32f);
        FontRenderContext frc = new FontRenderContext(null, true, true);
        
        String text = "عرب";
        System.out.println("Text length: " + text.length());
        
        // 1. TextLayout with RUN_DIRECTION_RTL
        AttributedString as1 = new AttributedString(text);
        as1.addAttribute(TextAttribute.FONT, customFont);
        as1.addAttribute(TextAttribute.RUN_DIRECTION, TextAttribute.RUN_DIRECTION_RTL);
        TextLayout layout1 = new TextLayout(as1.getIterator(), frc);
        System.out.println("Layout1 glyph count: " + layout1.getCharacterCount());
        // A shaped "عرب" (Ain, Ra, Ba) should have 3 glyphs. 
        // If it's shaped, Ain is initial, Ra is final, Ba is isolated.
        
        // Let's check how many visual glyphs
        // Actually java.awt.font.TextLayout doesn't easily expose if it used ligatures
        
        // Let's try to just output if we can use ligatures
        AttributedString as2 = new AttributedString(text);
        as2.addAttribute(TextAttribute.FONT, customFont);
        as2.addAttribute(TextAttribute.RUN_DIRECTION, TextAttribute.RUN_DIRECTION_RTL);
        as2.addAttribute(TextAttribute.LIGATURES, TextAttribute.LIGATURES_ON);
        TextLayout layout2 = new TextLayout(as2.getIterator(), frc);
        
        System.out.println("Done.");
    }
}
