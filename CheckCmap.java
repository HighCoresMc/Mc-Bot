import java.awt.Font;
import java.io.File;

public class CheckCmap {
    public static void main(String[] args) throws Exception {
        Font font = Font.createFont(Font.TRUETYPE_FONT, new File("src/main/resources/Identity/PixelAE-Bold.ttf"));
        
        System.out.println("Supports U+0627 (Alef): " + font.canDisplay('\u0627'));
        System.out.println("Supports U+0639 (Ain): " + font.canDisplay('\u0639'));
        
        // Presentation forms
        System.out.println("Supports U+FEF1 (Yeh final): " + font.canDisplay('\uFEF1'));
        System.out.println("Supports U+FE8D (Alef isolated): " + font.canDisplay('\uFE8D'));
        System.out.println("Supports U+FEEB (Hah initial): " + font.canDisplay('\uFEEB'));
        
        int count06 = 0;
        for (int i = 0x0600; i <= 0x06FF; i++) {
            if (font.canDisplay(i)) count06++;
        }
        System.out.println("Supported U+06xx count: " + count06);
        
        int countFE = 0;
        for (int i = 0xFE70; i <= 0xFEFF; i++) {
            if (font.canDisplay(i)) countFE++;
        }
        System.out.println("Supported U+FExx count: " + countFE);
    }
}
