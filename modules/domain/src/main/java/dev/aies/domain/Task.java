package dev.aies.domain;
import java.time.Instant;
import java.util.UUID;
public record Task(UUID id, String objective, TaskStatus status, Instant createdAt, Instant updatedAt) {
    public Task {
        if (id == null || status == null || createdAt == null || updatedAt == null) throw new IllegalArgumentException("Missing task fields");
        if (objective == null || objective.isBlank() || objective.length() > 4000) throw new IllegalArgumentException("Objective must contain 1-4000 characters");
    }
}
