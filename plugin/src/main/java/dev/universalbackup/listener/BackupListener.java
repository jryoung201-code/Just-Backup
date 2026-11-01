package dev.universalbackup.listener;

import dev.universalbackup.UniversalBackupPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class BackupListener implements Listener {
    private final UniversalBackupPlugin plugin;
    public BackupListener(UniversalBackupPlugin plugin) { this.plugin = plugin; }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (!plugin.isReady()) return;
        if (!plugin.shouldLoad("PLAYER_JOIN") && !plugin.shouldLoad("SERVER_RESTART")) return;
        try { plugin.playerBackups().restore(e.getUniqueId()); }
        catch (Exception ex) { plugin.logFailure("Automatic pre-login restore failed", ex); }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        plugin.notifyAdminIfUnregistered(e.getPlayer());
        if (!plugin.isReady()) return;
        try { plugin.playerBackups().applyPending(e.getPlayer()); }
        catch (Exception ex) { plugin.logFailure("Applying restored player data failed", ex); }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (!plugin.isReady() || !plugin.shouldSave("PLAYER_QUIT")) return;
        plugin.runAsync(() -> {
            try { plugin.playerBackups().save(e.getPlayer()); }
            catch (Exception ex) { plugin.logFailure("Automatic player save failed", ex); }
        });
    }
}
