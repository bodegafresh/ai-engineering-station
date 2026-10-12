package dev.aies.control.prompts;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Read-only, versioned specialist knowledge. No authority to grant tools or execute actions. */
@ApplicationScoped
public class SpecialistProfileRegistry {
    private final SpecialistCatalog catalog;
    public SpecialistProfileRegistry(SpecialistCatalog catalog) { this.catalog = catalog; }
    public Set<String> ids() { return catalog.ids(); }
    public String profile(String id) {
        catalog.get(id); // allow-list from the existing catalog; no arbitrary paths
        String resource = "specialists/profiles/" + id + ".md";
        try (var in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("Missing profile: " + id);
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (!content.startsWith("---\nid: " + id + "\n") || !content.contains("\nversion: "))
                throw new IllegalStateException("Invalid profile metadata: " + id);
            if (content.length() > 30000) throw new IllegalStateException("Profile too large: " + id);
            return content;
        } catch (IOException e) { throw new IllegalStateException("Cannot read profile: " + id, e); }
    }
}
