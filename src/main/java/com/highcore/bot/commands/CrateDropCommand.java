// PACKAGE
package com.highcore.bot.commands;

// IMPORTS
import com.highcore.bot.LeonTrotskyBot;
import com.highcore.bot.services.RewardService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
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
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.modals.Modal;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;

// CLASS DECLARATION
public class CrateDropCommand extends ListenerAdapter {
    // CONSTANTS
    private static final Logger logger = LoggerFactory.getLogger(CrateDropCommand.class);
    private static final String REQUIRED_ROLE_ID = "1487195247430602852";
    private static final Map<String, CrateChallenge> activeChallenges = new ConcurrentHashMap<>();
    private static final Map<String, CustomWizardState> wizardStates = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    // INSTANCE VARIABLES
    private static net.dv8tion.jda.api.JDA jdaInstance;

    // INITIALIZATION
    static {
        initializeDatabase();
    }

    private static void initializeDatabase() {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS drop_config (" +
                    "id INT PRIMARY KEY, " +
                    "enabled BOOLEAN DEFAULT TRUE, " +
                    "interval_type VARCHAR(20) DEFAULT 'daily', " +
                    "frequency INT DEFAULT 2, " +
                    "target_channel_id VARCHAR(64), " +
                    "last_drop_at BIGINT DEFAULT 0, " +
                    "next_drop_at BIGINT DEFAULT 0, " +
                    "timezone VARCHAR(50) DEFAULT 'UTC', " +
                    "min_join_days INT DEFAULT 3, " +
                    "require_linked_account BOOLEAN DEFAULT TRUE, " +
                    "max_daily_wins INT DEFAULT 1, " +
                    "updated_by VARCHAR(64), " +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP" +
                    ")");

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS drop_history (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "drop_id VARCHAR(64), " +
                    "session_id VARCHAR(64), " +
                    "level VARCHAR(20), " +
                    "prize_type VARCHAR(50), " +
                    "prize_display VARCHAR(100), " +
                    "reward_command VARCHAR(255), " +
                    "winner_discord_id VARCHAR(64) NULL, " +
                    "winner_minecraft_uuid VARCHAR(64) NULL, " +
                    "winner_minecraft_name VARCHAR(100) NULL, " +
                    "channel_id VARCHAR(64), " +
                    "message_id VARCHAR(64), " +
                    "status VARCHAR(50), " +
                    "attempts_count INT DEFAULT 0, " +
                    "failed_reason VARCHAR(255) NULL, " +
                    "solve_time_ms BIGINT NULL, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "completed_at TIMESTAMP NULL" +
                    ")");

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS drop_attempts (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "drop_id VARCHAR(64), " +
                    "user_id VARCHAR(64), " +
                    "minecraft_uuid VARCHAR(64), " +
                    "status VARCHAR(50), " +
                    "started_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "expires_at TIMESTAMP NULL, " +
                    "solve_time_ms BIGINT DEFAULT 0, " +
                    "wrong_answers INT DEFAULT 0" +
                    ")");

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS drop_blacklist (" +
                    "discord_id VARCHAR(64) PRIMARY KEY, " +
                    "added_by VARCHAR(64), " +
                    "reason TEXT, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")");

            stmt.executeUpdate("INSERT IGNORE INTO drop_config (id, enabled, interval_type, frequency, target_channel_id, last_drop_at, next_drop_at, timezone, min_join_days, require_linked_account, max_daily_wins, updated_by) " +
                    "VALUES (1, TRUE, 'daily', 2, '1487139736748425236', 0, 0, 'UTC', 3, TRUE, 1, 'SYSTEM')");

            stmt.executeUpdate("UPDATE drop_history SET status = 'CANCELLED' WHERE status IN ('SPAWNED', 'LOCKED')");

        } catch (Exception e) {
            logger.error("Failed to initialize drop tables", e);
        }
    }

    // SCHEDULER METHODS
    public static void startScheduler(net.dv8tion.jda.api.JDA jda) {
        jdaInstance = jda;
        scheduler.scheduleAtFixedRate(() -> {
            try {
                checkAndTriggerDrop();
            } catch (Exception e) {
                logger.error("Error in drop scheduler task", e);
            }
        }, 0, 1, TimeUnit.MINUTES);
    }

    private static void checkAndTriggerDrop() {
        if (jdaInstance == null) return;
        
        boolean enabled = false;
        String intervalType = "daily";
        int frequency = 2;
        String channelId = null;
        long nextDropAt = 0;

        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String query = "SELECT enabled, interval_type, frequency, target_channel_id, next_drop_at FROM drop_config WHERE id = 1";
            try (PreparedStatement ps = conn.prepareStatement(query);
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    enabled = rs.getBoolean("enabled");
                    intervalType = rs.getString("interval_type");
                    frequency = rs.getInt("frequency");
                    channelId = rs.getString("target_channel_id");
                    nextDropAt = rs.getLong("next_drop_at");
                }
            }
        } catch (Exception e) {
            logger.error("Failed to fetch drop config", e);
            return;
        }

        if (!enabled || channelId == null || channelId.isEmpty()) return;

        long now = System.currentTimeMillis();

        if (nextDropAt == 0) {
            long newNext = calculateNextDropTime(intervalType, frequency);
            updateNextDropTime(newNext);
            return;
        }

        if (now >= nextDropAt) {
            triggerAutomaticDrop(channelId);
            long newNext = calculateNextDropTime(intervalType, frequency);
            updateNextDropTime(newNext);
        }
    }

    private static long calculateNextDropTime(String intervalType, int frequency) {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneId.systemDefault());
        long nowMs = System.currentTimeMillis();
        long nextTime = 0;

        if ("daily".equalsIgnoreCase(intervalType)) {
            java.time.ZonedDateTime startOfToday = now.toLocalDate().atStartOfDay(now.getZone());
            long startMs = startOfToday.toInstant().toEpochMilli();
            long windowSize = (24 * 60 * 60 * 1000L) / frequency;
            for (int i = 0; i < frequency; i++) {
                long windowEnd = startMs + (i + 1) * windowSize;
                if (windowEnd > nowMs) {
                    long windowStart = Math.max(nowMs, startMs + i * windowSize);
                    nextTime = windowStart + (long) (Math.random() * (windowEnd - windowStart));
                    break;
                }
            }
            if (nextTime == 0) {
                java.time.ZonedDateTime startOfTomorrow = startOfToday.plusDays(1);
                long tomorrowStartMs = startOfTomorrow.toInstant().toEpochMilli();
                nextTime = tomorrowStartMs + (long) (Math.random() * windowSize);
            }
        } else if ("weekly".equalsIgnoreCase(intervalType)) {
            java.time.ZonedDateTime startOfWeek = now.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).toLocalDate().atStartOfDay(now.getZone());
            long startMs = startOfWeek.toInstant().toEpochMilli();
            long windowSize = (7 * 24 * 60 * 60 * 1000L) / frequency;
            for (int i = 0; i < frequency; i++) {
                long windowEnd = startMs + (i + 1) * windowSize;
                if (windowEnd > nowMs) {
                    long windowStart = Math.max(nowMs, startMs + i * windowSize);
                    nextTime = windowStart + (long) (Math.random() * (windowEnd - windowStart));
                    break;
                }
            }
            if (nextTime == 0) {
                java.time.ZonedDateTime startOfNextWeek = startOfWeek.plusWeeks(1);
                long nextWeekStartMs = startOfNextWeek.toInstant().toEpochMilli();
                nextTime = nextWeekStartMs + (long) (Math.random() * windowSize);
            }
        } else {
            java.time.ZonedDateTime startOfMonth = now.with(java.time.temporal.TemporalAdjusters.firstDayOfMonth()).toLocalDate().atStartOfDay(now.getZone());
            long startMs = startOfMonth.toInstant().toEpochMilli();
            long windowSize = (30 * 24 * 60 * 60 * 1000L) / frequency;
            for (int i = 0; i < frequency; i++) {
                long windowEnd = startMs + (i + 1) * windowSize;
                if (windowEnd > nowMs) {
                    long windowStart = Math.max(nowMs, startMs + i * windowSize);
                    nextTime = windowStart + (long) (Math.random() * (windowEnd - windowStart));
                    break;
                }
            }
            if (nextTime == 0) {
                java.time.ZonedDateTime startOfNextMonth = startOfMonth.plusMonths(1);
                long nextMonthStartMs = startOfNextMonth.toInstant().toEpochMilli();
                nextTime = nextMonthStartMs + (long) (Math.random() * windowSize);
            }
        }
        return nextTime;
    }

    private static void updateNextDropTime(long nextTime) {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String query = "UPDATE drop_config SET next_drop_at = ? WHERE id = 1";
            try (PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setLong(1, nextTime);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            logger.error("Failed to update next drop time", e);
        }
    }

    private static void triggerAutomaticDrop(String channelId) {
        TextChannel channel = jdaInstance.getTextChannelById(channelId);
        if (channel == null) return;

        double roll = Math.random();
        String level;
        if (roll < 0.40) level = "SIMPLE";
        else if (roll < 0.70) level = "RARE";
        else if (roll < 0.90) level = "EPIC";
        else level = "NETHERITE";

        Loot loot = selectRandomLootStatic(level);
        spawnCrateDrop(channel, level, loot);
    }

    // SLASH COMMAND EVENT
    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!event.getName().equals("drop")) return;

        Member member = event.getMember();
        if (member == null || (!member.hasPermission(Permission.ADMINISTRATOR) && !hasRole(member, REQUIRED_ROLE_ID))) {
            event.reply("ليس لديك صلاحية لاستخدام لوحة تحكم الدروبات!").setEphemeral(true).queue();
            return;
        }

        event.deferReply().queue(hook -> {
            sendControlPanel(hook);
        });
    }

    // BUTTON INTERACTION EVENT
    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        String id = event.getComponentId();

        if (id.startsWith("drop_claim_")) {
            int historyId = Integer.parseInt(id.replace("drop_claim_", ""));
            handleClaimClick(event, historyId);
            return;
        }

        if (id.startsWith("drop_hack_")) {
            int historyId = Integer.parseInt(id.replace("drop_hack_", ""));
            handleHackClick(event, historyId);
            return;
        }

        Member member = event.getMember();
        if (member == null || (!member.hasPermission(Permission.ADMINISTRATOR) && !hasRole(member, REQUIRED_ROLE_ID))) {
            event.reply("ليس لديك صلاحية للتحكم باللوحة!").setEphemeral(true).queue();
            return;
        }

        if (id.equals("drop_admin_toggle_pause")) {
            event.deferEdit().queue();
            togglePauseConfig();
            sendControlPanelEdit(event.getHook());
            return;
        }

        if (id.equals("drop_admin_instant_drop")) {
            event.deferReply(true).queue();
            double roll = Math.random();
            String level = roll < 0.40 ? "SIMPLE" : (roll < 0.70 ? "RARE" : (roll < 0.90 ? "EPIC" : "NETHERITE"));
            Loot loot = selectRandomLootStatic(level);
            spawnCrateDrop((TextChannel) event.getChannel(), level, loot);
            event.getHook().sendMessage("تم إطلاق دروب عشوائي عاجل في هذه القناة!").queue();
            return;
        }

        if (id.equals("drop_admin_custom")) {
            event.deferEdit().queue();
            showCustomWizardStep1(event.getHook(), event.getUser().getId());
            return;
        }

        if (id.equals("drop_admin_view_history")) {
            event.deferEdit().queue();
            showDropHistoryView(event.getHook());
            return;
        }

        if (id.equals("drop_admin_back_to_panel")) {
            event.deferEdit().queue();
            wizardStates.remove(event.getUser().getId());
            sendControlPanelEdit(event.getHook());
            return;
        }

        if (id.equals("drop_admin_custom_cancel")) {
            event.deferEdit().queue();
            wizardStates.remove(event.getUser().getId());
            sendControlPanelEdit(event.getHook());
            return;
        }

        if (id.equals("drop_admin_custom_send")) {
            event.deferReply(true).queue();
            CustomWizardState state = wizardStates.get(event.getUser().getId());
            if (state == null || state.level == null || state.channelId == null) {
                event.getHook().sendMessage("انتهت صلاحية جلسة معالج الدروب المخصص.").queue();
                return;
            }
            TextChannel targetChannel = event.getJDA().getTextChannelById(state.channelId);
            if (targetChannel == null) {
                event.getHook().sendMessage("لم يتم العثور على القناة المحددة.").queue();
                return;
            }
            Loot loot = selectRandomLootStatic(state.level);
            spawnCrateDrop(targetChannel, state.level, loot);
            wizardStates.remove(event.getUser().getId());
            event.getHook().sendMessage("تم إطلاق الدروب المخصص بنجاح في القناة!").queue();
            sendControlPanelEdit(event.getHook());
        }
    }

    // SELECT MENU INTERACTION EVENT
    @Override
    public void onStringSelectInteraction(@NotNull StringSelectInteractionEvent event) {
        String id = event.getComponentId();

        Member member = event.getMember();
        if (member == null || (!member.hasPermission(Permission.ADMINISTRATOR) && !hasRole(member, REQUIRED_ROLE_ID))) {
            event.reply("ليس لديك صلاحية للتحكم باللوحة!").setEphemeral(true).queue();
            return;
        }

        if (id.equals("drop_admin_set_interval")) {
            event.deferEdit().queue();
            String interval = event.getValues().get(0);
            updateConfigString("interval_type", interval);
            resetNextDropSchedule();
            sendControlPanelEdit(event.getHook());
            return;
        }

        if (id.equals("drop_admin_set_frequency")) {
            event.deferEdit().queue();
            int freq = Integer.parseInt(event.getValues().get(0));
            updateConfigInt("frequency", freq);
            resetNextDropSchedule();
            sendControlPanelEdit(event.getHook());
            return;
        }

        if (id.equals("drop_admin_custom_level")) {
            event.deferEdit().queue();
            String level = event.getValues().get(0);
            CustomWizardState state = wizardStates.computeIfAbsent(event.getUser().getId(), k -> new CustomWizardState());
            state.level = level;
            showCustomWizardStep2(event.getHook());
        }
    }

    @Override
    public void onEntitySelectInteraction(@NotNull EntitySelectInteractionEvent event) {
        String id = event.getComponentId();

        Member member = event.getMember();
        if (member == null || (!member.hasPermission(Permission.ADMINISTRATOR) && !hasRole(member, REQUIRED_ROLE_ID))) {
            event.reply("ليس لديك صلاحية للتحكم باللوحة!").setEphemeral(true).queue();
            return;
        }

        if (id.equals("drop_admin_set_channel")) {
            event.deferEdit().queue();
            String channelId = event.getValues().get(0).getId();
            updateConfigString("target_channel_id", channelId);
            sendControlPanelEdit(event.getHook());
            return;
        }

        if (id.equals("drop_admin_custom_channel")) {
            event.deferEdit().queue();
            CustomWizardState state = wizardStates.get(event.getUser().getId());
            if (state == null) return;
            state.channelId = event.getValues().get(0).getId();
            showCustomWizardStep3(event.getHook(), state);
        }
    }

    // MODAL INTERACTION EVENT
    @Override
    public void onModalInteraction(@NotNull ModalInteractionEvent event) {
        String id = event.getModalId();
        if (!id.startsWith("drop_modal_")) return;

        int historyId = Integer.parseInt(id.replace("drop_modal_", ""));
        CrateChallenge challenge = activeChallenges.values().stream()
                .filter(c -> event.getMessage() != null && c.messageId.equals(event.getMessage().getId()))
                .findFirst().orElse(null);

        if (challenge == null) {
            event.reply("انتهت صلاحية هذا التحدي بالفعل!").setEphemeral(true).queue();
            return;
        }

        if (!event.getUser().getId().equals(challenge.lockedByUserId)) {
            event.reply("لست أنت من يحاول فك الكريت حالياً.").setEphemeral(true).queue();
            return;
        }

        event.deferEdit().queue();

        boolean allCorrect = true;
        for (int slot : challenge.questionSlots) {
            String value = event.getValue("slot_" + slot) != null ? event.getValue("slot_" + slot).getAsString().trim() : "";
            String correct = challenge.correctAnswers.get(slot);
            if (!value.equalsIgnoreCase(correct)) {
                allCorrect = false;
                break;
            }
        }

        if (allCorrect) {
            challenge.solved = true;
            if (challenge.timeoutTask != null) {
                challenge.timeoutTask.cancel(false);
            }
            startDecodingAnimation((TextChannel) event.getChannel(), challenge.messageId, historyId, challenge);
        } else {
            challenge.wrongAnswersCount++;
            resetCrate((TextChannel) event.getChannel(), challenge.messageId, historyId, challenge, "إدخال رموز خاطئة", "WRONG_ANSWERS");
        }
    }

    // UTILITY METHODS
    private boolean hasRole(Member member, String roleId) {
        return member.getRoles().stream().anyMatch(r -> r.getId().equals(roleId));
    }

    private void sendControlPanel(net.dv8tion.jda.api.interactions.InteractionHook hook) {
        Container container = buildControlPanelContainer();
        hook.sendMessageComponents(container).useComponentsV2(true).queue();
    }

    private void sendControlPanelEdit(net.dv8tion.jda.api.interactions.InteractionHook hook) {
        Container container = buildControlPanelContainer();
        hook.editOriginalComponents(container).useComponentsV2(true).queue();
    }

    private Container buildControlPanelContainer() {
        boolean enabled = true;
        String interval = "daily";
        int frequency = 2;
        String channelId = "Unknown";
        int totalDrops = 0;

        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String q1 = "SELECT enabled, interval_type, frequency, target_channel_id FROM drop_config WHERE id = 1";
            try (PreparedStatement ps = conn.prepareStatement(q1);
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    enabled = rs.getBoolean("enabled");
                    interval = rs.getString("interval_type");
                    frequency = rs.getInt("frequency");
                    channelId = rs.getString("target_channel_id");
                }
            }
            String q2 = "SELECT COUNT(*) FROM drop_history";
            try (PreparedStatement ps = conn.prepareStatement(q2);
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    totalDrops = rs.getInt(1);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to load control panel config", e);
        }

        String intervalAr = interval.equalsIgnoreCase("daily") ? "يومي" : (interval.equalsIgnoreCase("weekly") ? "اسبوعي" : "شهري");
        String statusText = enabled ? "نشط 🟢" : "متوقف ⏸️";
        String channelMention = channelId.equals("Unknown") ? "غير محدد" : "<#" + channelId + ">";

        Button pauseBtn = enabled ? Button.danger("drop_admin_toggle_pause", "⏸️ إيقاف التنزيل التلقائي")
                : Button.success("drop_admin_toggle_pause", "▶️ تفعيل التنزيل التلقائي");

        Button instantBtn = Button.primary("drop_admin_instant_drop", "➕ دروب عشوائي عاجل");
        Button customBtn = Button.secondary("drop_admin_custom", "⚙️ دروب مخصص");
        Button logsBtn = Button.secondary("drop_admin_view_history", "📜 عرض السجل");

        StringSelectMenu intervalMenu = StringSelectMenu.create("drop_admin_set_interval")
                .setPlaceholder("تغيير الفترة (يومي/اسبوعي/شهري)")
                .addOption("يومي", "daily")
                .addOption("اسبوعي", "weekly")
                .addOption("شهري", "monthly")
                .setDefaultValues(interval.toLowerCase())
                .build();

        StringSelectMenu freqMenu = StringSelectMenu.create("drop_admin_set_frequency")
                .setPlaceholder("تغيير عدد المرات في الفترة")
                .addOption("مرة واحدة", "1")
                .addOption("مرتين (2)", "2")
                .addOption("3 مرات", "3")
                .addOption("4 مرات", "4")
                .addOption("5 مرات", "5")
                .setDefaultValues(String.valueOf(frequency))
                .build();

        EntitySelectMenu channelMenu = EntitySelectMenu.create("drop_admin_set_channel", EntitySelectMenu.SelectTarget.CHANNEL)
                .setPlaceholder("تحديد قناة التنزيل التلقائي")
                .setChannelTypes(net.dv8tion.jda.api.entities.channel.ChannelType.TEXT)
                .build();

        return Container.of(
            TextDisplay.of("## 📦 لوحة تحكم نظام الدروبات العشوائية"),
            Separator.createDivider(Separator.Spacing.SMALL),
            TextDisplay.of("**حالة التنزيل التلقائي:** `" + statusText + "`\n" +
                           "**الفترة:** `" + intervalAr + "`\n" +
                           "**التكرار:** `" + frequency + " مرات في الفترة`\n" +
                           "**قناة التنزيل التلقائي:** " + channelMention + "\n" +
                           "**إجمالي الدروبات السابقة:** `" + totalDrops + "`"),
            Separator.createDivider(Separator.Spacing.SMALL),
            ActionRow.of(pauseBtn, instantBtn, customBtn, logsBtn),
            Separator.createDivider(Separator.Spacing.SMALL),
            ActionRow.of(intervalMenu),
            ActionRow.of(freqMenu),
            ActionRow.of(channelMenu)
        );
    }

    private void togglePauseConfig() {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String update = "UPDATE drop_config SET enabled = NOT enabled WHERE id = 1";
            try (PreparedStatement ps = conn.prepareStatement(update)) {
                ps.executeUpdate();
            }
        } catch (Exception e) {
            logger.error("Failed to toggle config enabled state", e);
        }
    }

    private void updateConfigString(String column, String value) {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String update = "UPDATE drop_config SET " + column + " = ? WHERE id = 1";
            try (PreparedStatement ps = conn.prepareStatement(update)) {
                ps.setString(1, value);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            logger.error("Failed to update config string: " + column, e);
        }
    }

    private void updateConfigInt(String column, int value) {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String update = "UPDATE drop_config SET " + column + " = ? WHERE id = 1";
            try (PreparedStatement ps = conn.prepareStatement(update)) {
                ps.setInt(1, value);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            logger.error("Failed to update config int: " + column, e);
        }
    }

    private void resetNextDropSchedule() {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String q = "SELECT interval_type, frequency FROM drop_config WHERE id = 1";
            try (PreparedStatement ps = conn.prepareStatement(q);
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String interval = rs.getString("interval_type");
                    int freq = rs.getInt("frequency");
                    long newNext = calculateNextDropTime(interval, freq);
                    updateNextDropTime(newNext);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to reset drop schedule", e);
        }
    }

    private void showCustomWizardStep1(net.dv8tion.jda.api.interactions.InteractionHook hook, String userId) {
        StringSelectMenu levelMenu = StringSelectMenu.create("drop_admin_custom_level")
                .setPlaceholder("اختر مستوى الصعوبة للدروب المخصص...")
                .addOption("🟢 بسيطة (Simple)", "SIMPLE")
                .addOption("🔵 متوسطة (Medium)", "RARE")
                .addOption("🟣 نادرة (Rare)", "EPIC")
                .addOption("🟠 نذر رايت (Netherite)", "NETHERITE")
                .build();

        Container container = Container.of(
            TextDisplay.of("## ⚙️ معالج دروب مخصص | خطوة 1 من 3"),
            Separator.createDivider(Separator.Spacing.SMALL),
            TextDisplay.of("يرجى اختيار مستوى الصعوبة للدروب المطلوب إطلاقه:"),
            Separator.createDivider(Separator.Spacing.SMALL),
            ActionRow.of(levelMenu),
            Separator.createDivider(Separator.Spacing.SMALL),
            ActionRow.of(Button.danger("drop_admin_custom_cancel", "❌ إلغاء المعالج"))
        );

        hook.editOriginalComponents(container).useComponentsV2(true).queue();
    }

    private void showCustomWizardStep2(net.dv8tion.jda.api.interactions.InteractionHook hook) {
        EntitySelectMenu channelMenu = EntitySelectMenu.create("drop_admin_custom_channel", EntitySelectMenu.SelectTarget.CHANNEL)
                .setPlaceholder("اختر قناة إرسال الدروب...")
                .setChannelTypes(net.dv8tion.jda.api.entities.channel.ChannelType.TEXT)
                .build();

        Container container = Container.of(
            TextDisplay.of("## ⚙️ معالج دروب مخصص | خطوة 2 من 3"),
            Separator.createDivider(Separator.Spacing.SMALL),
            TextDisplay.of("يرجى اختيار قناة الشات التي سيتم إرسال الصندوق إليها:"),
            Separator.createDivider(Separator.Spacing.SMALL),
            ActionRow.of(channelMenu),
            Separator.createDivider(Separator.Spacing.SMALL),
            ActionRow.of(Button.danger("drop_admin_custom_cancel", "❌ إلغاء المعالج"))
        );

        hook.editOriginalComponents(container).useComponentsV2(true).queue();
    }

    private void showCustomWizardStep3(net.dv8tion.jda.api.interactions.InteractionHook hook, CustomWizardState state) {
        String levelAr = getLevelText(state.level);

        Container container = Container.of(
            TextDisplay.of("## ⚙️ معالج دروب مخصص | خطوة 3 من 3"),
            Separator.createDivider(Separator.Spacing.SMALL),
            TextDisplay.of("تأكيد تفاصيل الدروب المخصص:\n\n" +
                           "**مستوى الصعوبة:** `" + levelAr + "`\n" +
                           "**قناة الإرسال:** <#" + state.channelId + ">"),
            Separator.createDivider(Separator.Spacing.SMALL),
            ActionRow.of(
                Button.success("drop_admin_custom_send", "🚀 إطلاق الدروب"),
                Button.danger("drop_admin_custom_cancel", "❌ إلغاء")
            )
        );

        hook.editOriginalComponents(container).useComponentsV2(true).queue();
    }

    private void showDropHistoryView(net.dv8tion.jda.api.interactions.InteractionHook hook) {
        StringBuilder sb = new StringBuilder();
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String query = "SELECT level, prize_display, winner_minecraft_name, status, created_at FROM drop_history ORDER BY id DESC LIMIT 5";
            try (PreparedStatement ps = conn.prepareStatement(query);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String lvl = rs.getString("level");
                    String prize = rs.getString("prize_display");
                    String name = rs.getString("winner_minecraft_name");
                    String status = rs.getString("status");
                    String date = rs.getString("created_at");
                    
                    String statusEmoji = status.equals("SOLVED") ? "✅" : (status.equals("FAILED") ? "❌" : "⏳");
                    String winner = name != null ? name : "لا يوجد";
                    sb.append(String.format("%s `%s` | الجائزة: `%s` | الفائز: `%s` | الوقت: `%s`\n", statusEmoji, lvl, prize, winner, date));
                }
            }
        } catch (Exception e) {
            logger.error("Failed to load drop history", e);
        }

        if (sb.length() == 0) {
            sb.append("لا توجد سجلات سابقة للدروبات.");
        }

        Container container = Container.of(
            TextDisplay.of("## 📜 سجل الدروبات الأخيرة"),
            Separator.createDivider(Separator.Spacing.SMALL),
            TextDisplay.of(sb.toString()),
            Separator.createDivider(Separator.Spacing.SMALL),
            ActionRow.of(Button.secondary("drop_admin_back_to_panel", "🔙 العودة للوحة التحكم"))
        );

        hook.editOriginalComponents(container).useComponentsV2(true).queue();
    }

    private static Loot selectRandomLootStatic(String level) {
        java.util.Random rand = new java.util.Random();
        if ("SIMPLE".equalsIgnoreCase(level)) {
            int pick = rand.nextInt(3);
            if (pick == 0) return new Loot("500 CMI 💵", "cmi money add %player% 500");
            else if (pick == 1) return new Loot("15 Tokens 🌀", "points give %player% 15");
            else return new Loot("100 Claim Blocks 🧱", "adjustbonusclaimblocks %player% 100");
        } else if ("RARE".equalsIgnoreCase(level)) {
            int pick = rand.nextInt(3);
            if (pick == 0) return new Loot("1,500 CMI 💵", "cmi money add %player% 1500");
            else if (pick == 1) return new Loot("30 Tokens 🌀", "points give %player% 30");
            else return new Loot("Vote Key 🔑", "crate key give %player% vote 1");
        } else if ("EPIC".equalsIgnoreCase(level)) {
            int pick = rand.nextInt(3);
            if (pick == 0) return new Loot("Rare Key 🔑", "crate key give %player% rare 1");
            else if (pick == 1) return new Loot("500 Claim Blocks 🧱", "adjustbonusclaimblocks %player% 500");
            else return new Loot("Temporary Epic Rank 7 Days 🏆", "lp user %player% parent addtemp epic 7d");
        } else {
            int pick = rand.nextInt(7);
            if (pick == 0) return new Loot("Netherite Upgrade Template 🌋", "cmi give %player% netherite_upgrade_template 1");
            else if (pick == 1) return new Loot("Netherite Ingot 👑", "cmi give %player% netherite_ingot 1");
            else if (pick == 2) return new Loot("Netherite Helmet 🪖", "cmi give %player% netherite_helmet 1");
            else if (pick == 3) return new Loot("Netherite Chestplate 👕", "cmi give %player% netherite_chestplate 1");
            else if (pick == 4) return new Loot("Netherite Leggings 👖", "cmi give %player% netherite_leggings 1");
            else if (pick == 5) return new Loot("Netherite Boots 🥾", "cmi give %player% netherite_boots 1");
            else return new Loot("Legendary Key 🔑", "crate key give %player% legendary 1");
        }
    }

    private static void spawnCrateDrop(TextChannel channel, String level, Loot loot) {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String insert = "INSERT INTO drop_history (level, prize_type, prize_display, reward_command, channel_id, status) VALUES (?, ?, ?, ?, ?, 'SPAWNED')";
            try (PreparedStatement ps = conn.prepareStatement(insert, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, level);
                ps.setString(2, "ITEM");
                ps.setString(3, loot.prizeDisplay);
                ps.setString(4, loot.command);
                ps.setString(5, channel.getId());
                ps.executeUpdate();

                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        int historyId = rs.getInt(1);
                        String levelText = getLevelText(level);

                        Container claimContainer = Container.of(
                            TextDisplay.of("## 🌟 ───────── 📦 ظُهُور صُنْدُوق مُشَفَّر ───────── 🌟"),
                            Separator.createDivider(Separator.Spacing.SMALL),
                            TextDisplay.of("> 🏆 **الـجَـائِـزَة:** `" + loot.prizeDisplay + "`\n\n" +
                                           "> ⚡ **الـمُـسْـتَـوَى:** `" + levelText + "`\n\n" +
                                           "> 🟢 **الـحَـالَـة:** `بانتظار المتحدي الأول`"),
                            Separator.createDivider(Separator.Spacing.SMALL),
                            ActionRow.of(Button.primary("drop_claim_" + historyId, "🔓 فك الكريت"))
                        );

                        channel.sendMessageComponents(claimContainer).useComponentsV2(true).queue(msg -> {
                            updateHistoryMessage(historyId, msg.getId());
                            
                            CrateChallenge challenge = new CrateChallenge();
                            challenge.messageId = msg.getId();
                            challenge.channelId = channel.getId();
                            challenge.level = level;
                            challenge.prize = loot.prizeDisplay;
                            challenge.command = loot.command;
                            challenge.lockedByUserId = null;
                            challenge.lockedUntil = 0;
                            
                            activeChallenges.put(msg.getId(), challenge);
                        });
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to spawn crate drop in database", e);
        }
    }

    private static void updateHistoryMessage(int id, String messageId) {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String query = "UPDATE drop_history SET message_id = ? WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setString(1, messageId);
                ps.setInt(2, id);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            logger.error("Failed to update drop history message ID", e);
        }
    }

    private static String getLevelText(String level) {
        if ("SIMPLE".equalsIgnoreCase(level)) return "🟢 بسيطة (2 خانات - 22 ثانية)";
        if ("RARE".equalsIgnoreCase(level)) return "🔵 متوسطة (3 خانات - 20 ثانية)";
        if ("EPIC".equalsIgnoreCase(level)) return "🟣 نادرة (4 خانات - 18 ثانية)";
        return "🟠 نذر رايت / كريت قوي (5 خانات - 15 ثانية)";
    }

    private void handleClaimClick(ButtonInteractionEvent event, int historyId) {
        String userId = event.getUser().getId();
        CrateChallenge challenge = activeChallenges.get(event.getMessageId());

        if (challenge == null) {
            event.reply("هذا التحدي لم يعد متوفراً.").setEphemeral(true).queue();
            return;
        }

        if (challenge.lockedByUserId != null && challenge.lockedUntil > System.currentTimeMillis()) {
            event.reply("❌ يوجد لاعب يحاول فك الكريت الآن. انتظر فشل المحاولة أو انتهاء الوقت.").setEphemeral(true).queue();
            return;
        }

        if (challenge.firstAttemptUserId != null && !challenge.firstAttemptUserId.equals(userId)) {
            event.reply("❌ هذا الصندوق محجوز للاعب الذي بدأ محاولة فكه أولاً ولا يمكن للاعبين الآخرين المشاركة فيه.").setEphemeral(true).queue();
            return;
        }

        event.deferReply(true).queue(hook -> {
            boolean isImmune = false;
            Member member = event.getMember();
            if (member != null) {
                isImmune = member.getRoles().stream().anyMatch(r -> r.getId().equals("1487152572207861870"));
            }

            if (!isImmune) {
                if (isBlacklisted(userId)) {
                    hook.sendMessage("❌ حسابك محظور من المشاركة في نظام الدروبات.").queue();
                    return;
                }

                int dailyWinsLimit = 1;
                try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
                    try (PreparedStatement ps = conn.prepareStatement("SELECT max_daily_wins FROM drop_config WHERE id = 1")) {
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) dailyWinsLimit = rs.getInt("max_daily_wins");
                        }
                    }
                } catch (Exception ignored) {}

                int dailyWins = getDailyWins(userId);
                if (dailyWins >= dailyWinsLimit) {
                    hook.sendMessage("❌ لقد وصلت للحد الأقصى للفوز بالدروبات اليوم وهو " + dailyWinsLimit + ".").queue();
                    return;
                }

                if ("RARE".equalsIgnoreCase(challenge.level) || "EPIC".equalsIgnoreCase(challenge.level) || "NETHERITE".equalsIgnoreCase(challenge.level)) {
                    int weeklyRareWins = getWeeklyRareWins(userId);
                    if (weeklyRareWins >= 2) {
                        hook.sendMessage("❌ لقد وصلت للحد الأقصى للفوز بالجوائز النادرة هذا الأسبوع (حد الفوز: 2).").queue();
                        return;
                    }
                }
            }

            Optional<String> uuidOpt = LeonTrotskyBot.getDiscordSRVManager().getUuidByDiscordId(userId);
            if (uuidOpt.isEmpty()) {
                hook.sendMessage("❌ يجب أن يكون حسابك مربوطاً بالسيرفر للمشاركة!").queue();
                return;
            }

            String uuid = uuidOpt.get();
            if (!isImmune) {
                if (!isPlayerActive(uuid)) {
                    hook.sendMessage("❌ يجب أن تكون قد قمت بتسجيل الدخول إلى خادم ماينكرافت خلال آخر 7 أيام للمشاركة.").queue();
                    return;
                }

                if (member != null) {
                    long daysJoined = Duration.between(member.getTimeJoined(), OffsetDateTime.now()).toDays();
                    if (daysJoined < 3) {
                        hook.sendMessage("❌ يجب أن يكون عمر انضمامك للسيرفر أكثر من 3 أيام للمشاركة!").queue();
                        return;
                    }
                }
            }

            challenge.lockedByUserId = userId;
            if (challenge.firstAttemptUserId == null) {
                challenge.firstAttemptUserId = userId;
            }
            challenge.challengeStartTime = System.currentTimeMillis();
            challenge.grid = generateRandomGrid(challenge.level);
            challenge.questionSlots = selectQuestionSlots(challenge.level);
            
            challenge.correctAnswers = new HashMap<>();
            for (int slot : challenge.questionSlots) {
                challenge.correctAnswers.put(slot, challenge.grid[slot - 1]);
            }

            int solveTime = 15;
            if ("SIMPLE".equalsIgnoreCase(challenge.level)) solveTime = 22;
            else if ("RARE".equalsIgnoreCase(challenge.level)) solveTime = 20;
            else if ("EPIC".equalsIgnoreCase(challenge.level)) solveTime = 18;

            challenge.lockedUntil = System.currentTimeMillis() + 5000 + (solveTime * 1000L) + 2000;
            challenge.isSolving = true;

            hook.sendMessage("بدأت محاولة فك الكريت! تذكر الأرقام المعروضة في الشات العام الآن.").queue();

            updateHistoryOnLock(historyId, userId, uuid);

            String levelText = getLevelText(challenge.level);
            long memEnd = (System.currentTimeMillis() + 5000) / 1000;
            Container memContainer = Container.of(
                TextDisplay.of("## 🔐 ───────── 💾 جَارِي فِكِ التَّشْفِير ───────── 🔐"),
                Separator.createDivider(Separator.Spacing.SMALL),
                TextDisplay.of("> 👤 **الـمُـتَـحَدِّي:** <@" + userId + ">\n\n" +
                               "> 🏆 **الـجَـائِـزَة:** `" + challenge.prize + "`\n\n" +
                               "> ⚡ **الـمُـسْـتَـوَى:** `" + levelText + "`"),
                Separator.createDivider(Separator.Spacing.SMALL),
                TextDisplay.of("### ⏱️ احفظ الرموز التالية قبل اختفائها:\n" +
                               "```\n" +
                               "  " + challenge.grid[0] + "   " + challenge.grid[1] + "   " + challenge.grid[2] + "\n" +
                               "  " + challenge.grid[3] + "   " + challenge.grid[4] + "   " + challenge.grid[5] + "\n" +
                               "  " + challenge.grid[6] + "   " + challenge.grid[7] + "   " + challenge.grid[8] + "\n" +
                               "```\n" +
                               "⏱️ **تختفي الرموز:** <t:" + memEnd + ":R>")
            );

            event.getMessage().editMessage(new net.dv8tion.jda.api.utils.messages.MessageEditBuilder()
                    .setComponents(memContainer)
                    .useComponentsV2(true)
                    .build())
                    .queue(null, e -> {});

            final int finalSolveTime = solveTime;
            scheduler.schedule(() -> {
                try {
                    if (challenge.solved || challenge.failedReason != null) return;

                    long solveEnd = (System.currentTimeMillis() + (finalSolveTime * 1000L)) / 1000;
                    Container solveContainer = Container.of(
                        TextDisplay.of("## 💻 ───────── 🛠️ اخْتِرِ الـرُّمُوزَ الـقَدِيمَة ───────── 💻"),
                        Separator.createDivider(Separator.Spacing.SMALL),
                        TextDisplay.of("> 👤 **الـمُـتَـحَدِّي:** <@" + userId + ">\n\n" +
                                       "> 🏆 **الـجَـائِـزَة:** `" + challenge.prize + "`\n\n" +
                                       "> ⚡ **الـمُـسْـتَـوَى:** `" + levelText + "`"),
                        Separator.createDivider(Separator.Spacing.SMALL),
                        TextDisplay.of("### 🔢 حدد مواقع الرموز القديمة بالترتيب:\n" +
                                       "```\n" +
                                       "  [1]  [2]  [3]\n" +
                                       "  [4]  [5]  [6]\n" +
                                       "  [7]  [8]  [9]\n" +
                                       "```\n" +
                                       "⏳ **ينتهي الوقت المتاح للحل:** <t:" + solveEnd + ":R>"),
                        Separator.createDivider(Separator.Spacing.MEDIUM),
                        ActionRow.of(Button.success("drop_hack_" + historyId, "💻 بدء التهكير"))
                    );

                    event.getMessage().editMessage(new net.dv8tion.jda.api.utils.messages.MessageEditBuilder()
                            .setComponents(solveContainer)
                            .useComponentsV2(true)
                            .build())
                            .queue(null, e -> {});

                    challenge.timeoutTask = scheduler.schedule(() -> {
                        try {
                            if (!challenge.solved && challenge.isSolving) {
                                resetCrate((TextChannel) event.getChannel(), challenge.messageId, historyId, challenge, "نفاد الوقت المخصص للحل", "TIMEOUT");
                            }
                        } catch (Exception ex) {
                            logger.error("Error in timeout execution", ex);
                        }
                    }, finalSolveTime, TimeUnit.SECONDS);

                } catch (Exception ex) {
                    logger.error("Error transitioning to solve stage", ex);
                }
            }, 5, TimeUnit.SECONDS);
        });
    }

    private void handleHackClick(ButtonInteractionEvent event, int historyId) {
        CrateChallenge challenge = activeChallenges.get(event.getMessageId());
        if (challenge == null) {
            event.reply("هذا التحدي انتهى.").setEphemeral(true).queue();
            return;
        }

        if (!event.getUser().getId().equals(challenge.lockedByUserId)) {
            event.reply("لست أنت من يحاول فك الكريت حالياً.").setEphemeral(true).queue();
            return;
        }

        Modal.Builder modalBuilder = Modal.create("drop_modal_" + historyId, "تهكير الصندوق المشفر");
        for (int slot : challenge.questionSlots) {
            TextInput input = TextInput.create("slot_" + slot, TextInputStyle.SHORT)
                    .setPlaceholder("اكتب الرمز القديم هنا...")
                    .setRequired(true)
                    .build();
            modalBuilder.addComponents(Label.of("الخانة " + slot + " (الرمز القديم):", input));
        }

        event.replyModal(modalBuilder.build()).queue();
    }

    private boolean isBlacklisted(String discordId) {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String query = "SELECT 1 FROM drop_blacklist WHERE discord_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setString(1, discordId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (Exception e) {
            logger.error("Failed to check blacklist for user " + discordId, e);
        }
        return false;
    }

    private int getDailyWins(String discordId) {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String query = "SELECT COUNT(*) FROM drop_history WHERE winner_discord_id = ? AND status = 'SOLVED' AND created_at >= CURDATE()";
            try (PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setString(1, discordId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getInt(1);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to get daily wins for user " + discordId, e);
        }
        return 0;
    }

    private int getWeeklyRareWins(String discordId) {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String query = "SELECT COUNT(*) FROM drop_history WHERE winner_discord_id = ? AND status = 'SOLVED' AND level IN ('RARE', 'EPIC', 'NETHERITE') AND created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)";
            try (PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setString(1, discordId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getInt(1);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to get weekly rare wins for user " + discordId, e);
        }
        return 0;
    }

    private boolean isPlayerActive(String uuid) {
        String uuidDash = uuid.trim().toLowerCase();
        if (uuidDash.length() == 32 && !uuidDash.contains("-")) {
            uuidDash = uuidDash.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5");
        }
        String uuidNoDash = uuidDash.replace("-", "");

        try {
            Connection conn = LeonTrotskyBot.getDbManager().isCmiPoolReady()
                ? LeonTrotskyBot.getDbManager().getCmiConnection()
                : LeonTrotskyBot.getDbManager().getConnection();
            try (conn) {
                String query = "SELECT LastLoginTime FROM CMI_users WHERE player_uuid = ? OR player_uuid = ?";
                try (PreparedStatement ps = conn.prepareStatement(query)) {
                    ps.setString(1, uuidDash);
                    ps.setString(2, uuidNoDash);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            long lastLogin = rs.getLong("LastLoginTime");
                            return lastLogin >= (System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to check CMI user activity", e);
        }
        return false;
    }

    private String[] generateRandomGrid(String level) {
        String[] grid = new String[9];
        java.util.Random rand = new java.util.Random();
        String pool = "SIMPLE".equalsIgnoreCase(level) ? "0123456789" : "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        for (int i = 0; i < 9; i++) {
            grid[i] = String.valueOf(pool.charAt(rand.nextInt(pool.length())));
        }
        return grid;
    }

    private List<Integer> selectQuestionSlots(String level) {
        List<Integer> all = new ArrayList<>();
        for (int i = 1; i <= 9; i++) all.add(i);
        Collections.shuffle(all);
        int count = 5;
        if ("SIMPLE".equalsIgnoreCase(level)) count = 2;
        else if ("RARE".equalsIgnoreCase(level)) count = 3;
        else if ("EPIC".equalsIgnoreCase(level)) count = 4;
        return new ArrayList<>(all.subList(0, count));
    }

    private void updateHistoryOnLock(int id, String discordId, String uuid) {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String query = "UPDATE drop_history SET status = 'LOCKED', winner_discord_id = ?, winner_minecraft_uuid = ? WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setString(1, discordId);
                ps.setString(2, uuid);
                ps.setInt(3, id);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            logger.error("Failed to update history on lock", e);
        }
    }

    private void updateHistoryOnSuccess(int id, String discordId, String uuid, String mcName, double elapsed) {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String query = "UPDATE drop_history SET winner_discord_id = ?, winner_minecraft_uuid = ?, winner_minecraft_name = ?, solve_time_ms = ?, status = 'SOLVED' WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setString(1, discordId);
                ps.setString(2, uuid);
                ps.setString(3, mcName);
                ps.setLong(4, (long) (elapsed * 1000));
                ps.setInt(5, id);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            logger.error("Failed to update history on success", e);
        }
    }

    private void updateHistoryStatus(int id, String status, String failedReason) {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String query = "UPDATE drop_history SET status = ?, failed_reason = ? WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setString(1, status);
                ps.setString(2, failedReason);
                ps.setInt(3, id);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            logger.error("Failed to update history status to: " + status, e);
        }
    }

    private void startDecodingAnimation(TextChannel channel, String messageId, int historyId, CrateChallenge challenge) {
        challenge.isDecoding = true;
        java.util.concurrent.atomic.AtomicInteger progress = new java.util.concurrent.atomic.AtomicInteger(0);
        
        java.util.concurrent.ScheduledFuture<?>[] animTask = new java.util.concurrent.ScheduledFuture<?>[1];
        animTask[0] = scheduler.scheduleAtFixedRate(() -> {
            try {
                int p = progress.getAndAdd(20);
                if (p >= 100) {
                    animTask[0].cancel(false);
                    
                    double elapsed = (System.currentTimeMillis() - challenge.challengeStartTime) / 1000.0;
                    String mcName = getMcName(challenge.lockedByUserId, "Player");
                    String uuid = getUuid(challenge.lockedByUserId);

                    updateHistoryOnSuccess(historyId, challenge.lockedByUserId, uuid, mcName, elapsed);

                    String commandToRun = challenge.command.replace("%player%", mcName);
                    RewardService.queueReward(historyId, commandToRun, challenge.lockedByUserId, mcName, challenge.prize);

                    String levelText = getLevelText(challenge.level);
                    Container successContainer = Container.of(
                        TextDisplay.of("## 🎉 ───────── 🔓 تَمَّ فَتْحُ الصُّنْدُوقِ بِنَجَاح ───────── 🎉"),
                        Separator.createDivider(Separator.Spacing.SMALL),
                        TextDisplay.of("> 👤 **الـفَـائِـز:** <@" + challenge.lockedByUserId + ">\n\n" +
                                       "> 🏆 **الـجَـائِـزَة:** `" + challenge.prize + "`\n\n" +
                                       "> ⚡ **الـمُـسْـتَـوَى:** `" + levelText + "`\n\n" +
                                       "> ⏱️ **الـوَقْـتُ الـمُـسْتَغْرَق:** `" + String.format(Locale.US, "%.1f", elapsed) + "s` ⚡")
                    );

                    channel.editMessageById(messageId, new net.dv8tion.jda.api.utils.messages.MessageEditBuilder()
                            .setComponents(successContainer)
                            .useComponentsV2(true)
                            .build())
                            .queue(null, e -> {});

                    activeChallenges.remove(messageId);
                    return;
                }

                String bar = "██████████ 100%";
                if (p == 0) bar = "██░░░░░░░░ 20%";
                else if (p == 20) bar = "████░░░░░░ 40%";
                else if (p == 40) bar = "██████░░░░ 60%";
                else if (p == 60) bar = "████████░░ 80%";

                Container decodingContainer = Container.of(
                    TextDisplay.of("## ⏳ ───────── 🛠️ جَارِي فَكُّ التَّشْفِير ───────── ⏳"),
                    Separator.createDivider(Separator.Spacing.SMALL),
                    TextDisplay.of("> 👤 **الـمُـتَـحَدِّي:** <@" + challenge.lockedByUserId + ">\n\n" +
                                   "> 🏆 **الـجَـائِـزَة:** `" + challenge.prize + "`"),
                    Separator.createDivider(Separator.Spacing.SMALL),
                    TextDisplay.of("### " + bar)
                );

                channel.editMessageById(messageId, new net.dv8tion.jda.api.utils.messages.MessageEditBuilder()
                        .setComponents(decodingContainer)
                        .useComponentsV2(true)
                        .build())
                        .queue(null, e -> {});

            } catch (Exception e) {
                logger.error("Error in animation task", e);
            }
        }, 0, 4, TimeUnit.SECONDS);
    }

    private void resetCrate(TextChannel channel, String messageId, int historyId, CrateChallenge challenge, String reason, String details) {
        challenge.isSolving = false;
        challenge.failedReason = reason;

        String uuid = getUuid(challenge.lockedByUserId);
        recordAttempt(historyId, challenge.lockedByUserId, uuid, details, System.currentTimeMillis() - challenge.challengeStartTime);
        updateHistoryStatus(historyId, details, reason);

        Container failureContainer = Container.of(
            TextDisplay.of("## ❌ ───────── 🔒 فَشَلَ فَتْحُ الصُّنْدُوق ───────── ❌"),
            Separator.createDivider(Separator.Spacing.SMALL),
            TextDisplay.of("> 👤 **الـمُـتَـحَدِّي:** <@" + challenge.lockedByUserId + ">\n\n" +
                           "> 🏆 **الـجَـائِـزَة:** `" + challenge.prize + "`\n\n" +
                           "> ⚠️ **الـسَّـبَـب:** `" + reason + "`\n\n" +
                           "🔄 تم الاحتفاظ بالصندوق للمتحدي الأول فقط لإعادة المحاولة.")
        );

        channel.editMessageById(messageId, new net.dv8tion.jda.api.utils.messages.MessageEditBuilder()
                .setComponents(failureContainer)
                .useComponentsV2(true)
                .build())
                .queue(null, e -> {});

        scheduler.schedule(() -> {
            try {
                updateHistoryStatus(historyId, "SPAWNED", null);

                challenge.lockedByUserId = null;
                challenge.lockedUntil = 0;
                challenge.grid = null;
                challenge.questionSlots = null;
                challenge.correctAnswers = null;
                challenge.isSolving = false;
                challenge.isDecoding = false;
                challenge.failedReason = null;
                challenge.wrongAnswersCount = 0;

                String levelText = getLevelText(challenge.level);
                String statusText = challenge.firstAttemptUserId == null 
                        ? "بانتظار المتحدي الأول" 
                        : "محجوز لـ <@" + challenge.firstAttemptUserId + ">";

                Container claimContainer = Container.of(
                    TextDisplay.of("## 🌟 ───────── 📦 ظُهُور صُنْدُوق مُشَفَّر ───────── 🌟"),
                    Separator.createDivider(Separator.Spacing.SMALL),
                    TextDisplay.of("> 🏆 **الـجَـائِـزَة:** `" + challenge.prize + "`\n\n" +
                                   "> ⚡ **الـمُـسْـتَـوَى:** `" + levelText + "`\n\n" +
                                   "> 🔒 **الـحَـالَـة:** " + statusText),
                    Separator.createDivider(Separator.Spacing.SMALL),
                    ActionRow.of(Button.primary("drop_claim_" + historyId, "🔓 فك الكريت"))
                );

                channel.editMessageById(messageId, new net.dv8tion.jda.api.utils.messages.MessageEditBuilder()
                        .setComponents(claimContainer)
                        .useComponentsV2(true)
                        .build())
                        .queue(null, e -> {});
            } catch (Exception e) {
                logger.error("Error resetting crate", e);
            }
        }, 5, TimeUnit.SECONDS);
    }

    private void recordAttempt(int historyId, String userId, String uuid, String status, long elapsedMs) {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String query = "INSERT INTO drop_attempts (drop_id, user_id, minecraft_uuid, status, solve_time_ms) VALUES (?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setString(1, String.valueOf(historyId));
                ps.setString(2, userId);
                ps.setString(3, uuid);
                ps.setString(4, status);
                ps.setLong(5, elapsedMs);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            logger.error("Failed to record drop attempt", e);
        }
    }

    private String getUuid(String discordId) {
        return LeonTrotskyBot.getDiscordSRVManager().getUuidByDiscordId(discordId).orElse(null);
    }

    private String getMcName(String discordId, String defaultName) {
        Optional<String> uuidOpt = LeonTrotskyBot.getDiscordSRVManager().getUuidByDiscordId(discordId);
        if (uuidOpt.isEmpty()) return "Unknown";
        String uuid = uuidOpt.get();
        String uuidDash = uuid.trim().toLowerCase();
        if (uuidDash.length() == 32 && !uuidDash.contains("-")) {
            uuidDash = uuidDash.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5");
        }
        String uuidNoDash = uuidDash.replace("-", "");

        String mcName = null;
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String getUsernameQuery = "SELECT username FROM `discordsrv__accounts` WHERE discord = ?";
            try (PreparedStatement psName = conn.prepareStatement(getUsernameQuery)) {
                psName.setString(1, discordId);
                try (ResultSet rsName = psName.executeQuery()) {
                    if (rsName.next()) {
                        mcName = rsName.getString("username");
                    }
                }
            }
        } catch (Exception ignored) {}

        if (mcName != null && !mcName.isEmpty() && !mcName.equalsIgnoreCase("Unknown")) {
            return mcName;
        }

        try {
            Connection conn = LeonTrotskyBot.getDbManager().isCmiPoolReady()
                ? LeonTrotskyBot.getDbManager().getCmiConnection()
                : LeonTrotskyBot.getDbManager().getConnection();
            try (conn) {
                String query = "SELECT username FROM CMI_users WHERE player_uuid = ? OR player_uuid = ? OR username = ? OR username = ?";
                try (PreparedStatement ps = conn.prepareStatement(query)) {
                    ps.setString(1, uuidDash);
                    ps.setString(2, uuidNoDash);
                    ps.setString(3, defaultName);
                    ps.setString(4, defaultName);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return rs.getString("username");
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        return "Unknown";
    }

    // HELPER CLASSES
    public static class Loot {
        public final String prizeDisplay;
        public final String command;
        public Loot(String prizeDisplay, String command) {
            this.prizeDisplay = prizeDisplay;
            this.command = command;
        }
    }

    public static class CustomWizardState {
        public String level;
        public String channelId;
    }

    public static class CrateChallenge {
        public String messageId;
        public String channelId;
        public String level;
        public String prize;
        public String command;
        public String lockedByUserId;
        public long lockedUntil;
        public String[] grid;
        public List<Integer> questionSlots;
        public Map<Integer, String> correctAnswers;
        public long challengeStartTime;
        public boolean isSolving;
        public boolean isDecoding;
        public boolean solved;
        public String failedReason;
        public int wrongAnswersCount;
        public ScheduledFuture<?> timeoutTask;
        public String firstAttemptUserId;
    }
}
