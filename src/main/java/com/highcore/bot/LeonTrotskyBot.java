package com.highcore.bot;

import com.highcore.bot.commands.PanelCommand;
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
            dbManager.initializeEventTables();
        } catch (Exception e) {
            logger.error("Failed to connect to MySQL database!", e);
        }

        String cmiHost = dotenv.get("CMI_DB_HOST", "");
        if (!cmiHost.isEmpty()) {
            try {
                dbManager.setupCmiPool(
                    cmiHost,
                    dotenv.get("CMI_DB_PORT", "3306"),
                    dotenv.get("CMI_DB_NAME", "minecraft"),
                    dotenv.get("CMI_DB_USER", "root"),
                    dotenv.get("CMI_DB_PASS", "")
                );
            } catch (Exception e) {
                logger.error("Failed to connect to CMI MySQL database!", e);
            }
        } else {
            logger.warn("CMI_DB_HOST not set. Profile command will not work.");
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

            // Start Event Reminder Scheduler
            com.highcore.bot.services.EventReminderService.startScheduler(jda);

            // Register Global Slash Commands
            var globalCommands = java.util.List.of(
                    net.dv8tion.jda.api.interactions.commands.build.Commands.slash("profile", "عرض الملف الشخصي والإحصائيات الخاصة باللاعب")
                            .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.USER, "user", "تحديد اللاعب المراد عرض ملفه الشخصي", false),
                    net.dv8tion.jda.api.interactions.commands.build.Commands.slash("stats", "عرض حالة الخادم والإحصائيات المباشرة"),
                    net.dv8tion.jda.api.interactions.commands.build.Commands.slash("panel", "التحكم الكامل بالخادم وإدارة النظام"),
                    net.dv8tion.jda.api.interactions.commands.build.Commands.slash("ec", "إنهاء حالة الصيانة أو التوقف الحالية"),
                    net.dv8tion.jda.api.interactions.commands.build.Commands.slash("event", "إدارة الفعاليات")
                        .addSubcommands(
                            new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("create", "إنشاء فعالية جديدة")
                                .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "name", "اسم الفعالية", true)
                                .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "type", "نوع الفعالية", true)
                                .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "date", "اليوم والوقت (مثال: 2026-06-20 21:00)", true)
                                .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "rewards", "المكافآت", true)
                                .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.INTEGER, "seats", "عدد المقاعد", true)
                                .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "conditions", "الشروط", true)
                                .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.BOOLEAN, "requires_link", "هل تتطلب الفعالية ربط حساب ماينكرافت؟", true)
                                .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.ATTACHMENT, "image", "صورة كفر الفعالية", false)
                                .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "custom_question", "سؤال إضافي يطرح عند التسجيل", false)
                        )
            );

            jda.updateCommands().addCommands(globalCommands).queue(cmds -> logger.info("Successfully registered {} global commands", cmds.size()));

            for (net.dv8tion.jda.api.entities.Guild guild : jda.getGuilds()) {
                guild.updateCommands().queue();
            }
            
            jda.addEventListener(new ProfileCommand());
            jda.addEventListener(new StatsCommand());
            jda.addEventListener(new PanelCommand(jda));
            jda.addEventListener(new com.highcore.bot.commands.EventCommand());
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
