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
import com.highcore.bot.services.ActionLogService;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.Timer;
import java.util.TimerTask;

public class PanelCommand extends ListenerAdapter {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(PanelCommand.class);
    private final PterodactylService pterodactylService = new PterodactylService();
    private final List<String> consoleBuffer = new ArrayList<>();
    private ScheduledExecutorService panelExecutor;
    private ScheduledExecutorService resourcesExecutor;
    private String activeMessageId = null;
    private String activeChannelId = null;
    private boolean isKillState = false;
    private volatile JsonObject cachedResources = null;
    private long lastResourcesFetchTime = 0;
    private final java.util.concurrent.atomic.AtomicBoolean isEditing = new java.util.concurrent.atomic.AtomicBoolean(false);
    private String lastSentMessagePayload = "";

    private final Map<String, MaintenanceState> userStates = new ConcurrentHashMap<>();
    private Timer maintenanceTimer;
    private Timer preMaintenanceTimer;
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
        refreshConsoleBufferFromFile();
    }

    private void refreshConsoleBufferFromFileSync() {
        try {
            java.util.List<String> fileLogs = pterodactylService.getLatestLogs(30);
            if (fileLogs != null && !fileLogs.isEmpty()) {
                synchronized (consoleBuffer) {
                    consoleBuffer.clear();
                    consoleBuffer.addAll(fileLogs);
                }
            }
        } catch (Exception ignored) {}
    }

    private void refreshConsoleBufferFromFile() {
        java.util.concurrent.CompletableFuture.runAsync(this::refreshConsoleBufferFromFileSync);
    }

    private void updatePanelMessage(net.dv8tion.jda.api.JDA jda) {
        if (activeMessageId == null || activeChannelId == null) return;
        if (!isEditing.compareAndSet(false, true)) return;
        try {
            String state = "Offline";
            String cpu = "0%";
            String ram = "0 MB";
            String disk = "0 MB";
            String uptimeStr = "0s";
            if (cachedResources != null) {
                state = cachedResources.get("current_state").getAsString();
                JsonObject util = cachedResources.getAsJsonObject("resources");
                if (util != null) {
                    cpu = String.format("%.2f%%", util.get("cpu_absolute").getAsDouble());
                    ram = formatBytes(util.get("memory_bytes").getAsLong());
                    disk = formatBytes(util.get("disk_bytes").getAsLong());
                    uptimeStr = String.valueOf(util.get("uptime").getAsLong());
                }
            }
            StringBuilder consoleSignature = new StringBuilder();
            synchronized (consoleBuffer) {
                for (String line : consoleBuffer) {
                    consoleSignature.append(line).append("\n");
                }
            }
            String currentPayload = state + "|" + cpu + "|" + ram + "|" + disk + "|" + uptimeStr + "|" + isKillState + "|" + consoleSignature.toString();
            if (currentPayload.equals(lastSentMessagePayload)) {
                isEditing.set(false);
                return;
            }
            Container updatedContainer = buildContainer(cachedResources, isKillState);
            MessageEditData editData = new MessageEditBuilder()
                    .setComponents(updatedContainer)
                    .setEmbeds(java.util.Collections.emptyList())
                    .useComponentsV2(true)
                    .build();
            var channel = jda.getTextChannelById(activeChannelId);
            if (channel != null) {
                channel.editMessageById(activeMessageId, editData).useComponentsV2().queue(msg -> {
                    lastSentMessagePayload = currentPayload;
                    isEditing.set(false);
                }, err -> {
                    isEditing.set(false);
                });
            } else {
                isEditing.set(false);
            }
        } catch (Throwable ignored) {
            isEditing.set(false);
        }
    }

    private synchronized void ensurePanelUpdaterRunning(net.dv8tion.jda.api.JDA jda) {
        if (activeMessageId == null || activeChannelId == null) return;
        if (panelExecutor != null && !panelExecutor.isShutdown()) {
            return;
        }
        if (resourcesExecutor != null && !resourcesExecutor.isShutdown()) resourcesExecutor.shutdownNow();
        resourcesExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PanelResourcesFetcher");
            t.setDaemon(true);
            return t;
        });
        resourcesExecutor.scheduleAtFixedRate(() -> {
            try {
                JsonObject fresh = pterodactylService.getServerResources();
                if (fresh != null) {
                    cachedResources = fresh;
                    lastResourcesFetchTime = System.currentTimeMillis();
                }
            } catch (Throwable ignored) {}
        }, 0, 5, TimeUnit.SECONDS);

        panelExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PanelUpdateTimer");
            t.setDaemon(true);
            return t;
        });
        panelExecutor.scheduleAtFixedRate(() -> {
            if (activeMessageId == null || activeChannelId == null) return;
            try {
                boolean isOfflineOrStarting = true;
                if (cachedResources != null) {
                    String state = cachedResources.get("current_state").getAsString();
                    if ("running".equals(state)) {
                        isOfflineOrStarting = false;
                    }
                }
                if (pterodactylService.isConsoleDisconnected() || isOfflineOrStarting) {
                    refreshConsoleBufferFromFileSync();
                }
                updatePanelMessage(jda);
            } catch (Throwable ignored) {}
        }, 5, 5, TimeUnit.SECONDS);
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (event.getName().equals("ec")) {
            ActionLogService.logCommand(event.getJDA(), "/ec", event.getUser().getId(), event.getUser().getName(),
                "فتح لوحة إدارة الصيانة");
            event.deferReply(true).queue(hook -> {
                java.util.concurrent.CompletableFuture.runAsync(() -> {
                    MaintenanceState state = getActiveMaintenanceState();
                    if (state == null) {
                        try {
                            Button initBtn = Button.primary("ec_init", "بدء صيانة");
                            Container container = Container.of(
                                Section.of(
                                    Thumbnail.fromUrl("https://mc-heads.net/avatar/steve/128"),
                                    TextDisplay.of("### 🛠️ بدء صيانة جديدة\n" +
                                                   "انقر على الزر بالأسفل لتفعيل وضع الصيانة:")
                                ),
                                Separator.createDivider(Separator.Spacing.SMALL),
                                ActionRow.of(initBtn)
                            );
                            MessageCreateData choiceMsg = new MessageCreateBuilder()
                                    .setComponents(container)
                                    .useComponentsV2(true)
                                    .build();
                            hook.sendMessage(choiceMsg).queue();
                        } catch (Exception e) {
                            hook.sendMessage("حدث خطأ أثناء عرض معالج بدء الصيانة.").queue();
                        }
                        return;
                    }
                    try {
                        Button extendBtn = Button.primary("ec_extend", "تمديد الصيانة");
                        Button endBtn = Button.danger("ec_end", "إنهاء الصيانة");
                        Container container = Container.of(
                            Section.of(
                                Thumbnail.fromUrl("https://mc-heads.net/avatar/steve/128"),
                                TextDisplay.of("### 🛠️ إدارة الصيانة الحالية\n" +
                                               "الخادم خاضع للصيانة حالياً. حدد الإجراء المطلوب لإدارة الصيانة:")
                            ),
                            Separator.createDivider(Separator.Spacing.SMALL),
                            ActionRow.of(extendBtn, endBtn)
                        );
                        MessageCreateData choiceMsg = new MessageCreateBuilder()
                                .setComponents(container)
                                .useComponentsV2(true)
                                .build();
                        hook.sendMessage(choiceMsg).queue();
                    } catch (Exception e) {
                        hook.sendMessage("حدث خطأ أثناء عرض خيارات الصيانة.").queue();
                    }
                });
            });
            return;
        }

        if (!event.getName().equals("panel")) return;
        ActionLogService.logCommand(event.getJDA(), "/panel", event.getUser().getId(), event.getUser().getName(),
            "فتح لوحة تحكم الخادم (Panel)");
        event.deferReply().queue(hook -> {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    JsonObject resources = pterodactylService.getServerResources();
                    if (resources != null) {
                        cachedResources = resources;
                        lastResourcesFetchTime = System.currentTimeMillis();
                    }
                    isKillState = false;
                    refreshConsoleBufferFromFileSync();
                    Container container = buildContainer(cachedResources, isKillState);

                    MessageCreateData messageData = new MessageCreateBuilder()
                            .setComponents(container)
                            .setEmbeds(java.util.Collections.emptyList())
                            .useComponentsV2(true)
                            .build();

                    hook.sendMessage(messageData).queue(message -> {
                        activeMessageId = message.getId();
                        activeChannelId = message.getChannel().getId();

                        if (panelExecutor != null && !panelExecutor.isShutdown()) panelExecutor.shutdownNow();
                        if (resourcesExecutor != null && !resourcesExecutor.isShutdown()) resourcesExecutor.shutdownNow();
                        panelExecutor = null;
                        resourcesExecutor = null;

                        ensurePanelUpdaterRunning(event.getJDA());
                    });
                } catch (Exception e) {
                    logger.error("Error executing panel command", e);
                    hook.sendMessage("❌ حدث خطأ أثناء جلب حالة الخادم أو إنشاء اللوحة. المرجو المحاولة لاحقا.").queue();
                }
            });
        });
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        if (!event.getComponentId().startsWith("ptdl_") && !event.getComponentId().startsWith("ec_") && !event.getComponentId().startsWith("sched_")) return;

        String id = event.getComponentId();

        if (id.startsWith("ptdl_") && event.getMessageId() != null) {
            activeMessageId = event.getMessageId();
            activeChannelId = event.getChannel().getId();
            ensurePanelUpdaterRunning(event.getJDA());
        }

        if (id.equals("ec_end")) {
            ActionLogService.logMaintenance(event.getJDA(), "🛑 Maintenance Ended", event.getUser().getId(), event.getUser().getName(),
                "إنهاء حالة الصيانة الحالية");
            event.deferEdit().queue();
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                MaintenanceState state = getActiveMaintenanceState();
                if (state == null) {
                    event.getHook().sendMessage("لا توجد حالة صيانة نشطة حالياً.").setEphemeral(true).queue();
                    return;
                }
                finishMaintenance(event.getJDA(), state, true);
                event.getHook().editOriginal("تم إنهاء حالة الصيانة بنجاح وإرسال الإشعار.")
                        .setComponents(java.util.Collections.emptyList())
                        .queue();
            });
            return;
        }

        if (id.equals("ptdl_stop") || id.equals("ptdl_restart") || id.equals("ec_init")) {
            boolean isRestart = id.equals("ptdl_restart");
            boolean isFromEc = id.equals("ec_init");

            MaintenanceState state = new MaintenanceState();
            state.isRestart = isRestart;
            state.isFromEc = isFromEc;
            userStates.put(event.getUser().getId(), state);

            String prefix = isFromEc ? "ec" : (isRestart ? "restart" : "stop");

            Button instantBtn = Button.primary("sched_inst:" + prefix, "Instant (فوري)");
            Button scheduleBtn = Button.secondary("sched_schd:" + prefix, "Scheduling (مجدول)");

            Container container = Container.of(
                Section.of(
                    Thumbnail.fromUrl("https://mc-heads.net/avatar/steve/128"),
                    TextDisplay.of("### 📅 خيارات تنفيذ الإجراء\n" +
                                   "هل تريد تنفيذ هذا الإجراء فوراً أم جدولته لوقت لاحق؟")
                ),
                Separator.createDivider(Separator.Spacing.SMALL),
                ActionRow.of(instantBtn, scheduleBtn)
            );
            
            MessageCreateData wizardData = new MessageCreateBuilder()
                    .setComponents(container)
                    .useComponentsV2(true)
                    .build();

            if (isFromEc) {
                event.deferEdit().queue();
                event.getHook().editOriginal(MessageEditData.fromCreateData(wizardData)).queue();
            } else {
                event.reply(wizardData).setEphemeral(true).queue();
            }
            return;
        }

        if (id.startsWith("sched_schd:")) {
            String prefix = id.substring("sched_schd:".length());
            TextInput dateInput = TextInput.create("date", TextInputStyle.SHORT)
                    .setPlaceholder("مثال: 2026-06-14 20:11")
                    .setRequired(true)
                    .build();

            Modal modal = Modal.create("sched_modal:" + prefix, "جدولة الصيانة")
                    .addComponents(
                        net.dv8tion.jda.api.components.label.Label.of("تاريخ ووقت بدء الصيانة", dateInput)
                    )
                    .build();
            
            event.replyModal(modal).queue();
            return;
        }

        if (id.startsWith("sched_inst:")) {
            String prefix = id.substring("sched_inst:".length());
            MaintenanceState state = userStates.get(event.getUser().getId());
            if (state == null) {
                event.reply("حدث خطأ: انتهت صلاحية الجلسة.").setEphemeral(true).queue();
                return;
            }
            state.isScheduled = false;
            event.deferEdit().queue();
            showMaintenanceWizard(event.getHook(), prefix);
            return;
        }

        if (id.startsWith("ptdl_confirm:")) {
            String action = id.substring("ptdl_confirm:".length());
            String userId = event.getUser().getId();
            MaintenanceState state = userStates.get(userId);
            if (state == null) {
                event.reply("حدث خطأ: انتهت صلاحية الجلسة، يرجى إعادة المحاولة.").setEphemeral(true).queue();
                return;
            }

            if ("custom".equals(state.reason) || "custom".equals(state.duration)) {
                Modal.Builder modalBuilder = Modal.create("ptdl_custom_wizard_modal:" + action, "تفاصيل التوقف المخصصة");
                List<net.dv8tion.jda.api.components.label.Label> labels = new ArrayList<>();
                if ("custom".equals(state.reason)) {
                    TextInput reasonInput = TextInput.create("custom_reason", TextInputStyle.SHORT)
                            .setPlaceholder("مثال: ترقية الهاردوير للسيرفر")
                            .setRequired(true)
                            .build();
                    labels.add(net.dv8tion.jda.api.components.label.Label.of("السبب", reasonInput));
                }
                if ("custom".equals(state.duration)) {
                    TextInput durationInput = TextInput.create("custom_duration", TextInputStyle.SHORT)
                            .setPlaceholder("أدخل المدة (مثال: 30m, 1h)")
                            .setRequired(true)
                            .build();
                    labels.add(net.dv8tion.jda.api.components.label.Label.of("المدة (د/س/ي)", durationInput));
                }
                Modal modal = modalBuilder.addComponents(labels).build();
                event.replyModal(modal).queue();
                return;
            }

            event.deferEdit().queue();
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                executeMaintenance(event.getJDA(), state, event.getHook(), event.getUser().getId());
            });
            return;
        }

        if (id.equals("ptdl_cancel")) {
            event.deferEdit().queue();
            userStates.remove(event.getUser().getId());
            event.getHook().editOriginal("تم إلغاء الإجراء بنجاح.")
                    .setComponents(java.util.Collections.emptyList())
                    .queue();
            return;
        }

        event.deferEdit().queue();
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            if (id.equals("ptdl_start")) {
                ActionLogService.logMaintenance(event.getJDA(), "🟢 Server Started", event.getUser().getId(), event.getUser().getName(),
                    "تشغيل الخادم (Server Start)");
                isKillState = false;
                pterodactylService.sendPowerSignal("start");
                pterodactylService.reconnectConsole();
                java.util.concurrent.CompletableFuture.runAsync(() -> {
                    for (int i = 0; i < 4; i++) {
                        try { Thread.sleep(2000); } catch (Exception ignored) {}
                        refreshConsoleBufferFromFileSync();
                        updatePanelMessage(event.getJDA());
                    }
                });
            } else if (id.equals("ptdl_kill")) {
                ActionLogService.logMaintenance(event.getJDA(), "🔴 Server Killed", event.getUser().getId(), event.getUser().getName(),
                    "إيقاف قسري للخادم (Server Kill)");
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
        });
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
        
        if (modalId.startsWith("sched_modal:")) {
            String prefix = modalId.substring("sched_modal:".length());
            MaintenanceState state = userStates.get(event.getUser().getId());
            if (state == null) {
                event.reply("حدث خطأ: انتهت صلاحية الجلسة.").setEphemeral(true).queue();
                return;
            }
            try {
                String dateStr = event.getValue("date").getAsString();
                
                // Allow user to use colon instead of space between date and time (e.g. 2026-06-14:20:11)
                if (dateStr.matches(".*\\d{2}:\\d{2}:\\d{2}.*")) {
                    dateStr = dateStr.replaceFirst(":(?=\\d{2}:\\d{2})", " ");
                }
                
                if (!com.highcore.bot.utils.TimeUtils.isValidFormat(dateStr)) {
                    event.reply("صيغة الوقت غير صحيحة! جرب صيغة مثل: 2026-06-14 20:11").setEphemeral(true).queue();
                    return;
                }
                long unixTime = com.highcore.bot.utils.TimeUtils.parseToUnixTimestamp(dateStr);
                if (unixTime < 0 || unixTime * 1000L < System.currentTimeMillis()) {
                    event.reply("التاريخ المدخل غير صالح أو في الماضي!").setEphemeral(true).queue();
                    return;
                }
                
                state.isScheduled = true;
                state.scheduledStartTime = unixTime * 1000L;
                
                event.deferEdit().queue();
                showMaintenanceWizard(event.getHook(), prefix);
            } catch (Exception e) {
                event.reply("حدث خطأ في قراءة الوقت المُدخل.").setEphemeral(true).queue();
            }
            return;
        }

        if (modalId.equals("ec_extend_modal")) {
            event.deferReply(true).queue(hook -> {
                java.util.concurrent.CompletableFuture.runAsync(() -> {
                    MaintenanceState state = getActiveMaintenanceState();
                    if (state == null) {
                        hook.sendMessage("⚠️ لا توجد صيانة نشطة حالياً لتمديدها.").setEphemeral(true).queue();
                        return;
                    }
                    String input = event.getValue("new_duration").getAsString();
                    long durationMs = parseDurationToMs("custom", input);
                    state.returnTimestamp = System.currentTimeMillis() + durationMs;
                    state.duration = "custom";
                    state.customDurationText = input;
                    saveMaintenanceState(state);
                    startMaintenanceScheduler(event.getJDA(), state);
                    updateMaintenanceMessage(event.getJDA(), state);
                    com.highcore.bot.services.ServerStatsService.forceUpdate(event.getJDA());
                    hook.sendMessage("تم تمديد فترة الصيانة بنجاح إلى: <t:" + (state.returnTimestamp / 1000) + ":F> (<t:" + (state.returnTimestamp / 1000) + ":R>)").setEphemeral(true).queue();
                });
            });
            return;
        }

        if (modalId.equals("ptdl_modal")) {
            String command = event.getValue("command").getAsString();
            ActionLogService.logMaintenance(event.getJDA(), "🖥️ Console Command", event.getUser().getId(), event.getUser().getName(),
                "الأمر المُرسَل: `" + command + "`");
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

    private void showMaintenanceWizard(net.dv8tion.jda.api.interactions.InteractionHook hook, String prefix) {
        StringSelectMenu reasonMenu = StringSelectMenu.create("ptdl_reason:" + prefix)
                .setPlaceholder("اختر سبب التوقف...")
                .addOption("صيانة دورية وتحسينات", "maintenance", "صيانة دورية وتحسينات عامة للخادم")
                .addOption("تطوير وتحديث الأنظمة", "dev", "تطوير وتحديث برمجي للأنظمة")
                .addOption("إصلاح أخطاء تقنية", "bug", "إصلاح بعض المشاكل والأخطاء التقنية")
                .addOption("سبب مخصص...", "custom", "كتابة سبب مخصص يدوياً")
                .build();

        StringSelectMenu durationMenu = StringSelectMenu.create("ptdl_duration:" + prefix)
                .setPlaceholder("اختر وقت العودة المتوقع...")
                .addOption("15 دقيقة", "15m")
                .addOption("30 دقيقة", "30m")
                .addOption("ساعة واحدة", "1h")
                .addOption("ساعتين", "2h")
                .addOption("6 ساعات", "6h")
                .addOption("12 ساعة", "12h")
                .addOption("يوم كامل", "1d")
                .addOption("وقت مخصص...", "custom", "تحديد ساعة أو مدة مخصصة")
                .build();

        Button confirmBtn = Button.success("ptdl_confirm:" + prefix, "تأكيد الإجراء");
        Button cancelBtn = Button.danger("ptdl_cancel", "إلغاء الإجراء");

        Container container = Container.of(
            Section.of(
                Thumbnail.fromUrl("https://mc-heads.net/avatar/steve/128"),
                TextDisplay.of("### 🛠️ معالج بدء الصيانة\n" +
                               "يرجى تحديد تفاصيل التوقف والمدة المقدرة بالأسفل لإشعار اللاعبين:")
            ),
            Separator.createDivider(Separator.Spacing.SMALL),
            ActionRow.of(reasonMenu),
            ActionRow.of(durationMenu),
            ActionRow.of(confirmBtn, cancelBtn)
        );
        MessageEditData editData = new MessageEditBuilder()
                .setComponents(container)
                .useComponentsV2(true)
                .build();

        hook.editOriginal(editData).queue();
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
        java.util.List<String> copyBuffer = new java.util.ArrayList<>();
        synchronized (consoleBuffer) {
            if (consoleBuffer instanceof java.util.Collection) {
                copyBuffer.addAll((java.util.Collection<String>) consoleBuffer);
            }
        }

        StringBuilder consoleText = new StringBuilder();
        if (copyBuffer.isEmpty()) {
            consoleText.append("```ansi\nLoading console logs...\n```");
        } else {
            int maxLen = 3500;
            int currentLen = 0;
            java.util.List<String> linesToKeep = new java.util.ArrayList<>();
            
            for (int i = copyBuffer.size() - 1; i >= 0; i--) {
                String line = copyBuffer.get(i);
                if (currentLen + line.length() + 1 > maxLen) {
                    break;
                }
                linesToKeep.add(0, line);
                currentLen += line.length() + 1;
            }
            
            consoleText.append("```ansi\n");
            for (String line : linesToKeep) {
                consoleText.append(line).append("\n");
            }
            consoleText.append("```");
        }

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
        public boolean isFromEc;
        public String reason;
        public String duration;
        public String customReasonText;
        public String customDurationText;
        public String messageId;
        public String channelId;
        public long returnTimestamp;
        public String actualReason;
        public boolean isScheduled;
        public long scheduledStartTime;
        public boolean warned30m;
        public boolean warned15m;
        public boolean warned5m;
    }

    // MAINTENANCE EXECUTION
    private void executeMaintenance(net.dv8tion.jda.api.JDA jda, MaintenanceState state, net.dv8tion.jda.api.interactions.InteractionHook hook, String userId) {
        if (state.isScheduled) {
            long durationMs = parseDurationToMs(state.duration, state.customDurationText);
            state.returnTimestamp = state.scheduledStartTime + durationMs;
            state.actualReason = formatReason(state);

            TextChannel channel = jda.getTextChannelById("1487139736748425236");
            if (channel != null) {
                Container container = buildScheduledContainer(state);
                MessageCreateData message = new MessageCreateBuilder()
                        .setComponents(container)
                        .useComponentsV2(true)
                        .build();

                channel.sendMessage(message).useComponentsV2().queue(msg -> {
                    state.messageId = msg.getId();
                    state.channelId = msg.getChannel().getId();
                    saveMaintenanceState(state);
                    startPreMaintenanceScheduler(jda, state);

                    if (hook != null) {
                        hook.sendMessage("تمت جدولة الإجراء بنجاح وتم نشر الإشعار في القناة المحددة.").setEphemeral(true).queue();
                    }
                }, err -> {
                    logger.error("Failed to send scheduled maintenance message", err);
                });
            }
            userStates.remove(userId);
            return;
        }

        // Instant logic
        String actionTypeStr = state.isRestart ? "Restart" : "Stop";
        String mcMessage = "\\n§8[§c!§8] §a§lAction Executed!§r\\n§8» §7Type: §eInstant (" + actionTypeStr + ")\\n§8» §cThe proxy will shut down in a few seconds...\\n";
        pterodactylService.sendCommand("tellraw @a {\"text\":\"" + mcMessage + "\"}");

        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try { Thread.sleep(10000); } catch (Exception ignored) {}
            
            if (!state.isFromEc) {
                pterodactylService.sendPowerSignal(state.isRestart ? "restart" : "stop");
                pterodactylService.reconnectConsole();
                if (state.isRestart) {
                    for (int i = 0; i < 4; i++) {
                        try { Thread.sleep(2000); } catch (Exception ignored) {}
                        refreshConsoleBufferFromFileSync();
                        updatePanelMessage(jda);
                    }
                }
            }
            long durationMs = parseDurationToMs(state.duration, state.customDurationText);
            state.returnTimestamp = System.currentTimeMillis() + durationMs;
            state.actualReason = formatReason(state);

            TextChannel channel = jda.getTextChannelById("1487139736748425236");
            if (channel != null) {
                Container container = buildMaintenanceContainer(state, durationMs, false, false);
                MessageCreateData message = new MessageCreateBuilder()
                        .setComponents(container)
                        .useComponentsV2(true)
                        .build();

                channel.sendMessage(message).useComponentsV2().queue(msg -> {
                    state.messageId = msg.getId();
                    state.channelId = msg.getChannel().getId();
                    saveMaintenanceState(state);
                    startMaintenanceScheduler(jda, state);
                    com.highcore.bot.services.ServerStatsService.forceUpdate(jda);

                    channel.sendMessage("<@&1499896841150402692>").queue(ping -> ping.delete().queue());
                }, err -> {
                    logger.error("Failed to send maintenance message", err);
                });
            }
        });

        userStates.remove(userId);
    }

    // FINISH MAINTENANCE
    private void finishMaintenance(net.dv8tion.jda.api.JDA jda, MaintenanceState state, boolean serverRunning) {
        if (state.channelId != null) {
            TextChannel channel = jda.getTextChannelById(state.channelId);
            if (channel != null) {
                if (state.messageId != null) {
                    Container container = buildMaintenanceContainer(state, 0, true, serverRunning);
                    MessageEditData edit = new MessageEditBuilder()
                            .setComponents(container)
                            .useComponentsV2(true)
                            .build();
                    channel.editMessageById(state.messageId, edit).useComponentsV2().queue(msg -> {
                        channel.sendMessage("<@&1499896841150402692>").queue(ping -> ping.delete().queue());
                    }, err -> {
                        logger.error("Failed to edit maintenance message to finished state", err);
                    });
                }
            }
        }
        if (maintenanceTimer != null) {
            maintenanceTimer.cancel();
            maintenanceTimer = null;
        }
        clearMaintenanceState();
        com.highcore.bot.services.ServerStatsService.forceUpdate(jda);
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
                channel.editMessageById(state.messageId, edit).useComponentsV2().queue(msg -> {
                    channel.sendMessage("<@&1499896841150402692>").queue(ping -> ping.delete().queue());
                }, err -> {});
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
            return Long.parseLong(textToParse.replaceAll("[^0-9]", "")) * 60 * 1000L;
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
            case "maintenance" -> "صيانة دورية وتحسينات عامة للخادم";
            case "dev" -> "تطوير وتحديث برمجي للأنظمة";
            case "bug" -> "إصلاح بعض المشاكل والأخطاء التقنية";
            default -> "أعمال صيانة وتحديث عامة";
        };
    }

    private String formatReasonType(MaintenanceState state) {
        if (state.reason == null) return "الصيانة والتحديث";
        return switch (state.reason) {
            case "maintenance" -> "الصيانة";
            case "dev" -> "التطوير والتحديث";
            case "bug" -> "إصلاح الأخطاء";
            default -> "الصيانة";
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
            json.addProperty("isFromEc", state.isFromEc);
            json.addProperty("reason", state.reason);
            json.addProperty("duration", state.duration);
            json.addProperty("customReasonText", state.customReasonText);
            json.addProperty("customDurationText", state.customDurationText);
            json.addProperty("messageId", state.messageId);
            json.addProperty("channelId", state.channelId);
            json.addProperty("returnTimestamp", state.returnTimestamp);
            json.addProperty("actualReason", state.actualReason);
            
            json.addProperty("isScheduled", state.isScheduled);
            json.addProperty("scheduledStartTime", state.scheduledStartTime);
            json.addProperty("warned30m", state.warned30m);
            json.addProperty("warned15m", state.warned15m);
            json.addProperty("warned5m", state.warned5m);

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
            state.isFromEc = json.has("isFromEc") && json.get("isFromEc").getAsBoolean();
            state.reason = json.has("reason") && !json.get("reason").isJsonNull() ? json.get("reason").getAsString() : null;
            state.duration = json.has("duration") && !json.get("duration").isJsonNull() ? json.get("duration").getAsString() : null;
            state.customReasonText = json.has("customReasonText") && !json.get("customReasonText").isJsonNull() ? json.get("customReasonText").getAsString() : null;
            state.customDurationText = json.has("customDurationText") && !json.get("customDurationText").isJsonNull() ? json.get("customDurationText").getAsString() : null;
            state.messageId = json.has("messageId") && !json.get("messageId").isJsonNull() ? json.get("messageId").getAsString() : null;
            state.channelId = json.has("channelId") && !json.get("channelId").isJsonNull() ? json.get("channelId").getAsString() : null;
            state.returnTimestamp = json.get("returnTimestamp").getAsLong();
            state.actualReason = json.has("actualReason") && !json.get("actualReason").isJsonNull() ? json.get("actualReason").getAsString() : null;
            
            state.isScheduled = json.has("isScheduled") && json.get("isScheduled").getAsBoolean();
            state.scheduledStartTime = json.has("scheduledStartTime") ? json.get("scheduledStartTime").getAsLong() : 0;
            state.warned30m = json.has("warned30m") && json.get("warned30m").getAsBoolean();
            state.warned15m = json.has("warned15m") && json.get("warned15m").getAsBoolean();
            state.warned5m = json.has("warned5m") && json.get("warned5m").getAsBoolean();
            
            if (state.isScheduled) {
                startPreMaintenanceScheduler(jda, state);
            } else {
                startMaintenanceScheduler(jda, state);
            }
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
                    long timeLeft = state.returnTimestamp - System.currentTimeMillis();
                    if (timeLeft <= 0) {
                        finishMaintenance(jda, state, true);
                        cancel();
                    }
                } catch (Throwable t) {
                }
            }
        }, 60000, 60000);
    }

    // CONTAINER UTILS
    private Container buildMaintenanceContainer(MaintenanceState state, long timeLeftMs, boolean finished, boolean serverRunning) {
        String reasonType = formatReasonType(state);
        String title = finished ? "✅ انتهت حالة " + reasonType : "🚨 بدأت حالة " + reasonType;
        String reasonStr = formatReason(state);
        String bodyText;
        if (finished) {
            bodyText = "انتهت حالة **" + reasonType + "** وعاد الخادم للعمل الآن بشكل طبيعي، بإمكانكم الدخول واللعب.\n\nتنبيه: <@&1499896841150402692>";
        } else {
            bodyText = "تم إيقاف الخادم لبدء أعمال **" + reasonType + "**.\n" +
                       "**السبب:** `" + reasonStr + "`\n\n" +
                       "**وقت العودة المتوقع:** <t:" + (state.returnTimestamp / 1000) + ":F> (<t:" + (state.returnTimestamp / 1000) + ":R>)\n\nتنبيه: <@&1499896841150402692>";
        }
        return Container.of(
            Section.of(
                Thumbnail.fromUrl("https://mc-heads.net/avatar/steve/128"),
                TextDisplay.of("### " + title + "\n" + bodyText)
            )
        );
    }

    private Container buildScheduledContainer(MaintenanceState state) {
        String reasonType = formatReasonType(state);
        String reasonStr = formatReason(state);
        
        long startSec = state.scheduledStartTime / 1000;
        long returnSec = state.returnTimestamp / 1000;
        
        String bodyText = "تم جدولة إيقاف الخادم لبدء أعمال **" + reasonType + "**.\n" +
                          "**السبب:** `" + reasonStr + "`\n\n" +
                          "**موعد بدء الصيانة:** <t:" + startSec + ":F> (<t:" + startSec + ":R>)\n" +
                          "**موعد العودة المتوقع:** <t:" + returnSec + ":F> (<t:" + returnSec + ":R>)\n\n" +
                          "تنبيه: <@&1499896841150402692>";
                          
        return Container.of(
            Section.of(
                Thumbnail.fromUrl("https://mc-heads.net/avatar/steve/128"),
                TextDisplay.of("### 📅 صيانة مجدولة\n" + bodyText)
            )
        );
    }

    private MaintenanceState getActiveMaintenanceState() {
        java.io.File file = new java.io.File(STATE_FILE);
        if (!file.exists()) return null;
        try (java.io.FileReader reader = new java.io.FileReader(file)) {
            java.lang.StringBuilder sb = new java.lang.StringBuilder();
            int ch;
            while ((ch = reader.read()) != -1) {
                sb.append((char) ch);
            }
            JsonObject json = com.google.gson.JsonParser.parseString(sb.toString()).getAsJsonObject();
            MaintenanceState state = new MaintenanceState();
            state.isRestart = json.get("isRestart").getAsBoolean();
            state.isFromEc = json.has("isFromEc") && json.get("isFromEc").getAsBoolean();
            state.reason = json.has("reason") && !json.get("reason").isJsonNull() ? json.get("reason").getAsString() : null;
            state.duration = json.has("duration") && !json.get("duration").isJsonNull() ? json.get("duration").getAsString() : null;
            state.customReasonText = json.has("customReasonText") && !json.get("customReasonText").isJsonNull() ? json.get("customReasonText").getAsString() : null;
            state.customDurationText = json.has("customDurationText") && !json.get("customDurationText").isJsonNull() ? json.get("customDurationText").getAsString() : null;
            state.messageId = json.has("messageId") && !json.get("messageId").isJsonNull() ? json.get("messageId").getAsString() : null;
            state.channelId = json.has("channelId") && !json.get("channelId").isJsonNull() ? json.get("channelId").getAsString() : null;
            state.returnTimestamp = json.get("returnTimestamp").getAsLong();
            state.actualReason = json.has("actualReason") && !json.get("actualReason").isJsonNull() ? json.get("actualReason").getAsString() : null;
            
            state.isScheduled = json.has("isScheduled") && json.get("isScheduled").getAsBoolean();
            state.scheduledStartTime = json.has("scheduledStartTime") ? json.get("scheduledStartTime").getAsLong() : 0;
            state.warned30m = json.has("warned30m") && json.get("warned30m").getAsBoolean();
            state.warned15m = json.has("warned15m") && json.get("warned15m").getAsBoolean();
            state.warned5m = json.has("warned5m") && json.get("warned5m").getAsBoolean();

            return state;
        } catch (Exception e) {
            return null;
        }
    }
    
    private void startPreMaintenanceScheduler(net.dv8tion.jda.api.JDA jda, MaintenanceState state) {
        if (preMaintenanceTimer != null) preMaintenanceTimer.cancel();
        preMaintenanceTimer = new Timer();
        preMaintenanceTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                long timeLeft = state.scheduledStartTime - now;
                
                if (timeLeft <= 0) {
                    preMaintenanceTimer.cancel();
                    preMaintenanceTimer = null;
                    
                    String actionVerb = state.isRestart ? "restarting" : "stopping";
                    pterodactylService.sendCommand("tellraw @a {\"text\":\"\\n§8[§c!§8] §4§lPROXY MAINTENANCE §8[§c!§8]\\n§7The Proxy is " + actionVerb + " §c§lNOW§7.\\n§7Please wrap up your work immediately!\\n\"}");
                    
                    if (!state.isFromEc) {
                        pterodactylService.sendPowerSignal(state.isRestart ? "restart" : "stop");
                        pterodactylService.reconnectConsole();
                    }
                    
                    long durationMs = parseDurationToMs(state.duration, state.customDurationText);
                    state.returnTimestamp = System.currentTimeMillis() + durationMs;
                    
                    TextChannel channel = jda.getTextChannelById(state.channelId);
                    if (channel != null && state.messageId != null) {
                        Container container = buildMaintenanceContainer(state, durationMs, false, false);
                        MessageEditData edit = new MessageEditBuilder()
                                .setComponents(container)
                                .useComponentsV2(true)
                                .build();
                        channel.editMessageById(state.messageId, edit).useComponentsV2().queue(msg -> {
                            channel.sendMessage("<@&1499896841150402692>").queue(ping -> ping.delete().queue());
                        });
                    }
                    
                    state.isScheduled = false;
                    saveMaintenanceState(state);
                    startMaintenanceScheduler(jda, state);
                    return;
                }
                
                long minsLeft = timeLeft / 60000;
                String warnVerb = state.isRestart ? "restart" : "stop";
                
                if (minsLeft <= 30 && minsLeft > 15 && !state.warned30m) {
                    state.warned30m = true;
                    pterodactylService.sendCommand("tellraw @a {\"text\":\"\\n§8[§e!§8] §6§lPROXY MAINTENANCE §8[§e!§8]\\n§7The Proxy will " + warnVerb + " in §e30 minutes§7 for maintenance.\\n§7Please wrap up your work soon.\\n\"}");
                    saveMaintenanceState(state);
                } else if (minsLeft <= 15 && minsLeft > 5 && !state.warned15m) {
                    state.warned15m = true;
                    pterodactylService.sendCommand("tellraw @a {\"text\":\"\\n§8[§6!§8] §c§lPROXY MAINTENANCE §8[§6!§8]\\n§7The Proxy will " + warnVerb + " in §615 minutes§7 for maintenance!\\n\"}");
                    saveMaintenanceState(state);
                } else if (minsLeft <= 5 && minsLeft > 0 && !state.warned5m) {
                    state.warned5m = true;
                    pterodactylService.sendCommand("tellraw @a {\"text\":\"\\n§8[§4!§8] §4§lWARNING: PROXY MAINTENANCE §8[§4!§8]\\n§cThe Proxy will " + warnVerb + " in §45 minutes§c!\\n§cPlease disconnect safely as soon as possible.\\n\"}");
                    saveMaintenanceState(state);
                }
            }
        }, 0, 30000);
    }
}
