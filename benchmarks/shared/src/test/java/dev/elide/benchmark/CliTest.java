package dev.elide.benchmark;

/** Dependency-free smoke test, executed by both builds as part of check. */
public final class CliTest {
    public static void main(String[] args) {
        expect("Hello, world!", Greeting.message(new String[0]));
        expect("Hello, Ada!", Greeting.message(new String[] {"Ada"}));
        expect("Hello, Ada Lovelace!", Greeting.message(new String[] {"Ada", "Lovelace"}));
    }

    private static void expect(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected: " + expected + "; actual: " + actual);
        }
    }
}
