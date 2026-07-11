package com.highcore.bot;

import com.highcore.bot.commands.PanelCommand;
import com.highcore.bot.commands.ProfileCommand;
import com.highcore.bot.commands.StatsCommand;
import com.highcore.bot.database.DatabaseManager;
import com.highcore.bot.database.SupabaseManager;
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
    private static SupabaseManager supabaseManager;
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

        // Supabase
        String supabaseUrl = dotenv.get("SUPABASE_URL", "");
        String supabaseKey = dotenv.get("SUPABASE_KEY", "");
        if (!supabaseUrl.isEmpty() && !supabaseKey.isEmpty()) {
            supabaseManager = new SupabaseManager(supabaseUrl, supabaseKey);
            logger.info("Supabase connection initialized.");
        } else {
            logger.warn("SUPABASE_URL or SUPABASE_KEY not set. Event logging to Supabase disabled.");
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

            // Start Daily Streak Reminder Scheduler
            com.highcore.bot.services.DailyReminderService.startScheduler(jda);

            com.highcore.bot.commands.CrateDropCommand.startScheduler(jda);

            // Start Team Tag Scheduler
            com.highcore.bot.commands.TeamCommand.startTagScheduler();

            // Start Supabase Sync Scheduler
            com.highcore.bot.services.SupabaseSyncService.startScheduler(jda);

            // Register Global Slash Commands
            var globalCommands = java.util.List.of(
                    net.dv8tion.jda.api.interactions.commands.build.Commands.slash("profile", "عرض الملف الشخصي والإحصائيات الخاصة باللاعب")
                            .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.USER, "user", "تحديد اللاعب المراد عرض ملفه الشخصي", false),
                    net.dv8tion.jda.api.interactions.commands.build.Commands.slash("stats", "عرض حالة الخادم والإحصائيات المباشرة"),
                    net.dv8tion.jda.api.interactions.commands.build.Commands.slash("panel", "التحكم الكامل بالخادم وإدارة النظام"),
                    net.dv8tion.jda.api.interactions.commands.build.Commands.slash("ec", "إنهاء حالة الصيانة أو التوقف الحالية"),
                    net.dv8tion.jda.api.interactions.commands.build.Commands.slash("event", "لوحة تحكم الفعاليات"),
                    net.dv8tion.jda.api.interactions.commands.build.Commands.slash("daily", "استلام المكافأة اليومية الخاصة بك"),
                    net.dv8tion.jda.api.interactions.commands.build.Commands.slash("drop", "لوحة تحكم نظام الدروبات العشوائية"),
                    net.dv8tion.jda.api.interactions.commands.build.Commands.slash("team", "نظام إدارة الفرق")
                            .addSubcommands(
                                new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("view", "لوحة تحكم الفرق"),
                                new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("panel", "بنل قيادة التيم الخاص بك"),
                                new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("create", "إنشاء فريق جديد")
                                    .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "name",    "اسم الفريق", true)
                                    .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "color",   "كود اللون (مثال: #FF5733)", true)
                                    .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.USER,   "leader",  "قائد الفريق", true)
                                    .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.USER,   "member2", "العضو الثاني", true)
                                    .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.USER,   "member3", "العضو الثالث (اختياري)", false)
                                    .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.USER,   "member4", "العضو الرابع (اختياري)", false),
                                new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("edit", "تعديل فريق")
                                    .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "team", "اسم الفريق (اختياري)", false),
                                new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("top", "قائمة المتصدرين للأتيام")
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
            jda.addEventListener(new com.highcore.bot.commands.DailyCommand());
            jda.addEventListener(new com.highcore.bot.commands.CrateDropCommand());
            jda.addEventListener(new com.highcore.bot.commands.TeamCommand());

            // REGISTER AI ASSISTANT
            com.highcore.bot.services.PterodactylService pteroServiceForAI = new com.highcore.bot.services.PterodactylService();
            com.highcore.bot.services.AIAssistantService aiService = new com.highcore.bot.services.AIAssistantService(pteroServiceForAI);
            jda.addEventListener(new com.highcore.bot.listeners.AIAssistantListener(aiService));

            
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

    public static SupabaseManager getSupabaseManager() {
        return supabaseManager;
    }
    
    public static DiscordSRVManager getDiscordSRVManager() {
        return discordSRVManager;
    }

    public static JDA getJda() {
        return jda;
    }
}
