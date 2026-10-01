package io.adaptiveagents.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class SpawnedApp {
    private Process process;
    private final Path jarPath;
    private final Path logPath;
    private final Map<String, String> env;
    private final String[] args;

    public SpawnedApp(Path jarPath, Path logPath, Map<String, String> env, String... args) {
        this.jarPath = jarPath;
        this.logPath = logPath;
        this.env = env;
        this.args = args;
    }

    public void start() throws IOException {
        Files.createDirectories(logPath.getParent());
        List<String> command = new ArrayList<>();
        String javaHome = System.getProperty("java.home");
        command.add(javaHome + "/bin/java");
        command.add("-jar");
        command.add(jarPath.toAbsolutePath().toString());
        command.addAll(List.of(args));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().putAll(env);
        pb.redirectOutput(logPath.toFile());
        pb.redirectError(logPath.toFile());

        this.process = pb.start();
    }

    public void awaitHealthy(int timeoutSeconds) throws Exception {
        long end = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < end) {
            if (!process.isAlive()) {
                String logs = Files.exists(logPath) ? Files.readString(logPath) : "No logs found.";
                throw new IllegalStateException("Process died prematurely. Logs:\\n" + logs);
            }
            if (Files.exists(logPath)) {
                String logs = Files.readString(logPath);
                if (logs.contains("HARNESS_PROBE_READY")) {
                    return;
                }
            }
            Thread.sleep(500);
        }
        String logs = Files.exists(logPath) ? Files.readString(logPath) : "No logs found.";
        throw new IllegalStateException("Timeout waiting for healthy. Logs:\\n" + logs);
    }

    public void stopSigKill() throws InterruptedException {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
    }

    public void relaunch() throws Exception {
        stopSigKill();
        start();
    }
}
