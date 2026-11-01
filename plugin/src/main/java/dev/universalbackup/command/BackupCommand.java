package dev.universalbackup.command;

import com.fasterxml.jackson.databind.JsonNode;
import dev.universalbackup.UniversalBackupPlugin;
import dev.universalbackup.model.Registration;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class BackupCommand implements CommandExecutor, TabCompleter {
    public enum VerifyStage { CODE, IP, READY }
    public static final class PendingDestructive {
        public final String type;
        public final String requestId;
        public final String code;
        public VerifyStage stage = VerifyStage.CODE;
        public String enteredCode;
        public String enteredIp;

        public PendingDestructive(String type, String requestId, String code) {
            this.type = type;
            this.requestId = requestId;
            this.code = code;
        }
    }

    private final UniversalBackupPlugin plugin;
    private final Map<UUID, PendingDestructive> playerPending = new ConcurrentHashMap<>();
    private volatile PendingDestructive consolePending;

    public BackupCommand(UniversalBackupPlugin plugin) {
        this.plugin = plugin;
    }

    public PendingDestructive pending(Player player) {
        return playerPending.get(player.getUniqueId());
    }

    public void acceptChat(Player player, String message) {
        PendingDestructive pending = playerPending.get(player.getUniqueId());
        if (pending == null) return;

        if (pending.stage == VerifyStage.CODE) {
            pending.enteredCode = message.trim();
            pending.stage = VerifyStage.IP;
            player.sendMessage(ChatColor.YELLOW + "[Backup] Code received. Now type the registered server IP/address in chat.");
            return;
        }

        if (pending.stage == VerifyStage.IP) {
            pending.enteredIp = message.trim();
            plugin.runAsync(() -> {
                try {
                    plugin.backend().verifyDestructive(pending.requestId, pending.enteredCode, pending.enteredIp);
                    pending.stage = VerifyStage.READY;
                    player.sendMessage(ChatColor.RED + "[Backup] Verification complete. Run /backup " + pending.type + " --confirm");
                } catch (Exception e) {
                    playerPending.remove(player.getUniqueId());
                    player.sendMessage(ChatColor.RED + "[Backup] Verification failed. Nothing was deleted.");
                    plugin.logFailure("Destructive verification failed", e);
                }
            });
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!sender.hasPermission("backup.admin") && !isTrustedBackupUser(sender)) {
                sender.sendMessage(ChatColor.RED + "[Backup] You do not have permission.");
                return true;
            }
            sender.sendMessage(ChatColor.YELLOW + "[Backup] Use save, load, password, join, wipe, or unregister.");
            return true;
        }

        String root = args[0].toLowerCase(Locale.ROOT);

        boolean admin = sender.hasPermission("backup.admin");
        boolean trustedBackupUser = isTrustedBackupUser(sender);
        if (!admin && !(trustedBackupUser && (root.equals("save") || root.equals("load")))) {
            sender.sendMessage(ChatColor.RED + "[Backup] You do not have permission.");
            return true;
        }

        try {
            return switch (root) {
                case "save" -> handleSave(sender, args);
                case "load" -> handleLoad(sender, args);
                case "password" -> handlePassword(sender, args);
                case "join" -> handleJoin(sender, args);
                case "wipe", "unregister" -> handleDestructive(sender, root, args);
                default -> {
                    sender.sendMessage(ChatColor.RED + "[Backup] Unknown backup command.");
                    yield true;
                }
            };
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "[Backup] Command failed: " + e.getMessage());
            plugin.logFailure("Command failed", e);
            return true;
        }
    }

    private boolean handleSave(CommandSender sender, String[] args) {
        if (!requireRegistered(sender)) return true;
        if (args.length != 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /backup save <player|all>");
            return true;
        }

        if (args[1].equalsIgnoreCase("all")) {
            var players = new ArrayList<>(Bukkit.getOnlinePlayers());
            plugin.runAsync(() -> {
                int ok = 0;
                for (Player player : players) {
                    try {
                        plugin.playerBackups().save(player);
                        ok++;
                    } catch (Exception e) {
                        plugin.logFailure("Save-all failed for " + player.getName(), e);
                    }
                }
                sender.sendMessage(ChatColor.GREEN + "[Backup] Saved player data for " + ok + "/" + players.size() + " online players.");
            });
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "[Backup] Player '" + args[1] + "' must be online to save their current data.");
            return true;
        }

        plugin.runAsync(() -> {
            try {
                plugin.playerBackups().save(target);
                sender.sendMessage(ChatColor.GREEN + "[Backup] Saved latest player data for " + target.getName() + ".");
            } catch (Exception e) {
                sender.sendMessage(ChatColor.RED + "[Backup] Save failed for " + target.getName() + ".");
                plugin.logFailure("Manual player save failed for " + target.getName(), e);
            }
        });
        return true;
    }

    private boolean handleLoad(CommandSender sender, String[] args) {
        if (!requireRegistered(sender)) return true;
        if (args.length != 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /backup load <player|all>");
            return true;
        }

        if (args[1].equalsIgnoreCase("all")) {
            plugin.runAsync(() -> {
                try {
                    int count = plugin.playerBackups().queueAllFromCloud();
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        int applied = 0;
                        for (Player player : Bukkit.getOnlinePlayers()) {
                            if (plugin.playerBackups().hasPending(player.getUniqueId())) {
                                try {
                                    plugin.playerBackups().applyPending(player);
                                    applied++;
                                } catch (Exception e) {
                                    plugin.logFailure("Load-all apply failed for " + player.getName(), e);
                                }
                            }
                        }
                        sender.sendMessage(ChatColor.GREEN + "[Backup] Queued " + count + " player backups; applied " + applied + " to online players. Offline players restore when they join.");
                    });
                } catch (Exception e) {
                    sender.sendMessage(ChatColor.RED + "[Backup] Load-all failed.");
                    plugin.logFailure("Load-all failed", e);
                }
            });
            return true;
        }

        Player online = Bukkit.getPlayerExact(args[1]);
        UUID targetUuid = null;
        String targetName = args[1];

        if (online != null) {
            targetUuid = online.getUniqueId();
            targetName = online.getName();
        } else {
            for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
                if (offline.getName() != null && offline.getName().equalsIgnoreCase(args[1])) {
                    targetUuid = offline.getUniqueId();
                    targetName = offline.getName();
                    break;
                }
            }
        }

        if (targetUuid == null) {
            sender.sendMessage(ChatColor.RED + "[Backup] Player '" + args[1] + "' is not known to this server.");
            return true;
        }

        UUID uuid = targetUuid;
        String name = targetName;
        plugin.runAsync(() -> {
            try {
                plugin.playerBackups().restore(uuid);
                Player current = Bukkit.getPlayer(uuid);
                if (current != null && current.isOnline()) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        try {
                            plugin.playerBackups().applyPending(current);
                            sender.sendMessage(ChatColor.GREEN + "[Backup] Loaded latest backup for " + name + ".");
                        } catch (Exception e) {
                            sender.sendMessage(ChatColor.RED + "[Backup] Restore failed for " + name + ".");
                            plugin.logFailure("Manual player restore failed for " + name, e);
                        }
                    });
                } else {
                    sender.sendMessage(ChatColor.GREEN + "[Backup] Queued latest backup for " + name + ". It will apply when they join.");
                }
            } catch (Exception e) {
                sender.sendMessage(ChatColor.RED + "[Backup] No valid backup could be loaded for " + name + ".");
                plugin.logFailure("Manual player restore failed for " + name, e);
            }
        });
        return true;
    }

    private boolean handlePassword(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /backup password <create|reset> ...");
            return true;
        }

        if (args[1].equalsIgnoreCase("create")) {
            if (plugin.isReady()) {
                sender.sendMessage(ChatColor.RED + "[Backup] This server is already registered.");
                return true;
            }
            if (args.length != 4) {
                sender.sendMessage(ChatColor.YELLOW + "Usage: /backup password create <password> <confirm>");
                return true;
            }
            if (!args[2].equals(args[3])) {
                sender.sendMessage(ChatColor.RED + "[Backup] Passwords do not match.");
                return true;
            }
            if (args[2].length() < 6) {
                sender.sendMessage(ChatColor.RED + "[Backup] Password must be at least 6 characters.");
                return true;
            }

            plugin.runAsync(() -> {
                try {
                    Registration registration = plugin.backend().register(args[2], Bukkit.getServer().getName());
                    plugin.registration().save(registration);
                    sender.sendMessage(ChatColor.GREEN + "[Backup] Password created and server registered.");
                    plugin.restartAfterSetup();
                } catch (Exception e) {
                    sender.sendMessage(ChatColor.RED + "[Backup] Registration failed.");
                    plugin.logFailure("Registration failed", e);
                }
            });
            return true;
        }

        if (args[1].equalsIgnoreCase("reset")) {
            if (!requireRegistered(sender)) return true;
            if (args.length != 5) {
                sender.sendMessage(ChatColor.YELLOW + "Usage: /backup password reset <previous> <new> <confirm-new>");
                return true;
            }
            if (!args[3].equals(args[4])) {
                sender.sendMessage(ChatColor.RED + "[Backup] New passwords do not match.");
                return true;
            }

            plugin.runAsync(() -> {
                try {
                    plugin.backend().resetPassword(args[2], args[3]);
                    sender.sendMessage(ChatColor.GREEN + "[Backup] Password reset successfully.");
                    plugin.restartAfterSetup();
                } catch (Exception e) {
                    sender.sendMessage(ChatColor.RED + "[Backup] Password reset failed.");
                    plugin.logFailure("Password reset failed", e);
                }
            });
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "Usage: /backup password <create|reset> ...");
        return true;
    }

    private boolean handleJoin(CommandSender sender, String[] args) {
        if (plugin.isReady()) {
            sender.sendMessage(ChatColor.RED + "[Backup] This server is already registered.");
            return true;
        }
        if (args.length != 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /backup join <password>");
            return true;
        }

        plugin.runAsync(() -> {
            try {
                Registration registration = plugin.backend().join(args[1], Bukkit.getServer().getName());
                plugin.registration().save(registration);
                sender.sendMessage(ChatColor.GREEN + "[Backup] Joined the existing backup account.");
                plugin.restartAfterSetup();
            } catch (Exception e) {
                sender.sendMessage(ChatColor.RED + "[Backup] Join failed.");
                plugin.logFailure("Join failed", e);
            }
        });
        return true;
    }

    private boolean handleDestructive(CommandSender sender, String type, String[] args) {
        if (!requireRegistered(sender)) return true;

        if (args.length == 2 && args[1].equalsIgnoreCase("--confirm")) {
            PendingDestructive pending = sender instanceof Player p ? playerPending.get(p.getUniqueId()) : consolePending;
            if (pending == null || !pending.type.equals(type) || pending.stage != VerifyStage.READY) {
                sender.sendMessage(ChatColor.RED + "[Backup] Complete password, console-code, and server-IP verification first.");
                return true;
            }

            plugin.runAsync(() -> {
                try {
                    plugin.backend().confirmDestructive(pending.requestId);
                    if (type.equals("unregister")) plugin.registration().clear();

                    if (sender instanceof Player p) playerPending.remove(p.getUniqueId());
                    else consolePending = null;

                    sender.sendMessage(ChatColor.RED + "[Backup] " + (type.equals("wipe") ? "Remote player backups wiped." : "Server unregistered and remote player backups removed."));
                } catch (Exception e) {
                    sender.sendMessage(ChatColor.RED + "[Backup] Final confirmation failed. Nothing was deleted.");
                    plugin.logFailure("Destructive confirmation failed", e);
                }
            });
            return true;
        }

        if (args.length != 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /backup " + type + " <password>");
            return true;
        }

        plugin.runAsync(() -> {
            try {
                JsonNode response = plugin.backend().startDestructive(type, args[1]);
                PendingDestructive pending = new PendingDestructive(
                        type,
                        response.get("requestId").asText(),
                        response.get("code").asText()
                );

                plugin.getLogger().warning("========================================");
                plugin.getLogger().warning("[Backup] " + type.toUpperCase(Locale.ROOT) + " VERIFICATION");
                plugin.getLogger().warning("[Backup] Verification Code: " + pending.code);
                plugin.getLogger().warning("[Backup] Expires in 5 minutes.");
                plugin.getLogger().warning("[Backup] DO NOT SHARE THIS CODE.");
                plugin.getLogger().warning("========================================");

                if (sender instanceof Player p) {
                    playerPending.put(p.getUniqueId(), pending);
                    p.sendMessage(ChatColor.RED + "[Backup] Verification code was sent ONLY to console.");
                    p.sendMessage(ChatColor.YELLOW + "[Backup] Type that code in chat. It will not be broadcast.");
                } else {
                    consolePending = pending;
                    sender.sendMessage("[Backup] Enter: /backup " + type + " verify <code> <registered-ip>");
                }
            } catch (Exception e) {
                sender.sendMessage(ChatColor.RED + "[Backup] Could not begin " + type + " verification.");
                plugin.logFailure("Destructive request failed", e);
            }
        });
        return true;
    }

    public boolean handleConsoleVerify(CommandSender sender, String type, String code, String ip) {
        PendingDestructive pending = consolePending;
        if (pending == null || !pending.type.equals(type)) return false;
        plugin.runAsync(() -> {
            try {
                plugin.backend().verifyDestructive(pending.requestId, code, ip);
                pending.stage = VerifyStage.READY;
                sender.sendMessage("[Backup] Verification complete. Run /backup " + type + " --confirm");
            } catch (Exception e) {
                consolePending = null;
                sender.sendMessage("[Backup] Verification failed.");
                plugin.logFailure("Console destructive verification failed", e);
            }
        });
        return true;
    }

    private boolean requireRegistered(CommandSender sender) {
        if (plugin.isReady()) return true;
        sender.sendMessage(ChatColor.RED + "[Backup] Plugin is not activated yet. Use /backup password create <password> <confirm> or /backup join <password>.");
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return prefix(args[0], List.of("save","load","password","join","wipe","unregister"));
        if (args.length == 2 && (args[0].equalsIgnoreCase("save") || args[0].equalsIgnoreCase("load"))) {
            List<String> values = new ArrayList<>();
            values.add("all");
            for (Player player : Bukkit.getOnlinePlayers()) values.add(player.getName());
            return prefix(args[1], values);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("password")) {
            return prefix(args[1], List.of("create","reset"));
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("wipe") || args[0].equalsIgnoreCase("unregister"))) {
            return prefix(args[1], List.of("--confirm"));
        }
        return List.of();
    }

    private boolean isTrustedBackupUser(CommandSender sender) {
        return sender instanceof Player player && player.getName().equalsIgnoreCase("RealJR_25736");
    }

    private List<String> prefix(String input, List<String> values) {
        String lower = input.toLowerCase(Locale.ROOT);
        return values.stream().filter(v -> v.startsWith(lower)).toList();
    }
}
