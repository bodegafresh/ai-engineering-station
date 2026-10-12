package dev.aies.control.memory;

import java.util.*;

/** Conservative deterministic character budget; token estimate is not a tokenizer. */
public final class MemoryContextBudget {
    public record Selection(String context, int estimatedTokens, int included, int omitted) {}
    public Selection assemble(List<SqliteProjectMemory.Entry> entries, int maxEstimatedTokens) {
        Objects.requireNonNull(entries);
        if (maxEstimatedTokens < 64 || maxEstimatedTokens > 32768) throw new IllegalArgumentException("Invalid budget");
        int remaining = maxEstimatedTokens * 3; // conservative estimate: 3 chars per token
        StringBuilder out = new StringBuilder(); int included = 0;
        for (var e : entries) {
            String line = "[" + e.kind() + ";" + e.id() + "] " + e.content() + "\n";
            if (line.length() > remaining) continue;
            out.append(line); remaining -= line.length(); included++;
        }
        return new Selection(out.toString(), (out.length()+2)/3, included, entries.size()-included);
    }
}
