package dev.aies.control.science;

import java.net.URI;
import java.util.List;
import java.util.Objects;

/** P0.10: provenance supplied by an operator or future read-only connector; not a claim of web verification. */
public final class ResearchEvidence {
    private ResearchEvidence() {}
    public record Source(String workflowId, String experimentId, String title, String url,
                         String retrievedAt, String excerpt, String contentSha256, String method,
                         List<String> limitations) {}
    public static Source validate(Source s) {
        Objects.requireNonNull(s);
        validId(s.workflowId()); validId(s.experimentId());
        text(s.title(), 240); text(s.excerpt(), 8192); text(s.method(), 1000);
        text(s.retrievedAt(), 80);
        try {
            URI uri = URI.create(text(s.url(), 2048));
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                throw new IllegalArgumentException("HTTPS URL required");
        } catch (RuntimeException e) {throw new IllegalArgumentException("Invalid source URL",e);}
        if (s.contentSha256() == null || !s.contentSha256().matches("[a-fA-F0-9]{64}"))
            throw new IllegalArgumentException("SHA-256 required");
        if (s.limitations() == null || s.limitations().size() > 16) throw new IllegalArgumentException("Invalid limitations");
        s.limitations().forEach(x -> text(x, 1000));
        return new Source(s.workflowId(),s.experimentId(),s.title(),s.url(),s.retrievedAt(),s.excerpt(),
                s.contentSha256().toLowerCase(java.util.Locale.ROOT),s.method(),List.copyOf(s.limitations()));
    }
    static String text(String x,int max) {if(x==null || x.isBlank() || x.length()>max) throw new IllegalArgumentException("Invalid text");return x;}
    static void validId(String x) {if(x==null || !x.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")) throw new IllegalArgumentException("Invalid ID");}
}
