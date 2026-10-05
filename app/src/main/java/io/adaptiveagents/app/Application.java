package io.adaptiveagents.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Single deployable: assembles the whole runtime (core persistence, topology graphs, workload
 * tools, ingresses) via a component scan across the module root package {@code io.adaptiveagents}.
 */
@SpringBootApplication(scanBasePackages = "io.adaptiveagents")
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
