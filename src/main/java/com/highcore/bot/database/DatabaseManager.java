package com.highcore.bot.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public class DatabaseManager {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);
    private final Map<String, HikariDataSource> dataSources = new HashMap<>();

    /**
     * Register a new SQLite database connection pool
     */
    public void registerSqliteDb(String id, String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            logger.warn("File path for SQLite DB '{}' is empty, skipping.", id);
            return;
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + filePath);
        config.setDriverClassName("org.sqlite.JDBC");
        
        // SQLite specific pooling settings
        config.setMaximumPoolSize(1); // SQLite handles concurrent writes poorly, keeping pool size small
        config.setPoolName("SQLitePool-" + id);
        
        dataSources.put(id, new HikariDataSource(config));
        logger.info("Registered SQLite database: {}", id);
    }

    public Connection getConnection(String id) throws SQLException {
        HikariDataSource ds = dataSources.get(id);
        if (ds == null) throw new SQLException("No database registered with id: " + id);
        return ds.getConnection();
    }

    public void closeAll() {
        for (HikariDataSource ds : dataSources.values()) {
            if (ds != null && !ds.isClosed()) {
                ds.close();
            }
        }
        logger.info("All database connection pools closed.");
    }
}
