package dev.aies.control.models;

import java.util.List;

public record ModelSelection(
        String specialistId,
        String configurationId,
        String provider,
        String model,
        String endpoint,
        boolean fallback,
        List<String> rejectedCandidates) {
    public ModelSelection {
        rejectedCandidates = List.copyOf(rejectedCandidates);
    }
}
