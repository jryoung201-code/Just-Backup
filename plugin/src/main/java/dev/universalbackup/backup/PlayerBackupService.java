package dev.universalbackup.backup;

import com.fasterxml.jackson.databind.JsonNode;
import dev.universalbackup.UniversalBackupPlugin;
import dev.universalbackup.api.BackendClient;
import dev.universalbackup.model.PlayerSnapshot;
import dev.universalbackup.util.CryptoUtil;
import dev.universalbackup.util.GzipUtil;
import org.bukkit.*;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerBackupService {
    private final UniversalBackupPlugin plugin;
    private final BackendClient backend;
    private final Map<UUID, PlayerSnapshot> pending = new ConcurrentHashMap<>();

    public PlayerBackupService(UniversalBackupPlugin plugin, BackendClient backend) {
        this.plugin = plugin;
        this.backend = backend;
    }

    private boolean enabled(String key) {
        return plugin.getConfig().getBoolean("Backup.Player-Data." + key, true);
    }

    public void save(Player player) throws Exception {
        String yaml;
        if (Bukkit.isPrimaryThread()) yaml = serialize(player);
        else yaml = Bukkit.getScheduler().callSyncMethod(plugin, () -> serialize(player)).get();

        byte[] compressed = GzipUtil.compress(yaml);
        backend.save(new PlayerSnapshot(
                player.getUniqueId(),
                player.getName(),
                System.currentTimeMillis(),
                compressed,
                CryptoUtil.sha256(compressed)
        ));
    }

    public void restore(UUID uuid) throws Exception {
        PlayerSnapshot snapshot = backend.latest(uuid);
        if (!CryptoUtil.sha256(snapshot.compressedYaml()).equalsIgnoreCase(snapshot.checksum())) {
            throw new IllegalStateException("Backup checksum verification failed for " + uuid);
        }
        pending.put(uuid, snapshot);
    }

    public int queueAllFromCloud() throws Exception {
        JsonNode root = backend.listPlayers();
        int count = 0;
        for (JsonNode n : root.path("players")) {
            UUID uuid = UUID.fromString(n.get("uuid").asText());
            try {
                restore(uuid);
                count++;
            } catch (Exception ex) {
                plugin.logFailure("Could not queue player restore for " + uuid, ex);
            }
        }
        return count;
    }

    public boolean hasPending(UUID uuid) {
        return pending.containsKey(uuid);
    }

    public void applyPending(Player player) throws Exception {
        PlayerSnapshot snapshot = pending.remove(player.getUniqueId());
        if (snapshot == null) return;
        String yaml = GzipUtil.decompress(snapshot.compressedYaml());
        YamlConfiguration data = new YamlConfiguration();
        data.loadFromString(yaml);

        clearConfiguredData(player);
        apply(player, data);
        player.saveData();
    }

    private String serialize(Player p) {
        YamlConfiguration y = new YamlConfiguration();

        if (enabled("Gamemode")) y.set("gamemode", p.getGameMode().name());

        if (enabled("Location") || enabled("World")) {
            Location l = p.getLocation();
            y.set("location.world", l.getWorld() == null ? null : l.getWorld().getName());
            y.set("location.x", l.getX());
            y.set("location.y", l.getY());
            y.set("location.z", l.getZ());
            y.set("location.yaw", l.getYaw());
            y.set("location.pitch", l.getPitch());
        }

        if (enabled("Inventory")) y.set("inventory", p.getInventory().getStorageContents());
        if (enabled("Armor")) y.set("armor", p.getInventory().getArmorContents());
        if (enabled("Offhand")) y.set("offhand", p.getInventory().getItemInOffHand());
        if (enabled("EnderChest")) y.set("enderchest", p.getEnderChest().getContents());

        if (enabled("Health")) {
            y.set("health", p.getHealth());
            y.set("max-health", p.getMaxHealth());
        }
        if (enabled("Hunger")) y.set("food", p.getFoodLevel());
        if (enabled("Saturation")) y.set("saturation", p.getSaturation());

        if (enabled("Experience")) {
            y.set("xp.level", p.getLevel());
            y.set("xp.progress", p.getExp());
            y.set("xp.total", p.getTotalExperience());
        }

        if (enabled("Potion-Effects")) y.set("effects", new ArrayList<>(p.getActivePotionEffects()));

        if (enabled("Spawnpoint")) {
            Location spawn = p.getRespawnLocation();
            if (spawn != null && spawn.getWorld() != null) {
                y.set("spawn.world", spawn.getWorld().getName());
                y.set("spawn.x", spawn.getX());
                y.set("spawn.y", spawn.getY());
                y.set("spawn.z", spawn.getZ());
                y.set("spawn.yaw", spawn.getYaw());
                y.set("spawn.pitch", spawn.getPitch());
            }
        }

        if (enabled("Recipes")) {
            List<String> recipes = new ArrayList<>();
            for (NamespacedKey key : p.getDiscoveredRecipes()) recipes.add(key.toString());
            y.set("recipes", recipes);
        }

        if (enabled("Advancements")) {
            Map<String,List<String>> advancements = new LinkedHashMap<>();
            Iterator<Advancement> it = Bukkit.advancementIterator();
            while (it.hasNext()) {
                Advancement a = it.next();
                AdvancementProgress progress = p.getAdvancementProgress(a);
                if (!progress.getAwardedCriteria().isEmpty()) {
                    advancements.put(a.getKey().toString(), new ArrayList<>(progress.getAwardedCriteria()));
                }
            }
            y.set("advancements", advancements);
        }

        if (enabled("Statistics")) {
            Map<String,Integer> stats = new LinkedHashMap<>();
            for (Statistic stat : Statistic.values()) {
                if (stat.getType() == Statistic.Type.UNTYPED) {
                    try { stats.put(stat.name(), p.getStatistic(stat)); }
                    catch (IllegalArgumentException ignored) {}
                }
            }
            y.set("statistics", stats);
        }

        return y.saveToString();
    }

    private void clearConfiguredData(Player p) {
        if (enabled("Inventory")) p.getInventory().clear();
        if (enabled("Armor")) p.getInventory().setArmorContents(new ItemStack[4]);
        if (enabled("Offhand")) p.getInventory().setItemInOffHand(null);
        if (enabled("EnderChest")) p.getEnderChest().clear();
        if (enabled("Potion-Effects")) {
            for (PotionEffect effect : new ArrayList<>(p.getActivePotionEffects())) {
                p.removePotionEffect(effect.getType());
            }
        }
        if (enabled("Recipes")) p.undiscoverRecipes(new HashSet<>(p.getDiscoveredRecipes()));
        if (enabled("Advancements")) {
            Iterator<Advancement> it = Bukkit.advancementIterator();
            while (it.hasNext()) {
                AdvancementProgress progress = p.getAdvancementProgress(it.next());
                for (String criterion : new ArrayList<>(progress.getAwardedCriteria())) {
                    progress.revokeCriteria(criterion);
                }
            }
        }
    }

    private void apply(Player p, YamlConfiguration y) {
        if (enabled("Gamemode")) {
            String gm = y.getString("gamemode");
            if (gm != null) p.setGameMode(GameMode.valueOf(gm));
        }

        if ((enabled("Location") || enabled("World")) && y.contains("location.world")) {
            World world = Bukkit.getWorld(Objects.requireNonNull(y.getString("location.world")));
            if (world != null) {
                p.teleport(new Location(
                        world,
                        y.getDouble("location.x"),
                        y.getDouble("location.y"),
                        y.getDouble("location.z"),
                        (float)y.getDouble("location.yaw"),
                        (float)y.getDouble("location.pitch")
                ));
            }
        }

        if (enabled("Inventory")) p.getInventory().setStorageContents(items(y.getList("inventory"), p.getInventory().getStorageContents().length));
        if (enabled("Armor")) p.getInventory().setArmorContents(items(y.getList("armor"), 4));
        if (enabled("Offhand")) p.getInventory().setItemInOffHand(y.getItemStack("offhand"));
        if (enabled("EnderChest")) p.getEnderChest().setContents(items(y.getList("enderchest"), p.getEnderChest().getSize()));

        if (enabled("Health")) {
            double max = Math.max(1.0, y.getDouble("max-health", p.getMaxHealth()));
            p.setHealth(Math.min(max, Math.max(0.1, y.getDouble("health", p.getHealth()))));
        }
        if (enabled("Hunger")) p.setFoodLevel(Math.max(0, Math.min(20, y.getInt("food", p.getFoodLevel()))));
        if (enabled("Saturation")) p.setSaturation((float)y.getDouble("saturation", p.getSaturation()));

        if (enabled("Experience")) {
            p.setLevel(y.getInt("xp.level", 0));
            p.setExp((float)y.getDouble("xp.progress", 0));
            p.setTotalExperience(y.getInt("xp.total", 0));
        }

        if (enabled("Potion-Effects")) {
            List<?> effects = y.getList("effects", List.of());
            for (Object obj : effects) if (obj instanceof PotionEffect effect) p.addPotionEffect(effect);
        }

        if (enabled("Spawnpoint") && y.contains("spawn.world")) {
            World world = Bukkit.getWorld(Objects.requireNonNull(y.getString("spawn.world")));
            if (world != null) {
                Location spawn = new Location(
                        world,
                        y.getDouble("spawn.x"),
                        y.getDouble("spawn.y"),
                        y.getDouble("spawn.z"),
                        (float)y.getDouble("spawn.yaw"),
                        (float)y.getDouble("spawn.pitch")
                );
                p.setRespawnLocation(spawn, true);
            }
        }

        if (enabled("Recipes")) {
            List<String> raw = y.getStringList("recipes");
            Set<NamespacedKey> keys = new HashSet<>();
            for (String s : raw) {
                NamespacedKey key = NamespacedKey.fromString(s);
                if (key != null) keys.add(key);
            }
            p.discoverRecipes(keys);
        }

        if (enabled("Advancements")) {
            var section = y.getConfigurationSection("advancements");
            if (section != null) {
                for (String rawKey : section.getKeys(false)) {
                    NamespacedKey key = NamespacedKey.fromString(rawKey);
                    if (key == null) continue;
                    Advancement advancement = Bukkit.getAdvancement(key);
                    if (advancement == null) continue;
                    AdvancementProgress progress = p.getAdvancementProgress(advancement);
                    for (String criterion : y.getStringList("advancements." + rawKey)) {
                        progress.awardCriteria(criterion);
                    }
                }
            }
        }

        if (enabled("Statistics")) {
            var section = y.getConfigurationSection("statistics");
            if (section != null) {
                for (String statName : section.getKeys(false)) {
                    try {
                        Statistic stat = Statistic.valueOf(statName);
                        if (stat.getType() == Statistic.Type.UNTYPED) p.setStatistic(stat, section.getInt(statName));
                    } catch (IllegalArgumentException ignored) {}
                }
            }
        }

        p.updateInventory();
    }

    private ItemStack[] items(List<?> list, int size) {
        ItemStack[] out = new ItemStack[size];
        if (list == null) return out;
        for (int i = 0; i < Math.min(size, list.size()); i++) {
            Object obj = list.get(i);
            if (obj instanceof ItemStack stack) out[i] = stack;
        }
        return out;
    }
}
