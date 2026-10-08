package dev.autorestart;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Turns MiniMessage strings from the config into components and sends them. */
public final class Messenger {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final AutoRestartPlugin plugin;

    public Messenger(AutoRestartPlugin plugin) {
        this.plugin = plugin;
    }

    /** Values that can be inserted into a message. Any of them may be null. */
    public record Vars(String time, String reason, String player, String date) {
        public static final Vars NONE = new Vars(null, null, null, null);
    }

    public Component render(String text, Vars vars) {
        TagResolver.Builder resolvers = TagResolver.builder()
                .resolver(Placeholder.parsed("prefix", plugin.settings().message("prefix")));
        // "unparsed" so a player-typed reason can't inject formatting or click events
        resolvers.resolver(Placeholder.unparsed("time", orEmpty(vars.time())));
        resolvers.resolver(Placeholder.unparsed("reason", orEmpty(vars.reason())));
        resolvers.resolver(Placeholder.unparsed("player", orEmpty(vars.player())));
        resolvers.resolver(Placeholder.unparsed("date", orEmpty(vars.date())));
        return MM.deserialize(text, resolvers.build());
    }

    /** Sends {@code messages.<key>} to one audience. Does nothing if the message is empty. */
    public void send(Audience audience, String key, Vars vars) {
        String text = plugin.settings().message(key);
        if (!text.isEmpty()) audience.sendMessage(render(text, vars));
    }

    /** Sends {@code messages.<key>} to every player and the console. */
    public void broadcast(String key, Vars vars) {
        send(Bukkit.getServer(), key, vars);
    }

    /** Sends {@code messages.<key>} to staff with the notify permission and to the console. */
    public void notifyStaff(String key, Vars vars) {
        String text = plugin.settings().message(key);
        if (text.isEmpty()) return;
        Component message = render(text, vars);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("autorestart.notify")) player.sendMessage(message);
        }
        Bukkit.getConsoleSender().sendMessage(message);
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
