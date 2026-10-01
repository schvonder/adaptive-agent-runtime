package io.adaptiveagents.app;

import org.testcontainers.containers.PostgreSQLContainer;

public class SingletonPostgres {
    private static final PostgreSQLContainer<?> postgres;

    static {
        postgres = new PostgreSQLContainer<>("postgres:17-alpine");
        postgres.start();
    }

    public static String getJdbcUrl() {
        return postgres.getJdbcUrl();
    }

    public static String getUsername() {
        return postgres.getUsername();
    }

    public static String getPassword() {
        return postgres.getPassword();
    }
}
