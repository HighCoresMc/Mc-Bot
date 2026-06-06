package com.highcore.bot.commands;

import com.highcore.bot.LeonTrotskyBot;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.EmbedBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

// Commands
public class TeamCommand extends ListenerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(TeamCommand.class);

    private static final String LEADER_ROLE_ID      = "1512457786548682863";
    private static final String ANNOUNCE_CHANNEL_ID = "1512461188997578984";
    private static final String MANAGER_ROLE_ID     = "1487195816220430406";
    private static final String LOG_CHANNEL_ID      = "1512492553793044521";

    private static ScheduledExecutorService tagScheduler;

    // ===================================================================
    // Tag Scheduler
    // ===================================================================

    public static void startTagScheduler() {
        tagScheduler = Executors.newSingleThreadScheduledExecutor(R -> {
            Thread t = new Thread(R, "TeamTagScheduler");
            t.setDaemon(true);
            return t;
        });
        tagScheduler.scheduleAtFixedRate(TeamCommand::checkAndUpdateTags, 1, 12, TimeUnit.HOURS);
        logger.info("Team tag scheduler started.");
    }

    private static void checkAndUpdateTags() {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String sql = "SELECT id, name FROM teams WHERE tag = 'New Born' AND created_at <= DATE_SUB(NOW(), INTERVAL 3 DAY)";
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int     teamId   = rs.getInt("id");
                    String teamName = rs.getString("name");

                    try (PreparedStatement upd = conn.prepareStatement(
                            "UPDATE teams SET tag = 'Approved' WHERE id = ?")) {
                        upd.setInt(1, teamId);
                        upd.executeUpdate();
                    }

                    com.highcore.bot.database.SupabaseManager supa = LeonTrotskyBot.getSupabaseManager();
                    if (supa != null) supa.updateTeamTag(teamName, "Approved");

                    logger.info("Team '{}' tag updated to Approved.", teamName);
                }
            }
        } catch (Exception e) {
            logger.error("Error in team tag scheduler", e);
        }
    }

    // ===================================================================
    // Slash Command Router
    // ===================================================================

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("team")) return;

        Member member = event.getMember();
        if (member == null) return;

        if (!hasManagerRole(member)) {
            event.reply("انت لست اداري لاستخدام هاذا الامر").setEphemeral(true).queue();
            return;
        }

        String sub = event.getSubcommandName();
        if ("create".equals(sub)) {
            handleCreate(event);
        } else if ("edit".equals(sub)) {
            handleEdit(event);
        } else {
            showDashboard(event);
        }
    }

    private boolean hasManagerRole(Member member) {
        if (member.hasPermission(Permission.ADMINISTRATOR)) return true;
        return member.getRoles().stream().anyMatch(R -> R.getId().equals(MANAGER_ROLE_ID));
    }

    // ===================================================================
    // Dashboard
    // ===================================================================

    private void showDashboard(SlashCommandInteractionEvent event) {
        int            count = getTeamCount();
        List<TeamData> teams = getTeamList();

        StringBuilder sb = new StringBuilder();
        sb.append("## 🏆 لوحة تحكم الفرق\n\n");
        sb.append("**إجمالي الفرق:** `").append(count).append("`\n\n");

        if (!teams.isEmpty()) {
            for (TeamData t : teams) {
                sb.append(tagEmoji(t.tag)).append(" **").append(t.name)
                  .append("** | 🎨 `").append(t.color).append("` | 🏷️ `").append(t.tag).append("`\n");
            }
        } else {
            sb.append("*لا توجد فرق بعد.*");
        }

        sb.append("\n\n> لإنشاء فريق جديد استخدم `/team create`\n> للتعديل استخدم `/team edit`");

        Container container = Container.of(
            TextDisplay.of(sb.toString()),
            Separator.createDivider(Separator.Spacing.SMALL),
            ActionRow.of(
                Button.primary("tm_panel_list",   "📋 عرض الفرق"),
                Button.secondary("tm_panel_edit", "✏️ تعديل فريق"),
                Button.danger("tm_panel_delete",  "🗑️ حذف فريق")
            )
        );

        event.replyComponents(container).useComponentsV2(true).setEphemeral(true).queue();
    }

    // ===================================================================
    // Create
    // ===================================================================

    private void handleCreate(SlashCommandInteractionEvent event) {
        String teamName  = event.getOption("name").getAsString().trim();
        String colorCode = event.getOption("color").getAsString().trim();
        Member leader    = event.getOption("leader").getAsMember();
        Member member2   = event.getOption("member2").getAsMember();
        Member member3   = event.getOption("member3") != null ? event.getOption("member3").getAsMember() : null;
        Member member4   = event.getOption("member4") != null ? event.getOption("member4").getAsMember() : null;

        if (leader == null || member2 == null) {
            event.reply("❌ خطأ في تحديد الأعضاء.").setEphemeral(true).queue();
            return;
        }
        if (teamExistsByName(teamName)) {
            event.reply("❌ يوجد فريق بهذا الاسم بالفعل!").setEphemeral(true).queue();
            return;
        }

        Color color;
        try {
            String hex = colorCode.startsWith("#") ? colorCode : "#" + colorCode;
            color = Color.decode(hex);
        } catch (Exception e) {
            event.reply("❌ كود اللون غير صحيح! مثال: `#FF5733`").setEphemeral(true).queue();
            return;
        }

        event.deferReply().setEphemeral(true).queue();

        final Guild      guild      = event.getGuild();
        final Color      finalColor = color;
        final Member m3         = member3;
        final Member m4         = member4;
        final Member fLeader    = leader;
        final Member fMember2   = member2;
        final String fColor     = colorCode.startsWith("#") ? colorCode : "#" + colorCode;

        guild.createRole()
            .setName(teamName)
            .setColor(finalColor)
            .queue(teamRole -> {

                Role leaderRole = guild.getRoleById(LEADER_ROLE_ID);
                if (leaderRole != null) {
                    guild.addRoleToMember(fLeader, leaderRole).queue(null, E -> {});
                }

                guild.addRoleToMember(fLeader,  teamRole).queue(null, E -> {});
                guild.addRoleToMember(fMember2, teamRole).queue(null, E -> {});
                if (m3 != null) guild.addRoleToMember(m3, teamRole).queue(null, E -> {});
                if (m4 != null) guild.addRoleToMember(m4, teamRole).queue(null, E -> {});

                guild.createCategory("<— " + teamName + " —>")
                    .queue(category -> {

                        category.upsertPermissionOverride(guild.getPublicRole())
                            .deny(Permission.VIEW_CHANNEL).queue();
                        category.upsertPermissionOverride(teamRole)
                            .grant(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND,
                                   Permission.MESSAGE_HISTORY, Permission.VOICE_CONNECT,
                                   Permission.VOICE_SPEAK, Permission.VOICE_USE_VAD)
                            .queue();

                        guild.createVoiceChannel("🔊・" + teamName + "・voice")
                            .setParent(category)
                            .queue(vc -> {
                                vc.getManager().sync().queue();

                                guild.createTextChannel("💭・" + teamName)
                                    .setParent(category)
                                    .queue(tc -> {
                                        tc.getManager().sync().queue();

                                        String lId  = fLeader.getId();
                                        String m2Id = fMember2.getId();
                                        String m3Id = m3 != null ? m3.getId() : null;
                                        String m4Id = m4 != null ? m4.getId() : null;

                                        int dbId = saveTeamToDb(
                                            teamName, fColor,
                                            teamRole.getId(), category.getId(),
                                            vc.getId(), tc.getId(),
                                            lId, m2Id, m3Id, m4Id
                                        );

                                        sendAnnouncementAndSave(
                                            guild, teamName, fLeader, fMember2, m3, m4, fColor, dbId, false
                                        );

                                        sendLogMessage(guild, "صنع فريق جديد", "تم إنشاء الفريق **" + teamName + "** بواسطة " + event.getUser().getAsMention(), Color.GREEN);

                                        event.getHook().editOriginal(
                                            "✅ تم إنشاء فريق **" + teamName + "** بنجاح!"
                                        ).queue();
                                    });
                            });
                    });
            });
    }

    private int saveTeamToDb(String name, String color, String roleId, String catId,
                              String voiceId, String textId, String lId,
                              String m2, String m3, String m4) {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String sql = "INSERT INTO teams (name, color, role_id, category_id, voice_channel_id, " +
                         "text_channel_id, leader_id, member2_id, member3_id, member4_id, tag) " +
                         "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'New Born')";
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name);
                ps.setString(2, color);
                ps.setString(3, roleId);
                ps.setString(4, catId);
                ps.setString(5, voiceId);
                ps.setString(6, textId);
                ps.setString(7, lId);
                ps.setString(8, m2);
                ps.setString(9, m3);
                ps.setString(10, m4);
                ps.executeUpdate();

                ResultSet rs = ps.getGeneratedKeys();
                if (rs.next()) {
                    int id = rs.getInt(1);
                    syncToSupabase(name, color, lId, m2, m3, m4, "New Born", null);
                    return id;
                }
            }
        } catch (Exception e) {
            logger.error("Error saving team to DB", e);
        }
        return -1;
    }

    // ===================================================================
    // Edit
    // ===================================================================

    private void handleEdit(SlashCommandInteractionEvent event) {
        String teamOpt = event.getOption("team") != null
            ? event.getOption("team").getAsString().trim() : null;

        if (teamOpt != null && !teamOpt.isEmpty()) {
            TeamData td = getTeamByName(teamOpt);
            if (td == null) {
                event.reply("❌ لم يتم العثور على فريق باسم `" + teamOpt + "`.").setEphemeral(true).queue();
                return;
            }
            replyWithTeamManageEmbed(event, td);
        } else {
            List<TeamData> teams = getTeamList();
            if (teams.isEmpty()) {
                event.reply("❌ لا توجد فرق حالياً.").setEphemeral(true).queue();
                return;
            }
            StringSelectMenu.Builder menu = StringSelectMenu.create("tm_select_edit")
                .setPlaceholder("اختر الفريق الذي تريد تعديله...");
            teams.stream().limit(25).forEach(T -> menu.addOption(T.name, String.valueOf(T.id), T.tag));

            event.replyComponents(
                Container.of(
                    TextDisplay.of("## ✏️ تعديل فريق\nاختر الفريق:"),
                    Separator.createDivider(Separator.Spacing.SMALL),
                    ActionRow.of(menu.build())
                )
            ).useComponentsV2(true).setEphemeral(true).queue();
        }
    }

    private void replyWithTeamManageEmbed(SlashCommandInteractionEvent event, TeamData td) {
        Guild guild = event.getGuild();
        event.replyComponents(
            buildTeamInfoContainer(guild, td, ActionRow.of(
                Button.primary("tm_edit_"         + td.id, "✏️ تعديل المعلومات"),
                Button.secondary("tm_edit_members_" + td.id, "👥 تعديل الأعضاء")
            ))
        ).useComponentsV2(true).setEphemeral(true).queue();
    }

    private void replyWithTeamManageEmbed(ButtonInteractionEvent event, TeamData td) {
        Guild guild = event.getGuild();
        event.replyComponents(
            buildTeamInfoContainer(guild, td, ActionRow.of(
                Button.primary("tm_edit_"          + td.id, "✏️ تعديل المعلومات"),
                Button.secondary("tm_edit_members_" + td.id, "👥 تعديل الأعضاء")
            ))
        ).useComponentsV2(true).setEphemeral(true).queue();
    }

    private Container buildTeamInfoContainer(Guild guild, TeamData td, ActionRow actionRow) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 🏆 ").append(td.name).append("\n\n");
        sb.append("🎨 **Color:** `").append(td.color).append("`\n");
        sb.append("**Leader:** ").append(resolveDisplay(guild, td.leaderId)).append("\n");
        sb.append("**Member 2:** ").append(resolveDisplay(guild, td.member2Id)).append("\n");
        if (td.member3Id != null) sb.append("**Member 3:** ").append(resolveDisplay(guild, td.member3Id)).append("\n");
        if (td.member4Id != null) sb.append("**Member 4:** ").append(resolveDisplay(guild, td.member4Id)).append("\n");
        sb.append("\n🏷️ **Tag:** ").append(tagEmoji(td.tag)).append(" `").append(td.tag).append("`");
        
        if (actionRow != null) {
            return Container.of(TextDisplay.of(sb.toString()), Separator.createDivider(Separator.Spacing.SMALL), actionRow);
        }
        return Container.of(TextDisplay.of(sb.toString()));
    }

    // ===================================================================
    // Button Interactions
    // ===================================================================

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith("tm_")) return;

        Member member = event.getMember();
        if (member == null || !hasManagerRole(member)) {
            event.reply("انت لست اداري لاستخدام هاذا الامر").setEphemeral(true).queue();
            return;
        }

        if (id.equals("tm_panel_list")) {
            handlePanelList(event);
        } else if (id.equals("tm_panel_edit")) {
            handlePanelEditButton(event);
        } else if (id.equals("tm_panel_delete")) {
            handlePanelDelete(event);
        } else if (id.startsWith("tm_confirm_delete_")) {
            handleDeleteReasonModal(event, Integer.parseInt(id.replace("tm_confirm_delete_", "")));
        } else if (id.equals("tm_cancel")) {
            event.editMessage("تم إلغاء العملية.").setComponents(Collections.emptyList()).queue();
        } else if (id.startsWith("tm_edit_members_")) {
            handleEditMembersButton(event, Integer.parseInt(id.replace("tm_edit_members_", "")));
        } else if (id.startsWith("tm_edit_")) {
            handleEditButton(event, Integer.parseInt(id.replace("tm_edit_", "")));
        } else if (id.startsWith("tm_view_")) {
            TeamData td = getTeamById(Integer.parseInt(id.replace("tm_view_", "")));
            if (td != null) replyWithTeamManageEmbed(event, td);
            else event.reply("❌ الفريق غير موجود.").setEphemeral(true).queue();
        }
    }

    private void handlePanelList(ButtonInteractionEvent event) {
        List<TeamData> teams = getTeamList();
        if (teams.isEmpty()) {
            event.reply("❌ لا توجد فرق حالياً.").setEphemeral(true).queue();
            return;
        }

        StringBuilder sb = new StringBuilder("## 📋 قائمة الفرق\n\n");
        for (TeamData t : teams) {
            sb.append(tagEmoji(t.tag)).append(" **").append(t.name)
              .append("** | 🎨 `").append(t.color).append("`\n");
        }

        StringSelectMenu.Builder menu = StringSelectMenu.create("tm_select_view")
            .setPlaceholder("اختر فريقاً لعرض تفاصيله...");
        teams.stream().limit(25).forEach(T -> menu.addOption(T.name, String.valueOf(T.id), T.tag));

        event.replyComponents(
            Container.of(
                TextDisplay.of(sb.toString()),
                Separator.createDivider(Separator.Spacing.SMALL),
                ActionRow.of(menu.build())
            )
        ).useComponentsV2(true).setEphemeral(true).queue();
    }

    private void handlePanelEditButton(ButtonInteractionEvent event) {
        List<TeamData> teams = getTeamList();
        if (teams.isEmpty()) {
            event.reply("❌ لا توجد فرق للتعديل.").setEphemeral(true).queue();
            return;
        }

        StringSelectMenu.Builder menu = StringSelectMenu.create("tm_select_edit")
            .setPlaceholder("اختر الفريق الذي تريد تعديله...");
        teams.stream().limit(25).forEach(T -> menu.addOption(T.name, String.valueOf(T.id), T.tag));

        event.replyComponents(
            Container.of(
                TextDisplay.of("## ✏️ تعديل فريق\nاختر الفريق:"),
                Separator.createDivider(Separator.Spacing.SMALL),
                ActionRow.of(menu.build())
            )
        ).useComponentsV2(true).setEphemeral(true).queue();
    }

    private void handlePanelDelete(ButtonInteractionEvent event) {
        List<TeamData> teams = getTeamList();
        if (teams.isEmpty()) {
            event.reply("❌ لا توجد فرق للحذف.").setEphemeral(true).queue();
            return;
        }

        StringSelectMenu.Builder menu = StringSelectMenu.create("tm_select_delete")
            .setPlaceholder("اختر الفريق الذي تريد حذفه...");
        teams.stream().limit(25).forEach(T -> menu.addOption(T.name, String.valueOf(T.id), T.tag));

        event.replyComponents(
            Container.of(
                TextDisplay.of("## 🗑️ حذف فريق\n⚠️ **تحذير:** هذه العملية لا يمكن التراجع عنها!"),
                Separator.createDivider(Separator.Spacing.SMALL),
                ActionRow.of(menu.build())
            )
        ).useComponentsV2(true).setEphemeral(true).queue();
    }

    private void handleDeleteReasonModal(ButtonInteractionEvent event, int teamId) {
        TextInput reasonInput = TextInput.create("tm_delete_reason", "سبب الحذف", TextInputStyle.PARAGRAPH)
            .setPlaceholder("اكتب سبب حذف الفريق هنا...")
            .setRequired(true)
            .setMaxLength(200)
            .build();

        Modal modal = Modal.create("tm_modal_delete_" + teamId, "سبب حذف الفريق")
            .addComponents(Label.of("السبب", reasonInput))
            .build();

        event.replyModal(modal).queue();
    }

    private void handleConfirmDeleteActual(ModalInteractionEvent event, int teamId, String reason) {
        event.deferReply().setEphemeral(true).queue();

        TeamData td = getTeamById(teamId);
        if (td == null) {
            event.getHook().editOriginal("❌ الفريق غير موجود.").queue();
            return;
        }

        Guild guild = event.getGuild();
        
        if (td.announcementMessageId != null) {
            TextChannel ch = guild.getTextChannelById(ANNOUNCE_CHANNEL_ID);
            if (ch != null) {
                ch.deleteMessageById(td.announcementMessageId).queue(null, E -> logger.error("Failed to delete announcement message"));
            }
        }

        if (td.leaderId != null) {
            guild.retrieveMemberById(td.leaderId).queue(leaderMember -> {
                Role leaderRole = guild.getRoleById(LEADER_ROLE_ID);
                if (leaderRole != null && leaderMember.getRoles().contains(leaderRole)) {
                    guild.removeRoleFromMember(leaderMember, leaderRole).queue(null, E -> {});
                }
                leaderMember.getUser().openPrivateChannel().queue(pc -> {
                    pc.sendMessage("❌ تم حذف فريقك **" + td.name + "**\n**السبب:** " + reason).queue(null, E -> logger.error("Failed to send DM to leader"));
                });
            }, E -> {});
        }

        deleteDiscordResources(guild, td, () -> {
            deleteTeamFromDb(teamId, td.name);
            sendLogMessage(guild, "حذف فريق", "تم حذف الفريق **" + td.name + "** بواسطة " + event.getUser().getAsMention() + "\n**السبب:** " + reason, Color.RED);
            event.getHook().editOriginal("✅ تم حذف فريق **" + td.name + "** بنجاح! 🗑️").queue();
        });
    }

    private void handleEditButton(ButtonInteractionEvent event, int teamId) {
        TeamData td = getTeamById(teamId);
        if (td == null) {
            event.reply("❌ الفريق غير موجود.").setEphemeral(true).queue();
            return;
        }

        TextInput nameInput  = TextInput.create("tm_name", TextInputStyle.SHORT)
            .setValue(td.name).setMaxLength(50).setRequired(true).build();
        TextInput colorInput = TextInput.create("tm_color", TextInputStyle.SHORT)
            .setValue(td.color).setMaxLength(20).setRequired(true).build();

        Modal modal = Modal.create("tm_modal_edit_" + teamId, "تعديل الفريق: " + td.name)
            .addComponents(
                Label.of("اسم الفريق الجديد", nameInput),
                Label.of("كود اللون الجديد (مثال: #FF5733)", colorInput)
            )
            .build();

        event.replyModal(modal).queue();
    }

    private void handleEditMembersButton(ButtonInteractionEvent event, int teamId) {
        TeamData td = getTeamById(teamId);
        if (td == null) {
            event.reply("❌ الفريق غير موجود.").setEphemeral(true).queue();
            return;
        }

        TextInput leaderInput = TextInput.create("tm_leader", TextInputStyle.SHORT)
            .setValue(td.leaderId  != null ? td.leaderId : "")
            .setPlaceholder("@mention أو Discord ID").setRequired(true).build();
        TextInput m2Input = TextInput.create("tm_m2", TextInputStyle.SHORT)
            .setValue(td.member2Id != null ? td.member2Id : "")
            .setPlaceholder("@mention أو Discord ID").setRequired(true).build();
        TextInput m3Input = TextInput.create("tm_m3", TextInputStyle.SHORT)
            .setValue(td.member3Id != null ? td.member3Id : "")
            .setPlaceholder("@mention أو Discord ID (اختياري)").setRequired(false).build();
        TextInput m4Input = TextInput.create("tm_m4", TextInputStyle.SHORT)
            .setValue(td.member4Id != null ? td.member4Id : "")
            .setPlaceholder("@mention أو Discord ID (اختياري)").setRequired(false).build();

        Modal modal = Modal.create("tm_modal_members_" + teamId, "تعديل أعضاء: " + td.name)
            .addComponents(
                Label.of("القائد (Leader)", leaderInput),
                Label.of("العضو الثاني", m2Input),
                Label.of("العضو الثالث (اختياري)", m3Input),
                Label.of("العضو الرابع (اختياري)", m4Input)
            )
            .build();

        event.replyModal(modal).queue();
    }

    // ===================================================================
    // String Select Interactions
    // ===================================================================

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith("tm_select_")) return;

        Member member = event.getMember();
        if (member == null || !hasManagerRole(member)) {
            event.reply("انت لست اداري لاستخدام هاذا الامر").setEphemeral(true).queue();
            return;
        }

        int       teamId = Integer.parseInt(event.getSelectedOptions().get(0).getValue());
        TeamData td     = getTeamById(teamId);
        if (td == null) {
            event.reply("❌ الفريق غير موجود!").setEphemeral(true).queue();
            return;
        }

        if (id.equals("tm_select_delete")) {
            event.replyComponents(
                Container.of(
                    TextDisplay.of("## ⚠️ تأكيد الحذف\n\nهل أنت متأكد من حذف فريق **" + td.name + "**?\n" +
                                   "سيتم حذف الكاتيقوري والقنوات والرتبة نهائياً!"),
                    Separator.createDivider(Separator.Spacing.SMALL),
                    ActionRow.of(
                        Button.danger("tm_confirm_delete_" + teamId, "✅ نعم، احذف"),
                        Button.secondary("tm_cancel", "❌ إلغاء")
                    )
                )
            ).useComponentsV2(true).setEphemeral(true).queue();

        } else if (id.equals("tm_select_edit")) {
            Guild guild = event.getGuild();
            event.replyComponents(
                buildTeamInfoContainer(guild, td, ActionRow.of(
                    Button.primary("tm_edit_"          + td.id, "✏️ تعديل المعلومات"),
                    Button.secondary("tm_edit_members_" + td.id, "👥 تعديل الأعضاء")
                ))
            ).useComponentsV2(true).setEphemeral(true).queue();

        } else if (id.equals("tm_select_view")) {
            Guild guild = event.getGuild();
            event.replyComponents(
                buildTeamInfoContainer(guild, td, null)
            ).useComponentsV2(true).setEphemeral(true).queue();
        }
    }

    // ===================================================================
    // Modal Interactions
    // ===================================================================

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        String id = event.getModalId();
        if (id.startsWith("tm_modal_edit_")) {
            handleEditInfoModal(event, Integer.parseInt(id.replace("tm_modal_edit_", "")));
        } else if (id.startsWith("tm_modal_members_")) {
            handleEditMembersModal(event, Integer.parseInt(id.replace("tm_modal_members_", "")));
        } else if (id.startsWith("tm_modal_delete_")) {
            handleConfirmDeleteActual(event, Integer.parseInt(id.replace("tm_modal_delete_", "")), event.getValue("tm_delete_reason").getAsString().trim());
        }
    }

    private void handleEditInfoModal(ModalInteractionEvent event, int teamId) {
        String newName  = event.getValue("tm_name").getAsString().trim();
        String newColor = event.getValue("tm_color").getAsString().trim();

        event.deferReply().setEphemeral(true).queue();

        TeamData td = getTeamById(teamId);
        if (td == null) {
            event.getHook().editOriginal("❌ الفريق غير موجود!").queue();
            return;
        }

        Color color;
        try {
            String hex = newColor.startsWith("#") ? newColor : "#" + newColor;
            color = Color.decode(hex);
        } catch (Exception e) {
            event.getHook().editOriginal("❌ كود اللون غير صحيح!").queue();
            return;
        }

        final String fColor   = newColor.startsWith("#") ? newColor : "#" + newColor;
        final String oldName  = td.name;
        final Guild  guild    = event.getGuild();

        if (td.roleId != null) {
            Role role = guild.getRoleById(td.roleId);
            if (role != null) role.getManager().setName(newName).setColor(color).queue(null, E -> {});
        }
        if (td.categoryId != null) {
            Category cat = guild.getCategoryById(td.categoryId);
            if (cat != null) cat.getManager().setName("<— " + newName + " —>").queue(null, E -> {});
        }
        if (td.voiceChannelId != null) {
            VoiceChannel vc = guild.getVoiceChannelById(td.voiceChannelId);
            if (vc != null) vc.getManager().setName("🔊・" + newName + "・voice").queue(null, E -> {});
        }
        if (td.textChannelId != null) {
            TextChannel tc = guild.getTextChannelById(td.textChannelId);
            if (tc != null) tc.getManager().setName("💭・" + newName).queue(null, E -> {});
        }

        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE teams SET name = ?, color = ?, tag = 'Modified' WHERE id = ?")) {
            ps.setString(1, newName);
            ps.setString(2, fColor);
            ps.setInt(3, teamId);
            ps.executeUpdate();
        } catch (Exception e) {
            logger.error("Error updating team info in DB", e);
        }

        syncToSupabase(newName, fColor, td.leaderId, td.member2Id, td.member3Id, td.member4Id,
                       "Modified", oldName.equals(newName) ? null : oldName);

        updateAnnouncementEmbed(guild, teamId, newName, td.leaderId, td.member2Id,
                                td.member3Id, td.member4Id, fColor);

        sendLogMessage(guild, "تعديل معلومات الفريق", "تم تعديل معلومات الفريق **" + oldName + "** إلى **" + newName + "** بواسطة " + event.getUser().getAsMention(), Color.BLUE);

        event.getHook().editOriginal("✅ تم تعديل معلومات الفريق **" + newName + "** بنجاح!").queue();
    }

    private void handleEditMembersModal(ModalInteractionEvent event, int teamId) {
        String leaderRaw = event.getValue("tm_leader").getAsString().trim();
        String m2Raw     = event.getValue("tm_m2").getAsString().trim();
        String m3Raw     = event.getValue("tm_m3") != null ? event.getValue("tm_m3").getAsString().trim() : "";
        String m4Raw     = event.getValue("tm_m4") != null ? event.getValue("tm_m4").getAsString().trim() : "";

        event.deferReply().setEphemeral(true).queue();

        TeamData td = getTeamById(teamId);
        if (td == null) {
            event.getHook().editOriginal("❌ الفريق غير موجود!").queue();
            return;
        }

        String leaderId = extractUserId(leaderRaw);
        String m2Id     = extractUserId(m2Raw);
        String m3Id     = m3Raw.isEmpty() ? null : extractUserId(m3Raw);
        String m4Id     = m4Raw.isEmpty() ? null : extractUserId(m4Raw);

        if (leaderId == null || m2Id == null) {
            event.getHook().editOriginal("❌ خطأ في بيانات الأعضاء! استخدم @mention أو Discord ID.").queue();
            return;
        }

        Guild guild     = event.getGuild();
        Role  teamRole  = td.roleId != null ? guild.getRoleById(td.roleId) : null;
        Role  leaderRole = guild.getRoleById(LEADER_ROLE_ID);

        removeRolesFromOldMembers(guild, td, teamRole, leaderRole);

        addRoleToMember(guild, leaderId, teamRole);
        addRoleToMember(guild, leaderId, leaderRole);
        addRoleToMember(guild, m2Id,     teamRole);
        if (m3Id != null) addRoleToMember(guild, m3Id, teamRole);
        if (m4Id != null) addRoleToMember(guild, m4Id, teamRole);

        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE teams SET leader_id = ?, member2_id = ?, member3_id = ?, member4_id = ?, tag = 'Modified' WHERE id = ?")) {
            ps.setString(1, leaderId);
            ps.setString(2, m2Id);
            ps.setString(3, m3Id);
            ps.setString(4, m4Id);
            ps.setInt(5, teamId);
            ps.executeUpdate();
        } catch (Exception e) {
            logger.error("Error updating team members", e);
        }

        syncToSupabase(td.name, td.color, leaderId, m2Id, m3Id, m4Id, "Modified", null);

        updateAnnouncementEmbed(guild, teamId, td.name, leaderId, m2Id, m3Id, m4Id, td.color);

        sendLogMessage(guild, "تعديل أعضاء الفريق", "تم تعديل أعضاء فريق **" + td.name + "** بواسطة " + event.getUser().getAsMention(), Color.BLUE);

        event.getHook().editOriginal("✅ تم تعديل أعضاء الفريق **" + td.name + "** بنجاح!").queue();
    }

    // ===================================================================
    // Announcement
    // ===================================================================

    private void sendAnnouncementAndSave(Guild guild, String teamName,
                                          Member leader, Member member2,
                                          Member member3, Member member4,
                                          String colorCode, int teamDbId, boolean isEdit) {
        TextChannel ch = guild.getTextChannelById(ANNOUNCE_CHANNEL_ID);
        if (ch == null) return;

        Container container = buildAnnouncementContainer(
            teamName,
            leader.getId(),   member2.getId(),
            member3 != null ? member3.getId() : null,
            member4 != null ? member4.getId() : null,
            colorCode, isEdit
        );

        ch.sendMessage(new MessageCreateBuilder().setComponents(container).useComponentsV2(true).build())
            .queue(msg -> {
                try (Connection conn = LeonTrotskyBot.getDbManager().getConnection();
                     PreparedStatement ps = conn.prepareStatement(
                         "UPDATE teams SET announcement_message_id = ? WHERE id = ?")) {
                    ps.setString(1, msg.getId());
                    ps.setInt(2, teamDbId);
                    ps.executeUpdate();
                } catch (Exception e) {
                    logger.error("Error saving announcement message id", e);
                }
            });
    }

    private void updateAnnouncementEmbed(Guild guild, int teamId, String teamName,
                                          String leaderId, String m2Id, String m3Id, String m4Id,
                                          String colorCode) {
        TextChannel ch = guild.getTextChannelById(ANNOUNCE_CHANNEL_ID);
        if (ch == null) return;

        String msgId = getAnnouncementMsgId(teamId);
        if (msgId == null) return;

        Container container = buildAnnouncementContainer(
            teamName, leaderId, m2Id, m3Id, m4Id,
            colorCode, true
        );

        MessageEditBuilder meb = new MessageEditBuilder().setComponents(container).useComponentsV2(true);
        ch.editMessageById(msgId, meb.build())
            .queue(null, E -> logger.error("Failed to update announcement for team {}", teamName));
    }

    private Container buildAnnouncementContainer(
            String teamName,
            String leaderId, String m2Id, String m3Id, String m4Id,
            String colorCode, boolean isEdit) {

        StringBuilder sb = new StringBuilder();
        sb.append("Leader : <@").append(leaderId).append(">\n");
        sb.append("Member 2 : <@").append(m2Id).append(">\n");
        if (m3Id != null) sb.append("Member 3 : <@").append(m3Id).append(">\n");
        if (m4Id != null) sb.append("Member 4 : <@").append(m4Id).append(">\n");
        sb.append("\n🎨 **Color:** `").append(colorCode).append("`");

        return Container.of(
            TextDisplay.of("## " + teamName),
            Separator.createDivider(Separator.Spacing.SMALL),
            TextDisplay.of(sb.toString()),
            Separator.createDivider(Separator.Spacing.SMALL),
            TextDisplay.of("> 🏆 HighCore MC • Team System")
        );
    }

    // ===================================================================
    // Helpers
    // ===================================================================

    private void sendLogMessage(Guild guild, String title, String description, Color color) {
        TextChannel ch = guild.getTextChannelById(LOG_CHANNEL_ID);
        if (ch == null) return;
        net.dv8tion.jda.api.entities.MessageEmbed embed = new EmbedBuilder()
            .setTitle(title)
            .setDescription(description)
            .setColor(color)
            .setTimestamp(java.time.Instant.now())
            .build();
        ch.sendMessageEmbeds(embed).queue(null, E -> logger.error("Failed to send log message"));
    }

    private void syncToSupabase(String name, String color, String leader, String m2,
                                  String m3, String m4, String tag, String oldName) {
        com.highcore.bot.database.SupabaseManager supa = LeonTrotskyBot.getSupabaseManager();
        if (supa == null) return;
        if (oldName != null && !oldName.equals(name)) {
            supa.deleteTeam(oldName);
        }
        supa.upsertTeam(name, color, leader, m2, m3, m4, tag);
    }

    private void deleteDiscordResources(Guild guild, TeamData td, Runnable onComplete) {
        if (td.roleId != null) {
            Role r = guild.getRoleById(td.roleId);
            if (r != null) r.delete().queue(null, E -> {});
        }
        if (td.textChannelId != null) {
            TextChannel tc = guild.getTextChannelById(td.textChannelId);
            if (tc != null) tc.delete().queue(null, E -> {});
        }
        if (td.voiceChannelId != null) {
            VoiceChannel vc = guild.getVoiceChannelById(td.voiceChannelId);
            if (vc != null) vc.delete().queue(null, E -> {});
        }
        if (td.categoryId != null) {
            new Thread(() -> {
                try { Thread.sleep(2000); } catch (Exception ignored) {}
                Category cat = guild.getCategoryById(td.categoryId);
                if (cat != null) cat.delete().queue(null, E -> {});
                if (onComplete != null) onComplete.run();
            }, "team-delete-" + td.id).start();
        } else {
            if (onComplete != null) onComplete.run();
        }
    }

    private void deleteTeamFromDb(int teamId, String teamName) {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM teams WHERE id = ?")) {
            ps.setInt(1, teamId);
            ps.executeUpdate();
            com.highcore.bot.database.SupabaseManager supa = LeonTrotskyBot.getSupabaseManager();
            if (supa != null) supa.deleteTeam(teamName);
        } catch (Exception e) {
            logger.error("Error deleting team from DB", e);
        }
    }

    private void removeRolesFromOldMembers(Guild guild, TeamData td, Role teamRole, Role leaderRole) {
        String[] ids = {td.leaderId, td.member2Id, td.member3Id, td.member4Id};
        for (String uid : ids) {
            if (uid == null) continue;
            guild.retrieveMemberById(uid).queue(M -> {
                if (teamRole != null) guild.removeRoleFromMember(M, teamRole).queue(null, E -> {});
                if (uid.equals(td.leaderId) && leaderRole != null) {
                    guild.removeRoleFromMember(M, leaderRole).queue(null, E -> {});
                }
            }, E -> {});
        }
    }

    private void addRoleToMember(Guild guild, String userId, Role role) {
        if (userId == null || role == null) return;
        guild.retrieveMemberById(userId).queue(
            M -> guild.addRoleToMember(M, role).queue(null, E -> {}),
            E -> {}
        );
    }

    private String extractUserId(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        raw = raw.trim().replaceAll("[<@!>]", "");
        try { Long.parseLong(raw); return raw; } catch (NumberFormatException e) { return null; }
    }

    private String resolveDisplay(Guild guild, String userId) {
        if (userId == null) return "—";
        return "<@" + userId + ">";
    }

    private String tagEmoji(String tag) {
        if ("Approved".equals(tag)) return "✅";
        if ("Modified".equals(tag)) return "✏️";
        return "🆕";
    }

    private boolean teamExistsByName(String name) {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM teams WHERE name = ?")) {
            ps.setString(1, name);
            return ps.executeQuery().next();
        } catch (Exception e) { return false; }
    }

    private int getTeamCount() {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM teams");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getInt(1);
        } catch (Exception e) { logger.error("Error getting team count", e); }
        return 0;
    }

    private List<TeamData> getTeamList() {
        List<TeamData> list = new ArrayList<>();
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM teams ORDER BY created_at DESC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(TeamData.from(rs));
        } catch (Exception e) { logger.error("Error getting team list", e); }
        return list;
    }

    private TeamData getTeamById(int id) {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM teams WHERE id = ?")) {
            ps.setInt(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return TeamData.from(rs);
        } catch (Exception e) { logger.error("Error getting team by id", e); }
        return null;
    }

    private TeamData getTeamByName(String name) {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM teams WHERE name LIKE ?")) {
            ps.setString(1, "%" + name + "%");
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return TeamData.from(rs);
        } catch (Exception e) { logger.error("Error getting team by name", e); }
        return null;
    }

    private String getAnnouncementMsgId(int teamId) {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT announcement_message_id FROM teams WHERE id = ?")) {
            ps.setInt(1, teamId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("announcement_message_id");
        } catch (Exception e) { logger.error("Error getting announcement msg id", e); }
        return null;
    }

    // ===================================================================
    // Data Class
    // ===================================================================

    private static class TeamData {
        int    id;
        String name, color, roleId, categoryId, voiceChannelId, textChannelId;
        String leaderId, member2Id, member3Id, member4Id;
        String tag, announcementMessageId;

        static TeamData from(ResultSet rs) throws SQLException {
            TeamData t = new TeamData();
            t.id                    = rs.getInt("id");
            t.name                  = rs.getString("name");
            t.color                 = rs.getString("color");
            t.roleId                = rs.getString("role_id");
            t.categoryId            = rs.getString("category_id");
            t.voiceChannelId        = rs.getString("voice_channel_id");
            t.textChannelId         = rs.getString("text_channel_id");
            t.leaderId              = rs.getString("leader_id");
            t.member2Id             = rs.getString("member2_id");
            t.member3Id             = rs.getString("member3_id");
            t.member4Id             = rs.getString("member4_id");
            t.tag                   = rs.getString("tag");
            t.announcementMessageId = rs.getString("announcement_message_id");
            return t;
        }
    }
}