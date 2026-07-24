package com.highcore.bot.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;

public class DatabaseManager {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);
    private HikariDataSource dataSource;
    private HikariDataSource cmiDataSource;

    public void setupPool(String host, String port, String database, String user, String password) {
        HikariConfig config = new HikariConfig();
        String jdbcUrl = String.format("jdbc:mysql://%s:%s/%s?useSSL=false&allowPublicKeyRetrieval=true", host, port, database);
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(user);
        config.setPassword(password);
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.setMaximumPoolSize(10);
        config.setPoolName("BotPool");
        dataSource = new HikariDataSource(config);
        logger.info("MySQL Connection Pool initialized for database: {}", database);
    }

    public void setupCmiPool(String host, String port, String database, String user, String password) {
        if (host == null || host.isEmpty() || password == null || password.isEmpty()) {
            String envHost = System.getenv("CMI_DB_HOST");
            String envPort = System.getenv("CMI_DB_PORT");
            String envName = System.getenv("CMI_DB_NAME");
            String envUser = System.getenv("CMI_DB_USER");
            String envPass = System.getenv("CMI_DB_PASSWORD");
            
            if (envHost != null && !envHost.isEmpty()) host = envHost;
            if (envPort != null && !envPort.isEmpty()) port = envPort;
            if (envName != null && !envName.isEmpty()) database = envName;
            if (envUser != null && !envUser.isEmpty()) user = envUser;
            if (envPass != null && !envPass.isEmpty()) password = envPass;
        }

        HikariConfig config = new HikariConfig();
        String jdbcUrl = String.format("jdbc:mysql://%s:%s/%s?useSSL=false&allowPublicKeyRetrieval=true", host, port, database);
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(user);
        config.setPassword(password);
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.setMaximumPoolSize(5);
        config.setPoolName("CmiPool");
        cmiDataSource = new HikariDataSource(config);
        logger.info("CMI MySQL Connection Pool initialized for database: {}", database);
    }

    public Connection getConnection() throws SQLException {
        if (dataSource == null) throw new SQLException("Database pool not initialized!");
        return dataSource.getConnection();
    }

    public void initializeEventTables() {
        try (Connection conn = getConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            
            String createEventsTable = "CREATE TABLE IF NOT EXISTS events (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "message_id VARCHAR(64), " +
                    "channel_id VARCHAR(64), " +
                    "staff_message_id VARCHAR(64), " +
                    "staff_channel_id VARCHAR(64), " +
                    "name VARCHAR(255) NOT NULL, " +
                    "type VARCHAR(255), " +
                    "event_date VARCHAR(64), " +
                    "rewards_json TEXT, " +
                    "max_seats INT, " +
                    "conditions TEXT, " +
                    "status VARCHAR(32) DEFAULT 'OPEN', " +
                    "requires_link BOOLEAN DEFAULT FALSE, " +
                    "custom_question TEXT, " +
                    "image_url TEXT, " +
                    "winner_id VARCHAR(64), " +
                    "reminder_sent BOOLEAN DEFAULT FALSE, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")";
            stmt.executeUpdate(createEventsTable);
            
            try { stmt.executeUpdate("ALTER TABLE events ADD COLUMN rewards_json TEXT"); } catch (Exception ignored) {}
            try { stmt.executeUpdate("ALTER TABLE events ADD COLUMN winner_id VARCHAR(64)"); } catch (Exception ignored) {}
            try { stmt.executeUpdate("ALTER TABLE events ADD COLUMN supabase_id INT"); } catch (Exception ignored) {}
            try { stmt.executeUpdate("ALTER TABLE events ADD COLUMN category VARCHAR(32) DEFAULT 'MC'"); } catch (Exception ignored) {}

            String createParticipantsTable = "CREATE TABLE IF NOT EXISTS event_participants (" +
                    "event_id INT, " +
                    "user_id VARCHAR(64), " +
                    "discord_id VARCHAR(64), " +
                    "mc_name VARCHAR(255), " +
                    "mc_uuid VARCHAR(64), " +
                    "custom_answer TEXT, " +
                    "wants_reminder BOOLEAN DEFAULT FALSE, " +
                    "reward_given BOOLEAN DEFAULT FALSE, " +
                    "joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "PRIMARY KEY (event_id, user_id), " +
                    "FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE" +
                    ")";
            stmt.executeUpdate(createParticipantsTable);

            // DATABASE TABLES INITIALIZATION
            String createDailyTable = "CREATE TABLE IF NOT EXISTS daily_streaks (" +
                    "discord_id VARCHAR(64) PRIMARY KEY, " +
                    "mc_uuid VARCHAR(64), " +
                    "mc_name VARCHAR(255), " +
                    "streak INT DEFAULT 0, " +
                    "last_claim_time TIMESTAMP NULL, " +
                    "warning_sent BOOLEAN DEFAULT FALSE, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")";
            stmt.executeUpdate(createDailyTable);
            try { stmt.executeUpdate("ALTER TABLE daily_streaks ADD COLUMN warning_sent BOOLEAN DEFAULT FALSE"); } catch (Exception ignored) {}

            // DATABASE TABLES INITIALIZATION
            String createTeamsTable = "CREATE TABLE IF NOT EXISTS teams (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "name VARCHAR(255) NOT NULL, " +
                    "color VARCHAR(20) NOT NULL, " +
                    "role_id VARCHAR(64), " +
                    "category_id VARCHAR(64), " +
                    "voice_channel_id VARCHAR(64), " +
                    "text_channel_id VARCHAR(64), " +
                    "leader_id VARCHAR(64), " +
                    "member2_id VARCHAR(64), " +
                    "member3_id VARCHAR(64), " +
                    "member4_id VARCHAR(64), " +
                    "tag VARCHAR(64) DEFAULT 'New Born', " +
                    "announcement_message_id VARCHAR(64), " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")";
            stmt.executeUpdate(createTeamsTable);

            try { stmt.executeUpdate("ALTER TABLE teams ADD COLUMN cmd_channel_id VARCHAR(64)"); } catch (Exception ignored) {}
            try { stmt.executeUpdate("ALTER TABLE teams ADD COLUMN co_leader_id VARCHAR(64)"); } catch (Exception ignored) {}
            try { stmt.executeUpdate("ALTER TABLE teams ADD COLUMN logo_url VARCHAR(255)"); } catch (Exception ignored) {}
            try { stmt.executeUpdate("ALTER TABLE teams ADD COLUMN log_channel_id VARCHAR(64)"); } catch (Exception ignored) {}

            // AI THREADS
            String createAiThreadsTable = "CREATE TABLE IF NOT EXISTS ai_threads (" +
                    "thread_id VARCHAR(64) PRIMARY KEY, " +
                    "thread_name VARCHAR(255) NOT NULL, " +
                    "original_question TEXT NOT NULL, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")";
            stmt.executeUpdate(createAiThreadsTable);

            logger.info("Event tables initialized successfully.");
        } catch (SQLException e) {
            logger.error("Failed to initialize event tables!", e);
        }
    }

    public Connection getCmiConnection() throws SQLException {
        if (cmiDataSource == null) throw new SQLException("CMI database pool not initialized!");
        return cmiDataSource.getConnection();
    }

    public boolean isCmiPoolReady() {
        return cmiDataSource != null && !cmiDataSource.isClosed();
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
        if (cmiDataSource != null && !cmiDataSource.isClosed()) {
            cmiDataSource.close();
        }
    }
}