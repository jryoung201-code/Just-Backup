package dev.universalbackup.security;

import dev.universalbackup.model.Registration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

public final class RegistrationStore {
    private final File file;
    private Registration registration;

    public RegistrationStore(JavaPlugin plugin) {
        this.file = new File(plugin.getDataFolder(), ".registration.yml");
        load();
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        String token = y.getString("token");
        if (token == null || token.isBlank()) return;
        registration = new Registration(
                y.getString("server-id", ""),
                y.getString("account-id", ""),
                token,
                y.getString("registered-ip", "")
        );
    }

    public boolean isRegistered() { return registration != null; }
    public Registration get() { return registration; }

    public void save(Registration r) throws IOException {
        file.getParentFile().mkdirs();
        YamlConfiguration y = new YamlConfiguration();
        y.set("server-id", r.serverId());
        y.set("account-id", r.accountId());
        y.set("token", r.token());
        y.set("registered-ip", r.registeredIp());
        y.save(file);
        registration = r;
    }

    public void clear() {
        registration = null;
        if (file.exists()) file.delete();
    }
}
