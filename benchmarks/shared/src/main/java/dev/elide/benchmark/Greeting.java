package dev.elide.benchmark;

final class Greeting {
    static String message(String[] names) {
        return "Hello, " + (names.length == 0 ? "world" : String.join(" ", names)) + "!";
    }
}
