package dev.universalbackup.listener;

import dev.universalbackup.UniversalBackupPlugin;
import dev.universalbackup.command.BackupCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class BackupListener implements Listener {
    private final UniversalBackupPlugin plugin;
    private final BackupCommand command;

    public BackupListener(UniversalBackupPlugin plugin, BackupCommand command) {
        this.plugin = plugin;
        this.command = command;
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (!plugin.isReady()) return;
        if (!plugin.shouldLoad("PLAYER_JOIN") && !plugin.shouldLoad("SERVER_RESTART")) return;
        try {
            plugin.playerBackups().restore(e.getUniqueId());
        } catch (Exception ex) {
            // A missing cloud backup should never block a player from joining.
            plugin.getLogger().fine("No automatic backup restored for " + e.getUniqueId() + ": " + ex.getMessage());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        plugin.notifyAdminIfUnregistered(e.getPlayer());
        if (!plugin.isReady()) return;
        try {
            plugin.playerBackups().applyPending(e.getPlayer());
        } catch (Exception ex) {
            plugin.logFailure("Applying restored player data failed", ex);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (!plugin.isReady() || !plugin.shouldSave("PLAYER_QUIT")) return;
        plugin.runAsync(() -> {
            try {
                plugin.playerBackups().save(e.getPlayer());
            } catch (Exception ex) {
                plugin.logFailure("Automatic player save failed", ex);
            }
        });
    }

    @SuppressWarnings("deprecation")
    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        if (command.pending(e.getPlayer()) == null) return;
        e.setCancelled(true);
        command.acceptChat(e.getPlayer(), e.getMessage());
    }
}
