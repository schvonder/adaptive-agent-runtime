package io.adaptiveagents.topologies.runner;

import java.sql.Connection;
import java.util.LinkedList;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;

/**
 * {@link PostgresSaver} that closes the connection in {@code loadCheckpoints}.
 *
 * <p>The inherited implementation reads checkpoints through {@code AbstractPostgresSaver.exec},
 * which calls {@code DataSource.getConnection()} without ever closing it — leaking one pooled
 * connection per checkpoint read. Overriding {@code loadCheckpoints} with a try-with-resources
 * connection keeps checkpoint reads from exhausting the connection pool (fatal on resume-heavy
 * workloads). The query and decode logic are identical to the 1.9.1 superclass implementation.</p>
 */
public final class LeakFreePostgresSaver extends PostgresSaver {

    public LeakFreePostgresSaver(PostgresSaver.Builder builder) throws Exception {
        super(builder);
    }

    @Override
    protected LinkedList<Checkpoint> loadCheckpoints(RunnableConfig config) throws Exception {
        LinkedList<Checkpoint> checkpoints = new LinkedList<>();
        String threadId = threadId(config);
        String sqlCheckThread = sqlCommands.get("sqlCheckThread");
        String sqlQueryCheckpoints = sqlCommands.get("sqlSelectCheckpoints");

        try (Connection conn = datasource.getConnection()) {
            conn.setAutoCommit(true);

            try (var ps = conn.prepareStatement(sqlCheckThread)) {
                ps.setString(1, threadId);
                var resultSet = ps.executeQuery();
                resultSet.next();
                int count = resultSet.getInt(1);
                if (count == 0) {
                    return checkpoints;
                }
                if (count > 1) {
                    throw new IllegalStateException(
                            "there are more than one Thread '" + threadId + "' open (not released yet)");
                }
            }

            try (var ps = conn.prepareStatement(sqlQueryCheckpoints)) {
                ps.setString(1, threadId);
                var rs = ps.executeQuery();
                while (rs.next()) {
                    Checkpoint checkpoint = Checkpoint.builder()
                            .id(rs.getString(1))
                            .nodeId(rs.getString(2))
                            .nextNodeId(rs.getString(3))
                            .state(decodeState(rs.getBytes(4), rs.getString(5)))
                            .build();
                    checkpoints.add(checkpoint);
                }
            }
            return checkpoints;
        }
    }
}
