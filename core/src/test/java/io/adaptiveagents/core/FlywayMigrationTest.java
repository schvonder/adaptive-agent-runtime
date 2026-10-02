package io.adaptiveagents.core;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
public class FlywayMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    static HikariDataSource dataSource;

    @BeforeAll
    static void setup() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        dataSource = new HikariDataSource(config);
    }

    @AfterAll
    static void teardown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void migrationAppliesCleanlyAndCreatesSchema() {
        String schema = "schema_test1";
        Flyway flyway = Flyway.configure().dataSource(dataSource).schemas(schema).load();
        flyway.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        
        Integer tableCount = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_schema = ? AND table_name IN ('tasks', 'progress_events')", 
            Integer.class, schema);
        assertThat(tableCount).isEqualTo(2);

        Integer indexCount = jdbc.queryForObject(
            "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE c.relname = 'idx_progress_events_task_seq' AND n.nspname = ?",
            Integer.class, schema);
        assertThat(indexCount).isEqualTo(1);
    }

    @Test
    void checksumTamperValidationThrows() {
        String schema = "schema_test2";
        Flyway flyway = Flyway.configure().dataSource(dataSource).schemas(schema).load();
        flyway.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("UPDATE " + schema + ".flyway_schema_history SET checksum = -1 WHERE version = '1'");

        assertThatThrownBy(() -> flyway.validate())
            .isInstanceOf(FlywayValidateException.class)
            .hasMessageContaining("Validate failed");
    }

    @Test
    void constraintsSanityCheck() {
        String schema = "schema_test3";
        Flyway flyway = Flyway.configure().dataSource(dataSource).schemas(schema).load();
        flyway.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        
        // Check status constraint
        assertThatThrownBy(() -> 
            jdbc.update("INSERT INTO " + schema + ".tasks (task_id, envelope, status) VALUES (?, ?::jsonb, ?)", 
                UUID.randomUUID(), "{}", "BOGUS")
        ).isInstanceOf(DataAccessException.class);

        // Check foreign key
        assertThatThrownBy(() -> 
            jdbc.update("INSERT INTO " + schema + ".progress_events (task_id, type) VALUES (?, ?)", 
                UUID.randomUUID(), "INIT")
        ).isInstanceOf(DataAccessException.class);
    }
}
