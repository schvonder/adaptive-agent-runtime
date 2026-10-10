package io.adaptiveagents.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Single deployable: assembles the whole runtime (core persistence, topology graphs, workload
 * tools, ingresses) via a component scan across the module root package {@code io.adaptiveagents}.
 * {@code @EnableScheduling} drives the runtime deadline sweep ({@code RunningTaskSweep}).
 */
@SpringBootApplication(scanBasePackages = "io.adaptiveagents")
@EnableScheduling
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
