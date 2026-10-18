package io.adaptiveagents.core.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full transcription of the §7.1 task state machine: the complete 7×7 transition
 * matrix over {@link TaskStatus}, plus the terminal-status set.
 *
 * <p>Legal transitions per spec:</p>
 * <pre>
 *   SUBMITTED → ROUTED
 *   ROUTED    → RUNNING
 *   RUNNING   → SUCCEEDED | FAILED | BUDGET_EXCEEDED | DEADLINE_EXCEEDED
 * </pre>
 * <p>Terminal statuses ({@link TaskStatus#isTerminal()}): SUCCEEDED, FAILED,
 * BUDGET_EXCEEDED, DEADLINE_EXCEEDED — no outgoing transitions, from anywhere.</p>
 */
public class TaskStatusTest {

    private static final Set<Map.Entry<TaskStatus, TaskStatus>> LEGAL = Set.of(
            Map.entry(TaskStatus.SUBMITTED, TaskStatus.ROUTED),
            Map.entry(TaskStatus.ROUTED, TaskStatus.RUNNING),
            Map.entry(TaskStatus.RUNNING, TaskStatus.SUCCEEDED),
            Map.entry(TaskStatus.RUNNING, TaskStatus.FAILED),
            Map.entry(TaskStatus.RUNNING, TaskStatus.BUDGET_EXCEEDED),
            Map.entry(TaskStatus.RUNNING, TaskStatus.DEADLINE_EXCEEDED)
    );

    static Stream<Arguments> fullTransitionMatrix() {
        return Stream.of(TaskStatus.values())
                .flatMap(from -> Stream.of(TaskStatus.values())
                        .map(to -> Arguments.of(from, to, LEGAL.contains(Map.entry(from, to)))));
    }

    @ParameterizedTest(name = "{0} -> {1} : {2}")
    @MethodSource("fullTransitionMatrix")
    void transitionMatrix(TaskStatus from, TaskStatus to, boolean expectedLegal) {
        assertThat(from.canTransitionTo(to))
                .as("canTransitionTo(%s -> %s) per §7.1", from, to)
                .isEqualTo(expectedLegal);
    }

    static Stream<Arguments> terminalStatuses() {
        return Stream.of(
                Arguments.of(TaskStatus.SUBMITTED, false),
                Arguments.of(TaskStatus.ROUTED, false),
                Arguments.of(TaskStatus.RUNNING, false),
                Arguments.of(TaskStatus.SUCCEEDED, true),
                Arguments.of(TaskStatus.FAILED, true),
                Arguments.of(TaskStatus.BUDGET_EXCEEDED, true),
                Arguments.of(TaskStatus.DEADLINE_EXCEEDED, true)
        );
    }

    @ParameterizedTest(name = "{0} terminal={1}")
    @MethodSource("terminalStatuses")
    void terminalStatusSet(TaskStatus status, boolean expectedTerminal) {
        assertThat(status.isTerminal()).isEqualTo(expectedTerminal);
    }
}
