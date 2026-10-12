package dev.aies.control.memory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Bounded per-project memory interface; in-memory adapter for tests/POC only. */
public final class ProjectMemory {
    public record Entry(String id, String projectId, String kind, String content, Instant createdAt) {}
    private static final Pattern ID = Pattern.compile("[a-zA-Z0-9_-]{1,80}");
    private final Map<String, List<Entry>> data = new ConcurrentHashMap<>();
    private final int maxEntriesPerProject;
    public ProjectMemory(int maxEntriesPerProject) {
        if (maxEntriesPerProject < 1 || maxEntriesPerProject > 10000) throw new IllegalArgumentException("Invalid limit");
        this.maxEntriesPerProject = maxEntriesPerProject;
    }
    private static void checkId(String id) {
        if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid project ID");
    }
    public Entry remember(String projectId, String kind, String content) {
        checkId(projectId);
        if (kind == null || !kind.matches("[a-z-]{1,40}") || content == null || content.isBlank() || content.length() > 8192)
            throw new IllegalArgumentException("Invalid memory");
        Entry entry = new Entry(UUID.randomUUID().toString(), projectId, kind, content, Instant.now());
        List<Entry> entries = data.computeIfAbsent(projectId, ignored -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (entries) {
            entries.add(entry);
            while (entries.size() > maxEntriesPerProject) entries.remove(0);
        }
        return entry;
    }
    /** Deterministic lexical retrieval; no embeddings, no network calls. */
    public List<Entry> retrieve(String projectId, String query, int limit) {
        checkId(projectId);
        if (query == null || query.isBlank() || query.length() > 512 || limit < 1 || limit > 50)
            throw new IllegalArgumentException("Invalid query");
        List<Entry> entries = data.get(projectId);
        if (entries == null) return List.of();
        String[] terms = Arrays.stream(query.toLowerCase(Locale.ROOT).split("\\W+"))
                .filter(term -> term.length() > 2).distinct().toArray(String[]::new);
        if (terms.length == 0) return List.of();
        synchronized (entries) {
            return entries.stream().filter(e -> {
                String text = e.content().toLowerCase(Locale.ROOT);
                return Arrays.stream(terms).anyMatch(text::contains);
            }).sorted(Comparator.comparing(Entry::createdAt).reversed())
                    .limit(limit).toList();
        }
    }
}
