package com.highcore.bot;

import com.highcore.bot.commands.ProfileCommand;
import com.highcore.bot.database.DatabaseManager;
import com.highcore.bot.services.DiscordSRVManager;
import io.github.cdimascio.dotenv.Dotenv;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LeonTrotskyBot {
    private static final Logger logger = LoggerFactory.getLogger(LeonTrotskyBot.class);
    private static DatabaseManager dbManager;
    private static DiscordSRVManager discordSRVManager;
    private static JDA jda;

    public static void main(String[] args) {
        logger.info("Starting Leon Trotsky Bot for HighCore MC...");

        // Load environment variables
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        String token = dotenv.get("BOT_TOKEN");

        if (token == null || token.isEmpty() || token.equals("your_bot_token_here")) {
            logger.error("BOT_TOKEN is missing or not configured in .env file.");
            System.exit(1);
        }

        // Initialize Services
        String discordSrvPath = dotenv.get("DISCORDSRV_ACCOUNTS_PATH");
        discordSRVManager = new DiscordSRVManager(discordSrvPath);

        // Initialize Database Connections (SQLite)
        dbManager = new DatabaseManager();
        try {
            dbManager.registerSqliteDb("CMI", dotenv.get("CMI_DB_PATH"));
            dbManager.registerSqliteDb("PlayerPoints", dotenv.get("PLAYERPOINTS_DB_PATH"));
            logger.info("Connected to SQLite databases.");
        } catch (Exception e) {
            logger.error("Failed to connect to databases. Check your .env config.", e);
            System.exit(1);
        }

        // Initialize JDA
        try {
            jda = JDABuilder.createDefault(token)
                    .enableIntents(GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MESSAGES, GatewayIntent.GUILD_MEMBERS)
                    .setActivity(Activity.playing("HighCore MC"))
                    .build();
            
            jda.awaitReady();
            logger.info("Leon Trotsky Bot is ready and connected to Discord!");

            // Register Commands
            jda.addEventListener(new ProfileCommand());
            jda.updateCommands().addCommands(
                    net.dv8tion.jda.api.interactions.commands.build.Commands.slash("profile", "عرض ملف اللاعب")
                            .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.USER, "user", "اللاعب المراد عرض ملفه", false)
            ).queue();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Shutting down...");
                if (dbManager != null) dbManager.closeAll();
                if (jda != null) jda.shutdown();
            }));
            
        } catch (Exception e) {
            logger.error("Failed to start JDA", e);
            System.exit(1);
        }
    }

    public static DatabaseManager getDbManager() {
        return dbManager;
    }
    
    public static DiscordSRVManager getDiscordSRVManager() {
        return discordSRVManager;
    }

    public static JDA getJda() {
        return jda;
    }
}
