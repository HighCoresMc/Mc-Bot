// PACKAGE
package com.highcore.bot.services;

// IMPORTS
import com.highcore.bot.LeonTrotskyBot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

// CLASS DECLARATION
public class RewardService {
    // CONSTANTS
    private static final Logger logger = LoggerFactory.getLogger(RewardService.class);
    private static final BlockingQueue<RewardTask> queue = new LinkedBlockingQueue<>();
    private static final PterodactylService pteroService = new PterodactylService();

    // INITIALIZATION
    static {
        Thread worker = new Thread(() -> {
            while (true) {
                try {
                    RewardTask task = queue.take();
                    boolean success = pteroService.sendConsoleCommand(task.command);
                    if (success) {
                        updateHistoryStatus(task.historyId, "SOLVED");
                    } else {
                        updateHistoryStatus(task.historyId, "REWARD_FAILED");
                        notifyAdmins(task);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.error("Error processing reward task", e);
                }
            }
        }, "RewardQueueWorker");
        worker.setDaemon(true);
        worker.start();
    }

    // UTILITY METHODS
    public static void queueReward(int historyId, String command, String winnerDiscordId, String winnerMcName, String prizeDisplay) {
        queue.add(new RewardTask(historyId, command, winnerDiscordId, winnerMcName, prizeDisplay));
    }

    private static void updateHistoryStatus(int historyId, String status) {
        try (Connection conn = LeonTrotskyBot.getDbManager().getConnection()) {
            String query = "UPDATE drop_history SET status = ?, completed_at = CURRENT_TIMESTAMP WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setString(1, status);
                ps.setInt(2, historyId);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            logger.error("Failed to update drop history status for reward", e);
        }
    }

    private static void notifyAdmins(RewardTask task) {
        logger.error("REWARD FAILED: Command '{}' failed to execute on server for player {} (Discord: {}). Crate prize: {}.",
                task.command, task.winnerMcName, task.winnerDiscordId, task.prizeDisplay);
    }

    // HELPER CLASSES
    public static class RewardTask {
        public final int historyId;
        public final String command;
        public final String winnerDiscordId;
        public final String winnerMcName;
        public final String prizeDisplay;

        public RewardTask(int historyId, String command, String winnerDiscordId, String winnerMcName, String prizeDisplay) {
            this.historyId = historyId;
            this.command = command;
            this.winnerDiscordId = winnerDiscordId;
            this.winnerMcName = winnerMcName;
            this.prizeDisplay = prizeDisplay;
        }
    }
}
