package io.adaptiveagents.app;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("harness")
public class HarnessProbeRunner implements CommandLineRunner {
    
    private final JdbcTemplate jdbcTemplate;

    public HarnessProbeRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) throws Exception {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS harness_probe(id text primary key, phase text)");
        jdbcTemplate.update("INSERT INTO harness_probe(id, phase) VALUES (?, ?) ON CONFLICT DO NOTHING", "probe-1", "init");
        System.out.println("HARNESS_PROBE_READY");
        
        Thread.currentThread().join();
    }
}
