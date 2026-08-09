import re

with open('src/main/java/com/highcore/bot/commands/CrateDropCommand.java', 'r', encoding='utf-8') as f:
    content = f.read()

target = """                              String levelText = getLevelText(challenge.level);

                              Container claimContainer = Container.of(
                                      TextDisplay.of("## 🌟 ───────── 📦 ظُهُور صُنْدُوق مُشَفَّر ───────── 🌟"),
                                      Separator.createDivider(Separator.Spacing.SMALL),
                                      TextDisplay.of(
                                              "> 🏆 **الـجَـائِـزَة:** `❓ مَجْهُولَة (تُكْشَفُ عِنْدَ الْفَوْز)`\n\n" +
                                                      "> ⚡ **الـمُـسْـتَـوَى:** `" + levelText + "`\n\n" +
                                                      "> 🟢 **الـحَالَة:** `بانتظار المتحدي`"),
                                      Separator.createDivider(Separator.Spacing.SMALL),
                                      ActionRow.of(Button.primary("drop_claim_" + historyId, "🔓 فك الكريت")));

                              channel.editMessageById(messageId,
                                      new net.dv8tion.jda.api.utils.messages.MessageEditBuilder()
                                              .setComponents(claimContainer)
                                              .useComponentsV2(true)
                                              .build())
                                      .queue(null, e -> {
                                      });"""

replacement = """                              byte[] resetDropImage = generateDropImage(challenge.level,
                                      "❓ مَجْهُولَة (تُكْشَفُ عِنْدَ الْفَوْز)", "بانتظار المتحدي");
                              Container claimContainer = Container.of(
                                      net.dv8tion.jda.api.components.mediagallery.MediaGallery
                                              .of(net.dv8tion.jda.api.components.mediagallery.MediaGalleryItem
                                                      .fromUrl("attachment://drop_gen.png")),
                                      ActionRow.of(Button.primary("drop_claim_" + historyId, "🔓 فك الكريت")));

                              channel.editMessageById(messageId,
                                      new net.dv8tion.jda.api.utils.messages.MessageEditBuilder()
                                              .setComponents(claimContainer)
                                              .setFiles(net.dv8tion.jda.api.utils.FileUpload.fromData(resetDropImage,
                                                      "drop_gen.png"))
                                              .useComponentsV2(true)
                                              .build())
                                      .queue(null, e -> {
                                      });"""

if target in content:
    content = content.replace(target, replacement)
    with open('src/main/java/com/highcore/bot/commands/CrateDropCommand.java', 'w', encoding='utf-8') as f:
        f.write(content)
    print("Replaced successfully!")
else:
    print("Target not found. Doing regex fallback...")
    # fallback
    match = re.search(r'String levelText = getLevelText\(challenge\.level\);\s*Container claimContainer = Container\.of\(.*?\}\);', content, re.DOTALL)
    if match:
        content = content[:match.start()] + replacement + content[match.end():]
        with open('src/main/java/com/highcore/bot/commands/CrateDropCommand.java', 'w', encoding='utf-8') as f:
            f.write(content)
        print("Replaced with regex successfully!")
    else:
        print("Regex fallback also failed!")
