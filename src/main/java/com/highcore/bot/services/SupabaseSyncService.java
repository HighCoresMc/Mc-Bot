package com.highcore.bot.services;

import com.highcore.bot.LeonTrotskyBot;
import com.highcore.bot.database.SupabaseManager;
import com.highcore.bot.commands.EventCommand;
import com.highcore.bot.utils.TimeUtils;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.separator.Separator;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SupabaseSyncService {
    private static final Logger logger = LoggerFactory.getLogger(SupabaseSyncService.class);
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "SupabaseSyncThread");
        t.setDaemon(true);
        return t;
    });

    private static final String EVENTS_FORUM_ID     = "1487142537666760735";
    private static final String STAFF_CHANNEL_ID     = "1487142453214711888";
    private static final String LEADER_ROLE_ID      = "1512457786548682863";
    private static final String ANNOUNCE_CHANNEL_ID = "1512461188997578984";
    private static final String LOG_CHANNEL_ID      = "1512492553793044521";

    public static void startScheduler(JDA jda) {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                sync(jda);
            } catch (Exception e) {
                logger.error("Error in SupabaseSyncService execution", e);
            }
        }, 10, 15, TimeUnit.SECONDS);
        logger.info("SupabaseSyncService background scheduler started.");
    }

    private static void sync(JDA jda) {
        SupabaseManager supa = LeonTrotskyBot.getSupabaseManager();
        if (supa == null) return;

        Guild guild = null;
        for (Guild g : jda.getGuilds()) {
            if (g.getForumChannelById(EVENTS_FORUM_ID) != null) {
                guild = g;
                break;
            }
        }
        if (guild == null && !jda.getGuilds().isEmpty()) {
            guild = jda.getGuilds().get(0);
        }
        if (guild == null) return;

        syncEvents(guild, supa);
        syncTeams(guild, supa);
    }

    // Sync
    private static void syncEvents(Guild guild, SupabaseManager supa) {
        try {
            HttpClient client = supa.getHttpClient();
            String url = supa.getSupabaseUrl();
            String key = supa.getSupabaseKey();

            List<Integer> activeSupabaseIds = new ArrayList<>();
            boolean mcSuccess = false;
            boolean dcSuccess = false;

            // 1. Fetch MC Events
            HttpRequest mcRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/rest/v1/mc_events?select=*"))
                    .timeout(Duration.ofSeconds(10))
                    .header("apikey", key)
                    .header("Authorization", "Bearer " + key)
                    .GET()
                    .build();
            HttpResponse<String> mcResponse = client.send(mcRequest, HttpResponse.BodyHandlers.ofString());
            if (mcResponse.statusCode() >= 200 && mcResponse.statusCode() < 300) {
                mcSuccess = true;
                JsonArray mcArray = JsonParser.parseString(mcResponse.body()).getAsJsonArray();
                for (int i = 0; i < mcArray.size(); i++) {
                    JsonObject obj = mcArray.get(i).getAsJsonObject();
                    activeSupabaseIds.add(obj.get("id").getAsInt());
                    processEventRow(guild, obj, "MC");
                }
            }

            // 2. Fetch DC Events
            HttpRequest dcRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/rest/v1/events?section=eq.dc&select=*"))
                    .timeout(Duration.ofSeconds(10))
                    .header("apikey", key)
                    .header("Authorization", "Bearer " + key)
                    .GET()
                    .build();
            HttpResponse<String> dcResponse = client.send(dcRequest, HttpResponse.BodyHandlers.ofString());
            if (dcResponse.statusCode() >= 200 && dcResponse.statusCode() < 300) {
                dcSuccess = true;
                JsonArray dcArray = JsonParser.parseString(dcResponse.body()).getAsJsonArray();
                for (int i = 0; i < dcArray.size(); i++) {
                    JsonObject obj = dcArray.get(i).getAsJsonObject();
                    activeSupabaseIds.add(obj.get("id").getAsInt());
                    processEventRow(guild, obj, "DC");
                }
            }

            // 3. Delete removed events (only if both requests succeeded)
            if (mcSuccess && dcSuccess) {
                deleteRemovedEvents(guild, activeSupabaseIds);
            }
        } catch (Exception e) {
            logger.error("Error fetching events from Supabase", e);
        }
    }

    private static void deleteRemovedEvents(Guild guild, List<Integer> activeSupabaseIds) {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            List<Integer> localIdsToDelete = new ArrayList<>();
            List<String> threadIdsToDelete = new ArrayList<>();
            List<String[]> staffMsgsToDelete = new ArrayList<>();
            
            try (PreparedStatement ps = conn.prepareStatement("SELECT id, channel_id, staff_message_id, staff_channel_id, supabase_id FROM events WHERE supabase_id IS NOT NULL")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int supabaseId = rs.getInt("supabase_id");
                        if (!activeSupabaseIds.contains(supabaseId)) {
                            localIdsToDelete.add(rs.getInt("id"));
                            String threadId = rs.getString("channel_id");
                            if (threadId != null && !threadId.isEmpty()) {
                                threadIdsToDelete.add(threadId);
                            }
                            String sMsgId = rs.getString("staff_message_id");
                            String sChId = rs.getString("staff_channel_id");
                            if (sMsgId != null && sChId != null) {
                                staffMsgsToDelete.add(new String[]{sChId, sMsgId});
                            }
                        }
                    }
                }
            }

            for (String threadId : threadIdsToDelete) {
                try {
                    net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel thread = guild.getThreadChannelById(threadId);
                    if (thread != null) {
                        thread.delete().queue();
                    }
                } catch (Exception e) {
                    logger.error("Failed to delete thread " + threadId, e);
                }
            }

            for (String[] staffMsg : staffMsgsToDelete) {
                try {
                    net.dv8tion.jda.api.entities.channel.concrete.TextChannel sChannel = guild.getTextChannelById(staffMsg[0]);
                    if (sChannel != null) {
                        sChannel.deleteMessageById(staffMsg[1]).queue(s -> {}, f -> {});
                    }
                } catch (Exception ignored) {}
            }

            for (int localId : localIdsToDelete) {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM events WHERE id = ?")) {
                    ps.setInt(1, localId);
                    ps.executeUpdate();
                }
                logger.info("Deleted event local ID {} because it was removed from Supabase.", localId);
            }
        } catch (Exception e) {
            logger.error("Error deleting removed events", e);
        }
    }

    private static void processEventRow(Guild guild, JsonObject obj, String category) {
        int supabaseId = obj.get("id").getAsInt();
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            boolean exists = false;
            String existingMessageId = null;
            int localId = -1;
            try (PreparedStatement ps = conn.prepareStatement("SELECT id, message_id FROM events WHERE supabase_id = ? AND category = ?")) {
                ps.setInt(1, supabaseId);
                ps.setString(2, category);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        exists = true;
                        localId = rs.getInt("id");
                        existingMessageId = rs.getString("message_id");
                    }
                }
            }

            if (exists && existingMessageId != null) {
                logger.debug("Event with Supabase ID " + supabaseId + " (" + category + ") already exists in local DB.");
                return;
            }

            logger.info("Syncing new event from Supabase: ID=" + supabaseId + " Category=" + category);

            String title = obj.has("title") && !obj.get("title").isJsonNull() ? obj.get("title").getAsString() : "Untitled";
            String type = obj.has("event_type") && !obj.get("event_type").isJsonNull() ? obj.get("event_type").getAsString() : (obj.has("type") && !obj.get("type").isJsonNull() ? obj.get("type").getAsString() : "written");
            String eventDateIso = obj.has("event_date") && !obj.get("event_date").isJsonNull() ? obj.get("event_date").getAsString() : "2099-01-01T00:00:00Z";
            String description = obj.has("description") && !obj.get("description").isJsonNull() ? obj.get("description").getAsString() : "";
            int maxSeats = obj.has("max_supervisors") && !obj.get("max_supervisors").isJsonNull() ? obj.get("max_supervisors").getAsInt() : 50;

            String dbDateStr = formatIsoToStandard(eventDateIso);
            long unixTime = TimeUtils.parseToUnixTimestamp(dbDateStr);

            String imageUrl = obj.has("image_url") && !obj.get("image_url").isJsonNull() ? obj.get("image_url").getAsString() : null;

            if (!exists) {
                String insertSql = "INSERT INTO events (name, type, event_date, rewards_json, max_seats, conditions, requires_link, custom_question, image_url, staff_channel_id, category, supabase_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, title);
                    ps.setString(2, type);
                    ps.setString(3, dbDateStr);
                    ps.setString(4, "[]");
                    ps.setInt(5, maxSeats);
                    ps.setString(6, description);
                    ps.setBoolean(7, true);
                    ps.setNull(8, java.sql.Types.VARCHAR);
                    if (imageUrl != null && !imageUrl.isEmpty()) {
                        ps.setString(9, imageUrl);
                    } else {
                        ps.setNull(9, java.sql.Types.VARCHAR);
                    }
                    ps.setString(10, STAFF_CHANNEL_ID);
                    ps.setString(11, category);
                    ps.setInt(12, supabaseId);
                    ps.executeUpdate();

                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (rs.next()) localId = rs.getInt(1);
                    }
                }
            }

            if (localId == -1) return;

            ForumChannel forumChannel = guild.getForumChannelById(EVENTS_FORUM_ID);
            if (forumChannel != null) {
                final int finalLocalId = localId;
                Container publicContainer = EventCommand.getPublicEventContainer(title, type, unixTime, "[]", maxSeats, 0, description, "OPEN", finalLocalId, imageUrl, null, null, guild);
                ActionRow actionRow = ActionRow.of(EventCommand.getPublicButtons(finalLocalId, "OPEN"));

                MessageCreateBuilder builder = new MessageCreateBuilder()
                        .setComponents(publicContainer, actionRow)
                        .useComponentsV2(true);

                var postAction = forumChannel.createForumPost("🎉 " + title, builder.build());
                List<net.dv8tion.jda.api.entities.channel.forums.ForumTag> tags = new ArrayList<>();
                for (net.dv8tion.jda.api.entities.channel.forums.ForumTag t : forumChannel.getAvailableTags()) {
                    if (category.equalsIgnoreCase("MC") && t.getName().equalsIgnoreCase("MC")) {
                        tags.add(t);
                    } else if (category.equalsIgnoreCase("DC") && t.getName().equalsIgnoreCase("DC")) {
                        tags.add(t);
                    }
                }
                if (!tags.isEmpty()) {
                    postAction = postAction.setTags(tags);
                }
                var forumPost = postAction.complete();
                String threadId = forumPost.getThreadChannel().getId();
                String messageId = forumPost.getMessage().getId();

                try (PreparedStatement p2 = conn.prepareStatement("UPDATE events SET message_id = ?, channel_id = ? WHERE id = ?")) {
                    p2.setString(1, messageId);
                    p2.setString(2, threadId);
                    p2.setInt(3, finalLocalId);
                    p2.executeUpdate();
                }

                Container staffContainer = EventCommand.getStaffContainer(title, forumPost.getThreadChannel().getAsMention(), finalLocalId, "OPEN", 0, new ArrayList<>());
                MessageCreateBuilder staffBuilder = new MessageCreateBuilder()
                        .setComponents(staffContainer)
                        .useComponentsV2(true);

                TextChannel staffChannel = guild.getTextChannelById(STAFF_CHANNEL_ID);
                if (staffChannel != null) {
                    var staffMsg = staffChannel.sendMessage(staffBuilder.build()).complete();
                    try (PreparedStatement p3 = conn.prepareStatement("UPDATE events SET staff_message_id = ?, staff_channel_id = ? WHERE id = ?")) {
                        p3.setString(1, staffMsg.getId());
                        p3.setString(2, STAFF_CHANNEL_ID);
                        p3.setInt(3, finalLocalId);
                        p3.executeUpdate();
                    }
                } else {
                    logger.warn("Could not find Staff Channel with ID " + STAFF_CHANNEL_ID);
                }
            } else {
                logger.warn("Could not find Forum Channel with ID " + EVENTS_FORUM_ID + " to create the event post!");
            }
            logger.info("Automatically synced and created event from web. Local ID: {}, Supabase ID: {}, Title: {}", localId, supabaseId, title);
        } catch (Exception e) {
            logger.error("Error processing event row ID " + supabaseId, e);
        }
    }

    // Sync
    private static void syncTeams(Guild guild, SupabaseManager supa) {
        try {
            HttpClient client = supa.getHttpClient();
            String url = supa.getSupabaseUrl();
            String key = supa.getSupabaseKey();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/rest/v1/teams?select=*"))
                    .timeout(Duration.ofSeconds(10))
                    .header("apikey", key)
                    .header("Authorization", "Bearer " + key)
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) return;

            JsonArray supaArray = JsonParser.parseString(response.body()).getAsJsonArray();
            List<SupabaseTeam> supaTeams = new ArrayList<>();
            for (int i = 0; i < supaArray.size(); i++) {
                JsonObject obj = supaArray.get(i).getAsJsonObject();
                SupabaseTeam t = new SupabaseTeam();
                t.name = obj.has("name") && !obj.get("name").isJsonNull() ? obj.get("name").getAsString() : "";
                t.color = obj.has("color") && !obj.get("color").isJsonNull() ? obj.get("color").getAsString() : "#5865F2";
                t.leader = obj.has("leader") && !obj.get("leader").isJsonNull() ? obj.get("leader").getAsString() : null;
                t.member2 = obj.has("member2") && !obj.get("member2").isJsonNull() ? obj.get("member2").getAsString() : null;
                t.member3 = obj.has("member3") && !obj.get("member3").isJsonNull() ? obj.get("member3").getAsString() : null;
                t.member4 = obj.has("member4") && !obj.get("member4").isJsonNull() ? obj.get("member4").getAsString() : null;
                t.tag = obj.has("tag") && !obj.get("tag").isJsonNull() ? obj.get("tag").getAsString() : "New Born";
                if (!t.name.isEmpty()) supaTeams.add(t);
            }

            List<LocalTeam> localTeams = new ArrayList<>();
            try (Connection conn = LeonTrotskyBot.getDbManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT * FROM teams");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LocalTeam t = new LocalTeam();
                    t.id = rs.getInt("id");
                    t.name = rs.getString("name");
                    t.color = rs.getString("color");
                    t.roleId = rs.getString("role_id");
                    t.categoryId = rs.getString("category_id");
                    t.voiceChannelId = rs.getString("voice_channel_id");
                    t.textChannelId = rs.getString("text_channel_id");
                    t.leaderId = rs.getString("leader_id");
                    t.member2Id = rs.getString("member2_id");
                    t.member3Id = rs.getString("member3_id");
                    t.member4Id = rs.getString("member4_id");
                    t.tag = rs.getString("tag");
                    t.announcementMessageId = rs.getString("announcement_message_id");
                    localTeams.add(t);
                }
            }

            // A. Check for New Teams (in Supabase but not in MySQL)
            for (SupabaseTeam st : supaTeams) {
                boolean found = false;
                for (LocalTeam lt : localTeams) {
                    if (lt.name.equalsIgnoreCase(st.name)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    createTeamFromWeb(guild, st);
                }
            }

            // B. Check for Deleted Teams (in MySQL but not in Supabase)
            for (LocalTeam lt : localTeams) {
                boolean found = false;
                for (SupabaseTeam st : supaTeams) {
                    if (st.name.equalsIgnoreCase(lt.name)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    deleteTeamFromWeb(guild, lt);
                }
            }

            // C. Check for Modified Teams (in both but fields differ)
            for (SupabaseTeam st : supaTeams) {
                for (LocalTeam lt : localTeams) {
                    if (st.name.equalsIgnoreCase(lt.name)) {
                        String sLeader = extractId(st.leader);
                        String sM2 = extractId(st.member2);
                        String sM3 = extractId(st.member3);
                        String sM4 = extractId(st.member4);

                        String lLeader = extractId(lt.leaderId);
                        String lM2 = extractId(lt.member2Id);
                        String lM3 = extractId(lt.member3Id);
                        String lM4 = extractId(lt.member4Id);

                        boolean modified = !Objects.equals(st.color, lt.color)
                                || !Objects.equals(sLeader, lLeader)
                                || !Objects.equals(sM2, lM2)
                                || !Objects.equals(sM3, lM3)
                                || !Objects.equals(sM4, lM4)
                                || !Objects.equals(st.tag, lt.tag);

                        if (modified) {
                            updateTeamFromWeb(guild, lt, st, sLeader, sM2, sM3, sM4, lLeader, lM2, lM3, lM4);
                        }
                    }
                }
            }

        } catch (Exception e) {
            logger.error("Error syncing teams from Supabase", e);
        }
    }

    private static void createTeamFromWeb(Guild guild, SupabaseTeam st) {
        try {
            String leaderId = extractId(st.leader);
            String member2Id = extractId(st.member2);
            String member3Id = extractId(st.member3);
            String member4Id = extractId(st.member4);

            if (leaderId == null || member2Id == null) return;

            Color color = Color.decode(st.color);
            Role teamRole = guild.createRole().setName(st.name).setColor(color).complete();

            Role leaderRole = guild.getRoleById(LEADER_ROLE_ID);
            if (leaderRole != null) {
                Member leaderMember = guild.getMemberById(leaderId);
                if (leaderMember != null) {
                    guild.addRoleToMember(leaderMember, leaderRole).complete();
                }
            }

            String[] members = {leaderId, member2Id, member3Id, member4Id};
            for (String mid : members) {
                if (mid != null) {
                    Member m = guild.getMemberById(mid);
                    if (m != null) guild.addRoleToMember(m, teamRole).complete();
                }
            }

            Category category = guild.createCategory("<— " + st.name + " —>").complete();
            category.upsertPermissionOverride(guild.getPublicRole()).deny(Permission.VIEW_CHANNEL).complete();
            category.upsertPermissionOverride(teamRole)
                    .grant(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY, Permission.VOICE_CONNECT, Permission.VOICE_SPEAK, Permission.VOICE_USE_VAD)
                    .complete();

            VoiceChannel vc = guild.createVoiceChannel("🔊・" + st.name + "・voice").setParent(category).complete();
            vc.getManager().sync().complete();

            TextChannel tc = guild.createTextChannel("💭・" + st.name).setParent(category).complete();
            tc.getManager().sync().complete();

            String lDb = formatDbUser(guild, leaderId);
            String m2Db = formatDbUser(guild, member2Id);
            String m3Db = member3Id != null ? formatDbUser(guild, member3Id) : null;
            String m4Db = member4Id != null ? formatDbUser(guild, member4Id) : null;

            int localId = -1;
            try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
                String sql = "INSERT INTO teams (name, color, role_id, category_id, voice_channel_id, text_channel_id, leader_id, member2_id, member3_id, member4_id, tag) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, st.name);
                    ps.setString(2, st.color);
                    ps.setString(3, teamRole.getId());
                    ps.setString(4, category.getId());
                    ps.setString(5, vc.getId());
                    ps.setString(6, tc.getId());
                    ps.setString(7, lDb);
                    ps.setString(8, m2Db);
                    ps.setString(9, m3Db);
                    ps.setString(10, m4Db);
                    ps.setString(11, st.tag);
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (rs.next()) localId = rs.getInt(1);
                    }
                }
            }

            if (localId != -1) {
                sendAnnouncementAndSave(guild, st.name, leaderId, member2Id, member3Id, member4Id, st.color, localId);
            }

            String details = "### ✅ تم إنشاء فريق جديد من لوحة التحكم\n"
                           + "▫️ **اسم الفريق:** " + st.name + "\n"
                           + "▫️ **اللون:** `" + st.color + "`\n"
                           + "▫️ **رتبة الفريق:** <@&" + teamRole.getId() + ">\n"
                           + "▫️ **قسم الفريق:** <#" + category.getId() + ">\n"
                           + "▫️ **القائد:** <@" + leaderId + ">\n"
                           + "▫️ **العضو 2:** <@" + member2Id + ">\n"
                           + (member3Id != null ? "▫️ **العضو 3:** <@" + member3Id + ">\n" : "")
                           + (member4Id != null ? "▫️ **العضو 4:** <@" + member4Id + ">\n" : "");
            sendLog(guild, "Create Team (Web Dashboard)", st.name, details, st.color);

            logger.info("Automatically synced and created team from web: " + st.name);
        } catch (Exception e) {
            logger.error("Error creating team from web: " + st.name, e);
        }
    }

    private static void deleteTeamFromWeb(Guild guild, LocalTeam lt) {
        try {
            Role leaderRole = guild.getRoleById(LEADER_ROLE_ID);
            if (leaderRole != null && lt.leaderId != null) {
                String lId = extractId(lt.leaderId);
                if (lId != null) {
                    guild.retrieveMemberById(lId).queue(m -> {
                        guild.removeRoleFromMember(m, leaderRole).queue(null, e -> {});
                    }, e -> {});
                }
            }

            if (lt.announcementMessageId != null) {
                TextChannel annCh = guild.getTextChannelById(ANNOUNCE_CHANNEL_ID);
                if (annCh != null) {
                    annCh.deleteMessageById(lt.announcementMessageId).queue(null, e -> {});
                }
            }

            if (lt.leaderId != null) {
                String lId = extractId(lt.leaderId);
                if (lId != null) {
                    guild.retrieveMemberById(lId).queue(m -> {
                        m.getUser().openPrivateChannel().queue(pc -> {
                            pc.sendMessage("تم حذف فريقك **" + lt.name + "** من لوحة التحكم.").queue(null, e -> {});
                        }, e -> {});
                    }, e -> {});
                }
            }

            if (lt.roleId != null) {
                Role r = guild.getRoleById(lt.roleId);
                if (r != null) r.delete().queue(null, e -> {});
            }
            if (lt.textChannelId != null) {
                TextChannel tc = guild.getTextChannelById(lt.textChannelId);
                if (tc != null) tc.delete().queue(null, e -> {});
            }
            if (lt.voiceChannelId != null) {
                VoiceChannel vc = guild.getVoiceChannelById(lt.voiceChannelId);
                if (vc != null) vc.delete().queue(null, e -> {});
            }
            if (lt.categoryId != null) {
                new Thread(() -> {
                    try { Thread.sleep(2000); } catch (Exception ignored) {}
                    Category cat = guild.getCategoryById(lt.categoryId);
                    if (cat != null) cat.delete().queue(null, e -> {});
                }).start();
            }

            try (Connection conn = LeonTrotskyBot.getDbManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM teams WHERE id = ?")) {
                ps.setInt(1, lt.id);
                ps.executeUpdate();
            }

            String details = "### 🗑️ تم حذف الفريق من لوحة التحكم\n"
                           + "▫️ **اسم الفريق:** " + lt.name + "\n"
                           + "▫️ **القائد السابق:** <@" + extractId(lt.leaderId) + ">\n"
                           + "▫️ **لون الفريق:** `" + lt.color + "`";
            sendLog(guild, "Delete Team (Web Dashboard)", lt.name, details, "#ff0000");

            logger.info("Automatically synced and deleted team from web: " + lt.name);
        } catch (Exception e) {
            logger.error("Error deleting team from web: " + lt.name, e);
        }
    }

    private static void updateTeamFromWeb(Guild guild, LocalTeam lt, SupabaseTeam st,
                                         String leaderId, String member2Id, String member3Id, String member4Id,
                                         String oldLeader, String oldM2, String oldM3, String oldM4) {
        try {
            Role teamRole = lt.roleId != null ? guild.getRoleById(lt.roleId) : null;

            // 1. Color
            if (!st.color.equalsIgnoreCase(lt.color) && teamRole != null) {
                teamRole.getManager().setColor(Color.decode(st.color)).complete();
            }

            // 2. Members
            boolean membersChanged = !Objects.equals(leaderId, oldLeader)
                    || !Objects.equals(member2Id, oldM2)
                    || !Objects.equals(member3Id, oldM3)
                    || !Objects.equals(member4Id, oldM4);

            if (membersChanged) {
                Role leaderRole = guild.getRoleById(LEADER_ROLE_ID);
                String[] oldIds = {oldLeader, oldM2, oldM3, oldM4};
                for (String uid : oldIds) {
                    if (uid == null) continue;
                    try {
                        Member m = guild.getMemberById(uid);
                        if (m != null) {
                            if (teamRole != null) guild.removeRoleFromMember(m, teamRole).complete();
                            if (uid.equals(oldLeader) && leaderRole != null) {
                                guild.removeRoleFromMember(m, leaderRole).complete();
                            }
                        }
                    } catch (Exception ignored) {}
                }

                String[] newIds = {leaderId, member2Id, member3Id, member4Id};
                for (String uid : newIds) {
                    if (uid == null) continue;
                    try {
                        Member m = guild.getMemberById(uid);
                        if (m != null) {
                            if (teamRole != null) guild.addRoleToMember(m, teamRole).complete();
                            if (uid.equals(leaderId) && leaderRole != null) {
                                guild.addRoleToMember(m, leaderRole).complete();
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }

            // 3. MySQL
            String lDb = formatDbUser(guild, leaderId);
            String m2Db = formatDbUser(guild, member2Id);
            String m3Db = member3Id != null ? formatDbUser(guild, member3Id) : null;
            String m4Db = member4Id != null ? formatDbUser(guild, member4Id) : null;

            try (Connection conn = LeonTrotskyBot.getDbManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement("UPDATE teams SET color = ?, leader_id = ?, member2_id = ?, member3_id = ?, member4_id = ?, tag = ? WHERE id = ?")) {
                ps.setString(1, st.color);
                ps.setString(2, lDb);
                ps.setString(3, m2Db);
                ps.setString(4, m3Db);
                ps.setString(5, m4Db);
                ps.setString(6, st.tag);
                ps.setInt(7, lt.id);
                ps.executeUpdate();
            }

            // 4. Announcement Message
            if (lt.announcementMessageId != null) {
                TextChannel annCh = guild.getTextChannelById(ANNOUNCE_CHANNEL_ID);
                if (annCh != null) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("## ▶ Team Updated\n\n");
                    sb.append("### 🏆 ").append(st.name).append("\n\n");
                    sb.append("**👥 Team Members:**\n");
                    sb.append("👑 Leader : <@").append(leaderId).append(">\n");
                    if (member2Id != null) sb.append("👤 Member 2 : <@").append(member2Id).append(">");
                    if (member3Id != null) sb.append("\n👤 Member 3 : <@").append(member3Id).append(">");
                    if (member4Id != null) sb.append("\n👤 Member 4 : <@").append(member4Id).append(">");
                    sb.append("\n\n**🎨 Color:** `").append(st.color).append("`");
                    sb.append("\n**🏷️ Status:** ✏️ `").append(st.tag).append("`");

                    Container container = Container.of(
                            TextDisplay.of(sb.toString()),
                            Separator.createDivider(Separator.Spacing.SMALL),
                            TextDisplay.of("> 🏆 HighCore MC • Team System")
                    );

                    MessageEditBuilder meb = new MessageEditBuilder().setComponents(container).useComponentsV2(true);
                    annCh.editMessageById(lt.announcementMessageId, meb.build()).complete();
                }
            }

            // 5. Send Log
            String details = "### ✏️ تم تعديل معلومات الفريق من لوحة التحكم\n"
                           + "▫️ **اسم الفريق:** " + st.name + "\n"
                           + "▫️ **اللون:** `" + st.color + "`\n"
                           + "▫️ **القائد الجديد:** <@" + leaderId + ">\n"
                           + "▫️ **العضو 2 الجديد:** " + (member2Id != null ? "<@" + member2Id + ">" : "*محذوف*") + "\n"
                           + (member3Id != null ? "▫️ **العضو 3 الجديد:** <@" + member3Id + ">\n" : "")
                           + (member4Id != null ? "▫️ **العضو 4 الجديد:** <@" + member4Id + ">\n" : "")
                           + "▫️ **الحالة:** `" + st.tag + "`\n";
            sendLog(guild, "Edit Team (Web Dashboard)", st.name, details, st.color);

            logger.info("Automatically synced and updated team from web: " + st.name);
        } catch (Exception e) {
            logger.error("Error updating team from web: " + st.name, e);
        }
    }

    // Helpers
    private static String extractId(String dbValue) {
        if (dbValue == null || dbValue.isEmpty() || "none".equalsIgnoreCase(dbValue)) return null;
        if (dbValue.contains("|")) {
            String[] parts = dbValue.split("\\|");
            if (parts.length > 1) return parts[1].trim();
        }
        String cleaned = dbValue.trim().replaceAll("[<@!>]", "");
        try {
            Long.parseLong(cleaned);
            return cleaned;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String formatDbUser(Guild guild, String id) {
        if (id == null) return null;
        try {
            Member m = guild.getMemberById(id);
            if (m != null) return m.getUser().getName() + " | " + id;
            var u = guild.getJDA().retrieveUserById(id).complete();
            if (u != null) return u.getName() + " | " + id;
        } catch (Exception e) {}
        return "Unknown | " + id;
    }

    private static void sendAnnouncementAndSave(Guild guild, String teamName,
                                                 String leaderId, String m2Id, String m3Id, String m4Id,
                                                 String colorCode, int teamDbId) {
        TextChannel ch = guild.getTextChannelById(ANNOUNCE_CHANNEL_ID);
        if (ch == null) return;

        StringBuilder sb = new StringBuilder();
        sb.append("## ▶ New Team\n\n");
        sb.append("### 🏆 ").append(teamName).append("\n\n");
        sb.append("**👥 Team Members:**\n");
        sb.append("👑 Leader : <@").append(leaderId).append(">\n");
        if (m2Id != null) sb.append("👤 Member 2 : <@").append(m2Id).append(">");
        if (m3Id != null) sb.append("\n👤 Member 3 : <@").append(m3Id).append(">");
        if (m4Id != null) sb.append("\n👤 Member 4 : <@").append(m4Id).append(">");
        sb.append("\n\n**🎨 Color:** `").append(colorCode).append("`");
        sb.append("\n**🏷️ Status:** 🆕 `New Born`");

        Container container = Container.of(
                TextDisplay.of(sb.toString()),
                Separator.createDivider(Separator.Spacing.SMALL),
                TextDisplay.of("> 🏆 HighCore MC • Team System")
        );

        ch.sendMessage(new MessageCreateBuilder().setComponents(container).useComponentsV2(true).build())
                .queue(msg -> {
                    try (Connection conn = LeonTrotskyBot.getDbManager().getConnection();
                         PreparedStatement ps = conn.prepareStatement("UPDATE teams SET announcement_message_id = ? WHERE id = ?")) {
                        ps.setString(1, msg.getId());
                        ps.setInt(2, teamDbId);
                        ps.executeUpdate();
                    } catch (Exception e) {
                        logger.error("Error saving announcement message id from sync", e);
                    }
                });
    }

    private static void sendLog(Guild guild, String action, String target, String details, String colorCode) {
        TextChannel logCh = guild.getTextChannelById(LOG_CHANNEL_ID);
        if (logCh == null) return;

        Color color;
        try {
            color = Color.decode(colorCode.startsWith("#") ? colorCode : "#" + colorCode);
        } catch (Exception e) {
            color = Color.DARK_GRAY;
        }

        net.dv8tion.jda.api.EmbedBuilder eb = new net.dv8tion.jda.api.EmbedBuilder();
        eb.setAuthor("► HighcoreMc・ Activity Log", null, guild.getIconUrl());

        StringBuilder desc = new StringBuilder();
        desc.append("**Action:**\n").append(action).append("\n\n");
        desc.append("**User:**\n").append("Automated Web Sync").append("\n\n");
        desc.append("**Target:**\n").append(target).append("\n\n");
        desc.append("**Details:**\n").append(details);

        eb.setDescription(desc.toString());
        eb.setColor(color);
        eb.setTimestamp(java.time.Instant.now());

        logCh.sendMessageEmbeds(eb.build()).queue();
    }

    private static String formatIsoToStandard(String isoStr) {
        if (isoStr == null) return "2099-01-01 00:00";
        try {
            // First try to parse as ISO with offset
            java.time.OffsetDateTime odt = java.time.OffsetDateTime.parse(isoStr);
            java.time.ZonedDateTime zdt = odt.atZoneSameInstant(java.time.ZoneId.of("Asia/Riyadh"));
            return zdt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        } catch (Exception e) {
            try {
                // Try parsing without offset (assume UTC)
                java.time.LocalDateTime ldt;
                if (!isoStr.contains("T")) {
                    ldt = java.time.LocalDateTime.parse(isoStr, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                } else {
                    ldt = java.time.LocalDateTime.parse(isoStr);
                }
                java.time.ZonedDateTime zdt = ldt.atZone(java.time.ZoneId.of("UTC")).withZoneSameInstant(java.time.ZoneId.of("Asia/Riyadh"));
                return zdt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            } catch (Exception ex) {
                return isoStr.replace("T", " ").substring(0, Math.min(isoStr.length(), 16));
            }
        }
    }

    // Classes
    private static class SupabaseTeam {
        String name;
        String color;
        String leader;
        String member2;
        String member3;
        String member4;
        String tag;
    }

    private static class LocalTeam {
        int id;
        String name;
        String color;
        String roleId;
        String categoryId;
        String voiceChannelId;
        String textChannelId;
        String leaderId;
        String member2Id;
        String member3Id;
        String member4Id;
        String tag;
        String announcementMessageId;
    }
}
