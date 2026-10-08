package dev.autorestart;

import dev.autorestart.command.AutoRestartCommand;
import dev.autorestart.hook.AutoRestartExpansion;
import dev.autorestart.hook.DiscordWebhook;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

public final class AutoRestartPlugin extends JavaPlugin {

    private Settings settings;
    private Messenger messenger;
    private DiscordWebhook discord;
    private RestartManager restartManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = new Settings(getConfig(), getLogger());
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
    public void reloadSettings() {
        reloadConfig();
        settings = new Settings(getConfig(), getLogger());
        restartManager.reload();
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
