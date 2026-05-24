package com.highcore.bot.commands;

import com.google.gson.JsonObject;
import com.highcore.bot.services.PterodactylService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.modals.Modal;
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

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!event.getName().equals("panel")) return;
        event.deferReply().queue();

        // WEBSOCKET LISTENER
        pterodactylService.connectToConsole(line -> {
            synchronized (consoleBuffer) {
                consoleBuffer.add(line);
                if (consoleBuffer.size() > 20) {
                    consoleBuffer.remove(0);
                }
            }
        });

        // RESOURCES
        JsonObject resources = pterodactylService.getServerResources();
        
        MessageEmbed embed = buildEmbed(resources);
        
        event.getHook().sendMessageEmbeds(embed).addComponents(
                ActionRow.of(
                        Button.success("ptdl_start", "Start"),
                        Button.primary("ptdl_restart", "Restart"),
                        Button.danger("ptdl_stop", "Stop"),
                        Button.secondary("ptdl_cmd", "Send Command")
                )
        ).queue(message -> {
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
                            MessageEmbed updatedEmbed = buildEmbed(currentResources);
                            
                            event.getJDA().getTextChannelById(activeChannelId)
                                    .editMessageEmbedsById(activeMessageId, updatedEmbed)
                                    .queue(null, error -> {
                                    });
                        } catch (Exception e) {
                        }
                    }
                }
            }, 3000, 3000);
        });
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        if (!event.getComponentId().startsWith("ptdl_")) return;

        String id = event.getComponentId();
        
        if (id.equals("ptdl_cmd")) {
            TextInput commandInput = TextInput.create("command", TextInputStyle.SHORT)
                    .setLabel("Command")
                    .setPlaceholder("ex: say hello")
                    .setMinLength(1)
                    .setRequired(true)
                    .build();

            Modal modal = Modal.create("ptdl_modal", "Send Console Command")
                    .addActionRow(commandInput)
                    .build();
            
            event.replyModal(modal).queue();
            return;
        }

        event.deferEdit().queue();

        if (id.equals("ptdl_start")) {
            pterodactylService.sendPowerSignal("start");
        } else if (id.equals("ptdl_restart")) {
            pterodactylService.sendPowerSignal("restart");
        } else if (id.equals("ptdl_stop")) {
            pterodactylService.sendPowerSignal("stop");
            
            // UI UPDATE
            event.getHook().editMessageComponentsById(event.getMessageId(), 
                    ActionRow.of(
                            Button.success("ptdl_start", "Start"),
                            Button.primary("ptdl_restart", "Restart"),
                            Button.danger("ptdl_kill", "Kill"),
                            Button.secondary("ptdl_cmd", "Send Command")
                    )).queue();
        } else if (id.equals("ptdl_kill")) {
            pterodactylService.sendPowerSignal("kill");
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

    private MessageEmbed buildEmbed(JsonObject resources) {
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("Pterodactyl Server Panel");
        embed.setColor(Color.decode("#2B3945"));

        // STATUS VARS
        String state = "Offline \uD83D\uDD34";
        String cpu = "0%";
        String ram = "0 MB";
        String disk = "0 MB";
        String uptimeStr = "0s";

        if (resources != null) {
            String currentState = resources.get("current_state").getAsString();
            if ("running".equals(currentState)) state = "Running \uD83D\uDFE2";
            else if ("starting".equals(currentState)) state = "Starting \uD83D\uDFE1";
            else if ("stopping".equals(currentState)) state = "Stopping \uD83D\uDFE0";

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

        embed.addField("State", state, true);
        embed.addField("CPU", cpu, true);
        embed.addField("RAM", ram, true);
        embed.addField("Disk", disk, true);
        embed.addField("Uptime", uptimeStr, true);

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
        
        embed.setDescription(consoleText.toString());
        return embed.build();
    }
    
    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "i";
        return String.format("%.2f %sB", bytes / Math.pow(1024, exp), pre);
    }
}
