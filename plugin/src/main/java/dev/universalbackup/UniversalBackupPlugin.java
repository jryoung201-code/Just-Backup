package dev.universalbackup;

import dev.universalbackup.api.BackendClient;
import dev.universalbackup.backup.PlayerBackupService;
import dev.universalbackup.command.BackupCommand;
import dev.universalbackup.listener.BackupListener;
import dev.universalbackup.security.RegistrationStore;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;

public final class UniversalBackupPlugin extends JavaPlugin {
    private RegistrationStore registration;
    private BackendClient backend;
    private PlayerBackupService playerBackups;

    @Override
    public void onLoad() {
        saveDefaultConfig();
        registration = new RegistrationStore(this);
        backend = new BackendClient(registration);
    }

    @Override
    public void onEnable() {
        playerBackups = new PlayerBackupService(this, backend);

        BackupCommand command = new BackupCommand(this);
        getCommand("backup").setExecutor(command);
        getCommand("backup").setTabCompleter(command);
        getServer().getPluginManager().registerEvents(new BackupListener(this, command), this);

        if (!registration.isRegistered()) announceNotActivated();
        else getLogger().info("Just-Backup is active. Backup mode: player data only.");

        scheduleIntervals();
    }

    @Override
    public void onDisable() {
        if (!isReady() || !shouldSave("SERVER_SHUTDOWN")) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                playerBackups.save(player);
            } catch (Exception e) {
                logFailure("Shutdown backup failed for " + player.getName(), e);
            }
        }
    }

    private void scheduleIntervals() {
        for (String trigger : getConfig().getStringList("Backup.Save-When")) {
            String value = trigger.toUpperCase(Locale.ROOT);
            if (!value.startsWith("EVERY_") || !value.endsWith("_MINUTES")) continue;

            try {
                long minutes = Long.parseLong(value.substring(6, value.length() - "_MINUTES".length()));
                if (minutes <= 0) continue;
                long ticks = minutes * 60L * 20L;

                Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                    if (!isReady()) return;
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        try {
                            playerBackups.save(player);
                        } catch (Exception e) {
                            logFailure("Scheduled backup failed for " + player.getName(), e);
                        }
                    }
                }, ticks, ticks);
            } catch (NumberFormatException ignored) {
                getLogger().warning("Ignoring invalid Save-When interval: " + trigger);
            }
        }
    }

    public boolean isReady() {
        return registration != null && registration.isRegistered();
    }

    public RegistrationStore registration() {
        return registration;
    }

    public BackendClient backend() {
        return backend;
    }

    public PlayerBackupService playerBackups() {
        return playerBackups;
    }

    public void runAsync(Runnable task) {
        Bukkit.getScheduler().runTaskAsynchronously(this, task);
    }

    public boolean shouldSave(String trigger) {
        return containsIgnoreCase(getConfig().getStringList("Backup.Save-When"), trigger);
    }

    public boolean shouldLoad(String trigger) {
        return containsIgnoreCase(getConfig().getStringList("Backup.Load-When"), trigger);
    }

    private boolean containsIgnoreCase(List<String> list, String value) {
        return list.stream().anyMatch(item -> item.equalsIgnoreCase(value));
    }

    public void notifyAdminIfUnregistered(Player player) {
        if (!isReady() && (player.isOp() || player.hasPermission("backup.admin"))) {
            player.sendMessage(ChatColor.RED + "[Backup] Not activated. Use /backup password create <password> <confirm>.");
        }
    }

    private void announceNotActivated() {
        getLogger().warning("========================================");
        getLogger().warning("JUST-BACKUP IS NOT ACTIVATED");
        getLogger().warning("Use: /backup password create <password> <confirm>");
        getLogger().warning("Player backups are disabled until setup is complete.");
        getLogger().warning("========================================");
    }

    public void restartAfterSetup() {
        getLogger().warning("[Backup] Setup changed. Restarting server...");
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isOp() || player.hasPermission("backup.admin")) {
                player.sendMessage(ChatColor.YELLOW + "[Backup] Setup changed. Server restarting...");
            }
        }
        Bukkit.getScheduler().runTaskLater(this, () -> Bukkit.spigot().restart(), 40L);
    }

    public void logFailure(String message, Throwable error) {
        getLogger().severe("[Backup] " + message + ": " + error.getMessage());
    }
}
