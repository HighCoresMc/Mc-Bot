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