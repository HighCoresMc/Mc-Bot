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
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class PanelCommand extends ListenerAdapter {
    private final PterodactylService pterodactylService = new PterodactylService();
    private final List<String> consoleBuffer = new ArrayList<>();
    private Timer updateTimer;
    private String activeMessageId = null;
    private String activeChannelId = null;
    private boolean isKillState = false;

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!event.getName().equals("panel")) return;
        event.deferReply().queue();

        // WEBSOCKET LISTENER
        pterodactylService.connectToConsole(line -> {
            synchronized (consoleBuffer) {
                consoleBuffer.add(line);
                if (consoleBuffer.size() > 30) {
                    consoleBuffer.remove(0);
                }
            }
        });

        // RESOURCES
        JsonObject resources = pterodactylService.getServerResources();
        isKillState = false;
        Container container = buildContainer(resources, isKillState);

        MessageCreateData messageData = new MessageCreateBuilder()
                .setComponents(container)
                .setEmbeds(java.util.Collections.emptyList())
                .useComponentsV2(true)
                .build();

        event.getHook().sendMessage(messageData).queue(message -> {
            activeMessageId = message.getId();
            activeChannelId = message.getChannel().getId();
            
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
                            JsonObject currentResources = pterodactylService.getServerResources();
                            Container updatedContainer = buildContainer(currentResources, isKillState);
                            
                            MessageEditData editData = new MessageEditBuilder()
                                    .setComponents(updatedContainer)
                                    .setEmbeds(java.util.Collections.emptyList())
                                    .useComponentsV2(true)
                                    .build();

                            event.getJDA().getTextChannelById(activeChannelId)
                                    .editMessageById(activeMessageId, editData)
                                    .queue(null, error -> {
                                    });
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

        event.deferEdit().queue();

        if (id.equals("ptdl_start")) {
            isKillState = false;
            pterodactylService.sendPowerSignal("start");
        } else if (id.equals("ptdl_restart")) {
            isKillState = false;
            pterodactylService.sendPowerSignal("restart");
        } else if (id.equals("ptdl_stop")) {
            isKillState = true;
            pterodactylService.sendPowerSignal("stop");
            
            // UI UPDATE
            try {
                JsonObject currentResources = pterodactylService.getServerResources();
                Container updatedContainer = buildContainer(currentResources, isKillState);
                
                MessageEditData editData = new MessageEditBuilder()
                        .setComponents(updatedContainer)
                        .setEmbeds(java.util.Collections.emptyList())
                        .useComponentsV2(true)
                        .build();

                event.getHook().editMessageById(event.getMessageId(), editData).queue();
            } catch (Exception e) {
            }
            return;
        } else if (id.equals("ptdl_kill")) {
            isKillState = false;
            pterodactylService.sendPowerSignal("kill");
        }

        // UI UPDATE
        try {
            JsonObject currentResources = pterodactylService.getServerResources();
            Container updatedContainer = buildContainer(currentResources, isKillState);
            
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
    public void onModalInteraction(@NotNull ModalInteractionEvent event) {
        if (event.getModalId().equals("ptdl_modal")) {
            String command = event.getValue("command").getAsString();
            pterodactylService.sendCommand(command);
            event.reply("Command sent: `" + command + "`").setEphemeral(true).queue();
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
        StringBuilder consoleText = new StringBuilder("```log\n");
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
        ).withAccentColor(Color.decode("#2F3136"));
    }
    
    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "i";
        return String.format("%.2f %sB", bytes / Math.pow(1024, exp), pre);
    }
}
