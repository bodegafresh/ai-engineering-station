package dev.aies.control.execution;

import java.util.*;

/** Immutable operator-controlled verification catalog; never populated from agent output. */
public final class VerificationProfileRegistry {
    public record Profile(String id, String configKey, String marker, String command, int timeoutSeconds) {
        public Profile {
            Objects.requireNonNull(id); Objects.requireNonNull(configKey);
            Objects.requireNonNull(marker); Objects.requireNonNull(command);
            if (timeoutSeconds < 5 || timeoutSeconds > 300) throw new IllegalArgumentException("Invalid timeout");
        }
    }
    private static final Map<String, Profile> PROFILES;
    static {
        Map<String, Profile> m = new LinkedHashMap<>();
        add(m, "java-gradle", "gradlew", "gradle test --offline --no-daemon --console=plain", 180);
        add(m, "java-maven", "pom.xml", "mvn -o -B test", 180);
        add(m, "node-npm", "package-lock.json", "npm ci --offline --ignore-scripts --no-audit --no-fund && npm test --", 180);
        add(m, "react-vite", "package-lock.json", "npm ci --offline --ignore-scripts --no-audit --no-fund && npm run build && npm test -- --run", 180);
        add(m, "python-pytest", "pyproject.toml", "python -m pytest -q", 180);
        add(m, "go-test", "go.mod", "GOPROXY=off GOSUMDB=off go test ./...", 180);
        add(m, "bash-shellcheck", ".", "find . -type f -name '*.sh' -print0 | xargs -0 -r -n1 sh -c 'bash -n \"$0\" && shellcheck \"$0\"'", 90);
        add(m, "cpp-cmake", "CMakeLists.txt", "cmake -S . -B /work/build -DCMAKE_BUILD_TYPE=Debug && cmake --build /work/build -j2 && ctest --test-dir /work/build --output-on-failure --no-tests=error", 240);
        PROFILES = Collections.unmodifiableMap(m);
    }
    private static void add(Map<String, Profile> m, String id, String marker, String command, int timeout) {
        m.put(id, new Profile(id, "aies.workspace-verification.images." + id, marker, command, timeout));
    }
    public Profile require(String id) {
        Profile result = PROFILES.get(id);
        if (result == null) throw new IllegalArgumentException("Unsupported verification profile");
        return result;
    }
    public List<String> ids() { return List.copyOf(PROFILES.keySet()); }
}
