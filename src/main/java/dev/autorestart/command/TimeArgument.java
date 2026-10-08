package dev.autorestart.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Reads one space-separated token as raw text, so formats like {@code 1:30} work.
 * (Brigadier's own word argument stops at ':'.) The text is validated later by TimeParser,
 * which lets us show our own friendly error message.
 */
final class TimeArgument implements CustomArgumentType<String, String> {

    private static final List<String> SUGGESTIONS = List.of("30s", "1m", "5m", "10m", "30m", "1h", "1h30m");

    @Override
    public String parse(StringReader reader) {
        int start = reader.getCursor();
        while (reader.canRead() && reader.peek() != ' ') {
            reader.skip();
        }
        return reader.getString().substring(start, reader.getCursor());
    }

    @Override
    public ArgumentType<String> getNativeType() {
        return StringArgumentType.word();
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        String typed = builder.getRemainingLowerCase();
        SUGGESTIONS.stream().filter(s -> s.startsWith(typed)).forEach(builder::suggest);
        return builder.buildFuture();
    }
}
