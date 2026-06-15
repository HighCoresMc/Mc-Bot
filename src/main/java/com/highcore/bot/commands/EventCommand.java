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
import net.dv8tion.jda.api.components.mediagallery.MediaGallery;
import net.dv8tion.jda.api.components.mediagallery.MediaGalleryItem;
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
        String category;

        PendingEvent(String name, String type, String dateStr, com.google.gson.JsonArray rewardsJson, int seats, String conditions, String customQuestion, String staffChannelId, String category) {
            this.name = name;
            this.type = type;
            this.dateStr = dateStr;
            this.rewardsJson = rewardsJson;
            this.seats = seats;
            this.conditions = conditions;
            this.customQuestion = customQuestion;
            this.staffChannelId = staffChannelId;
            this.category = category;
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
                Button.primary("ev_panel_history", "📜 سجل الفعاليات"),
                Button.secondary("ev_panel_wins", "🏆 تاريخ الفوز"),
                Button.secondary("ev_panel_pending", "⏳ فعاليات معلقة")
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
                    try {
                        byte[] bytes = inputStream.readAllBytes();
                        pendingEvents.remove(user.getId());
                        event.getMessage().delete().queue(null, e -> {});
                        createEventFinal(user, event.getGuild(), pe, bytes, attachment.getFileName());
                    } catch (Exception ex) {
                        logger.error("Error reading image stream", ex);
                        event.getChannel().sendMessage("حدث خطأ أثناء تحميل الصورة.").queue();
                    }
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

    private void createEventFinal(User creator, Guild guild, PendingEvent pe, byte[] imageBytes, String fileName) {
        ForumChannel forumChannel = guild.getForumChannelById(EVENTS_FORUM_ID);
        if (forumChannel == null) return;
        
        long unixTime = TimeUtils.parseToUnixTimestamp(pe.dateStr);
        String dbImageUrl = fileName != null ? "attachment://" + fileName : null;

        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String insertSql = "INSERT INTO events (name, type, event_date, rewards_json, max_seats, conditions, requires_link, custom_question, image_url, staff_channel_id, category) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, pe.name);
                ps.setString(2, pe.type);
                ps.setString(3, pe.dateStr);
                ps.setString(4, pe.rewardsJson.toString());
                ps.setInt(5, pe.seats);
                ps.setString(6, pe.conditions);
                ps.setBoolean(7, !"DC".equalsIgnoreCase(pe.category));
                ps.setString(8, pe.customQuestion);
                ps.setString(9, dbImageUrl);
                ps.setString(10, pe.staffChannelId);
                ps.setString(11, pe.category);
                ps.executeUpdate();

                ResultSet rs = ps.getGeneratedKeys();
                if (rs.next()) {
                    int eventId = rs.getInt(1);

                    // Supabase
                    com.highcore.bot.database.SupabaseManager supa = LeonTrotskyBot.getSupabaseManager();
                    if (supa != null) {
                        int points = "written".equalsIgnoreCase(pe.type) || "text".equalsIgnoreCase(pe.type) ? 15 : 55;
                        int maxSupervisors = pe.seats; // Using the value inputted by the user
                        if ("DC".equalsIgnoreCase(pe.category)) {
                            supa.logDcEvent(eventId, pe.name, pe.type, pe.conditions, pe.dateStr, points, maxSupervisors);
                        } else {
                            supa.logEvent(eventId, pe.name, pe.type, pe.conditions, pe.dateStr, points, maxSupervisors);
                        }
                    }

                    Container publicContainer = getPublicEventContainer(pe.name, pe.type, unixTime, pe.rewardsJson.toString(), pe.seats, 0, pe.conditions, "OPEN", eventId, dbImageUrl, null, null, guild, !"DC".equalsIgnoreCase(pe.category));
                    ActionRow actionRow = ActionRow.of(getPublicButtons(eventId, "OPEN"));

                    MessageCreateBuilder builder = new MessageCreateBuilder()
                        .setComponents(publicContainer, actionRow)
                        .useComponentsV2(true);
                        
                    if (imageBytes != null && fileName != null) {
                        builder.addFiles(net.dv8tion.jda.api.utils.FileUpload.fromData(imageBytes, fileName));
                    }

                    java.util.List<net.dv8tion.jda.api.entities.channel.forums.ForumTag> availableTags = forumChannel.getAvailableTags();
                    net.dv8tion.jda.api.entities.channel.forums.ForumTag targetTag = null;
                    for (net.dv8tion.jda.api.entities.channel.forums.ForumTag t : availableTags) {
                        if (pe.category.equalsIgnoreCase(t.getName())) {
                            targetTag = t;
                            break;
                        }
                    }

                    net.dv8tion.jda.api.requests.restaction.ForumPostAction postAction = forumChannel.createForumPost("🎉 " + pe.name, builder.build());
                    if (targetTag != null) {
                        postAction = postAction.setTags(targetTag);
                    } else {
                        String tagIdFallback = "DC".equalsIgnoreCase(pe.category) ? "1514287675589525704" : "1514290357813252186";
                        postAction = postAction.setTags(net.dv8tion.jda.api.entities.channel.forums.ForumTagSnowflake.fromId(tagIdFallback));
                    }
                    
                    postAction.queue(forumPost -> {
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

    public static Container getPublicEventContainer(String name, String type, long unixTime, String rewards, int maxSeats, int currentSeats, String conditions, String status, int eventId, String imageUrl, String winnerId, String winnerMcName, Guild guild, boolean requiresLink) {
        String rewardsStr = "لا توجد";
        try {
            if (rewards != null && !rewards.isEmpty() && !rewards.equals("[]")) {
                com.google.gson.JsonArray rArr = com.google.gson.JsonParser.parseString(rewards).getAsJsonArray();
                java.util.List<String> rList = new java.util.ArrayList<>();
                for (int i = 0; i < rArr.size(); i++) {
                    com.google.gson.JsonObject rObj = rArr.get(i).getAsJsonObject();
                    String rType = rObj.has("type") ? rObj.get("type").getAsString() : "unknown";
                    if (rType.equals("cmi")) rList.add(rObj.get("amount").getAsString() + " فلوس");
                    else if (rType.equals("tokens")) rList.add(rObj.get("amount").getAsString() + " توكنز");
                    else if (rType.equals("rank")) rList.add("رتبة " + rObj.get("roleId").getAsString());
                    else if (rType.equals("item")) rList.add(rObj.get("amount").getAsString() + "x " + rObj.get("itemName").getAsString());
                    else if (rType.equals("xp")) rList.add(rObj.get("amount").getAsString() + " مستوى خبرة");
                    else if (rType.equals("claims")) rList.add(rObj.get("amount").getAsString() + " كليم بلوك");
                    else if (rType.equals("afk")) rList.add(rObj.get("amount").getAsString() + " مفتاح AFK");
                }
                if (!rList.isEmpty()) rewardsStr = String.join(" + ", rList);
            }
        } catch (Exception ex) {}

        String statusText;
        if (status.equals("OPEN")) {
            statusText = "🟢 `التسجيل مفتوح`";
        } else if (status.equals("STARTED")) {
            statusText = "🟡 `الفعالية بدأت`";
        } else if (status.equals("FINISHED")) {
            statusText = "🌑 `انتهت الفعالية`";
        } else if (status.equals("CANCELLED")) {
            statusText = "🔴 `تم إلغاء الفعالية`";
        } else {
            statusText = "🔴 `مغلقة`";
        }

        java.util.List<net.dv8tion.jda.api.components.container.ContainerChildComponent> components = new java.util.ArrayList<>();
        components.add(TextDisplay.of("## 🎉 فعالية جديدة: " + name));
        components.add(Separator.createDivider(Separator.Spacing.SMALL));
        if (requiresLink) {
            components.add(TextDisplay.of("⚠️ **تنبيه:** هذه الفعالية تتطلب حساب ماينكرافت مربوط بالديسكورد للتسجيل."));
            components.add(Separator.createDivider(Separator.Spacing.SMALL));
        }
        components.add(TextDisplay.of("📋 **التفاصيل**"));
        components.add(TextDisplay.of("**نوع الفعالية:** `" + type + "`\n**الوقت:** <t:" + unixTime + ":F>\n**المكافآت:** `" + rewardsStr + "`\n**عدد المشاركين:** `" + currentSeats + "`"));

        if (winnerId != null && !winnerId.isEmpty()) {
            components.add(Separator.createDivider(Separator.Spacing.SMALL));
            components.add(TextDisplay.of("🏆 **الفائز في الفعالية**"));
            components.add(ActionRow.of(Button.secondary("winner_name", winnerMcName != null ? winnerMcName : "غير معروف").asDisabled()));
            components.add(TextDisplay.of("**الفائز:** <@" + winnerId + ">"));
        }

        components.add(Separator.createDivider(Separator.Spacing.SMALL));
        components.add(TextDisplay.of("📝 **الشروط**"));
        components.add(TextDisplay.of(conditions != null && !conditions.isEmpty() ? conditions : "لا توجد شروط إضافية"));
        
        components.add(Separator.createDivider(Separator.Spacing.SMALL));
        components.add(TextDisplay.of("**الحالة:** " + statusText + " | **Event ID:** `" + eventId + "`"));

        if (imageUrl != null && !imageUrl.trim().isEmpty() && imageUrl.startsWith("http") && !imageUrl.contains("localhost") && !imageUrl.contains("127.0.0.1")) {
            try {
                components.add(MediaGallery.of(MediaGalleryItem.fromUrl(imageUrl)));
            } catch (Exception ignored) {}
        }

        components.add(Separator.createDivider(Separator.Spacing.SMALL));
        
        String ticketsMention = "#tickets";
        if (guild != null) {
            java.util.List<net.dv8tion.jda.api.entities.channel.concrete.TextChannel> channels = guild.getTextChannelsByName("tickets", true);
            if (!channels.isEmpty()) {
                ticketsMention = channels.get(0).getAsMention();
            }
        }
        components.add(TextDisplay.of("> لو عندك اي استفسار تفضل 🎫 " + ticketsMention + " ← الفعاليات"));

        return Container.of(components);
    }

    public static Container getStaffContainer(String name, String mention, int eventId, String status, int participantCount, List<String> participantMentions) {
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
            Container categoryContainer = Container.of(
                TextDisplay.of("## ❓ تحديد قسم الفعالية"),
                Separator.createDivider(Separator.Spacing.SMALL),
                TextDisplay.of("يرجى اختيار القسم المخصص لهذه الفعالية لتظهر في التبويب الصحيح بالموقع:"),
                Separator.createDivider(Separator.Spacing.SMALL),
                ActionRow.of(
                    Button.primary("ev_choose_dc", "🌐 فعالية DC"),
                    Button.success("ev_choose_mc", "🎮 فعالية MC")
                )
            );
            event.replyComponents(categoryContainer).useComponentsV2(true).setEphemeral(true).queue();
            return;
        }

        if (id.equals("ev_choose_dc")) {
            Container typeContainer = Container.of(
                TextDisplay.of("## ❓ تحديد نوع الفعالية (DC)"),
                Separator.createDivider(Separator.Spacing.SMALL),
                ActionRow.of(
                    Button.primary("ev_type_dc_stage", "🎤 Stage"),
                    Button.primary("ev_type_dc_written", "💬 Written")
                )
            );
            event.replyComponents(typeContainer).useComponentsV2(true).setEphemeral(true).queue();
            return;
        }

        if (id.equals("ev_choose_mc")) {
            Container typeContainer = Container.of(
                TextDisplay.of("## ❓ تحديد نوع الفعالية (MC)"),
                Separator.createDivider(Separator.Spacing.SMALL),
                ActionRow.of(
                    Button.success("ev_type_mc_voice", "🎤 Voice"),
                    Button.success("ev_type_mc_text", "💬 Text")
                )
            );
            event.replyComponents(typeContainer).useComponentsV2(true).setEphemeral(true).queue();
            return;
        }

        if (id.startsWith("ev_type_")) {
            String[] parts = id.split("_");
            String category = parts[2].toUpperCase(); // DC or MC
            String type = parts[3]; // stage, written, voice, text
            
            TextInput nameInput = TextInput.create("ev_create_name", TextInputStyle.SHORT)
                .setRequired(true)
                .build();
            TextInput dateInput = TextInput.create("ev_create_date", TextInputStyle.SHORT)
                .setPlaceholder("مثال: 2026-06-20 21:00")
                .setRequired(true)
                .build();
            TextInput staffInput = TextInput.create("ev_create_staff", TextInputStyle.SHORT)
                .setPlaceholder("عدد الإداريين الممسكين للفعالية (حد أقصى 5)")
                .setRequired(true)
                .build();
            TextInput conditionsInput = TextInput.create("ev_create_conditions", TextInputStyle.PARAGRAPH)
                .setPlaceholder("الشروط، (أضف سؤالاً إضافياً بين قوسين في النهاية إذا أردت)")
                .setRequired(true)
                .build();

            Modal modal = Modal.create("ev_modal_create_" + category.toLowerCase() + "_" + type, "إنشاء فعالية جديدة (" + category + ")")
                .addComponents(
                    Label.of("اسم الفعالية", nameInput),
                    Label.of("تاريخ ووقت الفعالية", dateInput),
                    Label.of("عدد الإداريين", staffInput),
                    Label.of("الشروط (والسؤال الإضافي)", conditionsInput)
                )
                .build();
            
            event.replyModal(modal).queue();
            return;
        }

        if (id.equals("ev_panel_history")) {
            sendEventsList(event, "سجل الفعاليات", "SELECT id, name, status, event_date FROM events ORDER BY id DESC LIMIT 10");
            return;
        }

        if (id.equals("ev_panel_wins")) {
            sendEventsList(event, "تاريخ الفوز", "SELECT id, name, status, event_date FROM events WHERE winner_id IS NOT NULL ORDER BY id DESC LIMIT 10");
            return;
        }

        if (id.equals("ev_panel_pending")) {
            sendEventsList(event, "فعاليات معلقة", "SELECT id, name, status, event_date FROM events WHERE status IN ('OPEN', 'CLOSED') ORDER BY id DESC LIMIT 10");
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

    private void sendEventsList(ButtonInteractionEvent event, String title, String sql) {
        StringBuilder sb = new StringBuilder();
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int id = rs.getInt("id");
                String name = rs.getString("name");
                String status = rs.getString("status");
                String date = rs.getString("event_date");
                String statusEmoji = "OPEN".equals(status) ? "🟢" : ("CLOSED".equals(status) ? "🔴" : "⚫");
                sb.append(String.format("`#%d` | %s **%s** | 🕒 %s\n", id, statusEmoji, name, date));
            }
        } catch (Exception e) {
            logger.error("Error fetching events list", e);
        }

        if (sb.length() == 0) {
            sb.append("لا توجد فعاليات لعرضها حالياً.");
        }

        Container container = Container.of(
            TextDisplay.of("## 📋 " + title),
            Separator.createDivider(Separator.Spacing.SMALL),
            TextDisplay.of(sb.toString())
        );

        event.replyComponents(container).useComponentsV2(true).setEphemeral(true).queue();
    }

    private void handleRegisterClick(ButtonInteractionEvent event, int eventId) {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            boolean requiresLink = false;
            String customQuestion = null;
            int maxSeats = 0;
            String status = "OPEN";
            String category = "MC";
            String q1 = "SELECT requires_link, custom_question, max_seats, status, category FROM events WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(q1)) {
                ps.setInt(1, eventId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    requiresLink = rs.getBoolean("requires_link");
                    customQuestion = rs.getString("custom_question");
                    maxSeats = rs.getInt("max_seats");
                    status = rs.getString("status");
                    category = rs.getString("category");
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
                    if (maxSeats > 0 && rs3.getInt(1) >= maxSeats) {
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

            if ("DC".equalsIgnoreCase(category)) {
                if (customQuestion != null && !customQuestion.trim().isEmpty()) {
                    TextInput customInput = TextInput.create("custom_answer", TextInputStyle.PARAGRAPH)
                        .setRequired(true)
                        .build();
                    Modal modal = Modal.create("ev_modal_" + eventId, "تسجيل الفعالية")
                        .addComponents(Label.of(customQuestion, customInput))
                        .build();
                    event.replyModal(modal).queue();
                } else {
                    registerParticipant(event.getUser().getId(), event.getUser().getId(), eventId, event.getUser().getName(), null, null);
                    event.reply("تم تسجيلك بنجاح في الفعالية!").setEphemeral(true).queue();
                    giveEventRole(event.getGuild(), event.getUser().getId());
                    updatePublicEmbedSeats(event.getGuild(), eventId);
                    updateStaffEmbed(event.getGuild(), eventId);
                }
            } else {
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
            }

        } catch (Exception e) {
            logger.error("Error handling register click", e);
            event.reply("حدث خطأ.").setEphemeral(true).queue();
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (event.getModalId().startsWith("ev_staff_remind_")) {
            int eventId = Integer.parseInt(event.getModalId().replace("ev_staff_remind_", ""));
            String timeValue = event.getValue("time_value").getAsString();
            
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
                        thread.sendMessage("🔔 تذكير: فعالية **" + name + "** ستبدأ بعد " + timeValue + "! <@&" + EVENT_ROLE_ID + ">").queue();
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
                                u.openPrivateChannel().queue(pc -> pc.sendMessage("تذكير: فعاليتك " + finalName + " ستبدأ بعد " + timeValue + "!").queue(s -> {}, e -> {}));
                            }
                        } else {
                            String finalName = name;
                            m.getUser().openPrivateChannel().queue(pc -> pc.sendMessage("تذكير: فعاليتك " + finalName + " ستبدأ بعد " + timeValue + "!").queue(s -> {}, e -> {}));
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
            return;
        }

        if (event.getModalId().startsWith("ev_modal_create_")) {
            String[] parts = event.getModalId().split("_");
            String category = parts[3].toUpperCase();
            String type = parts[4];
            String name = event.getValue("ev_create_name").getAsString();
            String dateStr = event.getValue("ev_create_date").getAsString();
            String staffStr = event.getValue("ev_create_staff").getAsString();
            String conditions = event.getValue("ev_create_conditions").getAsString();
            
            if (!TimeUtils.isValidFormat(dateStr)) {
                event.reply("صيغة الوقت غير صحيحة! جرب صيغة مثل: 2026-06-20 21:00").setEphemeral(true).queue();
                return;
            }
            dateStr = TimeUtils.getStandardFormat(dateStr);

            int maxStaff = 1;
            try { maxStaff = Integer.parseInt(staffStr.trim()); } catch (Exception ignored) {}
            if (maxStaff > 5) {
                event.reply("الحد الاقصى للمشرفين 5").setEphemeral(true).queue();
                return;
            }
            if (maxStaff < 1) maxStaff = 1;

            int seats = maxStaff;

            String customQuestion = null;
            if (conditions.contains("(") && conditions.endsWith(")")) {
                int lastOpen = conditions.lastIndexOf("(");
                customQuestion = conditions.substring(lastOpen + 1, conditions.length() - 1);
                conditions = conditions.substring(0, lastOpen).trim();
            }

            PendingEvent pe = new PendingEvent(name, type, dateStr, new com.google.gson.JsonArray(), seats, conditions, customQuestion, event.getChannel().getId(), category);
            pendingEvents.put(event.getUser().getId(), pe);

            net.dv8tion.jda.api.components.selections.StringSelectMenu.Builder rewardMenuBuilder = net.dv8tion.jda.api.components.selections.StringSelectMenu.create("ev_reward_menu")
                .setPlaceholder("اختر نوع الجائزة للإضافة...");

            if ("DC".equalsIgnoreCase(category)) {
                rewardMenuBuilder.addOption("نقاط اوبيكس", "opics")
                    .addOption("رتبة معينة (ID)", "dc_role")
                    .addOption("نيترو (رابط)", "nitro");
            } else {
                rewardMenuBuilder.addOption("فلوس CMI", "cmi")
                    .addOption("فلوس Tokens", "tokens")
                    .addOption("رتبة (LuckPerms)", "rank")
                    .addOption("أيتم", "item")
                    .addOption("خبرة (XP)", "xp")
                    .addOption("كليمات (acb)", "claims")
                    .addOption("مفتاح AFK", "afk");
            }

            event.reply("🎁 **الرجاء إضافة جوائز للفعالية:**\nاختر من القائمة بالأسفل:")
                 .setComponents(ActionRow.of(rewardMenuBuilder.build()),
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
            
            if (type.equals("rank") || type.equals("dc_role")) {
                reward.addProperty("roleId", event.getValue("rank_id").getAsString());
            } else if (type.equals("item")) {
                reward.addProperty("itemName", event.getValue("item_name").getAsString());
                reward.addProperty("amount", event.getValue("item_amount").getAsString());
            } else if (type.equals("nitro")) {
                reward.addProperty("nitroLink", event.getValue("nitro_link").getAsString());
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
            String category = "MC";
            String q1 = "SELECT requires_link, category FROM events WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(q1)) {
                ps.setInt(1, eventId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    requiresLink = rs.getBoolean("requires_link");
                    category = rs.getString("category");
                }
            }

            String mcName = null;
            String mcUuid = null;
            if ("DC".equalsIgnoreCase(category)) {
                mcName = event.getUser().getName();
            } else if (requiresLink) {
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
            
            if ("CANCELLED".equals(newStatus) || "FINISHED".equals(newStatus)) {
                String qParts = "SELECT user_id FROM event_participants WHERE event_id = ?";
                try (PreparedStatement ps = conn.prepareStatement(qParts)) {
                    ps.setInt(1, eventId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            removeEventRole(event.getGuild(), rs.getString("user_id"));
                        }
                    }
                } catch (Exception e) {
                    logger.error("Error removing roles on event end", e);
                }
            }
            
            int supabaseId = -1;
            String category = null;
            try (PreparedStatement ps = conn.prepareStatement("SELECT supabase_id, category FROM events WHERE id = ?")) {
                ps.setInt(1, eventId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        supabaseId = rs.getInt("supabase_id");
                        category = rs.getString("category");
                    }
                }
            }
            
            if (supabaseId > 0 && category != null) {
                com.highcore.bot.database.SupabaseManager supa = LeonTrotskyBot.getSupabaseManager();
                if (supa != null) {
                    try {
                        String table = "DC".equalsIgnoreCase(category) ? "events" : "mc_events";
                        String jsonPayload = "{\"status\": \"" + newStatus + "\"}";
                        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create(supa.getSupabaseUrl() + "/rest/v1/" + table + "?id=eq." + supabaseId))
                            .timeout(java.time.Duration.ofSeconds(10))
                            .header("apikey", supa.getSupabaseKey())
                            .header("Authorization", "Bearer " + supa.getSupabaseKey())
                            .header("Content-Type", "application/json")
                            .method("PATCH", java.net.http.HttpRequest.BodyPublishers.ofString(jsonPayload))
                            .build();
                        supa.getHttpClient().sendAsync(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                    } catch (Exception e) {
                        logger.error("Error updating supabase status", e);
                    }
                }
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
                int count = 0;
                while (rs.next()) {
                    count++;
                    String dc = rs.getString("discord_id");
                    String mc = rs.getString("mc_name");
                    String ans = rs.getString("custom_answer");
                    if (ans == null) ans = "N/A";
                    sb.append(count).append(". <@").append(dc).append("> | **").append(mc).append("** | `").append(ans).append("`\n");
                }
                
                if (sb.length() == 0) {
                    event.getHook().editOriginal("لا يوجد مشاركين حتى الآن.").queue();
                    return;
                }
                
                net.dv8tion.jda.api.EmbedBuilder eb = new net.dv8tion.jda.api.EmbedBuilder();
                eb.setTitle("قائمة المشاركين - الفعالية #" + eventId);
                eb.setColor(java.awt.Color.decode("#5865F2"));
                
                String desc = sb.toString();
                if (desc.length() > 4096) {
                    desc = desc.substring(0, 4090) + "...";
                }
                eb.setDescription(desc);
                
                event.getHook().editOriginalEmbeds(eb.build()).queue();
            }
        } catch (Exception e) {
            logger.error("Error exporting", e);
            event.getHook().editOriginal("حدث خطأ.").queue();
        }
    }

    private void handleNotify(ButtonInteractionEvent event, int eventId) {
        TextInput timeInput = TextInput.create("time_value", TextInputStyle.SHORT)
            .setPlaceholder("مثال: 30 دقيقة, ساعة, 10 دقائق")
            .setMinLength(1)
            .setMaxLength(50)
            .setRequired(true)
            .build();
            
        Modal modal = Modal.create("ev_staff_remind_" + eventId, "إرسال تذكير للفعالية")
            .addComponents(net.dv8tion.jda.api.components.label.Label.of("الوقت المتبقي للفعالية", timeInput))
            .build();
            
        event.replyModal(modal).queue();
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

            final long fUnixTime = TimeUtils.parseToUnixTimestamp(date);
            final ActionRow fActionRow = ActionRow.of(getPublicButtons(eventId, status));
            
            final String fName = name;
            final String fType = type;
            final String fRewards = rewards;
            final int fMaxSeats = maxSeats;
            final int fCurrentSeats = currentSeats;
            final String fConditions = conditions;
            final String fStatus = status;
            final int fEventId = eventId;

            final String fWinnerId = winnerId;
            final String fWinnerMcName = winnerMcName;
            final boolean fRequiresLink = requiresLink;
            ThreadChannel thread = guild.getThreadChannelById(channelId);
            if (thread != null) {
                final String finalImageUrl = imageUrl;
                thread.retrieveMessageById(messageId).queue(msg -> {
                    String actualImageUrl = finalImageUrl;
                    if (actualImageUrl == null && !msg.getEmbeds().isEmpty() && msg.getEmbeds().get(0).getImage() != null) {
                        actualImageUrl = msg.getEmbeds().get(0).getImage().getUrl();
                    }
                    
                    Container eventContainer = getPublicEventContainer(fName, fType, fUnixTime, fRewards, fMaxSeats, fCurrentSeats, fConditions, fStatus, fEventId, actualImageUrl, fWinnerId, fWinnerMcName, guild, fRequiresLink);

                    MessageEditBuilder editBuilder = new MessageEditBuilder()
                        .setComponents(eventContainer, fActionRow)
                        .useComponentsV2(true)
                        .setEmbeds(); // Clear existing embeds

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

    public static List<Button> getPublicButtons(int eventId, String status) {
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

    public static List<Button> getStaffButtons(int eventId, String status) {
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
            
            if (type.equals("rank") || type.equals("dc_role")) {
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
            } else if (type.equals("nitro")) {
                TextInput nitroInput = TextInput.create("nitro_link", TextInputStyle.SHORT)
                    .setRequired(true)
                    .build();
                modalBuilder.addComponents(net.dv8tion.jda.api.components.label.Label.of("رابط النيترو (https://discord.gift/...)", nitroInput));
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
            event.deferReply(true).queue();
            int eventId = Integer.parseInt(event.getComponentId().replace("ev_staff_distribute_", ""));
            User winner = event.getMentions().getUsers().get(0);
            if (winner == null) {
                event.getHook().sendMessage("يرجى اختيار فائز!").queue();
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
                    event.getHook().sendMessage("لا توجد جوائز مسجلة لهذه الفعالية!").queue();
                    return;
                }

                boolean hasMcReward = false;
                JsonArray rewards = JsonParser.parseString(rewardsJson).getAsJsonArray();
                for (int i = 0; i < rewards.size(); i++) {
                    String type = rewards.get(i).getAsJsonObject().get("type").getAsString();
                    if (!type.equals("opics") && !type.equals("dc_role") && !type.equals("nitro")) {
                        hasMcReward = true;
                    }
                }

                if (hasMcReward && (mcName == null || mcName.equals("Unknown") || mcName.equals("مجهول"))) {
                    event.getHook().sendMessage("اللاعب غير مسجل في الفعالية أو لم يتم العثور على اسمه في ماينكرافت! (يحتاج اسم ماينكرافت لتوزيع الجوائز)").queue();
                    return;
                }


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
                        case "opics":
                            int amt = Integer.parseInt(r.get("amount").getAsString());
                            giveOpexyPoints(event.getGuild().getId(), winner.getId(), amt);
                            break;
                        case "dc_role":
                            String rId = r.get("roleId").getAsString();
                            net.dv8tion.jda.api.entities.Role role = event.getGuild().getRoleById(rId);
                            if (role != null) {
                                event.getGuild().addRoleToMember(winner, role).queue();
                            }
                            break;
                        case "nitro":
                            // Delivered in DM
                            break;
                    }
                    if (!cmd.isEmpty() && ptero != null) {
                        ptero.sendConsoleCommand(cmd);
                    }
                }

                try {
                    String updateWinner = "UPDATE events SET winner_id = ? WHERE id = ?";
                    try (PreparedStatement ps = conn.prepareStatement(updateWinner)) {
                        ps.setString(1, winner.getId());
                        ps.setInt(2, eventId);
                        ps.executeUpdate();
                    }
                } catch (Exception e) {
                    logger.warn("Could not update winner_id in events table", e);
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
                        Container winContainer = Container.of(
                            TextDisplay.of("## 🎉 الفائز بالفعالية!"),
                            Separator.createDivider(Separator.Spacing.SMALL),
                            TextDisplay.of("تهانينا للاعب **" + mcName + "** (" + winner.getAsMention() + ") بمناسبة الفوز بالفعالية! 🏆\n\nتم تسليم الجوائز بنجاح.")
                        );
                        net.dv8tion.jda.api.utils.messages.MessageCreateBuilder winBuilder = new net.dv8tion.jda.api.utils.messages.MessageCreateBuilder()
                            .setComponents(winContainer)
                            .useComponentsV2(true);
                        thread.sendMessage(winBuilder.build()).queue();
                    }
                }

                String dmMsg = "لقد فزت في الفعالية بحسابك **" + (mcName != null ? mcName : "غير متوفر") + "**!\n\nتم تسليم جوائز الفعالية لحسابك بنجاح، نتمنى لك وقتاً ممتعاً! 🎁";
                if (!hasMcReward) {
                    dmMsg = "لقد فزت في الفعالية!\n\nتم تسليم الجوائز بنجاح، نتمنى لك وقتاً ممتعاً! 🎁";
                }
                
                for (int i = 0; i < rewards.size(); i++) {
                    JsonObject r = rewards.get(i).getAsJsonObject();
                    if (r.get("type").getAsString().equals("nitro")) {
                        dmMsg += "\n\nرابط النيترو الخاص بك: " + r.get("nitroLink").getAsString();
                    }
                }

                String finalDmMsg = dmMsg;
                winner.openPrivateChannel().queue(privateChannel -> {
                    Container dmContainer = Container.of(
                        TextDisplay.of("## 🎉 مبروك الفوز في الفعالية!"),
                        Separator.createDivider(Separator.Spacing.SMALL),
                        TextDisplay.of(finalDmMsg)
                    );
                    net.dv8tion.jda.api.utils.messages.MessageCreateBuilder dmBuilder = new net.dv8tion.jda.api.utils.messages.MessageCreateBuilder()
                        .setComponents(dmContainer)
                        .useComponentsV2(true);
                    privateChannel.sendMessage(dmBuilder.build()).queue();
                }, error -> logger.warn("Failed to send DM to winner " + winner.getId()));

                event.getHook().sendMessage("تم توزيع الجوائز على اللاعب **" + mcName + "** (" + winner.getAsMention() + ") بنجاح! 🏆").queue();
            } catch(Exception e) {
                logger.error("Error distributing rewards", e);
                event.getHook().sendMessage("حدث خطأ أثناء توزيع الجوائز.").queue();
            }
        }
    }

    private String formatReward(JsonObject r) {
        String type = r.get("type").getAsString();
        switch (type) {
            case "opics": return r.get("amount").getAsString() + " نقاط أوبيكس";
            case "dc_role": return "رتبة ديسكورد (ID: " + r.get("roleId").getAsString() + ")";
            case "nitro": return "نيترو (" + r.get("nitroLink").getAsString() + ")";
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

    private void giveOpexyPoints(String guildId, String userId, int amount) {
        String dbUrl = "jdbc:postgresql://shuttle.proxy.rlwy.net:24812/railway";
        String user = "postgres";
        String pass = "XFToIKMrXECxyBkEHChsdDqyqIvQEsMT";
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(dbUrl, user, pass)) {
            String check = "SELECT event_points FROM user_entity WHERE guild_id = ? AND user_id = ?";
            boolean exists = false;
            try (PreparedStatement ps = conn.prepareStatement(check)) {
                ps.setString(1, guildId);
                ps.setString(2, userId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) exists = true;
            }
            if (exists) {
                String update = "UPDATE user_entity SET event_points = event_points + ? WHERE guild_id = ? AND user_id = ?";
                try (PreparedStatement ps = conn.prepareStatement(update)) {
                    ps.setInt(1, amount);
                    ps.setString(2, guildId);
                    ps.setString(3, userId);
                    ps.executeUpdate();
                }
            } else {
                String insert = "INSERT INTO user_entity (guild_id, user_id, event_points) VALUES (?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(insert)) {
                    ps.setString(1, guildId);
                    ps.setString(2, userId);
                    ps.setInt(3, amount);
                    ps.executeUpdate();
                }
            }
        } catch (Exception e) {
            logger.error("Failed to give opexy points", e);
        }
    }
}
