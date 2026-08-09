from fontTools.ttLib import TTFont
font = TTFont('src/main/resources/Identity/PixelAE-Bold.ttf')
if 'GSUB' in font:
    print("Font HAS GSUB")
else:
    print("Font DOES NOT HAVE GSUB")

has_fef1 = False
for table in font['cmap'].tables:
    if 0xFEF1 in table.cmap:
        has_fef1 = True
print("Has U+FEF1 (Shaped Yeh):", has_fef1)
