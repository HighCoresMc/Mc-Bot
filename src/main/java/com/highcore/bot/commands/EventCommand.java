package com.highcore.bot.commands;

import com.highcore.bot.LeonTrotskyBot;
import com.highcore.bot.utils.TimeUtils;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.Message.Attachment;
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.thumbnail.Thumbnail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

public class EventCommand extends ListenerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(EventCommand.class);

    private static class PendingEvent {
        String name;
        String type;
        String dateStr;
        String rewards;
        int seats;
        String conditions;
        String customQuestion;
        String staffChannelId;

        PendingEvent(String name, String type, String dateStr, String rewards, int seats, String conditions, String customQuestion, String staffChannelId) {
            this.name = name;
            this.type = type;
            this.dateStr = dateStr;
            this.rewards = rewards;
            this.seats = seats;
            this.conditions = conditions;
            this.customQuestion = customQuestion;
            this.staffChannelId = staffChannelId;
        }
    }

    private static final Map<String, PendingEvent> pendingEvents = new ConcurrentHashMap<>();
    private static final String EVENT_ROLE_ID = "1509885693818699776";
    private static final String HYPE_MANAGER_ID = "1487195247430602852";
    private static final String HYPE_EVENTS_ID = "1487195248059879555";
    private static final String EVENTS_FORUM_ID = "1487142537666760735";

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("event")) return;

        Member member = event.getMember();
        if (member == null || (!member.hasPermission(Permission.ADMINISTRATOR) && !hasRole(member, HYPE_MANAGER_ID))) {
            event.reply("ليس لديك صلاحية لإدارة الفعاليات! (فقط Hype Manager)").setEphemeral(true).queue();
            return;
        }

        Container panelContainer = Container.of(
            TextDisplay.of("## 🛠️ لوحة تحكم الفعاليات"),
            Separator.createDivider(Separator.Spacing.SMALL),
            TextDisplay.of("مرحباً بك في لوحة تحكم إدارة الفعاليات. يرجى اختيار الإجراء المطلوب من الأزرار بالأسفل:"),
            Separator.createDivider(Separator.Spacing.SMALL),
            ActionRow.of(
                Button.success("ev_panel_create", "🎉 فعالية جديدة"),
                Button.primary("ev_panel_history", "📜 سجل الفعاليات").asDisabled(),
                Button.secondary("ev_panel_wins", "🏆 تاريخ الفوز").asDisabled(),
                Button.secondary("ev_panel_pending", "⏳ فعاليات معلقة").asDisabled()
            )
        );

        event.replyComponents(panelContainer).useComponentsV2(true).queue();
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        User user = event.getAuthor();
        if (user.isBot()) return;
        
        PendingEvent pe = pendingEvents.get(user.getId());
        if (pe != null && event.getChannel().getId().equals(pe.staffChannelId)) {
            String imageUrl = null;
            if (!event.getMessage().getAttachments().isEmpty()) {
                imageUrl = event.getMessage().getAttachments().get(0).getUrl();
            } else if (!event.getMessage().getContentRaw().equalsIgnoreCase("skip")) {
                event.getMessage().reply("الرجاء إرسال صورة أو كتابة `skip`.").queue();
                return;
            }

            pendingEvents.remove(user.getId());
            event.getMessage().delete().queue(null, e -> {});
            
            createEventFinal(user, event.getGuild(), pe, imageUrl);
        }
    }

    private void createEventFinal(User creator, Guild guild, PendingEvent pe, String imageUrl) {
        ForumChannel forumChannel = guild.getForumChannelById(EVENTS_FORUM_ID);
        if (forumChannel == null) return;
        
        long unixTime = TimeUtils.parseToUnixTimestamp(pe.dateStr);

        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String insertSql = "INSERT INTO events (name, type, event_date, rewards, max_seats, conditions, requires_link, custom_question, image_url, staff_channel_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, pe.name);
                ps.setString(2, pe.type);
                ps.setString(3, pe.dateStr);
                ps.setString(4, pe.rewards);
                ps.setInt(5, pe.seats);
                ps.setString(6, pe.conditions);
                ps.setBoolean(7, true);
                ps.setString(8, pe.customQuestion);
                ps.setString(9, imageUrl);
                ps.setString(10, pe.staffChannelId);
                ps.executeUpdate();

                ResultSet rs = ps.getGeneratedKeys();
                if (rs.next()) {
                    int eventId = rs.getInt(1);
                    
                    Container publicContainer = getBasePublicContainer(pe.name, pe.type, unixTime, pe.rewards, pe.seats, 0, pe.conditions, "OPEN", imageUrl, eventId);

                    MessageCreateBuilder builder = new MessageCreateBuilder()
                        .setComponents(publicContainer)
                        .useComponentsV2(true);

                    forumChannel.createForumPost("🎉 " + pe.name, builder.build())
                        .queue(forumPost -> {
                            String threadId = forumPost.getThreadChannel().getId();
                            String messageId = forumPost.getMessage().getId();
                            
                            try (Connection c2 = LeonTrotskyBot.getDbManager().getConnection();
                                 PreparedStatement p2 = c2.prepareStatement("UPDATE events SET message_id = ?, channel_id = ? WHERE id = ?")) {
                                p2.setString(1, messageId);
                                p2.setString(2, threadId); 
                                p2.setInt(3, eventId);
                                p2.executeUpdate();
                            } catch (Exception e) {}

                            Container staffContainer = getStaffContainer(pe.name, forumPost.getThreadChannel().getAsMention(), eventId, "OPEN", 0, new ArrayList<>());

                            MessageCreateBuilder staffBuilder = new MessageCreateBuilder()
                                .setComponents(staffContainer)
                                .useComponentsV2(true);
                                
                            guild.getTextChannelById(pe.staffChannelId).sendMessage(staffBuilder.build())
                                .queue(staffMsg -> {
                                    try (Connection c3 = LeonTrotskyBot.getDbManager().getConnection();
                                         PreparedStatement p3 = c3.prepareStatement("UPDATE events SET staff_message_id = ? WHERE id = ?")) {
                                        p3.setString(1, staffMsg.getId());
                                        p3.setInt(2, eventId);
                                        p3.executeUpdate();
                                    } catch (Exception e) {}
                                });
                        });
                }
            }
        } catch (Exception e) {
            logger.error("Error creating event", e);
        }
    }

    private Container getStaffContainer(String name, String mention, int eventId, String status, int participantCount, List<String> participantMentions) {
        String participantsStr = participantMentions.isEmpty() ? "لا يوجد مشاركين" : String.join(", ", participantMentions);
        if (participantsStr.length() > 2000) {
            participantsStr = participantsStr.substring(0, 1996) + "...";
        }
        return Container.of(
            TextDisplay.of("## 🛠️ إدارة الفعالية: " + name),
            Separator.createDivider(Separator.Spacing.SMALL),
            TextDisplay.of("**بوست الفعالية:** " + mention + "\n**Event ID:** `" + eventId + "` | **المشاركين:** `" + participantCount + "`"),
            TextDisplay.of("**أسماء المشاركين:**\n" + participantsStr),
            Separator.createDivider(Separator.Spacing.SMALL),
            ActionRow.of(getStaffButtons(eventId, status))
        );
    }

    private Container getBasePublicContainer(String name, String type, long unixTime, String rewards, int maxSeats, int currentSeats, String conditions, String status, String imageUrl, int eventId) {
        String statusEmoji = "OPEN".equals(status) ? "🟢" : ("CLOSED".equals(status) ? "🔴" : "⚫");
        String statusText = switch (status) {
            case "OPEN" -> "التسجيل مفتوح";
            case "CLOSED" -> "التسجيل مغلق";
            case "FINISHED" -> "انتهت الفعالية";
            case "CANCELLED" -> "ملغية";
            default -> status;
        };
        
        String warning = "⚠️ **تنبيه:** هذه الفعالية تتطلب حساب ماينكرافت مربوط بالديسكورد للتسجيل.\n\n";

        if (imageUrl != null && !imageUrl.isEmpty()) {
            return Container.of(
                Section.of(Thumbnail.fromUrl(imageUrl), TextDisplay.of("## 🎉 فعالية جديدة: " + name)),
                Separator.createDivider(Separator.Spacing.SMALL),
                TextDisplay.of(warning + "### 📋 التفاصيل\n" +
                    "**نوع الفعالية:** `" + type + "`\n" +
                    "**الوقت:** <t:" + unixTime + ":F>\n" +
                    "**المكافآت:** `" + rewards + "`\n" +
                    "**المقاعد المتاحة:** `" + currentSeats + " / " + maxSeats + "`"),
                Separator.createDivider(Separator.Spacing.SMALL),
                TextDisplay.of("### 📝 الشروط\n" + conditions),
                Separator.createDivider(Separator.Spacing.SMALL),
                TextDisplay.of("**Event ID:** `" + eventId + "` | " + statusEmoji + " **الحالة:** `" + statusText + "`"),
                Separator.createDivider(Separator.Spacing.SMALL),
                ActionRow.of(getPublicButtons(eventId, status)),
                Separator.createDivider(Separator.Spacing.SMALL),
                TextDisplay.of("> لو عندك اي استفسار تفضل <#1487143271586074624> ← الفعاليات")
            );
        } else {
            return Container.of(
                TextDisplay.of("## 🎉 فعالية جديدة: " + name),
                Separator.createDivider(Separator.Spacing.SMALL),
                TextDisplay.of(warning + "### 📋 التفاصيل\n" +
                    "**نوع الفعالية:** `" + type + "`\n" +
                    "**الوقت:** <t:" + unixTime + ":F>\n" +
                    "**المكافآت:** `" + rewards + "`\n" +
                    "**المقاعد المتاحة:** `" + currentSeats + " / " + maxSeats + "`"),
                Separator.createDivider(Separator.Spacing.SMALL),
                TextDisplay.of("### 📝 الشروط\n" + conditions),
                Separator.createDivider(Separator.Spacing.SMALL),
                TextDisplay.of("**Event ID:** `" + eventId + "` | " + statusEmoji + " **الحالة:** `" + statusText + "`"),
                Separator.createDivider(Separator.Spacing.SMALL),
                ActionRow.of(getPublicButtons(eventId, status)),
                Separator.createDivider(Separator.Spacing.SMALL),
                TextDisplay.of("> لو عندك اي استفسار تفضل <#1487143271586074624> ← الفعاليات")
            );
        }
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith("ev_")) return;

        if (id.equals("ev_panel_create")) {
            TextInput nameInput = TextInput.create("ev_create_name", TextInputStyle.SHORT)
                .setRequired(true)
                .build();
            TextInput typeInput = TextInput.create("ev_create_type", TextInputStyle.SHORT)
                .setRequired(true)
                .build();
            TextInput dateInput = TextInput.create("ev_create_date", TextInputStyle.SHORT)
                .setPlaceholder("مثال: 2026-06-20 21:00")
                .setRequired(true)
                .build();
            TextInput rewardsSeatsInput = TextInput.create("ev_create_rewards_seats", TextInputStyle.SHORT)
                .setPlaceholder("مثال: رتبة فيب - 50")
                .setRequired(true)
                .build();
            TextInput conditionsInput = TextInput.create("ev_create_conditions", TextInputStyle.PARAGRAPH)
                .setPlaceholder("الشروط، (أضف سؤالاً إضافياً بين قوسين في النهاية إذا أردت)")
                .setRequired(true)
                .build();

            Modal modal = Modal.create("ev_modal_create", "إنشاء فعالية جديدة")
                .addComponents(
                    Label.of("اسم الفعالية", nameInput),
                    Label.of("نوع الفعالية", typeInput),
                    Label.of("تاريخ ووقت الفعالية", dateInput),
                    Label.of("المكافآت - المقاعد", rewardsSeatsInput),
                    Label.of("الشروط (والسؤال الإضافي)", conditionsInput)
                )
                .build();
            
            event.replyModal(modal).queue();
            return;
        }

        if (id.startsWith("ev_reg_")) {
            int eventId = Integer.parseInt(id.replace("ev_reg_", ""));
            handleRegisterClick(event, eventId);
        } else if (id.startsWith("ev_unreg_")) {
            int eventId = Integer.parseInt(id.replace("ev_unreg_", ""));
            handleUnregister(event, eventId);
        } else if (id.startsWith("ev_remind_")) {
            int eventId = Integer.parseInt(id.replace("ev_remind_", ""));
            handleRemindToggle(event, eventId);
        } else if (id.startsWith("ev_view_")) {
            int eventId = Integer.parseInt(id.replace("ev_view_", ""));
            handleViewParticipants(event, eventId);
        } else if (id.startsWith("ev_staff_")) {
            Member member = event.getMember();
            if (member == null || (!member.hasPermission(Permission.ADMINISTRATOR) && !hasRole(member, HYPE_MANAGER_ID) && !hasRole(member, HYPE_EVENTS_ID))) {
                event.reply("ليس لديك صلاحية!").setEphemeral(true).queue();
                return;
            }

            if (id.startsWith("ev_staff_close_")) {
                updateEventStatus(event, Integer.parseInt(id.replace("ev_staff_close_", "")), "CLOSED");
            } else if (id.startsWith("ev_staff_open_")) {
                updateEventStatus(event, Integer.parseInt(id.replace("ev_staff_open_", "")), "OPEN");
            } else if (id.startsWith("ev_staff_cancel_")) {
                updateEventStatus(event, Integer.parseInt(id.replace("ev_staff_cancel_", "")), "CANCELLED");
            } else if (id.startsWith("ev_staff_finish_")) {
                handleFinishEvent(event, Integer.parseInt(id.replace("ev_staff_finish_", "")));
            } else if (id.startsWith("ev_staff_export_")) {
                handleExport(event, Integer.parseInt(id.replace("ev_staff_export_", "")));
            } else if (id.startsWith("ev_staff_notify_")) {
                handleNotify(event, Integer.parseInt(id.replace("ev_staff_notify_", "")));
            }
        }
    }

    private void handleRegisterClick(ButtonInteractionEvent event, int eventId) {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            boolean requiresLink = false;
            String customQuestion = null;
            int maxSeats = 0;
            String status = "OPEN";
            String q1 = "SELECT requires_link, custom_question, max_seats, status FROM events WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(q1)) {
                ps.setInt(1, eventId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    requiresLink = rs.getBoolean("requires_link");
                    customQuestion = rs.getString("custom_question");
                    maxSeats = rs.getInt("max_seats");
                    status = rs.getString("status");
                } else {
                    event.reply("الفعالية غير موجودة!").setEphemeral(true).queue();
                    return;
                }
            }

            if (!"OPEN".equals(status)) {
                event.reply("التسجيل مغلق!").setEphemeral(true).queue();
                return;
            }

            String q2 = "SELECT 1 FROM event_participants WHERE event_id = ? AND user_id = ?";
            try (PreparedStatement ps2 = conn.prepareStatement(q2)) {
                ps2.setInt(1, eventId);
                ps2.setString(2, event.getUser().getId());
                ResultSet rs2 = ps2.executeQuery();
                if (rs2.next()) {
                    event.reply("أنت مسجل بالفعل في هذه الفعالية!").setEphemeral(true).queue();
                    return;
                }
            }

            String q3 = "SELECT COUNT(*) FROM event_participants WHERE event_id = ?";
            try (PreparedStatement ps3 = conn.prepareStatement(q3)) {
                ps3.setInt(1, eventId);
                ResultSet rs3 = ps3.executeQuery();
                if (rs3.next()) {
                    if (rs3.getInt(1) >= maxSeats) {
                        event.reply("اكتمل العدد، لا يوجد مقاعد متاحة!").setEphemeral(true).queue();
                        return;
                    }
                }
            }

            String mcUuid = null;
            String mcName = null;
            if (requiresLink) {
                Optional<String> uuidOpt = LeonTrotskyBot.getDiscordSRVManager().getUuidByDiscordId(event.getUser().getId());
                if (uuidOpt.isEmpty()) {
                    event.reply("هذه الفعالية تتطلب حساب ماينكرافت مربوط بالديسكورد. يرجى ربط حسابك أولاً!").setEphemeral(true).queue();
                    return;
                }
                mcUuid = uuidOpt.get();
                String q4 = "SELECT username FROM discordsrv__accounts WHERE discord = ?";
                try (PreparedStatement ps4 = conn.prepareStatement(q4)) {
                    ps4.setString(1, event.getUser().getId());
                    ResultSet rs4 = ps4.executeQuery();
                    if (rs4.next()) {
                        mcName = rs4.getString("username");
                    }
                }
            }

            if (customQuestion != null && !customQuestion.trim().isEmpty()) {
                TextInput customInput = TextInput.create("custom_answer", TextInputStyle.PARAGRAPH)
                    .setRequired(true)
                    .build();
                
                Modal.Builder modalBuilder = Modal.create("ev_modal_" + eventId, "تسجيل الفعالية");
                if (!requiresLink) {
                    TextInput mcNameInput = TextInput.create("mc_name", TextInputStyle.SHORT)
                        .setRequired(true)
                        .build();
                    modalBuilder.addComponents(Label.of("اسمك في ماينكرافت", mcNameInput), Label.of(customQuestion, customInput));
                } else {
                    modalBuilder.addComponents(Label.of(customQuestion, customInput));
                }

                event.replyModal(modalBuilder.build()).queue();
            } else if (!requiresLink) {
                TextInput mcNameInput = TextInput.create("mc_name", TextInputStyle.SHORT)
                    .setRequired(true)
                    .build();
                Modal modal = Modal.create("ev_modal_" + eventId, "تسجيل الفعالية")
                    .addComponents(Label.of("اسمك في ماينكرافت", mcNameInput))
                    .build();
                event.replyModal(modal).queue();
            } else {
                registerParticipant(event.getUser().getId(), event.getUser().getId(), eventId, mcName, mcUuid, null);
                event.reply("تم تسجيلك بنجاح في الفعالية باسم: " + mcName + "!\nتم التعرف على حسابك المربوط تلقائياً.").setEphemeral(true).queue();
                giveEventRole(event.getGuild(), event.getUser().getId());
                updatePublicEmbedSeats(event.getGuild(), eventId);
                updateStaffEmbed(event.getGuild(), eventId);
            }

        } catch (Exception e) {
            logger.error("Error handling register click", e);
            event.reply("حدث خطأ.").setEphemeral(true).queue();
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (event.getModalId().equals("ev_modal_create")) {
            String name = event.getValue("ev_create_name").getAsString();
            String type = event.getValue("ev_create_type").getAsString();
            String dateStr = event.getValue("ev_create_date").getAsString();
            String rewardsSeats = event.getValue("ev_create_rewards_seats").getAsString();
            String conditions = event.getValue("ev_create_conditions").getAsString();
            
            if (!TimeUtils.isValidFormat(dateStr)) {
                event.reply("صيغة الوقت غير صحيحة! يجب أن تكون YYYY-MM-DD HH:MM (مثال: 2026-06-20 21:00)").setEphemeral(true).queue();
                return;
            }

            String rewards = rewardsSeats;
            int seats = 50;
            if (rewardsSeats.contains("-")) {
                String[] parts = rewardsSeats.split("-");
                rewards = parts[0].trim();
                try {
                    seats = Integer.parseInt(parts[1].trim());
                } catch (Exception ignored) {}
            }

            String customQuestion = null;
            if (conditions.contains("(") && conditions.endsWith(")")) {
                int lastOpen = conditions.lastIndexOf("(");
                customQuestion = conditions.substring(lastOpen + 1, conditions.length() - 1);
                conditions = conditions.substring(0, lastOpen).trim();
            }

            PendingEvent pe = new PendingEvent(name, type, dateStr, rewards, seats, conditions, customQuestion, event.getChannel().getId());
            pendingEvents.put(event.getUser().getId(), pe);

            event.reply("تم حفظ بيانات الفعالية مؤقتاً! 📸\nيرجى **إرسال صورة الفعالية** في هذا الشات الآن (أو اكتب `skip` لتخطي الصورة).").setEphemeral(true).queue();
            return;
        }

        if (!event.getModalId().startsWith("ev_modal_")) return;
        int eventId = Integer.parseInt(event.getModalId().replace("ev_modal_", ""));

        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            boolean requiresLink = false;
            String q1 = "SELECT requires_link FROM events WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(q1)) {
                ps.setInt(1, eventId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    requiresLink = rs.getBoolean("requires_link");
                }
            }

            String mcName = null;
            String mcUuid = null;
            if (requiresLink) {
                Optional<String> uuidOpt = LeonTrotskyBot.getDiscordSRVManager().getUuidByDiscordId(event.getUser().getId());
                mcUuid = uuidOpt.orElse(null);
                String q4 = "SELECT username FROM discordsrv__accounts WHERE discord = ?";
                try (PreparedStatement ps4 = conn.prepareStatement(q4)) {
                    ps4.setString(1, event.getUser().getId());
                    ResultSet rs4 = ps4.executeQuery();
                    if (rs4.next()) {
                        mcName = rs4.getString("username");
                    }
                }
            } else {
                mcName = event.getValue("mc_name") != null ? event.getValue("mc_name").getAsString() : "Unknown";
            }

            String customAnswer = event.getValue("custom_answer") != null ? event.getValue("custom_answer").getAsString() : null;

            registerParticipant(event.getUser().getId(), event.getUser().getId(), eventId, mcName, mcUuid, customAnswer);
            event.reply("تم تسجيلك بنجاح في الفعالية!").setEphemeral(true).queue();
            giveEventRole(event.getGuild(), event.getUser().getId());
            updatePublicEmbedSeats(event.getGuild(), eventId);
            updateStaffEmbed(event.getGuild(), eventId);

        } catch (Exception e) {
            logger.error("Error handling modal submit", e);
            event.reply("حدث خطأ.").setEphemeral(true).queue();
        }
    }

    private void handleUnregister(ButtonInteractionEvent event, int eventId) {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String status = "OPEN";
            String q1 = "SELECT status FROM events WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(q1)) {
                ps.setInt(1, eventId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) status = rs.getString("status");
            }
            if (!"OPEN".equals(status)) {
                event.reply("التسجيل مغلق، لا يمكنك الإلغاء الآن.").setEphemeral(true).queue();
                return;
            }

            String del = "DELETE FROM event_participants WHERE event_id = ? AND user_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(del)) {
                ps.setInt(1, eventId);
                ps.setString(2, event.getUser().getId());
                int rows = ps.executeUpdate();
                if (rows > 0) {
                    event.reply("تم إلغاء تسجيلك من الفعالية.").setEphemeral(true).queue();
                    removeEventRole(event.getGuild(), event.getUser().getId());
                    updatePublicEmbedSeats(event.getGuild(), eventId);
                    updateStaffEmbed(event.getGuild(), eventId);
                } else {
                    event.reply("أنت غير مسجل في الأساس!").setEphemeral(true).queue();
                }
            }
        } catch (Exception e) {
            logger.error("Error unregistering", e);
            event.reply("حدث خطأ.").setEphemeral(true).queue();
        }
    }

    private void handleRemindToggle(ButtonInteractionEvent event, int eventId) {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String q = "UPDATE event_participants SET wants_reminder = NOT wants_reminder WHERE event_id = ? AND user_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(q)) {
                ps.setInt(1, eventId);
                ps.setString(2, event.getUser().getId());
                if (ps.executeUpdate() > 0) {
                    event.reply("تم تغيير حالة إشعار التذكير الخاص بك للفعالية.").setEphemeral(true).queue();
                } else {
                    event.reply("أنت غير مسجل في الفعالية!").setEphemeral(true).queue();
                }
            }
        } catch (Exception e) {
            logger.error("Error toggling reminder", e);
            event.reply("حدث خطأ.").setEphemeral(true).queue();
        }
    }

    private void handleViewParticipants(ButtonInteractionEvent event, int eventId) {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String q = "SELECT mc_name FROM event_participants WHERE event_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(q)) {
                ps.setInt(1, eventId);
                ResultSet rs = ps.executeQuery();
                StringBuilder sb = new StringBuilder();
                int count = 0;
                while (rs.next()) {
                    sb.append("• ").append(rs.getString("mc_name")).append("\n");
                    count++;
                }
                if (count == 0) {
                    event.reply("لا يوجد مشاركين حتى الآن.").setEphemeral(true).queue();
                } else {
                    String msg = "👥 قائمة المشاركين (" + count + "):\n" + sb.toString();
                    if (msg.length() > 2000) msg = msg.substring(0, 1996) + "...";
                    event.reply(msg).setEphemeral(true).queue();
                }
            }
        } catch (Exception e) {
            logger.error("Error viewing participants", e);
            event.reply("حدث خطأ.").setEphemeral(true).queue();
        }
    }

    private void updateEventStatus(ButtonInteractionEvent event, int eventId, String newStatus) {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String update = "UPDATE events SET status = ? WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(update)) {
                ps.setString(1, newStatus);
                ps.setInt(2, eventId);
                ps.executeUpdate();
            }
            event.reply("تم تغيير حالة الفعالية إلى: " + newStatus).setEphemeral(true).queue();
            refreshPublicEmbed(event.getGuild(), eventId);
            updateStaffEmbed(event.getGuild(), eventId);
        } catch (Exception e) {
            logger.error("Error updating status", e);
            event.reply("حدث خطأ.").setEphemeral(true).queue();
        }
    }

    private void handleExport(ButtonInteractionEvent event, int eventId) {
        event.deferReply().setEphemeral(true).queue();
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String q = "SELECT discord_id, mc_name, custom_answer FROM event_participants WHERE event_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(q)) {
                ps.setInt(1, eventId);
                ResultSet rs = ps.executeQuery();
                StringBuilder sb = new StringBuilder();
                sb.append("Discord ID | Minecraft Name | Custom Answer\n");
                sb.append("-------------------------------------------\n");
                while (rs.next()) {
                    sb.append(rs.getString("discord_id")).append(" | ")
                      .append(rs.getString("mc_name")).append(" | ")
                      .append(rs.getString("custom_answer") == null ? "N/A" : rs.getString("custom_answer"))
                      .append("\n");
                }
                File file = new File("participants_" + eventId + ".txt");
                try (FileWriter writer = new FileWriter(file)) {
                    writer.write(sb.toString());
                }
                event.getHook().sendFiles(FileUpload.fromData(file)).queue(s -> file.delete(), e -> file.delete());
            }
        } catch (Exception e) {
            logger.error("Error exporting", e);
            event.getHook().editOriginal("حدث خطأ.").queue();
        }
    }

    private void handleNotify(ButtonInteractionEvent event, int eventId) {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String channelId = null;
            String name = null;
            String q = "SELECT channel_id, name FROM events WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(q)) {
                ps.setInt(1, eventId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    channelId = rs.getString("channel_id");
                    name = rs.getString("name");
                }
            }
            if (channelId != null) {
                ThreadChannel thread = event.getGuild().getThreadChannelById(channelId);
                if (thread != null) {
                    thread.sendMessage("🔔 تذكير: فعالية **" + name + "** ستبدأ بعد 30 دقيقة! <@&" + EVENT_ROLE_ID + ">").queue();
                }
            }

            // Send DMs to those who requested
            String q2 = "SELECT discord_id FROM event_participants WHERE event_id = ? AND wants_reminder = TRUE";
            try (PreparedStatement ps2 = conn.prepareStatement(q2)) {
                ps2.setInt(1, eventId);
                ResultSet rs2 = ps2.executeQuery();
                while (rs2.next()) {
                    String uid = rs2.getString("discord_id");
                    Member m = event.getGuild().getMemberById(uid);
                    if (m == null) {
                        User u = LeonTrotskyBot.getJda().getUserById(uid);
                        if (u != null) {
                            String finalName = name;
                            u.openPrivateChannel().queue(pc -> pc.sendMessage("تذكير: فعاليتك " + finalName + " ستبدأ بعد 30 دقيقة!").queue(s -> {}, e -> {}));
                        }
                    } else {
                        String finalName = name;
                        m.getUser().openPrivateChannel().queue(pc -> pc.sendMessage("تذكير: فعاليتك " + finalName + " ستبدأ بعد 30 دقيقة!").queue(s -> {}, e -> {}));
                    }
                }
            }
            event.reply("تم إرسال التذكير بنجاح!").setEphemeral(true).queue();
            
            // Mark reminder as sent
            String mark = "UPDATE events SET reminder_sent = TRUE WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(mark)) {
                ps.setInt(1, eventId);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            logger.error("Error notifying", e);
            event.reply("حدث خطأ.").setEphemeral(true).queue();
        }
    }

    private void handleFinishEvent(ButtonInteractionEvent event, int eventId) {
        updateEventStatus(event, eventId, "FINISHED");
        String msg = "🎉 الفعالية أغلقت! يرجى توزيع الجوائز: <@&" + HYPE_MANAGER_ID + "> <@&" + HYPE_EVENTS_ID + ">";
        event.getChannel().sendMessage(msg).queue();
        
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String channelId = null;
            String name = null;
            String q = "SELECT channel_id, name FROM events WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(q)) {
                ps.setInt(1, eventId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    channelId = rs.getString("channel_id");
                    name = rs.getString("name");
                }
            }
            if (channelId != null) {
                ThreadChannel thread = event.getGuild().getThreadChannelById(channelId);
                if (thread != null) {
                    thread.sendMessage("🎊 انتهت فعالية **" + name + "**! شكراً لجميع المشاركين.").queue();
                }
            }
            
            String q2 = "SELECT discord_id FROM event_participants WHERE event_id = ?";
            try (PreparedStatement ps2 = conn.prepareStatement(q2)) {
                ps2.setInt(1, eventId);
                ResultSet rs2 = ps2.executeQuery();
                while (rs2.next()) {
                    removeEventRole(event.getGuild(), rs2.getString("discord_id"));
                }
            }
        } catch (Exception e) {
            logger.error("Error finishing event", e);
        }
    }

    private void registerParticipant(String userId, String discordId, int eventId, String mcName, String mcUuid, String customAnswer) throws Exception {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String insert = "INSERT INTO event_participants (event_id, user_id, discord_id, mc_name, mc_uuid, custom_answer) VALUES (?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insert)) {
                ps.setInt(1, eventId);
                ps.setString(2, userId);
                ps.setString(3, discordId);
                ps.setString(4, mcName);
                ps.setString(5, mcUuid);
                ps.setString(6, customAnswer);
                ps.executeUpdate();
            }
        }
    }

    private void giveEventRole(Guild guild, String userId) {
        Role role = guild.getRoleById(EVENT_ROLE_ID);
        if (role != null) {
            guild.retrieveMemberById(userId).queue(m -> {
                guild.addRoleToMember(m, role).queue(s -> {}, e -> {});
            });
        }
    }

    private void removeEventRole(Guild guild, String userId) {
        Role role = guild.getRoleById(EVENT_ROLE_ID);
        if (role != null) {
            guild.retrieveMemberById(userId).queue(m -> {
                guild.removeRoleFromMember(m, role).queue(s -> {}, e -> {});
            });
        }
    }

    private void updatePublicEmbedSeats(Guild guild, int eventId) {
        refreshPublicEmbed(guild, eventId);
    }

    private void refreshPublicEmbed(Guild guild, int eventId) {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String channelId = null, messageId = null, name = null, type = null, date = null, rewards = null, conditions = null, status = null, imageUrl = null;
            int maxSeats = 0;
            boolean requiresLink = false;
            
            String q1 = "SELECT * FROM events WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(q1)) {
                ps.setInt(1, eventId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    channelId = rs.getString("channel_id");
                    messageId = rs.getString("message_id");
                    name = rs.getString("name");
                    type = rs.getString("type");
                    date = rs.getString("event_date");
                    rewards = rs.getString("rewards");
                    maxSeats = rs.getInt("max_seats");
                    conditions = rs.getString("conditions");
                    status = rs.getString("status");
                    requiresLink = rs.getBoolean("requires_link");
                    imageUrl = rs.getString("image_url");
                }
            }
            if (channelId == null || messageId == null) return;

            int currentSeats = 0;
            String q2 = "SELECT COUNT(*) FROM event_participants WHERE event_id = ?";
            try (PreparedStatement ps2 = conn.prepareStatement(q2)) {
                ps2.setInt(1, eventId);
                ResultSet rs2 = ps2.executeQuery();
                if (rs2.next()) {
                    currentSeats = rs2.getInt(1);
                }
            }

            long unixTime = TimeUtils.parseToUnixTimestamp(date);
            Container publicContainer = getBasePublicContainer(name, type, unixTime, rewards, maxSeats, currentSeats, conditions, status, imageUrl, eventId);

            ThreadChannel thread = guild.getThreadChannelById(channelId);
            if (thread != null) {
                thread.retrieveMessageById(messageId).queue(msg -> {
                    MessageEditBuilder editBuilder = new MessageEditBuilder()
                        .setComponents(publicContainer)
                        .setEmbeds(java.util.Collections.emptyList())
                        .useComponentsV2(true);
                    msg.editMessage(editBuilder.build()).queue();
                }, e -> {});
            }
        } catch (Exception e) {
            logger.error("Error refreshing public embed", e);
        }
    }

    private void updateStaffEmbed(Guild guild, int eventId) {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String name = "مجهول";
            String channelId = "";
            String staffChannelId = "";
            String staffMessageId = "";
            String status = "OPEN";

            String q = "SELECT name, channel_id, staff_channel_id, staff_message_id, status FROM events WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(q)) {
                ps.setInt(1, eventId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    name = rs.getString("name");
                    channelId = rs.getString("channel_id");
                    staffChannelId = rs.getString("staff_channel_id");
                    staffMessageId = rs.getString("staff_message_id");
                    status = rs.getString("status");
                }
            }

            if (staffChannelId == null || staffMessageId == null || staffChannelId.isEmpty() || staffMessageId.isEmpty()) {
                return;
            }

            List<String> participantMentions = new ArrayList<>();
            String q2 = "SELECT discord_id FROM event_participants WHERE event_id = ?";
            try (PreparedStatement ps2 = conn.prepareStatement(q2)) {
                ps2.setInt(1, eventId);
                ResultSet rs2 = ps2.executeQuery();
                while (rs2.next()) {
                    participantMentions.add("<@" + rs2.getString("discord_id") + ">");
                }
            }

            String mention = channelId.isEmpty() ? "مجهول" : "<#" + channelId + ">";
            Container staffContainer = getStaffContainer(name, mention, eventId, status, participantMentions.size(), participantMentions);

            net.dv8tion.jda.api.entities.channel.middleman.MessageChannel channel = guild.getTextChannelById(staffChannelId);
            if (channel != null) {
                channel.retrieveMessageById(staffMessageId).queue(msg -> {
                    MessageEditBuilder editBuilder = new MessageEditBuilder()
                        .setComponents(staffContainer)
                        .setEmbeds(java.util.Collections.emptyList())
                        .useComponentsV2(true);
                    msg.editMessage(editBuilder.build()).queue();
                }, e -> {});
            }
        } catch(Exception e) {
            logger.error("Error refreshing staff embed", e);
        }
    }

    private boolean hasRole(Member member, String roleId) {
        for (Role role : member.getRoles()) {
            if (role.getId().equals(roleId)) return true;
        }
        return false;
    }

    private List<Button> getPublicButtons(int eventId, String status) {
        List<Button> buttons = new ArrayList<>();
        if ("OPEN".equals(status)) {
            buttons.add(Button.success("ev_reg_" + eventId, "تسجيل"));
            buttons.add(Button.danger("ev_unreg_" + eventId, "إلغاء التسجيل"));
            buttons.add(Button.secondary("ev_remind_" + eventId, "تذكيري"));
            buttons.add(Button.primary("ev_view_" + eventId, "عرض المشاركين"));
        } else if ("CLOSED".equals(status)) {
            buttons.add(Button.secondary("ev_reg_disabled", "التسجيل مقفل").asDisabled());
            buttons.add(Button.danger("ev_unreg_" + eventId, "إلغاء التسجيل"));
            buttons.add(Button.primary("ev_view_" + eventId, "عرض المشاركين"));
        } else if ("FINISHED".equals(status)) {
            buttons.add(Button.secondary("ev_reg_disabled", "الفعالية انتهت").asDisabled());
            buttons.add(Button.primary("ev_view_" + eventId, "عرض المشاركين"));
        } else if ("CANCELLED".equals(status)) {
            buttons.add(Button.danger("ev_reg_disabled", "تم الإلغاء").asDisabled());
        }
        return buttons;
    }

    private List<Button> getStaffButtons(int eventId, String status) {
        List<Button> buttons = new ArrayList<>();
        if ("OPEN".equals(status)) {
            buttons.add(Button.danger("ev_staff_close_" + eventId, "إغلاق التسجيل"));
        } else if ("CLOSED".equals(status)) {
            buttons.add(Button.success("ev_staff_open_" + eventId, "فتح التسجيل"));
        }
        
        if (!"FINISHED".equals(status) && !"CANCELLED".equals(status)) {
            buttons.add(Button.secondary("ev_staff_export_" + eventId, "تصدير المشاركين"));
            buttons.add(Button.primary("ev_staff_notify_" + eventId, "إرسال تذكير"));
            buttons.add(Button.success("ev_staff_finish_" + eventId, "إنهاء الفعالية"));
            buttons.add(Button.danger("ev_staff_cancel_" + eventId, "إلغاء الفعالية"));
        } else {
            buttons.add(Button.secondary("ev_staff_export_" + eventId, "تصدير المشاركين"));
        }
        return buttons;
    }
}
