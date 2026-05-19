package com.highcore.bot;

import com.highcore.bot.commands.ProfileCommand;
import com.highcore.bot.commands.StatsCommand;
import com.highcore.bot.database.DatabaseManager;
import com.highcore.bot.services.DiscordSRVManager;
import com.highcore.bot.services.ServerStatsService;
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
        logger.info("Current Working Directory: " + System.getProperty("user.dir"));

        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        String token = dotenv.get("BOT_TOKEN");

        if (token == null || token.isEmpty()) {
            logger.error("BOT_TOKEN is missing! Set it in .env or as an Environment Variable.");
            System.exit(1);
        }

        // Initialize Services
        String discordSrvPath = dotenv.get("DISCORDSRV_ACCOUNTS_PATH");
        discordSRVManager = new DiscordSRVManager(discordSrvPath);

        // Initialize MySQL (Supports Railway defaults)
        dbManager = new DatabaseManager();
        try {
            dbManager.setupPool(
                dotenv.get("MYSQLHOST", dotenv.get("DB_HOST", "localhost")),
                dotenv.get("MYSQLPORT", dotenv.get("DB_PORT", "3306")),
                dotenv.get("MYSQL_DATABASE", dotenv.get("DB_NAME", "minecraft")),
                dotenv.get("MYSQLUSER", dotenv.get("DB_USER", "root")),
                dotenv.get("MYSQLPASSWORD", dotenv.get("DB_PASSWORD", ""))
            );
        } catch (Exception e) {
            logger.error("Failed to connect to MySQL database!", e);
        }

        // Initialize JDA
        try {
            jda = JDABuilder.createDefault(token)
                    .enableIntents(GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MESSAGES, GatewayIntent.GUILD_MEMBERS)
                    .setActivity(Activity.playing("HighCore MC"))
                    .build();
            
            jda.awaitReady();
            logger.info("Leon Trotsky Bot is ready and connected to Discord!");
            logger.info("Bot is currently in {} guilds.", jda.getGuilds().size());

            // Initialize real-time online players tracking from logs
            com.highcore.bot.listeners.MinecraftLogListener.initializeOnlinePlayers(jda);

            // Start Server Stats Updater Scheduler
            ServerStatsService.startScheduler(jda);

            // Register Global Slash Commands
            jda.updateCommands().addCommands(
                    net.dv8tion.jda.api.interactions.commands.build.Commands.slash("profile", "عرض ملف اللاعب في HighCore")
                            .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.USER, "user", "اللاعب المراد عرض ملفه", false),
                    net.dv8tion.jda.api.interactions.commands.build.Commands.slash("stats", "تحديث وعرض حالة سيرفر HighCore MC")
            ).queue(cmds -> logger.info("Successfully registered {} global commands", cmds.size()));
            
            jda.addEventListener(new ProfileCommand());
            jda.addEventListener(new StatsCommand());
            jda.addEventListener(new com.highcore.bot.listeners.MinecraftLogListener());
            
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Shutting down...");
                if (dbManager != null) dbManager.close();
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
