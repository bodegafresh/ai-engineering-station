package dev.aies.control.execution;

import dev.aies.control.specialists.GovernedSpecialistSelector;
import io.vertx.core.json.JsonObject;

/** Fail-closed internal consumer boundary. An Event Bus field is never a grant. */
final class GovernedDocumentationGuard {
    private GovernedDocumentationGuard() {}

    static GovernedSpecialistSelector.AssignmentSnapshot require(JsonObject request,
            GovernedSpecialistSelector guard) throws Exception {
        if (request == null) throw new SecurityException("Missing documentation request");
        if (guard == null) {
            if (request.containsKey("governedSpecialistId") || request.containsKey("governedSpecialistVersion")
                || request.containsKey("governedCapability") || request.containsKey("governedManifestSha256") ||
                request.containsKey("governedRegistryRevision"))
                throw new SecurityException("Governed assignment while governance disabled");
            return null;
        }
        String id = request.getString("governedSpecialistId");
        String version = request.getString("governedSpecialistVersion");
        String capability = request.getString("governedCapability");
        if (!"software-engineer".equals(id) || !"software".equals(capability)
            || version == null || version.isBlank() || version.length() > 128)
            throw new SecurityException("Missing or invalid governed documentation identity");
        var snapshot = new GovernedSpecialistSelector.AssignmentSnapshot(id, version, capability,
            request.getString("governedManifestSha256"), request.getLong("governedRegistryRevision", -1L));
        guard.verifySnapshot(snapshot);
        return snapshot;
    }
}
