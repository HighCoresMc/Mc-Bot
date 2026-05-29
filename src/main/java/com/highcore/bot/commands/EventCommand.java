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
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class EventCommand extends ListenerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(EventCommand.class);

    private static class PendingEvent {
        String name;
        String type;
        String dateStr;
        com.google.gson.JsonArray rewardsJson;
        int seats;
        String conditions;
        String customQuestion;
        String staffChannelId;

        PendingEvent(String name, String type, String dateStr, com.google.gson.JsonArray rewardsJson, int seats, String conditions, String customQuestion, String staffChannelId) {
            this.name = name;
            this.type = type;
            this.dateStr = dateStr;
            this.rewardsJson = rewardsJson;
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
            if (!event.getMessage().getAttachments().isEmpty()) {
                net.dv8tion.jda.api.entities.Message.Attachment attachment = event.getMessage().getAttachments().get(0);
                if (!attachment.isImage()) {
                    event.getChannel().sendMessage("الملف المرفق ليس صورة! يرجى رفع صورة صحيحة أو كتابة `skip`.").queue();
                    return;
                }
                attachment.getProxy().download().thenAccept(inputStream -> {
                    pendingEvents.remove(user.getId());
                    event.getMessage().delete().queue(null, e -> {});
                    createEventFinal(user, event.getGuild(), pe, inputStream, attachment.getFileName());
                }).exceptionally(e -> {
                    event.getChannel().sendMessage("حدث خطأ أثناء تحميل الصورة.").queue();
                    return null;
                });
            } else if (event.getMessage().getContentRaw().equalsIgnoreCase("skip")) {
                pendingEvents.remove(user.getId());
                event.getMessage().delete().queue(null, e -> {});
                createEventFinal(user, event.getGuild(), pe, null, null);
            } else {
                event.getChannel().sendMessage("الرجاء إرسال صورة أو كتابة `skip`.").queue();
                return;
            }
        }
    }

    private void createEventFinal(User creator, Guild guild, PendingEvent pe, java.io.InputStream imageStream, String fileName) {
        ForumChannel forumChannel = guild.getForumChannelById(EVENTS_FORUM_ID);
        if (forumChannel == null) return;
        
        long unixTime = TimeUtils.parseToUnixTimestamp(pe.dateStr);
        String dbImageUrl = fileName != null ? "attachment://" + fileName : null;

        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String insertSql = "INSERT INTO events (name, type, event_date, rewards_json, max_seats, conditions, requires_link, custom_question, image_url, staff_channel_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, pe.name);
                ps.setString(2, pe.type);
                ps.setString(3, pe.dateStr);
                ps.setString(4, pe.rewardsJson.toString());
                ps.setInt(5, pe.seats);
                ps.setString(6, pe.conditions);
                ps.setBoolean(7, true);
                ps.setString(8, pe.customQuestion);
                ps.setString(9, dbImageUrl);
                ps.setString(10, pe.staffChannelId);
                ps.executeUpdate();

                ResultSet rs = ps.getGeneratedKeys();
                if (rs.next()) {
                    int eventId = rs.getInt(1);
                    
                    Container publicContainer = getBasePublicContainer(pe.name, pe.type, unixTime, pe.rewardsJson.toString(), pe.seats, 0, pe.conditions, "OPEN", eventId, null, null);

                    MessageCreateBuilder builder = new MessageCreateBuilder()
                        .setComponents(publicContainer)
                        .useComponentsV2(true);
                        
                    if (imageStream != null && fileName != null) {
                        builder.addFiles(FileUpload.fromData(imageStream, fileName));
                    }

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

    private Container getBasePublicContainer(String name, String type, long unixTime, String rewards, int maxSeats, int currentSeats, String conditions, String status, int eventId, String winnerId, String winnerMcName) {
        String statusEmoji = "OPEN".equals(status) ? "🟢" : ("CLOSED".equals(status) ? "🔴" : "⚫");
        String statusText = switch (status) {
            case "OPEN" -> "التسجيل مفتوح";
            case "CLOSED" -> "التسجيل مغلق";
            case "FINISHED" -> "انتهت الفعالية";
            case "CANCELLED" -> "ملغية";
            default -> status;
        };
        
        String warning = "⚠️ **تنبيه:** هذه الفعالية تتطلب حساب ماينكرافت مربوط بالديسكورد للتسجيل.\n\n";

        TextDisplay header = TextDisplay.of("## 🎉 فعالية جديدة: " + name);
        Separator s1 = Separator.createDivider(Separator.Spacing.SMALL);
        TextDisplay details = TextDisplay.of(warning + "### 📋 التفاصيل\n" +
            "**نوع الفعالية:** `" + type + "`\n" +
            "**الوقت:** <t:" + unixTime + ":F>\n" +
            "**المكافآت:** `" + formatRewards(rewards) + "`\n" +
            "**المقاعد المتاحة:** `" + currentSeats + " / " + maxSeats + "`");

        TextDisplay conditionsDisplay = TextDisplay.of("### 📝 الشروط\n" + conditions);
        TextDisplay statusDisplay = TextDisplay.of("**Event ID:** `" + eventId + "` | " + statusEmoji + " **الحالة:** `" + statusText + "`");
        ActionRow actionRow = ActionRow.of(getPublicButtons(eventId, status));
        TextDisplay footer = TextDisplay.of("> لو عندك اي استفسار تفضل <#1487143271586074624> ← الفعاليات");

        if (winnerId != null && winnerMcName != null) {
            int innerWidth = Math.max(17, winnerMcName.length() + 4);
            String horizontal = "─".repeat(innerWidth);
            
            int leftPad = (innerWidth - winnerMcName.length()) / 2;
            int rightPad = innerWidth - winnerMcName.length() - leftPad;
            String middleLine = "│" + " ".repeat(leftPad) + winnerMcName + " ".repeat(rightPad) + "│";

            String winnerText = "```\n" +
                                "╭" + horizontal + "╮\n" +
                                middleLine + "\n" +
                                "╰" + horizontal + "╯\n" +
                                "```\nالفائز: <@" + winnerId + ">";
            Separator sWinner = Separator.createDivider(Separator.Spacing.SMALL);
            TextDisplay winnerDisplay = TextDisplay.of("### 🏆 الفائز في الفعالية\n" + winnerText);
            
            return Container.of(
                header, s1, details,
                sWinner, winnerDisplay,
                Separator.createDivider(Separator.Spacing.SMALL), conditionsDisplay,
                Separator.createDivider(Separator.Spacing.SMALL), statusDisplay,
                Separator.createDivider(Separator.Spacing.SMALL), actionRow,
                Separator.createDivider(Separator.Spacing.SMALL), footer
            );
        }

        return Container.of(
            header, s1, details,
            Separator.createDivider(Separator.Spacing.SMALL), conditionsDisplay,
            Separator.createDivider(Separator.Spacing.SMALL), statusDisplay,
            Separator.createDivider(Separator.Spacing.SMALL), actionRow,
            Separator.createDivider(Separator.Spacing.SMALL), footer
        );
    }

    private String formatRewards(String rewardsJson) {
        if (rewardsJson == null || rewardsJson.isEmpty() || rewardsJson.equals("[]")) {
            return "لا توجد جوائز";
        }
        try {
            com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseString(rewardsJson).getAsJsonArray();
            java.util.List<String> list = new java.util.ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                com.google.gson.JsonObject obj = arr.get(i).getAsJsonObject();
                String type = obj.get("type").getAsString();
                String amount = obj.has("amount") ? obj.get("amount").getAsString() : "1";
                switch (type) {
                    case "cmi": list.add(amount + " فلوس"); break;
                    case "tokens": list.add(amount + " توكن"); break;
                    case "xp": list.add(amount + " XP"); break;
                    case "claims": list.add(amount + " كليم"); break;
                    case "afk": list.add(amount + " مفتاح AFK"); break;
                    case "item": 
                        String item = obj.has("itemName") ? obj.get("itemName").getAsString() : "عنصر";
                        list.add(amount + " x " + item); 
                        break;
                    case "rank": 
                        String rank = obj.has("roleId") ? obj.get("roleId").getAsString() : "رتبة";
                        list.add("رتبة " + rank);
                        break;
                    default: list.add(amount + " " + type); break;
                }
            }
            return String.join(" + ", list);
        } catch (Exception e) {
            return rewardsJson;
        }
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith("ev_")) return;

        if (id.equals("ev_reward_finish")) {
            PendingEvent pe = pendingEvents.get(event.getUser().getId());
            if (pe == null) {
                event.reply("انتهت جلسة إنشاء الفعالية!").setEphemeral(true).queue();
                return;
            }

            event.editMessage("تم حفظ بيانات الفعالية مؤقتاً والجوائز! 📸\nيرجى **إرسال صورة الفعالية** في هذا الشات الآن (أو اكتب `skip` لتخطي الصورة).")
                 .setComponents(java.util.Collections.emptyList())
                 .queue();
            return;
        }

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
            TextInput seatsInput = TextInput.create("ev_create_seats", TextInputStyle.SHORT)
                .setPlaceholder("عدد المقاعد (مثال: 50)")
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
                    Label.of("عدد المقاعد", seatsInput),
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
                String uuidDash = mcUuid.contains("-") ? mcUuid : mcUuid.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5");
                String uuidNoDash = mcUuid.replace("-", "");
                String q4 = "SELECT username FROM CMI_users WHERE player_uuid = ? OR player_uuid = ?";
                try (Connection cmiConn = LeonTrotskyBot.getDbManager().getCmiConnection();
                     PreparedStatement ps4 = cmiConn.prepareStatement(q4)) {
                    ps4.setString(1, uuidDash);
                    ps4.setString(2, uuidNoDash);
                    try (ResultSet rs4 = ps4.executeQuery()) {
                        if (rs4.next()) {
                            mcName = rs4.getString("username");
                        } else {
                            mcName = "مجهول";
                        }
                    }
                } catch (Exception ex) {
                    logger.error("Error fetching username from CMI", ex);
                    mcName = "مجهول";
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
            String seatsStr = event.getValue("ev_create_seats").getAsString();
            String conditions = event.getValue("ev_create_conditions").getAsString();
            
            if (!TimeUtils.isValidFormat(dateStr)) {
                event.reply("صيغة الوقت غير صحيحة! يجب أن تكون YYYY-MM-DD HH:MM (مثال: 2026-06-20 21:00)").setEphemeral(true).queue();
                return;
            }

            int seats = 50;
            try { seats = Integer.parseInt(seatsStr.trim()); } catch (Exception ignored) {}

            String customQuestion = null;
            if (conditions.contains("(") && conditions.endsWith(")")) {
                int lastOpen = conditions.lastIndexOf("(");
                customQuestion = conditions.substring(lastOpen + 1, conditions.length() - 1);
                conditions = conditions.substring(0, lastOpen).trim();
            }

            PendingEvent pe = new PendingEvent(name, type, dateStr, new com.google.gson.JsonArray(), seats, conditions, customQuestion, event.getChannel().getId());
            pendingEvents.put(event.getUser().getId(), pe);

            net.dv8tion.jda.api.components.selections.StringSelectMenu rewardMenu = net.dv8tion.jda.api.components.selections.StringSelectMenu.create("ev_reward_menu")
                .setPlaceholder("اختر نوع الجائزة للإضافة...")
                .addOption("فلوس CMI", "cmi")
                .addOption("فلوس Tokens", "tokens")
                .addOption("رتبة (LuckPerms)", "rank")
                .addOption("أيتم", "item")
                .addOption("خبرة (XP)", "xp")
                .addOption("كليمات (acb)", "claims")
                .addOption("مفتاح AFK", "afk")
                .build();

            event.reply("🎁 **الرجاء إضافة جوائز للفعالية:**\nاختر من القائمة بالأسفل:")
                 .setComponents(ActionRow.of(rewardMenu),
                                ActionRow.of(Button.success("ev_reward_finish", "✅ الانتهاء من إضافة الجوائز ورفع الصورة")))
                 .setEphemeral(true)
                 .queue();
            return;
        }

        if (event.getModalId().startsWith("ev_reward_modal_")) {
            String type = event.getModalId().replace("ev_reward_modal_", "");
            PendingEvent pe = pendingEvents.get(event.getUser().getId());
            if (pe == null) {
                event.reply("انتهت جلسة إنشاء الفعالية!").setEphemeral(true).queue();
                return;
            }

            JsonObject reward = new JsonObject();
            reward.addProperty("type", type);
            
            if (type.equals("rank")) {
                reward.addProperty("roleId", event.getValue("rank_id").getAsString());
            } else if (type.equals("item")) {
                reward.addProperty("itemName", event.getValue("item_name").getAsString());
                reward.addProperty("amount", event.getValue("item_amount").getAsString());
            } else {
                reward.addProperty("amount", event.getValue("amount").getAsString());
            }

            pe.rewardsJson.add(reward);

            StringBuilder currentRewards = new StringBuilder();
            for (int i = 0; i < pe.rewardsJson.size(); i++) {
                JsonObject r = pe.rewardsJson.get(i).getAsJsonObject();
                currentRewards.append("- ").append(formatReward(r)).append("\n");
            }

            event.editMessage("🎁 **الرجاء إضافة جوائز للفعالية:**\nالجوائز المضافة حالياً:\n" + currentRewards.toString() + "\nاختر المزيد من القائمة بالأسفل:")
                 .queue();
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
                if (mcUuid != null) {
                    String uuidDash = mcUuid.contains("-") ? mcUuid : mcUuid.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5");
                    String uuidNoDash = mcUuid.replace("-", "");
                    String q4 = "SELECT username FROM CMI_users WHERE player_uuid = ? OR player_uuid = ?";
                    try (Connection cmiConn = LeonTrotskyBot.getDbManager().getCmiConnection();
                         PreparedStatement ps4 = cmiConn.prepareStatement(q4)) {
                        ps4.setString(1, uuidDash);
                        ps4.setString(2, uuidNoDash);
                        try (ResultSet rs4 = ps4.executeQuery()) {
                            if (rs4.next()) {
                                mcName = rs4.getString("username");
                            } else {
                                mcName = "مجهول";
                            }
                        }
                    } catch (Exception ex) {
                        logger.error("Error fetching username from CMI", ex);
                        mcName = "مجهول";
                    }
                } else {
                    mcName = "مجهول";
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
            
            String winnerId = null;
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
                    rewards = rs.getString("rewards_json");
                    maxSeats = rs.getInt("max_seats");
                    conditions = rs.getString("conditions");
                    status = rs.getString("status");
                    requiresLink = rs.getBoolean("requires_link");
                    imageUrl = rs.getString("image_url");
                    winnerId = rs.getString("winner_id");
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
            
            String winnerMcName = null;
            if (winnerId != null) {
                String qw = "SELECT mc_name FROM event_participants WHERE event_id = ? AND discord_id = ?";
                try (PreparedStatement psw = conn.prepareStatement(qw)) {
                    psw.setInt(1, eventId);
                    psw.setString(2, winnerId);
                    ResultSet rsw = psw.executeQuery();
                    if (rsw.next()) winnerMcName = rsw.getString("mc_name");
                }
            }

            long unixTime = TimeUtils.parseToUnixTimestamp(date);
            Container publicContainer = getBasePublicContainer(name, type, unixTime, rewards, maxSeats, currentSeats, conditions, status, eventId, winnerId, winnerMcName);

            ThreadChannel thread = guild.getThreadChannelById(channelId);
            if (thread != null) {
                thread.retrieveMessageById(messageId).queue(msg -> {
                    MessageEditBuilder editBuilder = new MessageEditBuilder()
                        .setComponents(publicContainer)
                        .useComponentsV2(true);
                        
                    if (!msg.getEmbeds().isEmpty()) {
                        editBuilder.setEmbeds(msg.getEmbeds());
                    }
                    net.dv8tion.jda.api.requests.restaction.MessageEditAction action = msg.editMessage(editBuilder.build());
                    action.queue();
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
                final String finalStatus = status;
                channel.retrieveMessageById(staffMessageId).queue(msg -> {
                    MessageEditBuilder editBuilder = new MessageEditBuilder()
                        .setComponents(staffContainer)
                        .setEmbeds(java.util.Collections.emptyList())
                        .useComponentsV2(true);
                    
                    if (finalStatus.equals("FINISHED")) {
                        EntitySelectMenu distributeMenu = EntitySelectMenu.create("ev_staff_distribute_" + eventId, EntitySelectMenu.SelectTarget.USER)
                            .setPlaceholder("🏆 اختر فائزاً لتوزيع الجوائز...")
                            .build();
                        editBuilder.setComponents(staffContainer, Container.of(ActionRow.of(distributeMenu)));
                    }

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

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        if (event.getComponentId().equals("ev_reward_menu")) {
            String type = event.getValues().get(0);
            PendingEvent pe = pendingEvents.get(event.getUser().getId());
            if (pe == null) {
                event.reply("انتهت جلسة إنشاء الفعالية أو لم يتم العثور عليها!").setEphemeral(true).queue();
                return;
            }

            Modal.Builder modalBuilder = Modal.create("ev_reward_modal_" + type, "إضافة جائزة");
            
            if (type.equals("rank")) {
                TextInput rankInput = TextInput.create("rank_id", TextInputStyle.SHORT)
                    .setRequired(true)
                    .build();
                modalBuilder.addComponents(net.dv8tion.jda.api.components.label.Label.of("الرتبة (أو الـ ID الخاص بها)", rankInput));
            } else if (type.equals("item")) {
                TextInput itemInput = TextInput.create("item_name", TextInputStyle.SHORT)
                    .setRequired(true)
                    .build();
                TextInput amountInput = TextInput.create("item_amount", TextInputStyle.SHORT)
                    .setRequired(true)
                    .build();
                modalBuilder.addComponents(net.dv8tion.jda.api.components.label.Label.of("اسم الأيتم (مثل minecraft:diamond)", itemInput), net.dv8tion.jda.api.components.label.Label.of("الكمية", amountInput));
            } else {
                TextInput amountInput = TextInput.create("amount", TextInputStyle.SHORT)
                    .setRequired(true)
                    .build();
                modalBuilder.addComponents(net.dv8tion.jda.api.components.label.Label.of("الكمية / القيمة", amountInput));
            }

            event.replyModal(modalBuilder.build()).queue();
        }
    }

    @Override
    public void onEntitySelectInteraction(EntitySelectInteractionEvent event) {
        if (event.getComponentId().startsWith("ev_staff_distribute_")) {
            int eventId = Integer.parseInt(event.getComponentId().replace("ev_staff_distribute_", ""));
            User winner = event.getMentions().getUsers().get(0);
            if (winner == null) {
                event.reply("يرجى اختيار فائز!").setEphemeral(true).queue();
                return;
            }

            try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
                String mcName = null;
                String q1 = "SELECT mc_name FROM event_participants WHERE event_id = ? AND discord_id = ?";
                try (PreparedStatement ps = conn.prepareStatement(q1)) {
                    ps.setInt(1, eventId);
                    ps.setString(2, winner.getId());
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        mcName = rs.getString("mc_name");
                    }
                }

                if (mcName == null || mcName.equals("Unknown") || mcName.equals("مجهول")) {
                    event.reply("اللاعب غير مسجل في الفعالية أو لم يتم العثور على اسمه في ماينكرافت!").setEphemeral(true).queue();
                    return;
                }

                String rewardsJson = null;
                String q2 = "SELECT rewards_json FROM events WHERE id = ?";
                try (PreparedStatement ps = conn.prepareStatement(q2)) {
                    ps.setInt(1, eventId);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        rewardsJson = rs.getString("rewards_json");
                    }
                }

                if (rewardsJson == null || rewardsJson.isEmpty() || rewardsJson.equals("[]")) {
                    event.reply("لا توجد جوائز مسجلة لهذه الفعالية!").setEphemeral(true).queue();
                    return;
                }

                JsonArray rewards = JsonParser.parseString(rewardsJson).getAsJsonArray();
                com.highcore.bot.services.PterodactylService ptero = new com.highcore.bot.services.PterodactylService();
                
                for (int i = 0; i < rewards.size(); i++) {
                    JsonObject r = rewards.get(i).getAsJsonObject();
                    String type = r.get("type").getAsString();
                    String cmd = "";
                    switch(type) {
                        case "cmi": cmd = "cmi money add " + mcName + " " + r.get("amount").getAsString(); break;
                        case "tokens": cmd = "points give " + mcName + " " + r.get("amount").getAsString(); break;
                        case "rank": cmd = "lp user " + mcName + " parent add " + r.get("roleId").getAsString(); break;
                        case "item": cmd = "cmi give " + mcName + " " + r.get("itemName").getAsString() + " " + r.get("amount").getAsString(); break;
                        case "xp": cmd = "cmi exp " + mcName + " add " + r.get("amount").getAsString() + "L"; break;
                        case "claims": cmd = "acb " + mcName + " " + r.get("amount").getAsString(); break;
                        case "afk": cmd = "crate key give " + mcName + " afk " + r.get("amount").getAsString(); break;
                    }
                    if (!cmd.isEmpty() && ptero != null) {
                        ptero.sendConsoleCommand(cmd);
                    }
                }

                String updateWinner = "UPDATE events SET winner_id = ? WHERE id = ?";
                try (PreparedStatement ps = conn.prepareStatement(updateWinner)) {
                    ps.setString(1, winner.getId());
                    ps.setInt(2, eventId);
                    ps.executeUpdate();
                }
                
                String channelId = null;
                String q3 = "SELECT channel_id FROM events WHERE id = ?";
                try (PreparedStatement ps = conn.prepareStatement(q3)) {
                    ps.setInt(1, eventId);
                    java.sql.ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        channelId = rs.getString("channel_id");
                    }
                }
                
                refreshPublicEmbed(event.getGuild(), eventId);

                if (channelId != null) {
                    net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel thread = event.getGuild().getThreadChannelById(channelId);
                    if (thread != null) {
                        net.dv8tion.jda.api.EmbedBuilder winEmbed = new net.dv8tion.jda.api.EmbedBuilder();
                        winEmbed.setTitle("🎉 الفائز بالفعالية!");
                        winEmbed.setDescription("تهانينا للاعب **" + mcName + "** (" + winner.getAsMention() + ") بمناسبة الفوز بالفعالية! 🏆\n\nتم تسليم الجوائز بنجاح.");
                        winEmbed.setColor(0xFFD700);
                        thread.sendMessage(winner.getAsMention()).addEmbeds(winEmbed.build()).queue();
                    }
                }

                winner.openPrivateChannel().queue(privateChannel -> {
                    net.dv8tion.jda.api.EmbedBuilder dmEmbed = new net.dv8tion.jda.api.EmbedBuilder();
                    dmEmbed.setTitle("🎉 مبروك الفوز في الفعالية!");
                    dmEmbed.setDescription("لقد فزت في فعالية الماينكرافت بحسابك **" + mcName + "**!\n\nتم تسليم جوائز الفعالية لحسابك في السيرفر بنجاح، نتمنى لك وقتاً ممتعاً! 🎁");
                    dmEmbed.setColor(0xFFD700);
                    privateChannel.sendMessageEmbeds(dmEmbed.build()).queue();
                }, error -> logger.warn("Failed to send DM to winner " + winner.getId()));

                event.reply("تم توزيع الجوائز على اللاعب **" + mcName + "** (" + winner.getAsMention() + ") بنجاح! 🏆").queue();
            } catch(Exception e) {
                logger.error("Error distributing rewards", e);
                event.reply("حدث خطأ أثناء توزيع الجوائز.").setEphemeral(true).queue();
            }
        }
    }

    private String formatReward(JsonObject r) {
        String type = r.get("type").getAsString();
        switch (type) {
            case "cmi": return r.get("amount").getAsString() + " فلوس CMI";
            case "tokens": return r.get("amount").getAsString() + " Tokens";
            case "rank": return "رتبة: " + r.get("roleId").getAsString();
            case "item": return "أيتم: " + r.get("itemName").getAsString() + " x" + r.get("amount").getAsString();
            case "xp": return r.get("amount").getAsString() + " مستوى خبرة (XP)";
            case "claims": return r.get("amount").getAsString() + " كليمات";
            case "afk": return r.get("amount").getAsString() + " مفتاح AFK";
            default: return "جائزة غير معروفة";
        }
    }
}
