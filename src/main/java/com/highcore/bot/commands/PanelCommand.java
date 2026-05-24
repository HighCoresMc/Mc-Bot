package com.highcore.bot.commands;

import com.google.gson.JsonObject;
import com.highcore.bot.services.PterodactylService;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.thumbnail.Thumbnail;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Timer;
import java.util.TimerTask;

public class PanelCommand extends ListenerAdapter {
    private final PterodactylService pterodactylService = new PterodactylService();
    private final List<String> consoleBuffer = new ArrayList<>();
    private Timer updateTimer;
    private String activeMessageId = null;
    private String activeChannelId = null;
    private boolean isKillState = false;
    private String lastPanelContentHash = null;
    private JsonObject cachedResources = null;
    private long lastResourcesFetchTime = 0;

    private final Map<String, MaintenanceState> userStates = new ConcurrentHashMap<>();
    private Timer maintenanceTimer;
    private static final String STATE_FILE = "maintenance_state.json";

    public PanelCommand(net.dv8tion.jda.api.JDA jda) {
        pterodactylService.connectToConsole(line -> {
            synchronized (consoleBuffer) {
                consoleBuffer.add(line);
                if (consoleBuffer.size() > 30) {
                    consoleBuffer.remove(0);
                }
            }
        });
        if (jda != null) {
            loadAndResumeMaintenance(jda);
        }
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!event.getName().equals("panel")) return;
        event.deferReply().queue();

        // RESOURCES
        JsonObject resources = pterodactylService.getServerResources();
        if (resources != null) {
            cachedResources = resources;
            lastResourcesFetchTime = System.currentTimeMillis();
        }
        isKillState = false;
        Container container = buildContainer(cachedResources, isKillState);

        MessageCreateData messageData = new MessageCreateBuilder()
                .setComponents(container)
                .setEmbeds(java.util.Collections.emptyList())
                .useComponentsV2(true)
                .build();

        event.getHook().sendMessage(messageData).queue(message -> {
            activeMessageId = message.getId();
            activeChannelId = message.getChannel().getId();
            lastPanelContentHash = null;
            
            // TIMER
            if (updateTimer != null) {
                updateTimer.cancel();
            }
            updateTimer = new Timer();
            updateTimer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    if (activeMessageId != null && activeChannelId != null) {
                        try {
                            long now = System.currentTimeMillis();
                            if (cachedResources == null || now - lastResourcesFetchTime >= 5000) {
                                JsonObject freshResources = pterodactylService.getServerResources();
                                if (freshResources != null) {
                                    cachedResources = freshResources;
                                    lastResourcesFetchTime = now;
                                }
                            }

                            String state = "offline";
                            String cpu = "0%";
                            String ram = "0 MB";
                            String uptimeStr = "0s";
                            if (cachedResources != null) {
                                state = cachedResources.get("current_state").getAsString();
                                JsonObject util = cachedResources.getAsJsonObject("resources");
                                if (util != null) {
                                    cpu = util.get("cpu_absolute").getAsString();
                                    ram = util.get("memory_bytes").getAsString();
                                    uptimeStr = util.get("uptime").getAsString();
                                }
                            }
                            StringBuilder consoleText = new StringBuilder();
                            synchronized (consoleBuffer) {
                                for (String line : consoleBuffer) {
                                    consoleText.append(line);
                                }
                            }
                            String currentHash = state + cpu + ram + uptimeStr + consoleText.toString();
                            if (currentHash.equals(lastPanelContentHash)) {
                                return;
                            }
                            lastPanelContentHash = currentHash;

                            Container updatedContainer = buildContainer(cachedResources, isKillState);
                            
                            MessageEditData editData = new MessageEditBuilder()
                                    .setComponents(updatedContainer)
                                    .setEmbeds(java.util.Collections.emptyList())
                                    .useComponentsV2(true)
                                    .build();

                            var channel = event.getJDA().getTextChannelById(activeChannelId);
                            if (channel != null) {
                                channel.editMessageById(activeMessageId, editData).queue(null, error -> {
                                });
                            }
                        } catch (Exception e) {
                        }
                    }
                }
            }, 1000, 1000);
        });
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        if (!event.getComponentId().startsWith("ptdl_")) return;

        String id = event.getComponentId();
        
        if (id.equals("ptdl_cmd")) {
            TextInput commandInput = TextInput.create("command", TextInputStyle.SHORT)
                    .setPlaceholder("ex: say hello")
                    .setMinLength(1)
                    .setRequired(true)
                    .build();

            Modal modal = Modal.create("ptdl_modal", "Send Console Command")
                    .addComponents(Label.of("Command", commandInput))
                    .build();
            
            event.replyModal(modal).queue();
            return;
        }

        if (id.equals("ptdl_stop") || id.equals("ptdl_restart")) {
            boolean isRestart = id.equals("ptdl_restart");
            MaintenanceState state = new MaintenanceState();
            state.isRestart = isRestart;
            userStates.put(event.getUser().getId(), state);

            StringSelectMenu reasonMenu = StringSelectMenu.create("ptdl_reason:" + (isRestart ? "restart" : "stop"))
                    .setPlaceholder("اختر سبب التوقف...")
                    .addOption("صيانة دورية وتحسينات", "maintenance", "🔧 صيانة دورية وتحسينات عامة للمخدم")
                    .addOption("تطوير وتحديث الأنظمة", "dev", "🚀 تطوير وتحديث برمجي للأنظمة")
                    .addOption("إصلاح أخطاء تقنية", "bug", "🐛 إصلاح بعض المشاكل والأخطاء التقنية")
                    .addOption("سبب مخصص...", "custom", "✏️ كتابة سبب مخصص يدوياً")
                    .build();

            StringSelectMenu durationMenu = StringSelectMenu.create("ptdl_duration:" + (isRestart ? "restart" : "stop"))
                    .setPlaceholder("اختر وقت العودة المتوقع...")
                    .addOption("15 دقيقة", "15m")
                    .addOption("30 دقيقة", "30m")
                    .addOption("ساعة واحدة", "1h")
                    .addOption("ساعتين", "2h")
                    .addOption("6 ساعات", "6h")
                    .addOption("12 ساعة", "12h")
                    .addOption("يوم كامل", "1d")
                    .addOption("وقت مخصص...", "custom", "⏱️ تحديد ساعة أو مدة مخصصة")
                    .build();

            Button confirmBtn = Button.success("ptdl_confirm:" + (isRestart ? "restart" : "stop"), "تأكيد الإجراء");
            Button cancelBtn = Button.danger("ptdl_cancel", "إلغاء الإجراء");

            MessageCreateData wizardData = new MessageCreateBuilder()
                    .setContent("### 🛠️ معالج إيقاف وإعادة تشغيل السيرفر\nيرجى تحديد تفاصيل التوقف لإشعار اللاعبين:")
                    .addComponents(ActionRow.of(reasonMenu))
                    .addComponents(ActionRow.of(durationMenu))
                    .addComponents(ActionRow.of(confirmBtn, cancelBtn))
                    .build();

            event.reply(wizardData).setEphemeral(true).queue();
            return;
        }

        if (id.startsWith("ptdl_confirm:")) {
            String action = id.substring("ptdl_confirm:".length());
            String userId = event.getUser().getId();
            MaintenanceState state = userStates.get(userId);
            if (state == null) {
                event.reply("⚠️ حدث خطأ: انتهت صلاحية الجلسة، يرجى إعادة المحاولة.").setEphemeral(true).queue();
                return;
            }

            if ("custom".equals(state.reason) || "custom".equals(state.duration)) {
                Modal.Builder modalBuilder = Modal.create("ptdl_custom_wizard_modal:" + action, "تفاصيل التوقف المخصصة");
                List<Label> labels = new ArrayList<>();
                if ("custom".equals(state.reason)) {
                    TextInput reasonInput = TextInput.create("custom_reason", TextInputStyle.SHORT)
                            .setPlaceholder("مثال: ترقية الهاردوير للسيرفر")
                            .setRequired(true)
                            .build();
                    labels.add(Label.of("السبب", reasonInput));
                }
                if ("custom".equals(state.duration)) {
                    TextInput durationInput = TextInput.create("custom_duration", TextInputStyle.SHORT)
                            .setPlaceholder("أدخل المدة أو الساعة المحددة")
                            .setRequired(true)
                            .build();
                    labels.add(Label.of("الوقت", durationInput));
                }
                Modal modal = modalBuilder.addComponents(labels).build();
                event.replyModal(modal).queue();
                return;
            }

            event.deferEdit().queue();
            executeMaintenance(event.getJDA(), state, event.getHook(), event.getUser().getId());
            return;
        }

        if (id.equals("ptdl_cancel")) {
            event.deferEdit().queue();
            userStates.remove(event.getUser().getId());
            event.getHook().editOriginal("❌ تم إلغاء الإجراء بنجاح.")
                    .setComponents(java.util.Collections.emptyList())
                    .queue();
            return;
        }

        event.deferEdit().queue();

        if (id.equals("ptdl_start")) {
            isKillState = false;
            pterodactylService.sendPowerSignal("start");
        } else if (id.equals("ptdl_kill")) {
            isKillState = false;
            pterodactylService.sendPowerSignal("kill");
        }

        try {
            JsonObject currentResources = pterodactylService.getServerResources();
            if (currentResources != null) {
                cachedResources = currentResources;
                lastResourcesFetchTime = System.currentTimeMillis();
            }
            Container updatedContainer = buildContainer(cachedResources, isKillState);
            
            MessageEditData editData = new MessageEditBuilder()
                    .setComponents(updatedContainer)
                    .setEmbeds(java.util.Collections.emptyList())
                    .useComponentsV2(true)
                    .build();

            event.getHook().editMessageById(event.getMessageId(), editData).queue();
        } catch (Exception e) {
        }
    }

    @Override
    public void onStringSelectInteraction(@NotNull StringSelectInteractionEvent event) {
        if (!event.getComponentId().startsWith("ptdl_reason:") && !event.getComponentId().startsWith("ptdl_duration:")) return;
        
        event.deferEdit().queue();
        
        String userId = event.getUser().getId();
        MaintenanceState state = userStates.get(userId);
        if (state == null) return;
        
        String val = event.getValues().get(0);
        if (event.getComponentId().startsWith("ptdl_reason:")) {
            state.reason = val;
        } else if (event.getComponentId().startsWith("ptdl_duration:")) {
            state.duration = val;
        }
    }

    @Override
    public void onModalInteraction(@NotNull ModalInteractionEvent event) {
        String modalId = event.getModalId();
        if (modalId.equals("ptdl_modal")) {
            String command = event.getValue("command").getAsString();
            pterodactylService.sendCommand(command);
            event.reply("Command sent: `" + command + "`").setEphemeral(true).queue();
            return;
        }

        if (modalId.startsWith("ptdl_custom_wizard_modal:")) {
            event.deferReply(true).queue();
            String userId = event.getUser().getId();
            MaintenanceState state = userStates.get(userId);
            if (state == null) {
                event.getHook().sendMessage("⚠️ حدث خطأ: انتهت صلاحية الجلسة، يرجى إعادة المحاولة.").setEphemeral(true).queue();
                return;
            }

            if (event.getValue("custom_reason") != null) {
                state.customReasonText = event.getValue("custom_reason").getAsString();
            }
            if (event.getValue("custom_duration") != null) {
                state.customDurationText = event.getValue("custom_duration").getAsString();
            }

            executeMaintenance(event.getJDA(), state, event.getHook(), event.getUser().getId());
        }
    }

    private Container buildContainer(JsonObject resources, boolean killStateActive) {
        // STATUS VARS
        String state = "Offline";
        String stateEmoji = "🔴";
        String cpu = "0%";
        String ram = "0 MB";
        String disk = "0 MB";
        String uptimeStr = "0s";
        
        String currentState = "offline";

        if (resources != null) {
            currentState = resources.get("current_state").getAsString();
            if ("running".equals(currentState)) {
                state = "Running";
                stateEmoji = "🟢";
            } else if ("starting".equals(currentState)) {
                state = "Starting";
                stateEmoji = "🟡";
            } else if ("stopping".equals(currentState)) {
                state = "Stopping";
                stateEmoji = "🟠";
            }

            JsonObject util = resources.getAsJsonObject("resources");
            if (util != null) {
                cpu = String.format("%.2f%%", util.get("cpu_absolute").getAsDouble());
                long ramBytes = util.get("memory_bytes").getAsLong();
                ram = formatBytes(ramBytes);
                long diskBytes = util.get("disk_bytes").getAsLong();
                disk = formatBytes(diskBytes);
                
                long uptimeMs = util.get("uptime").getAsLong();
                long uptimeSec = uptimeMs / 1000;
                long h = uptimeSec / 3600;
                long m = (uptimeSec % 3600) / 60;
                long s = uptimeSec % 60;
                if (h > 0) uptimeStr = h + "h " + m + "m " + s + "s";
                else if (m > 0) uptimeStr = m + "m " + s + "s";
                else uptimeStr = s + "s";
            }
        }

        // CONSOLE TEXT
        StringBuilder consoleText = new StringBuilder("```ansi\n");
        synchronized (consoleBuffer) {
            if (consoleBuffer.isEmpty()) {
                consoleText.append("Loading console logs...\n");
            } else {
                for (String line : consoleBuffer) {
                    consoleText.append(line).append("\n");
                }
            }
        }
        consoleText.append("```");

        boolean isRunning = "running".equals(currentState);
        boolean isOffline = "offline".equals(currentState) || resources == null;
        boolean isStarting = "starting".equals(currentState);
        boolean isStopping = "stopping".equals(currentState);

        Button startBtn = Button.success("ptdl_start", "Start");
        Button restartBtn = Button.primary("ptdl_restart", "Restart");
        Button stopBtn = killStateActive ? Button.danger("ptdl_kill", "Kill") : Button.danger("ptdl_stop", "Stop");
        Button cmdBtn = Button.secondary("ptdl_cmd", "Send Command");

        if (isOffline) {
            restartBtn = restartBtn.asDisabled();
            stopBtn = stopBtn.asDisabled();
            cmdBtn = cmdBtn.asDisabled();
        } else if (isRunning) {
            startBtn = startBtn.asDisabled();
        } else if (isStarting) {
            startBtn = startBtn.asDisabled();
            restartBtn = restartBtn.asDisabled();
            cmdBtn = cmdBtn.asDisabled();
            stopBtn = Button.danger("ptdl_kill", "Kill");
        } else if (isStopping) {
            startBtn = startBtn.asDisabled();
            restartBtn = restartBtn.asDisabled();
            cmdBtn = cmdBtn.asDisabled();
            stopBtn = Button.danger("ptdl_kill", "Kill");
        }

        return Container.of(
                Section.of(
                        Thumbnail.fromUrl("https://mc-heads.net/avatar/steve/128"),
                        TextDisplay.of("### " + stateEmoji + " Server Panel Status\n" +
                                       stateEmoji + " **State:** `" + state + "`   •   🖥️ **CPU:** `" + cpu + "`   •   💾 **RAM:** `" + ram + "`\n" +
                                       "💿 **Disk:** `" + disk + "`   •   ⏱️ **Uptime:** `" + uptimeStr + "`")
                ),
                Separator.createDivider(Separator.Spacing.SMALL),
                TextDisplay.of("💻 **Console Output (Last 30 lines):**\n" + consoleText.toString()),
                Separator.createDivider(Separator.Spacing.SMALL),
                ActionRow.of(startBtn, restartBtn, stopBtn, cmdBtn)
        );
    }
    
    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "i";
        return String.format("%.2f %sB", bytes / Math.pow(1024, exp), pre);
    }

    // MAINTENANCE STATE CLASS
    public static class MaintenanceState {
        public boolean isRestart;
        public String reason;
        public String duration;
        public String customReasonText;
        public String customDurationText;
        public String messageId;
        public String channelId;
        public long returnTimestamp;
        public String actualReason;
    }

    // MAINTENANCE EXECUTION
    private void executeMaintenance(net.dv8tion.jda.api.JDA jda, MaintenanceState state, net.dv8tion.jda.api.interactions.InteractionHook hook, String userId) {
        pterodactylService.sendPowerSignal(state.isRestart ? "restart" : "stop");
        long durationMs = parseDurationToMs(state.duration, state.customDurationText);
        state.returnTimestamp = System.currentTimeMillis() + durationMs;
        state.actualReason = formatReason(state);

        TextChannel channel = jda.getTextChannelById("1487139736748425236");
        if (channel != null) {
            String mention = "<@&1499896841150402692>";
            Container container = buildMaintenanceContainer(state, durationMs, false, false);
            MessageCreateData message = new MessageCreateBuilder()
                    .setContent(mention)
                    .setComponents(container)
                    .useComponentsV2(true)
                    .build();

            channel.sendMessage(message).queue(msg -> {
                state.messageId = msg.getId();
                state.channelId = msg.getChannel().getId();
                saveMaintenanceState(state);
                startMaintenanceScheduler(jda, state);

                if (hook != null) {
                    hook.sendMessage("✅ تم بدء الإجراء بنجاح وتم نشر الإشعار في القناة المحددة.").setEphemeral(true).queue();
                }
            }, err -> {
                if (hook != null) {
                    hook.sendMessage("⚠️ فشل في إرسال رسالة الإشعار إلى القناة.").setEphemeral(true).queue();
                }
            });
        } else {
            if (hook != null) {
                hook.sendMessage("⚠️ لم يتم العثور على القناة المحددة للإشعارات.").setEphemeral(true).queue();
            }
        }

        userStates.remove(userId);
    }

    // FINISH MAINTENANCE
    private void finishMaintenance(net.dv8tion.jda.api.JDA jda, MaintenanceState state, boolean serverRunning) {
        if (state.channelId != null && state.messageId != null) {
            TextChannel channel = jda.getTextChannelById(state.channelId);
            if (channel != null) {
                Container container = buildMaintenanceContainer(state, 0, true, serverRunning);
                MessageEditData edit = new MessageEditBuilder()
                        .setContent("")
                        .setComponents(container)
                        .useComponentsV2(true)
                        .build();
                channel.editMessageById(state.messageId, edit).queue(null, err -> {});
            }
        }
        clearMaintenanceState();
    }

    // UPDATE MAINTENANCE MESSAGE
    private void updateMaintenanceMessage(net.dv8tion.jda.api.JDA jda, MaintenanceState state) {
        if (state.channelId != null && state.messageId != null) {
            TextChannel channel = jda.getTextChannelById(state.channelId);
            if (channel != null) {
                long timeLeft = state.returnTimestamp - System.currentTimeMillis();
                if (timeLeft < 0) timeLeft = 0;
                Container container = buildMaintenanceContainer(state, timeLeft, false, false);
                MessageEditData edit = new MessageEditBuilder()
                        .setComponents(container)
                        .useComponentsV2(true)
                        .build();
                channel.editMessageById(state.messageId, edit).queue(null, err -> {});
            }
        }
    }

    // DURATION UTILS
    private long parseDurationToMs(String duration, String customText) {
        String textToParse = "custom".equals(duration) ? customText : duration;
        if (textToParse == null || textToParse.trim().isEmpty()) {
            return 30 * 60 * 1000L;
        }
        textToParse = textToParse.trim().toLowerCase();
        if (textToParse.matches("\\d{1,2}:\\d{2}")) {
            try {
                String[] parts = textToParse.split(":");
                int hour = Integer.parseInt(parts[0]);
                int minute = Integer.parseInt(parts[1]);
                java.util.Calendar now = java.util.Calendar.getInstance();
                java.util.Calendar target = java.util.Calendar.getInstance();
                target.set(java.util.Calendar.HOUR_OF_DAY, hour);
                target.set(java.util.Calendar.MINUTE, minute);
                target.set(java.util.Calendar.SECOND, 0);
                target.set(java.util.Calendar.MILLISECOND, 0);
                if (target.before(now)) {
                    target.add(java.util.Calendar.DAY_OF_MONTH, 1);
                }
                return target.getTimeInMillis() - now.getTimeInMillis();
            } catch (Exception e) {
            }
        }
        try {
            long totalMs = 0;
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(\\d+)([dhm])");
            java.util.regex.Matcher matcher = pattern.matcher(textToParse);
            boolean matched = false;
            while (matcher.find()) {
                matched = true;
                long value = Long.parseLong(matcher.group(1));
                String unit = matcher.group(2);
                switch (unit) {
                    case "d" -> totalMs += value * 24 * 60 * 60 * 1000L;
                    case "h" -> totalMs += value * 60 * 60 * 1000L;
                    case "m" -> totalMs += value * 60 * 1000L;
                }
            }
            if (matched) {
                return totalMs;
            }
            return Long.parseLong(textToParse) * 60 * 1000L;
        } catch (Exception e) {
            return 30 * 60 * 1000L;
        }
    }

    // FORMATTING UTILS
    private String formatReason(MaintenanceState state) {
        if ("custom".equals(state.reason)) {
            return state.customReasonText != null ? state.customReasonText : "أعمال صيانة وتطوير مخصصة";
        }
        if (state.reason == null) return "أعمال صيانة وتحديث عامة";
        return switch (state.reason) {
            case "maintenance" -> "🔧 صيانة دورية وتحسينات عامة للمخدم";
            case "dev" -> "🚀 تطوير وتحديث برمجي للأنظمة";
            case "bug" -> "🐛 إصلاح بعض المشاكل والأخطاء التقنية";
            default -> "🔧 أعمال صيانة وتحديث عامة";
        };
    }

    private String formatCountdown(long ms) {
        if (ms <= 0) return "0 دقيقة";
        long secs = ms / 1000;
        long days = secs / 86400;
        long hours = (secs % 86400) / 3600;
        long minutes = (secs % 3600) / 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append(" يوم ");
        }
        if (hours > 0) {
            sb.append(hours).append(" ساعة ");
        }
        if (minutes > 0 || sb.length() == 0) {
            sb.append(minutes).append(" دقيقة");
        }
        return sb.toString().trim();
    }

    // PERSISTENCE UTILS
    private void saveMaintenanceState(MaintenanceState state) {
        try (java.io.FileWriter writer = new java.io.FileWriter(STATE_FILE)) {
            JsonObject json = new JsonObject();
            json.addProperty("isRestart", state.isRestart);
            json.addProperty("reason", state.reason);
            json.addProperty("duration", state.duration);
            json.addProperty("customReasonText", state.customReasonText);
            json.addProperty("customDurationText", state.customDurationText);
            json.addProperty("messageId", state.messageId);
            json.addProperty("channelId", state.channelId);
            json.addProperty("returnTimestamp", state.returnTimestamp);
            json.addProperty("actualReason", state.actualReason);
            writer.write(json.toString());
        } catch (Exception e) {
        }
    }

    private void clearMaintenanceState() {
        try {
            java.io.File file = new java.io.File(STATE_FILE);
            if (file.exists()) {
                file.delete();
            }
        } catch (Exception e) {
        }
    }

    private void loadAndResumeMaintenance(net.dv8tion.jda.api.JDA jda) {
        java.io.File file = new java.io.File(STATE_FILE);
        if (!file.exists()) return;
        try (java.io.FileReader reader = new java.io.FileReader(file)) {
            java.lang.StringBuilder sb = new java.lang.StringBuilder();
            int ch;
            while ((ch = reader.read()) != -1) {
                sb.append((char) ch);
            }
            JsonObject json = com.google.gson.JsonParser.parseString(sb.toString()).getAsJsonObject();
            MaintenanceState state = new MaintenanceState();
            state.isRestart = json.get("isRestart").getAsBoolean();
            state.reason = json.has("reason") && !json.get("reason").isJsonNull() ? json.get("reason").getAsString() : null;
            state.duration = json.has("duration") && !json.get("duration").isJsonNull() ? json.get("duration").getAsString() : null;
            state.customReasonText = json.has("customReasonText") && !json.get("customReasonText").isJsonNull() ? json.get("customReasonText").getAsString() : null;
            state.customDurationText = json.has("customDurationText") && !json.get("customDurationText").isJsonNull() ? json.get("customDurationText").getAsString() : null;
            state.messageId = json.has("messageId") && !json.get("messageId").isJsonNull() ? json.get("messageId").getAsString() : null;
            state.channelId = json.has("channelId") && !json.get("channelId").isJsonNull() ? json.get("channelId").getAsString() : null;
            state.returnTimestamp = json.get("returnTimestamp").getAsLong();
            state.actualReason = json.has("actualReason") && !json.get("actualReason").isJsonNull() ? json.get("actualReason").getAsString() : null;
            startMaintenanceScheduler(jda, state);
        } catch (Exception e) {
        }
    }

    // SCHEDULER UTILS
    private void startMaintenanceScheduler(net.dv8tion.jda.api.JDA jda, MaintenanceState state) {
        if (maintenanceTimer != null) {
            maintenanceTimer.cancel();
        }
        maintenanceTimer = new Timer();
        maintenanceTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try {
                    JsonObject resources = pterodactylService.getServerResources();
                    String currentState = "offline";
                    if (resources != null) {
                        currentState = resources.get("current_state").getAsString();
                    }
                    long timeLeft = state.returnTimestamp - System.currentTimeMillis();
                    if ("running".equals(currentState) || timeLeft <= 0) {
                        finishMaintenance(jda, state, "running".equals(currentState));
                        cancel();
                        return;
                    }
                    updateMaintenanceMessage(jda, state);
                } catch (Throwable t) {
                }
            }
        }, 60000, 60000);
    }

    // CONTAINER UTILS
    private Container buildMaintenanceContainer(MaintenanceState state, long timeLeftMs, boolean finished, boolean serverRunning) {
        String title = state.isRestart ? "🔄 إعادة تشغيل مجدولة مخدم HighCore MC" : "🔧 صيانة مجدولة مخدم HighCore MC";
        String statusEmoji = finished ? (serverRunning ? "🟢" : "🔴") : "🟠";
        String statusText = finished ? (serverRunning ? "المخدم يعمل الآن بشكل طبيعي" : "انتهت فترة الصيانة") : "المخدم تحت الصيانة حالياً";
        String reasonStr = formatReason(state);
        String timeInfo;
        if (finished) {
            timeInfo = "✅ اكتملت العملية بنجاح.";
        } else {
            timeInfo = "⏳ **العودة التقريبية:** " + formatCountdown(timeLeftMs) + "\n" +
                       "⏰ **الوقت المحدد:** <t:" + (state.returnTimestamp / 1000) + ":F> (<t:" + (state.returnTimestamp / 1000) + ":R>)";
        }
        return Container.of(
            Section.of(
                Thumbnail.fromUrl("https://mc-heads.net/avatar/steve/128"),
                TextDisplay.of("### " + statusEmoji + " " + title + "\n" +
                               "🚦 **الحالة:** `" + statusText + "`\n" +
                               "📝 **السبب:** `" + reasonStr + "`\n\n" +
                               timeInfo)
            )
        );
    }
}
