package io.adaptiveagents.app;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

@Tag("e2e")
public class SpawnedAppKillRestartTest {

    private static Path getFatJarPath() throws Exception {
        Path targetDir = Paths.get("target");
        if (!Files.exists(targetDir)) {
            return null;
        }
        try (var stream = Files.list(targetDir)) {
            return stream.filter(p -> p.toString().endsWith(".jar") && !p.toString().endsWith("-original.jar") && !p.toString().endsWith("-sources.jar"))
                         .findFirst()
                         .orElse(null);
        }
    }

    @BeforeAll
    static void checkJarExists() throws Exception {
        Path jarPath = getFatJarPath();
        if (jarPath == null || !Files.exists(jarPath)) {
            fail("App fat jar not found in target/. Run 'mvn package' before running e2e tests.");
        }
    }

    @Test
    void testKillAndRestartPreservesData() throws Exception {
        Path jarPath = getFatJarPath();
        Path logPath = Paths.get("target", "spawned", "harness.log");
        
        // The app now assembles the full runtime (component scan across io.adaptiveagents), so the
        // spawned process needs the deterministic offline profiles alongside the harness probe:
        // stub-classifier satisfies RoutingService and fixture-model provides a no-network ChatModel.
        Map<String, String> env = Map.of(
            "SPRING_PROFILES_ACTIVE", "harness,stub-classifier,fixture-model",
            "SPRING_DATASOURCE_URL", SingletonPostgres.getJdbcUrl(),
            "SPRING_DATASOURCE_USERNAME", SingletonPostgres.getUsername(),
            "SPRING_DATASOURCE_PASSWORD", SingletonPostgres.getPassword()
        );

        SpawnedApp app = new SpawnedApp(jarPath, logPath, env,
                "--spring.main.web-application-type=none", "--spring.ai.model.chat=none");
        try {
            app.start();
            app.awaitHealthy(30);

            try (Connection conn = DriverManager.getConnection(SingletonPostgres.getJdbcUrl(), SingletonPostgres.getUsername(), SingletonPostgres.getPassword());
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT phase FROM harness_probe WHERE id = 'probe-1'")) {
                
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("phase")).isEqualTo("init");
            }

            app.stopSigKill();

            app.relaunch();
            app.awaitHealthy(30);

            try (Connection conn = DriverManager.getConnection(SingletonPostgres.getJdbcUrl(), SingletonPostgres.getUsername(), SingletonPostgres.getPassword());
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT phase FROM harness_probe WHERE id = 'probe-1'")) {
                
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("phase")).isEqualTo("init");
                
                try (ResultSet rsCount = stmt.executeQuery("SELECT COUNT(*) FROM harness_probe WHERE id = 'probe-1'")) {
                    rsCount.next();
                    assertThat(rsCount.getInt(1)).isEqualTo(1);
                }
            }
        } finally {
            app.stopSigKill();
        }
    }
}
