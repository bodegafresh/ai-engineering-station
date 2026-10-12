package example;

public final class Greeting {
    public static String hello(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name required");
        return "Hola, " + name.trim();
    }
}
