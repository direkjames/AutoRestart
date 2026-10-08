package dev.autorestart.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.autorestart.AutoRestartPlugin;
import dev.autorestart.Messenger;
import dev.autorestart.RestartManager;
import dev.autorestart.core.TimeParser;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * /autorestart (alias /ar)
 * <pre>
 *   time                      - when is the next restart (everyone)
 *   now    &lt;time&gt; [reason]    - restart in &lt;time&gt; from now
 *   delay  &lt;time&gt; [reason]    - push the pending restart back
 *   cancel [reason]           - skip the pending restart
 *   reload                    - reload config.yml
 * </pre>
 */
public final class AutoRestartCommand {

    public static final String NAME = "autorestart";
    public static final List<String> ALIASES = List.of("ar");

    private final AutoRestartPlugin plugin;

    public AutoRestartCommand(AutoRestartPlugin plugin) {
        this.plugin = plugin;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(NAME)
                .executes(this::time)
                .then(Commands.literal("time").executes(this::time))
                .then(Commands.literal("now")
                        .requires(src -> src.getSender().hasPermission("autorestart.admin.now"))
                        .then(timeArgument()
                                .executes(ctx -> now(ctx, null))
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(ctx -> now(ctx, StringArgumentType.getString(ctx, "reason"))))))
                .then(Commands.literal("delay")
                        .requires(src -> src.getSender().hasPermission("autorestart.admin.delay"))
                        .then(timeArgument()
                                .executes(ctx -> delay(ctx, null))
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(ctx -> delay(ctx, StringArgumentType.getString(ctx, "reason"))))))
                .then(Commands.literal("cancel")
                        .requires(src -> src.getSender().hasPermission("autorestart.admin.cancel"))
                        .executes(ctx -> cancel(ctx, null))
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(ctx -> cancel(ctx, StringArgumentType.getString(ctx, "reason")))))
                .then(Commands.literal("reload")
                        .requires(src -> src.getSender().hasPermission("autorestart.admin.reload"))
                        .executes(this::reload));
        return root.build();
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> timeArgument() {
        return Commands.argument("time", new TimeArgument());
    }

    // ---------------------------------------------------------------- executors

    private int time(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        RestartManager manager = plugin.restartManager();
        Messenger messenger = plugin.messenger();

        if (manager.target().isEmpty()) {
            messenger.send(sender, "no-restart", Messenger.Vars.NONE);
            return Command.SINGLE_SUCCESS;
        }
        Instant target = manager.target().get();
        String reason = manager.reason();
        Messenger.Vars vars = new Messenger.Vars(
                TimeParser.format(manager.remaining().orElse(Duration.ZERO)),
                reason, null, manager.formatDate(target));
        messenger.send(sender, reason == null || reason.isBlank() ? "time" : "time-with-reason", vars);
        return Command.SINGLE_SUCCESS;
    }

    private int now(CommandContext<CommandSourceStack> ctx, String reason) {
        CommandSender sender = ctx.getSource().getSender();
        Duration in = parseTime(ctx, sender);
        if (in == null) return 0;

        RestartManager manager = plugin.restartManager();
        manager.restartIn(in, reason);
        Vars vars = vars(sender, in, reason);

        plugin.messenger().send(sender, "done-now", vars.chat());
        plugin.messenger().broadcast("broadcast-now", vars.chat());
        plugin.messenger().notifyStaff("staff-now", vars.chat());
        plugin.discord().send(plugin.settings().discord, "now", vars.discord());
        return Command.SINGLE_SUCCESS;
    }

    private int delay(CommandContext<CommandSourceStack> ctx, String reason) {
        CommandSender sender = ctx.getSource().getSender();
        Duration by = parseTime(ctx, sender);
        if (by == null) return 0;

        RestartManager manager = plugin.restartManager();
        if (!manager.delay(by, reason)) {
            plugin.messenger().send(sender, "no-restart", Messenger.Vars.NONE);
            return 0;
        }
        Vars vars = vars(sender, by, reason);
        Messenger.Vars remaining = new Messenger.Vars(
                TimeParser.format(manager.remaining().orElse(Duration.ZERO)),
                vars.chat().reason(), vars.chat().player(), vars.chat().date());

        plugin.messenger().send(sender, "done-delayed", remaining);
        plugin.messenger().broadcast("broadcast-delayed", vars.chat());
        plugin.messenger().notifyStaff("staff-delayed", vars.chat());
        plugin.discord().send(plugin.settings().discord, "delayed", vars.discord());
        return Command.SINGLE_SUCCESS;
    }

    private int cancel(CommandContext<CommandSourceStack> ctx, String reason) {
        CommandSender sender = ctx.getSource().getSender();
        RestartManager manager = plugin.restartManager();
        if (!manager.cancel()) {
            plugin.messenger().send(sender, "no-restart", Messenger.Vars.NONE);
            return 0;
        }
        // After cancelling, <date> shows the next restart from the schedule (if any).
        Vars vars = vars(sender, null, reason);

        plugin.messenger().send(sender, "done-cancelled", vars.chat());
        plugin.messenger().broadcast("broadcast-cancelled", vars.chat());
        plugin.messenger().notifyStaff("staff-cancelled", vars.chat());
        plugin.discord().send(plugin.settings().discord, "cancelled", vars.discord());
        return Command.SINGLE_SUCCESS;
    }

    private int reload(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        try {
            plugin.reloadSettings();
            plugin.messenger().send(sender, "reloaded", Messenger.Vars.NONE);
        } catch (Exception e) {
            plugin.getLogger().severe("Reload failed: " + e);
            plugin.messenger().send(sender, "reload-failed", new Messenger.Vars(null, e.getMessage(), null, null));
        }
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------- helpers

    private Duration parseTime(CommandContext<CommandSourceStack> ctx, CommandSender sender) {
        try {
            return TimeParser.parse(ctx.getArgument("time", String.class));
        } catch (IllegalArgumentException e) {
            plugin.messenger().send(sender, "invalid-time", new Messenger.Vars(null, e.getMessage(), null, null));
            return null;
        }
    }

    /** The same values, shaped for chat (MiniMessage tags) and for Discord ({placeholders}). */
    private record Vars(Messenger.Vars chat, Map<String, String> discord) {
    }

    private Vars vars(CommandSender sender, Duration time, String reason) {
        RestartManager manager = plugin.restartManager();
        String reasonText = reason == null || reason.isBlank() ? plugin.settings().message("no-reason") : reason;
        String timeText = time == null ? "" : TimeParser.format(time);
        String date = manager.target().map(manager::formatDate).orElse("-");
        return new Vars(
                new Messenger.Vars(timeText, reasonText, sender.getName(), date),
                Map.of("time", timeText, "reason", reasonText, "player", sender.getName(), "date", date));
    }
}
