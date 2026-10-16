package dev.autorestart;

import dev.autorestart.command.AutoRestartCommand;
import dev.autorestart.hook.AutoRestartExpansion;
import dev.autorestart.hook.DiscordWebhook;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class AutoRestartPlugin extends JavaPlugin {

    private Settings settings;
    private Messenger messenger;
    private DiscordWebhook discord;
    private RestartManager restartManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            settings = new Settings(loadStrict(), getLogger());
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().severe("config.yml could not be read: " + e.getMessage());
            getLogger().severe("Using the built-in defaults until it is fixed. Fix the file and run /autorestart reload.");
            settings = new Settings(getConfig(), getLogger());
        }
        messenger = new Messenger(this);
        discord = new DiscordWebhook(getLogger());

        restartManager = new RestartManager(this);
        restartManager.start();

        AutoRestartCommand command = new AutoRestartCommand(this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(command.build(), "Scheduled server restarts", AutoRestartCommand.ALIASES));

        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new AutoRestartExpansion(this).register();
            getLogger().info("Hooked into PlaceholderAPI.");
        }
    }

    @Override
    public void onDisable() {
        if (restartManager != null) restartManager.stop();
    }

    /** Re-reads config.yml. Throws if the file can't be loaded so the command can report it. */
    public void reloadSettings() throws IOException, InvalidConfigurationException {
        // Loaded strictly: Bukkit's reloadConfig() swallows YAML errors and silently falls back to the
        // shipped defaults, which would replace your schedules while still saying "reloaded".
        settings = new Settings(loadStrict(), getLogger());
        restartManager.reload();
    }

    private FileConfiguration loadStrict() throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(new File(getDataFolder(), "config.yml"));
        try (InputStream in = getResource("config.yml")) {
            if (in != null) {
                yaml.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        }
        return yaml;
    }

    public Settings settings() {
        return settings;
    }

    public Messenger messenger() {
        return messenger;
    }

    public DiscordWebhook discord() {
        return discord;
    }

    public RestartManager restartManager() {
        return restartManager;
    }
}
