package com.example;

import com.google.common.util.concurrent.SettableFuture;

public class HelloWorld {
    public static void main(String[] args) throws Exception {
        // Exercise Guava and its transitive failureaccess dependency at compile time and runtime.
        var greeting = SettableFuture.<String>create();
        greeting.set("Hello, World!");
        System.out.println(greeting.get());
    }
}
