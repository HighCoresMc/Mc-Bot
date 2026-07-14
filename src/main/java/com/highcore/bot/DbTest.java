package com.highcore.bot;

import com.highcore.bot.database.DatabaseManager;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

public class DbTest {
    public static void main(String[] args) throws Exception {
        DatabaseManager dbManager = new DatabaseManager();
        // Since we can't easily load .env, we can just connect locally to root
        dbManager.setupPool("localhost", "3306", "coreclaims", "root", "");
        
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT * FROM claims");
            int count = 0;
            while(rs.next()) {
                System.out.println("Claim: " + rs.getString("team_name") + " Chunk: " + rs.getInt("chunk_x") + "," + rs.getInt("chunk_z"));
                count++;
            }
            System.out.println("Total claims: " + count);
            
            rs = stmt.executeQuery("SELECT * FROM generators");
            count = 0;
            while(rs.next()) {
                System.out.println("Gen: " + rs.getString("team_name") + " ID: " + rs.getInt("ID") + " Active: " + rs.getBoolean("is_active"));
                count++;
            }
            System.out.println("Total generators: " + count);
        }
        System.exit(0);
    }
}

