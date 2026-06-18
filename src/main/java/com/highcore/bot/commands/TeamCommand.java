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
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.sql.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

// Commands
public class TeamCommand extends ListenerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(TeamCommand.class);

    private static final String LEADER_ROLE_ID      = "1512457786548682863";
    private static final String CO_LEADER_ROLE_ID   = "1517128529563750411";
    private static final String ANNOUNCE_CHANNEL_ID = "1512461188997578984";
    private static final String WAR_CHANNEL_ID      = "1512462982410403961";
    private static final String ALLIANCE_CHANNEL_ID = "1517130272770887700";
    private static final String MANAGER_ROLE_ID     = "1487195816220430406";

    private static ScheduledExecutorService tagScheduler;

    // Pending logos: adminUserId -> PendingTeamLogo
    private static final Map<String, PendingTeamLogo>    pendingLogos  = new ConcurrentHashMap<>();
    // Alliance votes: voteId -> AllianceVoteState
    private static final Map<String, AllianceVoteState>  allianceVotes = new ConcurrentHashMap<>();

    // Logo session
    private static class PendingTeamLogo {
        int    teamDbId;
        String channelId;
        PendingTeamLogo(int id, String ch) { teamDbId = id; channelId = ch; }
    }

    // Alliance vote state
    private static class AllianceVoteState {
        int         requesterTeamId, targetTeamId;
        String      requesterTeamName, targetTeamName;
        Set<String> eligibleVoters;
        Set<String> acceptVoters = ConcurrentHashMap.newKeySet();
        Set<String> rejectVoters = ConcurrentHashMap.newKeySet();
        String      requesterCmdChannelId, targetCmdChannelId;
        String      voteMessageId;
        String      firstLeaderId, secondLeaderId;

        AllianceVoteState(int rId, int tId, String rName, String tName,
                          Set<String> eligible, String rCmd, String tCmd,
                          String fLdr, String sLdr) {
            requesterTeamId = rId; targetTeamId = tId;
            requesterTeamName = rName; targetTeamName = tName;
            eligibleVoters = eligible;
            requesterCmdChannelId = rCmd; targetCmdChannelId = tCmd;
            firstLeaderId = fLdr; secondLeaderId = sLdr;
        }
    }

    // ===================================================================
    // Tag Scheduler
    // ===================================================================

    public static void startTagScheduler() {
        tagScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TeamTagScheduler");
            t.setDaemon(true);
            return t;
        });
        tagScheduler.scheduleAtFixedRate(TeamCommand::checkAndUpdateTags, 1, 12, TimeUnit.HOURS);
        logger.info("Team tag scheduler started.");
    }

    private static void checkAndUpdateTags() {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String sql = "SELECT id, name FROM teams WHERE tag = 'New Born' AND created_at <= DATE_SUB(NOW(), INTERVAL 3 DAY)";
            try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int teamId = rs.getInt("id"); String teamName = rs.getString("name");
                    try (PreparedStatement upd = conn.prepareStatement("UPDATE teams SET tag = 'Approved' WHERE id = ?")) {
                        upd.setInt(1, teamId); upd.executeUpdate();
                    }
                    com.highcore.bot.database.SupabaseManager supa = LeonTrotskyBot.getSupabaseManager();
                    if (supa != null) supa.updateTeamTag(teamName, "Approved");
                    logger.info("Team '{}' tag updated to Approved.", teamName);
                }
            }
        } catch (Exception e) { logger.error("Error in team tag scheduler", e); }
    }

    // ===================================================================
    // Slash Command Router
    // ===================================================================

    @Override
    public void onCommandAutoCompleteInteraction(net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent event) {
        if (!event.getName().equals("team")) return;
        String input = event.getFocusedOption().getValue().toLowerCase();
        List<TeamData> teams = getTeamList();
        List<net.dv8tion.jda.api.interactions.commands.Command.Choice> choices = teams.stream()
            .filter(t -> t.name.toLowerCase().contains(input)).limit(25)
            .map(t -> new net.dv8tion.jda.api.interactions.commands.Command.Choice(t.name, t.name))
            .collect(java.util.stream.Collectors.toList());
        event.replyChoices(choices).queue();
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("team")) return;
        Member member = event.getMember();
        if (member == null) return;
        String sub = event.getSubcommandName();

        if ("panel".equals(sub)) { handlePanelCommand(event); return; }

        if (!hasManagerRole(member)) {
            event.reply("انت لست اداري لاستخدام هاذا الامر").setEphemeral(true).queue();
            return;
        }
        if ("create".equals(sub)) handleCreate(event);
        else if ("edit".equals(sub)) handleEdit(event);
        else showDashboard(event);
    }

    private boolean hasManagerRole(Member member) {
        if (member.hasPermission(Permission.ADMINISTRATOR)) return true;
        return member.getRoles().stream().anyMatch(r -> r.getId().equals(MANAGER_ROLE_ID));
    }
    private boolean hasLeaderRole(Member member) {
        return member.getRoles().stream().anyMatch(r -> r.getId().equals(LEADER_ROLE_ID));
    }
    private boolean hasCoLeaderRole(Member member) {
        return member.getRoles().stream().anyMatch(r -> r.getId().equals(CO_LEADER_ROLE_ID));
    }

    // ===================================================================
    // Dashboard
    // ===================================================================

    private void showDashboard(SlashCommandInteractionEvent event) {
        int count = getTeamCount(); List<TeamData> teams = getTeamList();
        StringBuilder sb = new StringBuilder("## 🏆 لوحة تحكم الفرق\n\n**إجمالي الفرق:** `").append(count).append("`\n\n");
        if (!teams.isEmpty()) for (TeamData t : teams) sb.append(tagEmoji(t.tag)).append(" **").append(t.name).append("** | 🎨 `").append(t.color).append("` | 🏷️ `").append(t.tag).append("`\n");
        else sb.append("*لا توجد فرق بعد.*");
        sb.append("\n\n> لإنشاء فريق جديد استخدم `/team create`\n> للتعديل استخدم `/team edit`");
        event.replyComponents(Container.of(
            TextDisplay.of(sb.toString()), Separator.createDivider(Separator.Spacing.SMALL),
            ActionRow.of(Button.primary("tm_panel_list","📋 عرض الفرق"), Button.secondary("tm_panel_edit","✏️ تعديل فريق"), Button.danger("tm_panel_delete","🗑️ حذف فريق"))
        )).useComponentsV2(true).setEphemeral(true).queue();
    }

    // ===================================================================
    // Leader Panel
    // ===================================================================

    private void handlePanelCommand(SlashCommandInteractionEvent event) {
        Member member = event.getMember();
        boolean isLeader = hasLeaderRole(member), isCoLeader = hasCoLeaderRole(member);
        if (!isLeader && !isCoLeader) { event.reply("❌ هذا الأمر خاص بقادة الأتيام فقط.").setEphemeral(true).queue(); return; }

        String userId = member.getId();
        TeamData td = isLeader ? getTeamByLeaderId(userId) : getTeamByCoLeaderId(userId);
        if (td == null) { event.reply("❌ لم يتم العثور على تيمك في النظام. تواصل مع الإدارة.").setEphemeral(true).queue(); return; }

        String coDisplay = (td.coLeaderId != null && !td.coLeaderId.isEmpty()) ? "<@" + td.coLeaderId + ">" : "*لا يوجد*";
        StringBuilder sb = new StringBuilder("## ⚡ بنل تيم ").append(td.name).append("\n\n")
            .append("🎨 **اللون:** `").append(td.color).append("`\n")
            .append("👑 **الليدر:** <@").append(extractIdOnly(td.leaderId)).append(">\n")
            .append("🥈 **الكو-ليدر:** ").append(coDisplay).append("\n")
            .append("👥 **عدد الأعضاء:** `").append(getTeamMemberCount(td)).append("`\n\nاختر الإجراء:");

        Container container;
        if (isLeader) {
            container = Container.of(TextDisplay.of(sb.toString()), Separator.createDivider(Separator.Spacing.SMALL),
                ActionRow.of(
                    Button.danger("tm_ldr_war_"    + td.id, "⚔️ إعلان حرب"),
                    Button.primary("tm_ldr_ally_"  + td.id, "🤝 إعلان تحالف"),
                    Button.secondary("tm_ldr_coset_"   + td.id, "🥈 تعيين كو-ليدر"),
                    Button.secondary("tm_ldr_color_"   + td.id, "🎨 تغيير اللون"),
                    Button.secondary("tm_ldr_members_" + td.id, "👥 تعديل الأعضاء")
                ));
        } else {
            container = Container.of(TextDisplay.of(sb.toString()), Separator.createDivider(Separator.Spacing.SMALL),
                ActionRow.of(Button.danger("tm_ldr_war_" + td.id, "⚔️ إعلان حرب"), Button.primary("tm_ldr_ally_" + td.id, "🤝 إعلان تحالف")));
        }
        event.replyComponents(container).useComponentsV2(true).queue();
    }

    // ===================================================================
    // Create
    // ===================================================================

    private void handleCreate(SlashCommandInteractionEvent event) {
        String teamName = event.getOption("name").getAsString().trim();
        String colorCode = event.getOption("color").getAsString().trim();
        Member leader = event.getOption("leader").getAsMember(), member2 = event.getOption("member2").getAsMember();
        Member member3 = event.getOption("member3") != null ? event.getOption("member3").getAsMember() : null;
        Member member4 = event.getOption("member4") != null ? event.getOption("member4").getAsMember() : null;

        if (leader == null || member2 == null) { event.reply("❌ خطأ في تحديد الأعضاء.").setEphemeral(true).queue(); return; }

        String BANNED_ROLE = "1513569599155867678"; int bannedNum = -1;
        if (leader.getRoles().stream().anyMatch(r -> r.getId().equals(BANNED_ROLE))) bannedNum = 1;
        else if (member2.getRoles().stream().anyMatch(r -> r.getId().equals(BANNED_ROLE))) bannedNum = 2;
        else if (member3 != null && member3.getRoles().stream().anyMatch(r -> r.getId().equals(BANNED_ROLE))) bannedNum = 3;
        else if (member4 != null && member4.getRoles().stream().anyMatch(r -> r.getId().equals(BANNED_ROLE))) bannedNum = 4;

        if (bannedNum != -1) {
            sendLog(event.getGuild(), "/team create (Blocked)", event.getUser(), "Team System",
                "### 🚫 محاولة إنشاء فريق بعضو محظور\n▫️ **اسم الفريق:** " + teamName + "\n▫️ **العضو المحظور:** رقم " + bannedNum, "#ff0000");
            event.reply("العضو المختار (رقم : " + bannedNum + ") محظور من نظام الاتيام").setEphemeral(true).queue(); return;
        }
        if (teamExistsByName(teamName)) { event.reply("❌ يوجد فريق بهذا الاسم بالفعل!").setEphemeral(true).queue(); return; }

        Color color;
        try { color = Color.decode(colorCode.startsWith("#") ? colorCode : "#" + colorCode); }
        catch (Exception e) { event.reply("❌ كود اللون غير صحيح! مثال: `#FF5733`").setEphemeral(true).queue(); return; }

        event.deferReply().setEphemeral(true).queue();
        final Guild guild = event.getGuild(); final Color finalColor = color;
        final Member m3 = member3, m4 = member4, fLeader = leader, fMember2 = member2;
        final String fColor = colorCode.startsWith("#") ? colorCode : "#" + colorCode;

        guild.createRole().setName(teamName).setColor(finalColor).queue(teamRole -> {
            Role leaderRole = guild.getRoleById(LEADER_ROLE_ID);
            if (leaderRole != null) guild.addRoleToMember(fLeader, leaderRole).queue(null, e -> {});
            guild.addRoleToMember(fLeader, teamRole).queue(null, e -> {});
            guild.addRoleToMember(fMember2, teamRole).queue(null, e -> {});
            if (m3 != null) guild.addRoleToMember(m3, teamRole).queue(null, e -> {});
            if (m4 != null) guild.addRoleToMember(m4, teamRole).queue(null, e -> {});

            guild.createCategory("<— " + teamName + " —>").queue(category -> {
                category.upsertPermissionOverride(guild.getPublicRole()).deny(Permission.VIEW_CHANNEL).queue();
                category.upsertPermissionOverride(teamRole).grant(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND,
                    Permission.MESSAGE_HISTORY, Permission.VOICE_CONNECT, Permission.VOICE_SPEAK, Permission.VOICE_USE_VAD).queue();

                guild.createVoiceChannel("🔊・" + teamName + "・voice").setParent(category).queue(vc -> {
                    vc.getManager().sync().queue();
                    guild.createTextChannel("💭・" + teamName).setParent(category).queue(tc -> {
                        tc.getManager().sync().queue();
                        guild.createTextChannel("📟︲" + teamName + "・cmd").setParent(category).queue(cmdCh -> {
                            cmdCh.getManager().sync().queue();

                            String lId = formatDbUser(fLeader), m2Id = formatDbUser(fMember2);
                            String m3Id = m3 != null ? formatDbUser(m3) : null, m4Id = m4 != null ? formatDbUser(m4) : null;

                            int dbId = saveTeamToDb(teamName, fColor, teamRole.getId(), category.getId(),
                                vc.getId(), tc.getId(), cmdCh.getId(), lId, m2Id, m3Id, m4Id);

                            sendAnnouncementAndSave(guild, teamName, fLeader.getId(), fMember2.getId(),
                                m3 != null ? m3.getId() : null, m4 != null ? m4.getId() : null, fColor, dbId, false);

                            String details = "### ✅ تم إنشاء فريق جديد\n▫️ **اسم الفريق:** " + teamName
                                + "\n▫️ **اللون:** `" + fColor + "`\n▫️ **القائد:** " + fLeader.getAsMention()
                                + "\n▫️ **العضو 2:** " + fMember2.getAsMention()
                                + (m3 != null ? "\n▫️ **العضو 3:** " + m3.getAsMention() : "")
                                + (m4 != null ? "\n▫️ **العضو 4:** " + m4.getAsMention() : "");
                            sendLog(guild, "/team create", event.getUser(), teamName, details, fColor);
                            event.getHook().editOriginal("✅ تم إنشاء فريق **" + teamName + "** بنجاح!").queue();

                            final int finalDbId = dbId;
                            event.getChannel().sendMessage("📸 يرجى **إرسال صورة شعار التيم** الآن (أو اكتب `skip` للتخطي).").queue(msg -> {
                                if (finalDbId > 0) pendingLogos.put(event.getUser().getId(), new PendingTeamLogo(finalDbId, event.getChannel().getId()));
                            });
                        });
                    });
                });
            });
        });
    }

    private int saveTeamToDb(String name, String color, String roleId, String catId, String voiceId,
                              String textId, String cmdId, String lId, String m2, String m3, String m4) {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String sql = "INSERT INTO teams (name, color, role_id, category_id, voice_channel_id, text_channel_id, cmd_channel_id, leader_id, member2_id, member3_id, member4_id, tag) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'New Born')";
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1,name); ps.setString(2,color); ps.setString(3,roleId); ps.setString(4,catId);
                ps.setString(5,voiceId); ps.setString(6,textId); ps.setString(7,cmdId);
                ps.setString(8,lId); ps.setString(9,m2); ps.setString(10,m3); ps.setString(11,m4);
                ps.executeUpdate();
                ResultSet rs = ps.getGeneratedKeys();
                if (rs.next()) { int id = rs.getInt(1); syncToSupabase(name, color, lId, m2, m3, m4, "New Born", null); return id; }
            }
        } catch (Exception e) { logger.error("Error saving team to DB", e); }
        return -1;
    }

    // ===================================================================
    // Message Received — Logo Upload
    // ===================================================================

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;
        String userId = event.getAuthor().getId();
        PendingTeamLogo pending = pendingLogos.get(userId);
        if (pending == null || !event.getChannel().getId().equals(pending.channelId)) return;

        if (!event.getMessage().getAttachments().isEmpty()) {
            net.dv8tion.jda.api.entities.Message.Attachment att = event.getMessage().getAttachments().get(0);
            if (!att.isImage()) { event.getChannel().sendMessage("الملف ليس صورة! أرسل صورة صحيحة أو اكتب `skip`.").queue(); return; }
            pendingLogos.remove(userId);
            event.getMessage().delete().queue(null, e -> {});
            saveLogoToDb(pending.teamDbId, att.getUrl());
            event.getChannel().sendMessage("✅ تم حفظ شعار التيم!").queue();
        } else if (event.getMessage().getContentRaw().equalsIgnoreCase("skip")) {
            pendingLogos.remove(userId);
            event.getMessage().delete().queue(null, e -> {});
            event.getChannel().sendMessage("تم تخطي الشعار.").queue();
        } else {
            event.getChannel().sendMessage("أرسل صورة أو اكتب `skip`.").queue();
        }
    }

    private void saveLogoToDb(int teamId, String url) {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE teams SET logo_url = ? WHERE id = ?")) {
            ps.setString(1, url); ps.setInt(2, teamId); ps.executeUpdate();
        } catch (Exception e) { logger.error("Error saving logo to DB", e); }
    }

    // ===================================================================
    // Edit
    // ===================================================================

    private void handleEdit(SlashCommandInteractionEvent event) {
        String teamOpt = event.getOption("team") != null ? event.getOption("team").getAsString().trim() : null;
        if (teamOpt != null && !teamOpt.isEmpty()) {
            TeamData td = getTeamByName(teamOpt);
            if (td == null) { event.reply("❌ لم يتم العثور على فريق باسم `" + teamOpt + "`.").setEphemeral(true).queue(); return; }
            replyWithTeamManageEmbed(event, td);
        } else {
            List<TeamData> teams = getTeamList();
            if (teams.isEmpty()) { event.reply("❌ لا توجد فرق حالياً.").setEphemeral(true).queue(); return; }
            StringSelectMenu.Builder menu = StringSelectMenu.create("tm_select_edit").setPlaceholder("اختر الفريق الذي تريد تعديله...");
            teams.stream().limit(25).forEach(t -> menu.addOption(t.name, String.valueOf(t.id), t.tag));
            event.replyComponents(Container.of(TextDisplay.of("## ✏️ تعديل فريق\nاختر الفريق:"), Separator.createDivider(Separator.Spacing.SMALL), ActionRow.of(menu.build()))).useComponentsV2(true).setEphemeral(true).queue();
        }
    }

    private void replyWithTeamManageEmbed(SlashCommandInteractionEvent event, TeamData td) {
        event.replyComponents(Container.of(TextDisplay.of(buildTeamInfoText(event.getGuild(), td)), Separator.createDivider(Separator.Spacing.SMALL),
            ActionRow.of(Button.primary("tm_edit_" + td.id, "✏️ تعديل المعلومات"), Button.secondary("tm_edit_members_" + td.id, "👥 تعديل الأعضاء"))
        )).useComponentsV2(true).setEphemeral(true).queue();
    }

    private void replyWithTeamManageEmbed(ButtonInteractionEvent event, TeamData td) {
        event.replyComponents(Container.of(TextDisplay.of(buildTeamInfoText(event.getGuild(), td)), Separator.createDivider(Separator.Spacing.SMALL),
            ActionRow.of(Button.primary("tm_edit_" + td.id, "✏️ تعديل المعلومات"), Button.secondary("tm_edit_members_" + td.id, "👥 تعديل الأعضاء"))
        )).useComponentsV2(true).setEphemeral(true).queue();
    }

    private String buildTeamInfoText(Guild guild, TeamData td) {
        StringBuilder sb = new StringBuilder("## 🏆 ").append(td.name).append("\n\n🎨 **Color:** `").append(td.color).append("`\n");
        sb.append("**Leader:** ").append(resolveDisplay(guild, extractIdOnly(td.leaderId))).append("\n");
        sb.append("**Member 2:** ").append(resolveDisplay(guild, extractIdOnly(td.member2Id))).append("\n");
        if (td.member3Id != null && !td.member3Id.isEmpty()) sb.append("**Member 3:** ").append(resolveDisplay(guild, extractIdOnly(td.member3Id))).append("\n");
        if (td.member4Id != null && !td.member4Id.isEmpty()) sb.append("**Member 4:** ").append(resolveDisplay(guild, extractIdOnly(td.member4Id))).append("\n");
        sb.append("\n🏷️ **Tag:** ").append(tagEmoji(td.tag)).append(" `").append(td.tag).append("`");
        return sb.toString();
    }

    // ===================================================================
    // Button Interactions
    // ===================================================================

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();

        // Alliance vote buttons
        if (id.startsWith("tm_ally_accept_")) { handleAllianceVote(event, id.replace("tm_ally_accept_", ""), true); return; }
        if (id.startsWith("tm_ally_reject_")) { handleAllianceVote(event, id.replace("tm_ally_reject_", ""), false); return; }
        if (!id.startsWith("tm_")) return;

        Member member = event.getMember();
        if (member == null) return;

        // Leader panel buttons
        if (id.startsWith("tm_ldr_")) { handleLeaderPanelButton(event, id, member); return; }

        // Admin buttons
        if (!hasManagerRole(member)) { event.reply("انت لست اداري لاستخدام هاذا الامر").setEphemeral(true).queue(); return; }

        if (id.equals("tm_panel_list"))              handlePanelList(event);
        else if (id.equals("tm_panel_edit"))         handlePanelEditButton(event);
        else if (id.equals("tm_panel_delete"))       handlePanelDelete(event);
        else if (id.startsWith("tm_confirm_delete_")) handleConfirmDelete(event, Integer.parseInt(id.replace("tm_confirm_delete_", "")));
        else if (id.equals("tm_cancel"))             event.editMessage("تم إلغاء العملية.").setComponents(Collections.emptyList()).queue();
        else if (id.startsWith("tm_edit_members_")) handleEditMembersButton(event, Integer.parseInt(id.replace("tm_edit_members_", "")));
        else if (id.startsWith("tm_edit_"))         handleEditButton(event, Integer.parseInt(id.replace("tm_edit_", "")));
        else if (id.startsWith("tm_view_")) {
            TeamData td = getTeamById(Integer.parseInt(id.replace("tm_view_", "")));
            if (td != null) replyWithTeamManageEmbed(event, td);
            else event.reply("❌ الفريق غير موجود.").setEphemeral(true).queue();
        }
    }

    private void handleLeaderPanelButton(ButtonInteractionEvent event, String id, Member member) {
        boolean isLeader = hasLeaderRole(member), isCoLeader = hasCoLeaderRole(member);
        if (!isLeader && !isCoLeader) { event.reply("❌ ليس لديك صلاحية.").setEphemeral(true).queue(); return; }

        if (id.startsWith("tm_ldr_war_")) {
            handleWarSelectTarget(event, Integer.parseInt(id.replace("tm_ldr_war_", "")));
        } else if (id.startsWith("tm_ldr_ally_")) {
            handleAllianceSelectTarget(event, Integer.parseInt(id.replace("tm_ldr_ally_", "")));
        } else if (id.startsWith("tm_ldr_coset_")) {
            if (!isLeader) { event.reply("❌ فقط الليدر يمكنه تعيين الكو-ليدر.").setEphemeral(true).queue(); return; }
            handleSetCoLeader(event, Integer.parseInt(id.replace("tm_ldr_coset_", "")));
        } else if (id.startsWith("tm_ldr_color_")) {
            if (!isLeader) { event.reply("❌ فقط الليدر يمكنه تغيير اللون.").setEphemeral(true).queue(); return; }
            handleColorChangeButton(event, Integer.parseInt(id.replace("tm_ldr_color_", "")));
        } else if (id.startsWith("tm_ldr_members_")) {
            if (!isLeader) { event.reply("❌ فقط الليدر يمكنه تعديل الأعضاء.").setEphemeral(true).queue(); return; }
            handleEditMembersButton(event, Integer.parseInt(id.replace("tm_ldr_members_", "")));
        }
    }

    private void handlePanelList(ButtonInteractionEvent event) {
        List<TeamData> teams = getTeamList();
        if (teams.isEmpty()) { event.reply("❌ لا توجد فرق حالياً.").setEphemeral(true).queue(); return; }
        StringBuilder sb = new StringBuilder("## 📋 قائمة الفرق\n\n");
        for (TeamData t : teams) sb.append(tagEmoji(t.tag)).append(" **").append(t.name).append("** | 🎨 `").append(t.color).append("`\n");
        StringSelectMenu.Builder menu = StringSelectMenu.create("tm_select_view").setPlaceholder("اختر فريقاً لعرض تفاصيله...");
        teams.stream().limit(25).forEach(t -> menu.addOption(t.name, String.valueOf(t.id), t.tag));
        event.replyComponents(Container.of(TextDisplay.of(sb.toString()), Separator.createDivider(Separator.Spacing.SMALL), ActionRow.of(menu.build()))).useComponentsV2(true).setEphemeral(true).queue();
    }

    private void handlePanelEditButton(ButtonInteractionEvent event) {
        List<TeamData> teams = getTeamList();
        if (teams.isEmpty()) { event.reply("❌ لا توجد فرق للتعديل.").setEphemeral(true).queue(); return; }
        StringSelectMenu.Builder menu = StringSelectMenu.create("tm_select_edit").setPlaceholder("اختر الفريق الذي تريد تعديله...");
        teams.stream().limit(25).forEach(t -> menu.addOption(t.name, String.valueOf(t.id), t.tag));
        event.replyComponents(Container.of(TextDisplay.of("## ✏️ تعديل فريق\nاختر الفريق:"), Separator.createDivider(Separator.Spacing.SMALL), ActionRow.of(menu.build()))).useComponentsV2(true).setEphemeral(true).queue();
    }

    private void handlePanelDelete(ButtonInteractionEvent event) {
        List<TeamData> teams = getTeamList();
        if (teams.isEmpty()) { event.reply("❌ لا توجد فرق للحذف.").setEphemeral(true).queue(); return; }
        StringSelectMenu.Builder menu = StringSelectMenu.create("tm_select_delete").setPlaceholder("اختر الفريق الذي تريد حذفه...");
        teams.stream().limit(25).forEach(t -> menu.addOption(t.name, String.valueOf(t.id), t.tag));
        event.replyComponents(Container.of(TextDisplay.of("## 🗑️ حذف فريق\n⚠️ **تحذير:** هذه العملية لا يمكن التراجع عنها!"), Separator.createDivider(Separator.Spacing.SMALL), ActionRow.of(menu.build()))).useComponentsV2(true).setEphemeral(true).queue();
    }

    private void handleConfirmDelete(ButtonInteractionEvent event, int teamId) {
        TextInput reasonInput = TextInput.create("tm_del_reason", TextInputStyle.PARAGRAPH).setPlaceholder("سبب الحذف...").setRequired(true).build();
        event.replyModal(Modal.create("tm_modal_del_" + teamId, "سبب حذف الفريق").addComponents(Label.of("السبب", reasonInput)).build()).queue();
    }

    private void handleEditButton(ButtonInteractionEvent event, int teamId) {
        TeamData td = getTeamById(teamId);
        if (td == null) { event.reply("❌ الفريق غير موجود.").setEphemeral(true).queue(); return; }
        TextInput nameInput  = TextInput.create("tm_name",  TextInputStyle.SHORT).setValue(td.name).setMaxLength(50).setRequired(true).build();
        TextInput colorInput = TextInput.create("tm_color", TextInputStyle.SHORT).setValue(td.color).setMaxLength(20).setRequired(true).build();
        event.replyModal(Modal.create("tm_modal_edit_" + teamId, "تعديل الفريق: " + td.name)
            .addComponents(Label.of("اسم الفريق الجديد", nameInput), Label.of("كود اللون الجديد (مثال: #FF5733)", colorInput)).build()).queue();
    }

    private void handleEditMembersButton(ButtonInteractionEvent event, int teamId) {
        TeamData td = getTeamById(teamId);
        if (td == null) { event.reply("❌ الفريق غير موجود.").setEphemeral(true).queue(); return; }
        TextInput.Builder lB = TextInput.create("tm_leader", TextInputStyle.SHORT).setPlaceholder("@mention أو Discord ID").setRequired(true);
        TextInput.Builder m2B = TextInput.create("tm_m2", TextInputStyle.SHORT).setPlaceholder("@mention (فارغ للحذف)").setRequired(false);
        TextInput.Builder m3B = TextInput.create("tm_m3", TextInputStyle.SHORT).setPlaceholder("@mention (فارغ للحذف)").setRequired(false);
        TextInput.Builder m4B = TextInput.create("tm_m4", TextInputStyle.SHORT).setPlaceholder("@mention (فارغ للحذف)").setRequired(false);
        if (td.leaderId  != null && !td.leaderId.isEmpty())  lB.setValue("<@"  + extractIdOnly(td.leaderId)  + ">");
        if (td.member2Id != null && !td.member2Id.isEmpty()) m2B.setValue("<@" + extractIdOnly(td.member2Id) + ">");
        if (td.member3Id != null && !td.member3Id.isEmpty()) m3B.setValue("<@" + extractIdOnly(td.member3Id) + ">");
        if (td.member4Id != null && !td.member4Id.isEmpty()) m4B.setValue("<@" + extractIdOnly(td.member4Id) + ">");
        event.replyModal(Modal.create("tm_modal_members_" + teamId, "تعديل أعضاء: " + td.name)
            .addComponents(Label.of("القائد (Leader)", lB.build()), Label.of("العضو الثاني", m2B.build()),
                Label.of("العضو الثالث (اختياري)", m3B.build()), Label.of("العضو الرابع (اختياري)", m4B.build())).build()).queue();
    }

    // ===================================================================
    // Co-Leader Management
    // ===================================================================

    private void handleSetCoLeader(ButtonInteractionEvent event, int teamId) {
        TeamData td = getTeamById(teamId);
        if (td == null) { event.reply("❌ الفريق غير موجود.").setEphemeral(true).queue(); return; }
        List<String> memberIds = getTeamMemberIds(td);
        memberIds.remove(extractIdOnly(td.leaderId));
        if (memberIds.isEmpty()) { event.reply("❌ لا يوجد أعضاء لتعيينهم كو-ليدر.").setEphemeral(true).queue(); return; }
        Guild guild = event.getGuild();
        StringSelectMenu.Builder menu = StringSelectMenu.create("tm_ldr_coset_select_" + teamId).setPlaceholder("اختر العضو لتعيينه كو-ليدر...");
        for (String uid : memberIds) {
            Member m = guild.getMemberById(uid);
            String label = m != null ? m.getUser().getEffectiveName() : uid;
            net.dv8tion.jda.api.components.selections.SelectOption opt = net.dv8tion.jda.api.components.selections.SelectOption.of(label, uid);
            if (m != null) opt = opt.withDescription("@" + m.getUser().getName());
            menu.addOptions(opt);
        }
        if (td.coLeaderId != null && !td.coLeaderId.isEmpty()) menu.addOption("🗑️ إزالة الكو-ليدر الحالي", "remove_co");
        event.replyComponents(Container.of(TextDisplay.of("## 🥈 تعيين كو-ليدر\nاختر العضو:"), Separator.createDivider(Separator.Spacing.SMALL), ActionRow.of(menu.build()))).useComponentsV2(true).setEphemeral(true).queue();
    }

    private void handleCoLeaderSelected(StringSelectInteractionEvent event, int teamId) {
        String selectedId = event.getSelectedOptions().get(0).getValue();
        TeamData td = getTeamById(teamId);
        if (td == null) { event.reply("❌ الفريق غير موجود.").setEphemeral(true).queue(); return; }
        Guild guild = event.getGuild(); Role coRole = guild.getRoleById(CO_LEADER_ROLE_ID);
        if (td.coLeaderId != null && !td.coLeaderId.isEmpty()) {
            guild.retrieveMemberById(td.coLeaderId).queue(m -> { if (coRole != null) guild.removeRoleFromMember(m, coRole).queue(null, e -> {}); }, e -> {});
        }
        if ("remove_co".equals(selectedId)) { updateCoLeaderInDb(teamId, null); event.reply("✅ تم إزالة الكو-ليدر.").setEphemeral(true).queue(); return; }
        guild.retrieveMemberById(selectedId).queue(m -> { if (coRole != null) guild.addRoleToMember(m, coRole).queue(null, e -> {}); }, e -> {});
        updateCoLeaderInDb(teamId, selectedId);
        event.reply("✅ تم تعيين <@" + selectedId + "> كو-ليدر!").setEphemeral(true).queue();
    }

    private void updateCoLeaderInDb(int teamId, String userId) {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE teams SET co_leader_id = ? WHERE id = ?")) {
            ps.setString(1, userId); ps.setInt(2, teamId); ps.executeUpdate();
        } catch (Exception e) { logger.error("Error updating co-leader in DB", e); }
    }

    // ===================================================================
    // Color Change
    // ===================================================================

    private void handleColorChangeButton(ButtonInteractionEvent event, int teamId) {
        TeamData td = getTeamById(teamId);
        if (td == null) { event.reply("❌ الفريق غير موجود.").setEphemeral(true).queue(); return; }
        TextInput colorInput = TextInput.create("tm_ldr_color_val", TextInputStyle.SHORT).setValue(td.color).setMaxLength(20).setRequired(true).build();
        event.replyModal(Modal.create("tm_modal_ldr_color_" + teamId, "تغيير لون تيم " + td.name).addComponents(Label.of("كود اللون الجديد (مثال: #FF5733)", colorInput)).build()).queue();
    }

    private void handleColorModal(ModalInteractionEvent event, int teamId) {
        String newColor = event.getValue("tm_ldr_color_val").getAsString().trim();
        event.deferReply().setEphemeral(true).queue();
        TeamData td = getTeamById(teamId);
        if (td == null) { event.getHook().editOriginal("❌ الفريق غير موجود.").queue(); return; }
        Color color;
        try { color = Color.decode(newColor.startsWith("#") ? newColor : "#" + newColor); }
        catch (Exception e) { event.getHook().editOriginal("❌ كود اللون غير صحيح!").queue(); return; }
        final String fColor = newColor.startsWith("#") ? newColor : "#" + newColor;
        Guild guild = event.getGuild();
        if (td.roleId != null) { Role role = guild.getRoleById(td.roleId); if (role != null) role.getManager().setColor(color).queue(null, e -> {}); }
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE teams SET color = ? WHERE id = ?")) {
            ps.setString(1, fColor); ps.setInt(2, teamId); ps.executeUpdate();
        } catch (Exception e) { logger.error("Error updating color", e); }
        syncToSupabase(td.name, fColor, td.leaderId, td.member2Id, td.member3Id, td.member4Id, td.tag, null);
        updateAnnouncementEmbed(guild, teamId, td.name, extractIdOnly(td.leaderId), extractIdOnly(td.member2Id), extractIdOnly(td.member3Id), extractIdOnly(td.member4Id), fColor);
        event.getHook().editOriginal("✅ تم تغيير لون التيم إلى `" + fColor + "`!").queue();
    }

    // ===================================================================
    // War Declaration
    // ===================================================================

    private void handleWarSelectTarget(ButtonInteractionEvent event, int teamId) {
        List<TeamData> teams = getTeamList(); teams.removeIf(t -> t.id == teamId);
        if (teams.isEmpty()) { event.reply("❌ لا توجد أتيام أخرى.").setEphemeral(true).queue(); return; }
        StringSelectMenu.Builder menu = StringSelectMenu.create("tm_war_select_" + teamId).setPlaceholder("اختر التيم الذي ستحارب...");
        teams.stream().limit(25).forEach(t -> menu.addOption(t.name, String.valueOf(t.id)));
        event.replyComponents(Container.of(TextDisplay.of("## ⚔️ إعلان حرب\nاختر التيم:"), Separator.createDivider(Separator.Spacing.SMALL), ActionRow.of(menu.build()))).useComponentsV2(true).setEphemeral(true).queue();
    }

    private void handleWarTargetSelected(StringSelectInteractionEvent event, int attackerTeamId) {
        int targetTeamId = Integer.parseInt(event.getSelectedOptions().get(0).getValue());
        TextInput reasonInput = TextInput.create("tm_war_reason", TextInputStyle.PARAGRAPH).setPlaceholder("اكتب سبب إعلان الحرب...").setMinLength(10).setRequired(true).build();
        event.replyModal(Modal.create("tm_modal_war_" + attackerTeamId + "_" + targetTeamId, "سبب إعلان الحرب").addComponents(Label.of("سبب الحرب", reasonInput)).build()).queue();
    }

    private void handleWarModal(ModalInteractionEvent event, int attackerTeamId, int targetTeamId) {
        String reason = event.getValue("tm_war_reason").getAsString();
        event.deferReply().setEphemeral(true).queue();
        TeamData attacker = getTeamById(attackerTeamId), target = getTeamById(targetTeamId);
        if (attacker == null || target == null) { event.getHook().editOriginal("❌ خطأ في بيانات الأتيام.").queue(); return; }
        TextChannel warCh = event.getGuild().getTextChannelById(WAR_CHANNEL_ID);
        if (warCh == null) { event.getHook().editOriginal("❌ قناة الحرب غير موجودة.").queue(); return; }

        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        String declarationId = "WAR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String leaderMention = "<@" + extractIdOnly(attacker.leaderId) + ">";

        String part1 = "### إعلان حرب رسمي\n\n**بِسْمِ السُّلطةِ والعَهْد**\n\n" +
            "> __إلى قيادة التيم **" + target.name + "** وأفرادها كافة،__\n\n" +
            "بموجب الصلاحيات الممنوحة إلى مجلس تيم **" + attacker.name + "**، وبعد النظر في المواقف والأحداث القائمة بين الطرفين، فقد صدر القرار الآتي:\n\n" +
            "# **إعلان حالة الحرب**\n\n" +
            "يعلن تيم **" + attacker.name + "** رسميًا دخوله في حالة حرب مع تيم **" + target.name + "**، ابتداءً من:\n\n`" + date + "`\n\n" +
            "**سبب إعلان الحرب**\n> " + reason + "\n\n**قائد التيم المُعلن**\n`" + leaderMention + "`\n\n" +
            "**شروط الحرب**\n> تُقام الحرب وفق أنظمة السيرفر والمواثيق المعتمدة، ويُمنع تجاوز حدود المنافسة المشروعة أو مخالفة القوانين العامة.";
        String part2 = "*منذ لحظة صدور هذا الإعلان، تُعد جميع الاتفاقيات السابقة بين الطرفين معلّقة، ما لم يصدر قرار رسمي بخلاف ذلك.*\n\n__سُجّل هذا الإعلان في سجلات الاتيام، وأصبح نافذًا من تاريخ صدوره.__\n\n`رقم المرسوم: " + declarationId + "`";

        warCh.sendMessage(new MessageCreateBuilder().setComponents(Container.of(TextDisplay.of(part1), Separator.createDivider(Separator.Spacing.LARGE), TextDisplay.of(part2))).useComponentsV2(true).build()).queue();
        event.getHook().editOriginal("✅ تم إرسال إعلان الحرب على تيم **" + target.name + "**!").queue();
    }

    // ===================================================================
    // Alliance Declaration
    // ===================================================================

    private void handleAllianceSelectTarget(ButtonInteractionEvent event, int teamId) {
        List<TeamData> teams = getTeamList(); teams.removeIf(t -> t.id == teamId);
        if (teams.isEmpty()) { event.reply("❌ لا توجد أتيام أخرى.").setEphemeral(true).queue(); return; }
        StringSelectMenu.Builder menu = StringSelectMenu.create("tm_ally_select_" + teamId).setPlaceholder("اختر التيم الذي تريد التحالف معه...");
        teams.stream().limit(25).forEach(t -> menu.addOption(t.name, String.valueOf(t.id)));
        event.replyComponents(Container.of(TextDisplay.of("## 🤝 إعلان تحالف\nاختر التيم:"), Separator.createDivider(Separator.Spacing.SMALL), ActionRow.of(menu.build()))).useComponentsV2(true).setEphemeral(true).queue();
    }

    private void handleAllianceTargetSelected(StringSelectInteractionEvent event, int requesterTeamId) {
        int targetTeamId = Integer.parseInt(event.getSelectedOptions().get(0).getValue());
        TeamData requester = getTeamById(requesterTeamId), target = getTeamById(targetTeamId);
        if (requester == null || target == null) { event.reply("❌ خطأ في بيانات الأتيام.").setEphemeral(true).queue(); return; }
        if (target.cmdChannelId == null) { event.reply("❌ التيم المستهدف لا يملك روم CMD.").setEphemeral(true).queue(); return; }
        Guild guild = event.getGuild();
        TextChannel cmdCh = guild.getTextChannelById(target.cmdChannelId);
        if (cmdCh == null) { event.reply("❌ لم يتم العثور على روم CMD للتيم.").setEphemeral(true).queue(); return; }

        List<String> eligibleIds = getTeamMemberIds(target);
        Set<String> eligibleSet = new HashSet<>(eligibleIds);
        String voteId = UUID.randomUUID().toString().substring(0, 12);
        int total = eligibleIds.size(), majority = total / 2 + 1;

        AllianceVoteState state = new AllianceVoteState(requesterTeamId, targetTeamId, requester.name, target.name,
            eligibleSet, requester.cmdChannelId, target.cmdChannelId,
            extractIdOnly(requester.leaderId), extractIdOnly(target.leaderId));
        allianceVotes.put(voteId, state);

        StringBuilder inv = new StringBuilder("## 🤝 طلب تحالف\n\n")
            .append("تيم **").append(requester.name).append("** يطلب التحالف مع تيم **").append(target.name).append("**!\n\n")
            .append("**الأعضاء المطلوب تصويتهم:** (يلزم ").append(majority).append(" من أصل ").append(total).append(")\n");
        for (String id : eligibleIds) inv.append("> <@").append(id).append(">\n");
        inv.append("\n**أصوات القبول ✅**\n> *لا يوجد بعد*\n\n**أصوات الرفض ❌**\n> *لا يوجد بعد*\n\n`0/").append(total).append("` تم التصويت");

        cmdCh.sendMessage(new MessageCreateBuilder().setComponents(Container.of(TextDisplay.of(inv.toString()), Separator.createDivider(Separator.Spacing.SMALL),
            ActionRow.of(Button.success("tm_ally_accept_" + voteId, "✅ قبول"), Button.danger("tm_ally_reject_" + voteId, "❌ رفض")))).useComponentsV2(true).build())
            .queue(msg -> state.voteMessageId = msg.getId());

        event.reply("✅ تم إرسال دعوة التحالف لتيم **" + target.name + "**!").setEphemeral(true).queue();
    }

    private void handleAllianceVote(ButtonInteractionEvent event, String voteId, boolean accepted) {
        AllianceVoteState state = allianceVotes.get(voteId);
        if (state == null) { event.reply("❌ انتهت جلسة التصويت.").setEphemeral(true).queue(); return; }
        String userId = event.getMember().getId();
        if (!state.eligibleVoters.contains(userId)) { event.reply("❌ أنت لست من أعضاء هذا التيم.").setEphemeral(true).queue(); return; }

        state.acceptVoters.remove(userId); state.rejectVoters.remove(userId);
        if (accepted) state.acceptVoters.add(userId); else state.rejectVoters.add(userId);

        int total = state.eligibleVoters.size(), accepts = state.acceptVoters.size(), rejects = state.rejectVoters.size(), majority = total / 2 + 1;
        updateAllianceVoteEmbed(event.getGuild(), state, voteId, total, accepts, rejects);
        event.deferEdit().queue();

        if (accepts >= majority) { allianceVotes.remove(voteId); finalizeAlliance(event.getGuild(), state, true); }
        else if (rejects >= majority || (accepts + rejects == total)) { allianceVotes.remove(voteId); finalizeAlliance(event.getGuild(), state, false); }
    }

    private void updateAllianceVoteEmbed(Guild guild, AllianceVoteState state, String voteId, int total, int accepts, int rejects) {
        StringBuilder sb = new StringBuilder("## 🤝 طلب تحالف\n\n")
            .append("تيم **").append(state.requesterTeamName).append("** ↔ تيم **").append(state.targetTeamName).append("**\n\n")
            .append("**الأعضاء:** (يلزم ").append(total / 2 + 1).append(" من أصل ").append(total).append(")\n");
        for (String id : state.eligibleVoters) sb.append("> <@").append(id).append(">\n");
        sb.append("\n**أصوات القبول ✅**\n");
        if (state.acceptVoters.isEmpty()) sb.append("> *لا يوجد بعد*\n"); else for (String id : state.acceptVoters) sb.append("> <@").append(id).append(">\n");
        sb.append("\n**أصوات الرفض ❌**\n");
        if (state.rejectVoters.isEmpty()) sb.append("> *لا يوجد بعد*\n"); else for (String id : state.rejectVoters) sb.append("> <@").append(id).append(">\n");
        sb.append("\n`").append(accepts + rejects).append("/").append(total).append("` تم التصويت");

        Container updated = Container.of(TextDisplay.of(sb.toString()), Separator.createDivider(Separator.Spacing.SMALL),
            ActionRow.of(Button.success("tm_ally_accept_" + voteId, "✅ قبول"), Button.danger("tm_ally_reject_" + voteId, "❌ رفض")));
        if (state.voteMessageId != null && state.targetCmdChannelId != null) {
            TextChannel ch = guild.getTextChannelById(state.targetCmdChannelId);
            if (ch != null) ch.editMessageById(state.voteMessageId, new MessageEditBuilder().setComponents(updated).useComponentsV2(true).build()).queue(null, e -> {});
        }
    }

    private void finalizeAlliance(Guild guild, AllianceVoteState state, boolean accepted) {
        if (accepted) {
            String date = LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
            String allyId = "ALLY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            String fLdr = "<@" + state.firstLeaderId + ">", sLdr = "<@" + state.secondLeaderId + ">";
            String part1 = "### إعلان تحالف رسمي\n\n**بِسْمِ الوَحْدَةِ والوَفاء**\n\n" +
                "> __إلى جميع الاتيام المسجّلة في أراضي السيرفر،__\n\n" +
                "بناءً على ما تم الاتفاق عليه بين قيادة تيم **" + state.requesterTeamName + "** وقيادة تيم **" + state.targetTeamName + "**، فقد تم إبرام ميثاق رسمي يجمع الطرفين تحت عهد واحد.\n\n" +
                "# **إعلان قيام تحالف**\n\n" +
                "أصبح تيم **" + state.requesterTeamName + "** وتيم **" + state.targetTeamName + "** حليفين رسميًا ابتداءً من:\n\n`" + date + "`\n\n" +
                "**أطراف التحالف**\n> **الطرف الأول:** " + state.requesterTeamName + "\n> **الطرف الثاني:** " + state.targetTeamName + "\n\n" +
                "**قادة التحالف**\n> `" + fLdr + "`\n> `" + sLdr + "`\n\n" +
                "**بنود الميثاق**\n> الدفاع المشترك عند التعرض للاعتداء.\n> عدم الدخول في أي عمل عدائي ضد الطرف الحليف.\n> احترام ممتلكات وحدود وقرارات كل تيم.\n> تقديم الدعم وفق الإمكانات والاتفاقات المعتمدة.\n> حل الخلافات بالحوار قبل اتخاذ أي موقف حربي.";
            String part2 = "*يظل هذا التحالف قائمًا ما دام الطرفان ملتزمين بعهدهما، ولا يُلغى إلا بإعلان رسمي مسجّل.*\n\n__ليشهد سجل الممالك أن هذا الميثاق قد عُقد بالرضا والقبول بين الطرفين.__\n\n`رقم الميثاق: " + allyId + "`";
            TextChannel allyCh = guild.getTextChannelById(ALLIANCE_CHANNEL_ID);
            if (allyCh != null) allyCh.sendMessage(new MessageCreateBuilder().setComponents(Container.of(TextDisplay.of(part1), Separator.createDivider(Separator.Spacing.LARGE), TextDisplay.of(part2))).useComponentsV2(true).build()).queue();
            sendToCmdChannel(guild, state.requesterCmdChannelId, "✅ قبل تيم **" + state.targetTeamName + "** التحالف! تم إرسال الإعلان الرسمي.");
            editVoteMessageToResult(guild, state, true);
            // منح صلاحية رؤية الكاتيقوري المتبادلة
            grantAllianceCategoryAccess(guild, state);
        } else {
            sendToCmdChannel(guild, state.requesterCmdChannelId, "❌ رفض تيم **" + state.targetTeamName + "** طلب التحالف.");
            editVoteMessageToResult(guild, state, false);
        }
    }

    private void grantAllianceCategoryAccess(Guild guild, AllianceVoteState state) {
        TeamData requester = getTeamById(state.requesterTeamId);
        TeamData target    = getTeamById(state.targetTeamId);
        if (requester == null || target == null) return;

        Role requesterRole = requester.roleId != null ? guild.getRoleById(requester.roleId) : null;
        Role targetRole    = target.roleId    != null ? guild.getRoleById(target.roleId)    : null;

        // كاتيقوري التيم الأول: يمنح للتيم الثاني صلاحية القراءة
        if (requester.categoryId != null && targetRole != null) {
            Category cat = guild.getCategoryById(requester.categoryId);
            if (cat != null) cat.upsertPermissionOverride(targetRole)
                .grant(Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY)
                .deny(Permission.MESSAGE_SEND, Permission.VOICE_CONNECT)
                .queue(null, e -> {});
        }
        // كاتيقوري التيم الثاني: يمنح للتيم الأول صلاحية القراءة
        if (target.categoryId != null && requesterRole != null) {
            Category cat = guild.getCategoryById(target.categoryId);
            if (cat != null) cat.upsertPermissionOverride(requesterRole)
                .grant(Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY)
                .deny(Permission.MESSAGE_SEND, Permission.VOICE_CONNECT)
                .queue(null, e -> {});
        }
    }

    private void sendToCmdChannel(Guild guild, String channelId, String msg) {
        if (channelId == null) return;
        TextChannel ch = guild.getTextChannelById(channelId);
        if (ch != null) ch.sendMessage(msg).queue(null, e -> {});
    }

    private void editVoteMessageToResult(Guild guild, AllianceVoteState state, boolean accepted) {
        if (state.voteMessageId == null || state.targetCmdChannelId == null) return;
        TextChannel ch = guild.getTextChannelById(state.targetCmdChannelId);
        if (ch == null) return;
        StringBuilder sb = new StringBuilder("## 🤝 طلب تحالف — النتيجة\n\n")
            .append("تيم **").append(state.requesterTeamName).append("** ↔ تيم **").append(state.targetTeamName).append("**\n\n")
            .append(accepted ? "✅ **تم قبول التحالف بالأغلبية!**" : "❌ **تم رفض التحالف.**").append("\n\n")
            .append("**أصوات القبول ✅**\n");
        if (state.acceptVoters.isEmpty()) sb.append("> *لا يوجد*\n"); else for (String id : state.acceptVoters) sb.append("> <@").append(id).append(">\n");
        sb.append("**أصوات الرفض ❌**\n");
        if (state.rejectVoters.isEmpty()) sb.append("> *لا يوجد*\n"); else for (String id : state.rejectVoters) sb.append("> <@").append(id).append(">\n");
        ch.editMessageById(state.voteMessageId, new MessageEditBuilder().setComponents(Container.of(TextDisplay.of(sb.toString()))).useComponentsV2(true).build()).queue(null, e -> {});
    }

    // ===================================================================
    // String Select Interactions
    // ===================================================================

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        String id = event.getComponentId(); Member member = event.getMember();

        if (id.startsWith("tm_war_select_")) {
            if (member == null || (!hasLeaderRole(member) && !hasCoLeaderRole(member))) { event.reply("❌ ليس لديك صلاحية.").setEphemeral(true).queue(); return; }
            handleWarTargetSelected(event, Integer.parseInt(id.replace("tm_war_select_", ""))); return;
        }
        if (id.startsWith("tm_ally_select_")) {
            if (member == null || (!hasLeaderRole(member) && !hasCoLeaderRole(member))) { event.reply("❌ ليس لديك صلاحية.").setEphemeral(true).queue(); return; }
            handleAllianceTargetSelected(event, Integer.parseInt(id.replace("tm_ally_select_", ""))); return;
        }
        if (id.startsWith("tm_ldr_coset_select_")) {
            if (member == null || !hasLeaderRole(member)) { event.reply("❌ فقط الليدر يمكنه تعيين الكو-ليدر.").setEphemeral(true).queue(); return; }
            handleCoLeaderSelected(event, Integer.parseInt(id.replace("tm_ldr_coset_select_", ""))); return;
        }
        if (!id.startsWith("tm_select_")) return;
        if (member == null || !hasManagerRole(member)) { event.reply("انت لست اداري لاستخدام هاذا الامر").setEphemeral(true).queue(); return; }

        int teamId = Integer.parseInt(event.getSelectedOptions().get(0).getValue());
        TeamData td = getTeamById(teamId);
        if (td == null) { event.reply("❌ الفريق غير موجود!").setEphemeral(true).queue(); return; }

        if (id.equals("tm_select_delete")) {
            event.replyComponents(Container.of(TextDisplay.of("## ⚠️ تأكيد الحذف\n\nهل أنت متأكد من حذف فريق **" + td.name + "**?\nسيتم حذف الكاتيقوري والقنوات والرتبة نهائياً!"),
                Separator.createDivider(Separator.Spacing.SMALL), ActionRow.of(Button.danger("tm_confirm_delete_" + teamId, "✅ نعم، احذف"), Button.secondary("tm_cancel", "❌ إلغاء")))).useComponentsV2(true).setEphemeral(true).queue();
        } else if (id.equals("tm_select_edit")) {
            event.replyComponents(Container.of(TextDisplay.of(buildTeamInfoText(event.getGuild(), td)), Separator.createDivider(Separator.Spacing.SMALL),
                ActionRow.of(Button.primary("tm_edit_" + td.id, "✏️ تعديل المعلومات"), Button.secondary("tm_edit_members_" + td.id, "👥 تعديل الأعضاء")))).useComponentsV2(true).setEphemeral(true).queue();
        } else if (id.equals("tm_select_view")) {
            event.replyComponents(Container.of(TextDisplay.of(buildTeamInfoText(event.getGuild(), td)))).useComponentsV2(true).setEphemeral(true).queue();
        }
    }

    // ===================================================================
    // Modal Interactions
    // ===================================================================

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        String id = event.getModalId();
        if (id.startsWith("tm_modal_war_")) {
            String[] parts = id.replace("tm_modal_war_", "").split("_");
            if (parts.length >= 2) handleWarModal(event, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        } else if (id.startsWith("tm_modal_ldr_color_")) {
            handleColorModal(event, Integer.parseInt(id.replace("tm_modal_ldr_color_", "")));
        } else if (id.startsWith("tm_modal_edit_")) {
            handleEditInfoModal(event, Integer.parseInt(id.replace("tm_modal_edit_", "")));
        } else if (id.startsWith("tm_modal_members_")) {
            handleEditMembersModal(event, Integer.parseInt(id.replace("tm_modal_members_", "")));
        } else if (id.startsWith("tm_modal_del_")) {
            handleDeleteModal(event, Integer.parseInt(id.replace("tm_modal_del_", "")));
        }
    }

    private void handleEditInfoModal(ModalInteractionEvent event, int teamId) {
        String newName = event.getValue("tm_name").getAsString().trim(), newColor = event.getValue("tm_color").getAsString().trim();
        event.deferReply().setEphemeral(true).queue();
        TeamData td = getTeamById(teamId);
        if (td == null) { event.getHook().editOriginal("❌ الفريق غير موجود!").queue(); return; }
        Color color; try { color = Color.decode(newColor.startsWith("#") ? newColor : "#" + newColor); } catch (Exception e) { event.getHook().editOriginal("❌ كود اللون غير صحيح!").queue(); return; }
        final String fColor = newColor.startsWith("#") ? newColor : "#" + newColor, oldName = td.name;
        Guild guild = event.getGuild();
        if (td.roleId != null)      { Role r = guild.getRoleById(td.roleId); if (r != null) r.getManager().setName(newName).setColor(color).queue(null, e -> {}); }
        if (td.categoryId != null)  { Category cat = guild.getCategoryById(td.categoryId); if (cat != null) cat.getManager().setName("<— " + newName + " —>").queue(null, e -> {}); }
        if (td.voiceChannelId != null) { VoiceChannel vc = guild.getVoiceChannelById(td.voiceChannelId); if (vc != null) vc.getManager().setName("🔊・" + newName + "・voice").queue(null, e -> {}); }
        if (td.textChannelId != null)  { TextChannel tc = guild.getTextChannelById(td.textChannelId); if (tc != null) tc.getManager().setName("💭・" + newName).queue(null, e -> {}); }
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE teams SET name = ?, color = ?, tag = 'Modified' WHERE id = ?")) {
            ps.setString(1, newName); ps.setString(2, fColor); ps.setInt(3, teamId); ps.executeUpdate();
        } catch (Exception e) { logger.error("Error updating team info in DB", e); }
        syncToSupabase(newName, fColor, td.leaderId, td.member2Id, td.member3Id, td.member4Id, "Modified", oldName.equals(newName) ? null : oldName);
        updateAnnouncementEmbed(guild, teamId, newName, extractIdOnly(td.leaderId), extractIdOnly(td.member2Id), extractIdOnly(td.member3Id), extractIdOnly(td.member4Id), fColor);
        sendLog(guild, "Edit Team Info", event.getUser(), newName, "### ✏️ تم تعديل معلومات الفريق\n▫️ **الاسم القديم:** " + td.name + "\n▫️ **الاسم الجديد:** " + newName + "\n▫️ **اللون القديم:** `" + td.color + "`\n▫️ **اللون الجديد:** `" + fColor + "`", fColor);
        event.getHook().editOriginal("✅ تم تعديل معلومات الفريق **" + newName + "** بنجاح!").queue();
    }

    private void handleEditMembersModal(ModalInteractionEvent event, int teamId) {
        String leaderRaw = event.getValue("tm_leader").getAsString().trim();
        String m2Raw = event.getValue("tm_m2") != null ? event.getValue("tm_m2").getAsString().trim() : "";
        String m3Raw = event.getValue("tm_m3") != null ? event.getValue("tm_m3").getAsString().trim() : "";
        String m4Raw = event.getValue("tm_m4") != null ? event.getValue("tm_m4").getAsString().trim() : "";
        event.deferReply().setEphemeral(true).queue();
        TeamData td = getTeamById(teamId);
        if (td == null) { event.getHook().editOriginal("❌ الفريق غير موجود!").queue(); return; }
        String leaderId = extractUserId(leaderRaw), m2Id = m2Raw.isEmpty() ? null : extractUserId(m2Raw);
        String m3Id = m3Raw.isEmpty() ? null : extractUserId(m3Raw), m4Id = m4Raw.isEmpty() ? null : extractUserId(m4Raw);
        if (leaderId == null) { event.getHook().editOriginal("❌ خطأ: القائد مطلوب! استخدم @mention أو Discord ID.").queue(); return; }
        // العضو الثاني إلزامي ولا يمكن حذفه
        if (m2Id == null) { event.getHook().editOriginal("❌ العضو الثاني إلزامي ولا يمكن حذفه!").queue(); return; }
        Guild guild = event.getGuild();
        String BANNED_ROLE = "1513569599155867678"; int bannedNum = -1;
        Member lM = guild.getMemberById(leaderId), m2M = m2Id != null ? guild.getMemberById(m2Id) : null;
        Member m3M = m3Id != null ? guild.getMemberById(m3Id) : null, m4M = m4Id != null ? guild.getMemberById(m4Id) : null;
        if (lM != null && lM.getRoles().stream().anyMatch(r -> r.getId().equals(BANNED_ROLE))) bannedNum = 1;
        else if (m2M != null && m2M.getRoles().stream().anyMatch(r -> r.getId().equals(BANNED_ROLE))) bannedNum = 2;
        else if (m3M != null && m3M.getRoles().stream().anyMatch(r -> r.getId().equals(BANNED_ROLE))) bannedNum = 3;
        else if (m4M != null && m4M.getRoles().stream().anyMatch(r -> r.getId().equals(BANNED_ROLE))) bannedNum = 4;
        if (bannedNum != -1) { sendLog(guild, "Team Edit Members (Blocked)", event.getUser(), td.name, "### 🚫 محاولة تعديل بعضو محظور\n▫️ **الرقم المحظور:** " + bannedNum, "#ff0000"); event.getHook().editOriginal("العضو المختار (رقم : " + bannedNum + ") محظور من نظام الاتيام").queue(); return; }
        Role teamRole = td.roleId != null ? guild.getRoleById(td.roleId) : null, leaderRole = guild.getRoleById(LEADER_ROLE_ID);
        removeRolesFromOldMembers(guild, td, teamRole, leaderRole);
        addRoleToMember(guild, leaderId, teamRole); addRoleToMember(guild, leaderId, leaderRole); addRoleToMember(guild, m2Id, teamRole);
        if (m3Id != null) addRoleToMember(guild, m3Id, teamRole); if (m4Id != null) addRoleToMember(guild, m4Id, teamRole);
        String fLDb = formatDbUser(guild, leaderId), fM2Db = formatDbUser(guild, m2Id);
        String fM3Db = m3Id != null ? formatDbUser(guild, m3Id) : null, fM4Db = m4Id != null ? formatDbUser(guild, m4Id) : null;
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE teams SET leader_id = ?, member2_id = ?, member3_id = ?, member4_id = ?, tag = 'Modified' WHERE id = ?")) {
            ps.setString(1, fLDb); ps.setString(2, fM2Db); ps.setString(3, fM3Db); ps.setString(4, fM4Db); ps.setInt(5, teamId); ps.executeUpdate();
        } catch (Exception e) { logger.error("Error updating team members", e); }
        syncToSupabase(td.name, td.color, fLDb, fM2Db, fM3Db, fM4Db, "Modified", null);
        updateAnnouncementEmbed(guild, teamId, td.name, leaderId, m2Id, m3Id, m4Id, td.color);
        sendLog(guild, "Edit Team Members", event.getUser(), td.name, "### 👥 تم تعديل أعضاء الفريق\n▫️ **القائد الجديد:** <@" + leaderId + ">", td.color);
        event.getHook().editOriginal("✅ تم تعديل أعضاء الفريق **" + td.name + "** بنجاح!").queue();
    }

    // ===================================================================
    // Announcement
    // ===================================================================

    private void sendAnnouncementAndSave(Guild guild, String teamName, String leaderId, String m2Id, String m3Id, String m4Id, String colorCode, int teamDbId, boolean isEdit) {
        TextChannel ch = guild.getTextChannelById(ANNOUNCE_CHANNEL_ID); if (ch == null) return;
        ch.sendMessage(new MessageCreateBuilder().setComponents(buildAnnouncementContainer(teamName, leaderId, m2Id, m3Id, m4Id, colorCode, isEdit)).useComponentsV2(true).build()).queue(msg -> {
            try (Connection conn = LeonTrotskyBot.getDbManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement("UPDATE teams SET announcement_message_id = ? WHERE id = ?")) {
                ps.setString(1, msg.getId()); ps.setInt(2, teamDbId); ps.executeUpdate();
            } catch (Exception e) { logger.error("Error saving announcement message id", e); }
        });
    }

    private void updateAnnouncementEmbed(Guild guild, int teamId, String teamName, String leaderId, String m2Id, String m3Id, String m4Id, String colorCode) {
        TextChannel ch = guild.getTextChannelById(ANNOUNCE_CHANNEL_ID); if (ch == null) return;
        String msgId = getAnnouncementMsgId(teamId); if (msgId == null) return;
        ch.editMessageById(msgId, new MessageEditBuilder().setComponents(buildAnnouncementContainer(teamName, leaderId, m2Id, m3Id, m4Id, colorCode, true)).useComponentsV2(true).build()).queue(null, e -> logger.error("Failed to update announcement for team {}", teamName));
    }

    private Container buildAnnouncementContainer(String teamName, String leaderId, String m2Id, String m3Id, String m4Id, String colorCode, boolean isEdit) {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        String cleanName = teamName.replaceAll("[^A-Za-z0-9]", "");
        String regId = "REG-" + (cleanName.isEmpty() ? "TEAM" : cleanName.toUpperCase().substring(0, Math.min(6, cleanName.length())));
        String lM = "<@" + extractIdOnly(leaderId) + ">", m2M = (m2Id != null && !m2Id.isEmpty()) ? "<@" + extractIdOnly(m2Id) + ">" : "*—*";
        String m3M = (m3Id != null && !m3Id.isEmpty()) ? "> **العضو الثالث:** <@" + extractIdOnly(m3Id) + ">\n" : "";
        String m4M = (m4Id != null && !m4Id.isEmpty()) ? "> **العضو الرابع:** <@" + extractIdOnly(m4Id) + ">\n" : "";
        String part1 = "### " + (isEdit ? "تحديث بيانات تيم" : "مرسوم تأسيس تيم") + "\n\n**بِسْمِ البِناءِ والسِّيادة**\n\n" +
            "> __صدر مرسوم رسمي بتأسيس تيم جديد ضمن أراضي السيرفر.__\n\n" +
            "# **تأسيس تيم " + teamName + "**\n\n" +
            "تم تسجيل تيم **" + teamName + "** رسميًا ضمن نظام الأتيام، بعد استيفاء جميع متطلبات التأسيس.\n\n" +
            "**بيانات التيم**\n\n> **القائد:** " + lM + "\n> **نائب القائد:** " + m2M + "\n" + m3M + m4M +
            "> **تاريخ التأسيس:** " + date + "\n> **لون التيم:** `" + colorCode + "`";
        String part2 = "*يحق للتيم عقد التحالفات، وإدارة أعضائه، والمشاركة في الحروب والفعاليات الرسمية.*\n\n__مرحبًا بتيم **" + teamName + "** بين أتيام السيرفر.__\n\n`رقم التسجيل: " + regId + "`";
        return Container.of(TextDisplay.of(part1), Separator.createDivider(Separator.Spacing.LARGE), TextDisplay.of(part2));
    }

    // ===================================================================
    // Helpers
    // ===================================================================

    private void handleDeleteModal(ModalInteractionEvent event, int teamId) {
        String reason = event.getValue("tm_del_reason").getAsString();
        event.deferReply().setEphemeral(true).queue();
        TeamData td = getTeamById(teamId);
        if (td == null) { event.getHook().editOriginal("❌ الفريق غير موجود.").queue(); return; }
        Guild guild = event.getGuild();
        Role leaderRole = guild.getRoleById(LEADER_ROLE_ID);
        if (leaderRole != null && td.leaderId != null) guild.retrieveMemberById(extractIdOnly(td.leaderId)).queue(m -> guild.removeRoleFromMember(m, leaderRole).queue(null, e -> {}), e -> {});
        Role coRole = guild.getRoleById(CO_LEADER_ROLE_ID);
        if (coRole != null && td.coLeaderId != null && !td.coLeaderId.isEmpty()) guild.retrieveMemberById(td.coLeaderId).queue(m -> guild.removeRoleFromMember(m, coRole).queue(null, e -> {}), e -> {});
        if (td.announcementMessageId != null) { TextChannel annCh = guild.getTextChannelById(ANNOUNCE_CHANNEL_ID); if (annCh != null) annCh.deleteMessageById(td.announcementMessageId).queue(null, e -> {}); }
        String deleteMsg = "تم حذف فريقك **" + td.name + "**\n**السبب:** " + reason;
        // DM للليدر
        if (td.leaderId != null) guild.retrieveMemberById(extractIdOnly(td.leaderId)).queue(m -> m.getUser().openPrivateChannel().queue(pc -> pc.sendMessage(deleteMsg).queue(null, e -> {}), e -> {}), e -> {});
        // DM للكو-ليدر
        if (td.coLeaderId != null && !td.coLeaderId.isEmpty()) guild.retrieveMemberById(td.coLeaderId).queue(m -> m.getUser().openPrivateChannel().queue(pc -> pc.sendMessage(deleteMsg).queue(null, e -> {}), e -> {}), e -> {});
        sendLog(guild, "Delete Team", event.getUser(), td.name, "### 🗑️ تم حذف الفريق\n▫️ **اسم الفريق:** " + td.name + "\n▫️ **السبب:**\n```text\n" + reason + "\n```\n▫️ **القائد السابق:** <@" + extractIdOnly(td.leaderId) + ">\n▫️ **اللون:** `" + td.color + "`", "#ff0000");
        deleteDiscordResources(guild, td, () -> { deleteTeamFromDb(teamId, td.name); event.getHook().editOriginal("✅ تم حذف فريق **" + td.name + "** بنجاح! 🗑️").queue(); });
    }

    private void syncToSupabase(String name, String color, String leader, String m2, String m3, String m4, String tag, String oldName) {
        com.highcore.bot.database.SupabaseManager supa = LeonTrotskyBot.getSupabaseManager();
        if (supa == null) return;
        if (tag.equals("New Born")) supa.upsertTeam(name, color, leader, m2, m3, m4, tag);
        else { if (oldName != null && !oldName.equals(name)) { supa.deleteTeam(oldName); supa.upsertTeam(name, color, leader, m2, m3, m4, tag); } else supa.updateTeam(name, color, leader, m2, m3, m4, tag); }
    }

    private void deleteDiscordResources(Guild guild, TeamData td, Runnable onComplete) {
        if (td.roleId != null) { Role r = guild.getRoleById(td.roleId); if (r != null) r.delete().queue(null, e -> {}); }
        if (td.textChannelId != null) { TextChannel tc = guild.getTextChannelById(td.textChannelId); if (tc != null) tc.delete().queue(null, e -> {}); }
        if (td.voiceChannelId != null) { VoiceChannel vc = guild.getVoiceChannelById(td.voiceChannelId); if (vc != null) vc.delete().queue(null, e -> {}); }
        if (td.cmdChannelId != null) { TextChannel cc = guild.getTextChannelById(td.cmdChannelId); if (cc != null) cc.delete().queue(null, e -> {}); }
        if (td.categoryId != null) { new Thread(() -> { try { Thread.sleep(2000); } catch (Exception ignored) {} Category cat = guild.getCategoryById(td.categoryId); if (cat != null) cat.delete().queue(null, e -> {}); if (onComplete != null) onComplete.run(); }, "team-delete-" + td.id).start(); }
        else { if (onComplete != null) onComplete.run(); }
    }

    private void deleteTeamFromDb(int teamId, String teamName) {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection(); PreparedStatement ps = conn.prepareStatement("DELETE FROM teams WHERE id = ?")) {
            ps.setInt(1, teamId); ps.executeUpdate();
            com.highcore.bot.database.SupabaseManager supa = LeonTrotskyBot.getSupabaseManager();
            if (supa != null) supa.deleteTeam(teamName);
        } catch (Exception e) { logger.error("Error deleting team from DB", e); }
    }

    private void removeRolesFromOldMembers(Guild guild, TeamData td, Role teamRole, Role leaderRole) {
        String[] ids = {extractIdOnly(td.leaderId), extractIdOnly(td.member2Id), extractIdOnly(td.member3Id), extractIdOnly(td.member4Id)};
        for (String uid : ids) { if (uid == null) continue; guild.retrieveMemberById(uid).queue(m -> { if (teamRole != null) guild.removeRoleFromMember(m, teamRole).queue(null, e -> {}); if (uid.equals(extractIdOnly(td.leaderId)) && leaderRole != null) guild.removeRoleFromMember(m, leaderRole).queue(null, e -> {}); }, e -> {}); }
    }

    private void addRoleToMember(Guild guild, String userId, Role role) {
        if (userId == null || role == null) return;
        guild.retrieveMemberById(userId).queue(m -> guild.addRoleToMember(m, role).queue(null, e -> {}), e -> {});
    }

    private List<String> getTeamMemberIds(TeamData td) {
        List<String> ids = new ArrayList<>();
        String[] raw = {td.leaderId, td.member2Id, td.member3Id, td.member4Id};
        for (String r : raw) { if (r != null) { String id = extractIdOnly(r); if (id != null) ids.add(id); } }
        return ids;
    }

    private int getTeamMemberCount(TeamData td) {
        int c = 0;
        if (td.leaderId  != null && !td.leaderId.isEmpty())  c++;
        if (td.member2Id != null && !td.member2Id.isEmpty()) c++;
        if (td.member3Id != null && !td.member3Id.isEmpty()) c++;
        if (td.member4Id != null && !td.member4Id.isEmpty()) c++;
        return c;
    }

    private String extractUserId(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        raw = raw.trim().replaceAll("[<@!>]", "");
        try { Long.parseLong(raw); return raw; } catch (NumberFormatException e) { return null; }
    }

    private String extractIdOnly(String dbValue) {
        if (dbValue == null) return null;
        if (dbValue.contains("|")) { String[] parts = dbValue.split("\\|"); if (parts.length > 1) return parts[1].trim(); }
        return dbValue.trim();
    }

    private String formatDbUser(Member m) { return m == null ? null : m.getUser().getName() + " | " + m.getId(); }

    private String formatDbUser(Guild guild, String id) {
        if (id == null) return null; Member m = guild.getMemberById(id);
        if (m != null) return m.getUser().getName() + " | " + id;
        try { net.dv8tion.jda.api.entities.User u = guild.getJDA().retrieveUserById(id).complete(); if (u != null) return u.getName() + " | " + id; } catch (Exception e) {}
        return "Unknown | " + id;
    }

    private void sendLog(Guild guild, String action, net.dv8tion.jda.api.entities.User user, String target, String details, String colorCode) {
        TextChannel logCh = guild.getTextChannelById("1512492553793044521"); if (logCh == null) return;
        Color color; try { color = Color.decode(colorCode.startsWith("#") ? colorCode : "#" + colorCode); } catch (Exception e) { color = Color.DARK_GRAY; }
        net.dv8tion.jda.api.EmbedBuilder eb = new net.dv8tion.jda.api.EmbedBuilder();
        eb.setAuthor("► HighcoreMc・ Activity Log", null, guild.getIconUrl());
        eb.setDescription("**Action:**\n" + action + "\n\n**User:**\n" + (user != null ? user.getAsMention() + " (" + user.getId() + ")" : "Automated System") + "\n\n**Target:**\n" + target + "\n\n**Details:**\n" + details);
        eb.setColor(color); eb.setTimestamp(java.time.Instant.now());
        logCh.sendMessageEmbeds(eb.build()).queue();
    }

    private String resolveDisplay(Guild guild, String userId) {
        if (userId == null) return "—";
        if (guild != null) { Member m = guild.getMemberById(userId); if (m != null) return "`" + m.getUser().getEffectiveName() + "`"; }
        return "<@" + userId + ">";
    }

    private String resolveDisplayName(Guild guild, String userId) {
        if (userId == null) return null;
        if (guild != null) { Member m = guild.getMemberById(userId); if (m != null) return m.getUser().getEffectiveName(); }
        return userId;
    }

    private String tagEmoji(String tag) { if ("Approved".equals(tag)) return "✅"; if ("Modified".equals(tag)) return "✏️"; return "🆕"; }
    private boolean teamExistsByName(String name) { try (Connection conn = LeonTrotskyBot.getDbManager().getConnection(); PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM teams WHERE name = ?")) { ps.setString(1, name); return ps.executeQuery().next(); } catch (Exception e) { return false; } }
    private int getTeamCount() { try (Connection conn = LeonTrotskyBot.getDbManager().getConnection(); PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM teams"); ResultSet rs = ps.executeQuery()) { if (rs.next()) return rs.getInt(1); } catch (Exception e) { logger.error("Error getting team count", e); } return 0; }

    private List<TeamData> getTeamList() {
        List<TeamData> list = new ArrayList<>();
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection(); PreparedStatement ps = conn.prepareStatement("SELECT * FROM teams ORDER BY created_at DESC"); ResultSet rs = ps.executeQuery()) { while (rs.next()) list.add(TeamData.from(rs)); } catch (Exception e) { logger.error("Error getting team list", e); }
        return list;
    }

    private TeamData getTeamById(int id) { try (Connection conn = LeonTrotskyBot.getDbManager().getConnection(); PreparedStatement ps = conn.prepareStatement("SELECT * FROM teams WHERE id = ?")) { ps.setInt(1, id); ResultSet rs = ps.executeQuery(); if (rs.next()) return TeamData.from(rs); } catch (Exception e) { logger.error("Error getting team by id", e); } return null; }
    private TeamData getTeamByName(String name) { try (Connection conn = LeonTrotskyBot.getDbManager().getConnection(); PreparedStatement ps = conn.prepareStatement("SELECT * FROM teams WHERE name LIKE ?")) { ps.setString(1, "%" + name + "%"); ResultSet rs = ps.executeQuery(); if (rs.next()) return TeamData.from(rs); } catch (Exception e) { logger.error("Error getting team by name", e); } return null; }
    private TeamData getTeamByLeaderId(String userId) { try (Connection conn = LeonTrotskyBot.getDbManager().getConnection(); PreparedStatement ps = conn.prepareStatement("SELECT * FROM teams WHERE leader_id LIKE ?")) { ps.setString(1, "%" + userId + "%"); ResultSet rs = ps.executeQuery(); if (rs.next()) return TeamData.from(rs); } catch (Exception e) { logger.error("Error getting team by leader id", e); } return null; }
    private TeamData getTeamByCoLeaderId(String userId) { try (Connection conn = LeonTrotskyBot.getDbManager().getConnection(); PreparedStatement ps = conn.prepareStatement("SELECT * FROM teams WHERE co_leader_id = ?")) { ps.setString(1, userId); ResultSet rs = ps.executeQuery(); if (rs.next()) return TeamData.from(rs); } catch (Exception e) { logger.error("Error getting team by co-leader id", e); } return null; }
    private String getAnnouncementMsgId(int teamId) { try (Connection conn = LeonTrotskyBot.getDbManager().getConnection(); PreparedStatement ps = conn.prepareStatement("SELECT announcement_message_id FROM teams WHERE id = ?")) { ps.setInt(1, teamId); ResultSet rs = ps.executeQuery(); if (rs.next()) return rs.getString("announcement_message_id"); } catch (Exception e) { logger.error("Error getting announcement msg id", e); } return null; }

    // ===================================================================
    // Data Class
    // ===================================================================

    private static class TeamData {
        int    id;
        String name, color, roleId, categoryId, voiceChannelId, textChannelId, cmdChannelId;
        String leaderId, member2Id, member3Id, member4Id;
        String coLeaderId, logoUrl, tag, announcementMessageId;

        static TeamData from(ResultSet rs) throws SQLException {
            TeamData t = new TeamData();
            t.id = rs.getInt("id"); t.name = rs.getString("name"); t.color = rs.getString("color");
            t.roleId = rs.getString("role_id"); t.categoryId = rs.getString("category_id");
            t.voiceChannelId = rs.getString("voice_channel_id"); t.textChannelId = rs.getString("text_channel_id");
            t.leaderId = rs.getString("leader_id"); t.member2Id = rs.getString("member2_id");
            t.member3Id = rs.getString("member3_id"); t.member4Id = rs.getString("member4_id");
            t.tag = rs.getString("tag"); t.announcementMessageId = rs.getString("announcement_message_id");
            try { t.cmdChannelId = rs.getString("cmd_channel_id"); } catch (Exception ignored) {}
            try { t.coLeaderId   = rs.getString("co_leader_id");   } catch (Exception ignored) {}
            try { t.logoUrl      = rs.getString("logo_url");        } catch (Exception ignored) {}
            return t;
        }
    }
}