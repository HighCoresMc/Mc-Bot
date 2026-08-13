import re

with open('src/main/java/com/highcore/bot/commands/PanelCommand.java', 'r') as f:
    content = f.read()

# 1. Update buildMaintenanceContainer definition
content = content.replace(
'''    private Container buildMaintenanceContainer(MaintenanceState state, long timeLeftMs, boolean finished, boolean serverRunning) {
        String reasonType = formatReasonType(state);
        String title = finished ? "✅ انتهت حالة " + reasonType : "🚨 بدأت حالة " + reasonType;
        String reasonStr = formatReason(state);
        String bodyText;
        if (finished) {
            bodyText = "انتهت حالة **" + reasonType + "** وعاد الخادم للعمل الآن بشكل طبيعي، بإمكانكم الدخول واللعب.\\n\\nتنبيه: <@&1499896841150402692>";
        } else {
            bodyText = "تم إيقاف الخادم لبدء أعمال **" + reasonType + "**.\\n" +
                       "**السبب:** `" + reasonStr + "`\\n\\n" +
                       "**وقت العودة المتوقع:** <t:" + (state.returnTimestamp / 1000) + ":F> (<t:" + (state.returnTimestamp / 1000) + ":R>)\\n\\nتنبيه: <@&1499896841150402692>";
        }
        return Container.of(
            Section.of(
                Thumbnail.fromUrl("https://raw.githubusercontent.com/HighCoresMc/Mc-Bot/main/src/main/resources/Identity/logo.png"),
                TextDisplay.of("### " + title + "\\n" + bodyText)
            )
        );
    }''',
'''    private MessageCreateData buildMaintenanceMessage(MaintenanceState state, long timeLeftMs, boolean finished, boolean serverRunning) {
        String reasonType = formatReasonType(state);
        String title = finished ? "✅ انتهت حالة " + reasonType : "🚨 بدأت حالة " + reasonType;
        String reasonStr = formatReason(state);
        String bodyText;
        if (finished) {
            bodyText = "انتهت حالة **" + reasonType + "** وعاد الخادم للعمل الآن بشكل طبيعي، بإمكانكم الدخول واللعب.\\n\\nتنبيه: <@&1499896841150402692>";
        } else {
            bodyText = "تم إيقاف الخادم لبدء أعمال **" + reasonType + "**.\\n" +
                       "**السبب:** `" + reasonStr + "`\\n\\n" +
                       "**وقت العودة المتوقع:** <t:" + (state.returnTimestamp / 1000) + ":F> (<t:" + (state.returnTimestamp / 1000) + ":R>)\\n\\nتنبيه: <@&1499896841150402692>";
        }
        
        EmbedBuilder embed = new EmbedBuilder();
        embed.setColor(java.awt.Color.decode("#F1C40F"));
        embed.setThumbnail("https://raw.githubusercontent.com/HighCoresMc/Mc-Bot/main/src/main/resources/Identity/logo.png");
        embed.setDescription("### " + title + "\\n" + bodyText);
        
        return new MessageCreateBuilder()
                .setEmbeds(embed.build())
                .useComponentsV2(false)
                .build();
    }'''
)

# 2. Update buildScheduledContainer definition
content = content.replace(
'''    private Container buildScheduledContainer(MaintenanceState state) {
        String reasonType = formatReasonType(state);
        String reasonStr = formatReason(state);
        
        long startSec = state.scheduledStartTime / 1000;
        long returnSec = state.returnTimestamp / 1000;
        
        String bodyText = "تم جدولة إيقاف الخادم لبدء أعمال **" + reasonType + "**.\\n" +
                          "**السبب:** `" + reasonStr + "`\\n\\n" +
                          "**موعد بدء الصيانة:** <t:" + startSec + ":F> (<t:" + startSec + ":R>)\\n" +
                          "**موعد العودة المتوقع:** <t:" + returnSec + ":F> (<t:" + returnSec + ":R>)\\n\\n" +
                          "تنبيه: <@&1499896841150402692>";
                          
        return Container.of(
            Section.of(
                Thumbnail.fromUrl("https://raw.githubusercontent.com/HighCoresMc/Mc-Bot/main/src/main/resources/Identity/logo.png"),
                TextDisplay.of("### 📅 صيانة مجدولة\\n" + bodyText)
            )
        );
    }''',
'''    private MessageCreateData buildScheduledMessage(MaintenanceState state) {
        String reasonType = formatReasonType(state);
        String reasonStr = formatReason(state);
        
        long startSec = state.scheduledStartTime / 1000;
        long returnSec = state.returnTimestamp / 1000;
        
        String bodyText = "تم جدولة إيقاف الخادم لبدء أعمال **" + reasonType + "**.\\n" +
                          "**السبب:** `" + reasonStr + "`\\n\\n" +
                          "**موعد بدء الصيانة:** <t:" + startSec + ":F> (<t:" + startSec + ":R>)\\n" +
                          "**موعد العودة المتوقع:** <t:" + returnSec + ":F> (<t:" + returnSec + ":R>)\\n\\n" +
                          "تنبيه: <@&1499896841150402692>";
                          
        EmbedBuilder embed = new EmbedBuilder();
        embed.setColor(java.awt.Color.decode("#F1C40F"));
        embed.setThumbnail("https://raw.githubusercontent.com/HighCoresMc/Mc-Bot/main/src/main/resources/Identity/logo.png");
        embed.setDescription("### 📅 صيانة مجدولة\\n" + bodyText);
        
        return new MessageCreateBuilder()
                .setEmbeds(embed.build())
                .useComponentsV2(false)
                .build();
    }'''
)

# Replace callers
content = re.sub(r'Container container = buildMaintenanceContainer\((.*?)\);\s*MessageCreateData message = new MessageCreateBuilder\(\)\s*\.setComponents\(container\)\s*\.useComponentsV2\(true\)\s*\.build\(\);', r'MessageCreateData message = buildMaintenanceMessage(\1);', content)

content = re.sub(r'Container container = buildMaintenanceContainer\((.*?)\);\s*MessageEditData edit = new MessageEditBuilder\(\)\s*\.setComponents\(container\)\s*\.useComponentsV2\(true\)\s*\.build\(\);', r'MessageCreateData message = buildMaintenanceMessage(\1);\n                MessageEditData edit = MessageEditData.fromCreateData(message);', content)

content = re.sub(r'Container container = buildScheduledContainer\((.*?)\);\s*MessageCreateData message = new MessageCreateBuilder\(\)\s*\.setComponents\(container\)\s*\.useComponentsV2\(true\)\s*\.build\(\);', r'MessageCreateData message = buildScheduledMessage(\1);', content)


with open('src/main/java/com/highcore/bot/commands/PanelCommand.java', 'w') as f:
    f.write(content)
