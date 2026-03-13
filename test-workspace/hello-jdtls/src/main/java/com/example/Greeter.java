package com.example;

/**
 * A simple greeter that demonstrates basic Java language features.
 *
 * <p>This class is used as a test target for the jdtls-mcp MCP tools:</p>
 * <ul>
 *   <li>{@code java_hover} — hover over {@link #greet()} to see this Javadoc</li>
 *   <li>{@code java_definition} — go-to-definition on {@code Greeter} or {@code name}</li>
 *   <li>{@code java_references} — find all usages of {@link #name}</li>
 *   <li>{@code java_document_symbols} — list all symbols in this file</li>
 *   <li>{@code java_completion} — trigger completion inside method bodies</li>
 * </ul>
 */
public class Greeter {

    /** The name to greet. */
    private final String name;

    /**
     * Creates a new {@code Greeter} for the given name.
     *
     * @param name the name of the person to greet; must not be {@code null}
     */
    public Greeter(String name) {
        this.name = name;
    }

    /**
     * Returns a personalized greeting.
     *
     * @return a greeting string, e.g. {@code "Hello, World!"}
     */
    public String greet() {
        return "Hello, " + name + "!";
    }

    /**
     * Application entry point.
     *
     * @param args command-line arguments (ignored)
     */
    public static void main(String[] args) {
        Greeter greeter = new Greeter("World");
        System.out.println(greeter.greet());

        // Greet a few more people
        String[] names = {"Alice", "Bob", "Charlie"};
        for (String n : names) {
            Greeter g = new Greeter(n);
            System.out.println(g.greet());
        }
    }
}
