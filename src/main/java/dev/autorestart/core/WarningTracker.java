package dev.autorestart.core;

import java.util.Collection;
import java.util.NavigableSet;
import java.util.OptionalLong;
import java.util.TreeSet;

/**
 * Decides which countdown warning (if any) should fire on each tick.
 * <p>
 * Marks are "seconds before restart". When the remaining time drops to or below a mark it fires once.
 * If lag makes the countdown skip past several marks at once, only the closest one fires, so players
 * don't get a burst of messages.
 */
public final class WarningTracker {

    private final NavigableSet<Long> marks = new TreeSet<>();
    private final NavigableSet<Long> pending = new TreeSet<>();

    public WarningTracker(Collection<Long> marks) {
        this.marks.addAll(marks);
    }

    /**
     * Call whenever the restart target changes. Marks above the new remaining time are skipped
     * (it's too late for them); marks at or below it will fire as the countdown reaches them.
     */
    public void reset(long remainingSeconds) {
        pending.clear();
        pending.addAll(marks.headSet(remainingSeconds, true));
    }

    /** @return the mark to announce now, or empty if nothing is due */
    public OptionalLong poll(long remainingSeconds) {
        NavigableSet<Long> due = pending.tailSet(remainingSeconds, true);
        if (due.isEmpty()) {
            return OptionalLong.empty();
        }
        long mark = due.first();
        due.clear();
        return OptionalLong.of(mark);
    }
}
