package dev.aies.control.execution;

/** Versioned, reviewed guidance for the existing software-engineer specialist. No new specialist registration. */
public final class TechnicalSpecialistGuidance {
    public static final String VERSION = "f2.1";
    private TechnicalSpecialistGuidance() {}
    public static String documentation() {
        return "Technical specialist guidance " + VERSION + ": distinguish inspected source from assumptions; " +
            "describe public API, dependencies, responsibilities and limitations; cite only the provided source; " +
            "never claim tests, builds, filesystem access or external research unless supported by tool evidence; " +
            "report uncertainty and concrete verification steps.\n";
    }
}
