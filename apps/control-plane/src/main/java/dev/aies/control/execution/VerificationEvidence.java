package dev.aies.control.execution;

import java.time.Instant;

/** Records observed process results, not a model's self-assessment. */
public record VerificationEvidence(String status, String profile, int exitCode,
                                   boolean timedOut, String outputSha256,
                                   String outputExcerpt, Instant finishedAt) {}
