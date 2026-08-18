package com.highcore.bot.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class SupabaseManager {
    private static final Logger logger = LoggerFactory.getLogger(SupabaseManager.class);

    private final String dbUrl = "jdbc:postgresql://198.186.130.131:5432/postgres?schema=public";
    private final String dbUser = "postgres";
    private final String dbPass = "fIQrOSfvhAB6FLcJycpr50Sqqk1YWySMwTZE1MktPv9oKBAoGSrlSoW82s0QmTvw";

    public SupabaseManager(String supabaseUrl, String supabaseKey) {
        // Migrated to direct PostgreSQL JDBC connection
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(dbUrl, dbUser, dbPass);
    }

    public void updateEventStatus(int id, String table, String status) {
        String sql = "UPDATE " + table + " SET status = ? WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setInt(2, id);
            ps.executeUpdate();
            logger.info("Updated status for event {} in table {} to {}", id, table, status);
        } catch (Exception e) {
            logger.error("Error updating event status", e);
        }
    }

    public void logEvent(int eventId, String title, String type, String description, String eventDate, int points,
            int maxSupervisors) {
        String sql = "INSERT INTO mc_events (id, title, type, description, event_date, points, max_supervisors) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, eventId);
            ps.setString(2, title);
            ps.setString(3, type);
            ps.setString(4, description);
            ps.setString(5, eventDate);
            ps.setInt(6, points);
            ps.setInt(7, maxSupervisors);
            ps.executeUpdate();
            logger.info("Event logged to Postgres successfully. ID: {}", eventId);
        } catch (Exception e) {
            logger.error("Error sending event to Postgres", e);
        }
    }

    public void upsertTeam(String name, String color, String leader, String member2,
            String member3, String member4, String tag) {
        String sql = "INSERT INTO teams (admin, name, team_name, color, leader, member2, member3, member4, tag) " +
                     "VALUES ('HighCoreMc Bot', ?, ?, ?, ?, ?, ?, ?, ?) " +
                     "ON CONFLICT (name) DO UPDATE SET " +
                     "team_name = EXCLUDED.team_name, color = EXCLUDED.color, leader = EXCLUDED.leader, " +
                     "member2 = EXCLUDED.member2, member3 = EXCLUDED.member3, member4 = EXCLUDED.member4, " +
                     "tag = EXCLUDED.tag";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, name);
            ps.setString(3, color);
            ps.setString(4, leader);
            ps.setString(5, member2);
            ps.setString(6, member3);
            ps.setString(7, member4);
            ps.setString(8, tag);
            ps.executeUpdate();
            logger.info("Team '{}' synced to Postgres successfully.", name);
        } catch (Exception e) {
            logger.error("Error sending team to Postgres", e);
        }
    }

    public void updateTeam(String name, String color, String leader, String member2,
            String member3, String member4, String tag) {
        String sql = "UPDATE teams SET team_name = ?, color = ?, leader = ?, member2 = ?, member3 = ?, member4 = ?, tag = ? " +
                     "WHERE name = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, color);
            ps.setString(3, leader);
            ps.setString(4, member2);
            ps.setString(5, member3);
            ps.setString(6, member4);
            ps.setString(7, tag);
            ps.setString(8, name);
            ps.executeUpdate();
            logger.info("Team '{}' updated in Postgres successfully.", name);
        } catch (Exception e) {
            logger.error("Error updating team in Postgres", e);
        }
    }

    public void deleteTeam(String name) {
        String sql = "DELETE FROM teams WHERE name = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.executeUpdate();
            logger.info("Team '{}' deleted from Postgres.", name);
        } catch (Exception e) {
            logger.error("Error deleting team from Postgres", e);
        }
    }

    public void updateTeamTag(String name, String tag) {
        String sql = "UPDATE teams SET tag = ? WHERE name = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tag);
            ps.setString(2, name);
            ps.executeUpdate();
            logger.info("Team '{}' tag updated to '{}' in Postgres.", name, tag);
        } catch (Exception e) {
            logger.error("Error updating team tag in Postgres", e);
        }
    }

    public void logDcEvent(int eventId, String title, String type, String description, String eventDate, int points,
            int maxSupervisors) {
        String sql = "INSERT INTO events (id, title, event_type, description, event_date, points, max_supervisors, section, created_by) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, 'dc', 'HighCoreMc Bot')";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, eventId);
            ps.setString(2, title);
            ps.setString(3, type);
            ps.setString(4, description);
            ps.setString(5, eventDate);
            ps.setInt(6, points);
            ps.setInt(7, maxSupervisors);
            ps.executeUpdate();
            logger.info("DC Event logged to Postgres successfully. ID: {}", eventId);
        } catch (Exception e) {
            logger.error("Error sending DC event to Postgres", e);
        }
    }
}
