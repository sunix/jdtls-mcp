package com.example;

/**
 * A simple counter that demonstrates field references and method calls.
 *
 * <p>Used as a secondary test target for the jdtls-mcp MCP tools — useful for
 * testing {@code java_references} across multiple files and
 * {@code java_workspace_symbols} searches.</p>
 */
public class Counter {

    private int count;

    /**
     * Creates a new counter starting at zero.
     */
    public Counter() {
        this.count = 0;
    }

    /**
     * Increments the counter by one.
     */
    public void increment() {
        count++;
    }

    /**
     * Decrements the counter by one.
     */
    public void decrement() {
        count--;
    }

    /**
     * Resets the counter to zero.
     */
    public void reset() {
        count = 0;
    }

    /**
     * Returns the current counter value.
     *
     * @return the current count
     */
    public int getCount() {
        return count;
    }

    /**
     * Returns a string representation of the counter.
     *
     * @return a human-readable description of the current count
     */
    @Override
    public String toString() {
        return "Counter{count=" + count + "}";
    }
}
