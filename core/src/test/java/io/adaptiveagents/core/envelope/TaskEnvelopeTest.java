package io.adaptiveagents.core.envelope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.adaptiveagents.core.model.Topology;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class TaskEnvelopeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonSchema getSchema() {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        InputStream is = getClass().getResourceAsStream("/envelope-v1.schema.json");
        return factory.getSchema(is);
    }

    @Test
    void jacksonRoundtripWithSnakeCaseAndDefaults() throws Exception {
        UUID id = UUID.randomUUID();
        String json = """
        {
          "task_id": "%s",
          "task_type": "research",
          "prompt": "Investigate X",
          "forced_topology": "FAN_OUT"
        }
        """.formatted(id.toString());

        TaskEnvelope env = mapper.readValue(json, TaskEnvelope.class);
        
        assertThat(env.taskId()).isEqualTo(id);
        assertThat(env.taskType()).isEqualTo("research");
        assertThat(env.prompt()).isEqualTo("Investigate X");
        assertThat(env.breadthHint()).isEqualTo(1); // default
        assertThat(env.deadlineMs()).isNull();
        assertThat(env.budgetUsd()).isNull();
        assertThat(env.forcedTopology()).isEqualTo(Topology.FAN_OUT);

        String serialized = mapper.writeValueAsString(env);
        assertThat(serialized).contains("\"task_id\":\"" + id + "\"");
        assertThat(serialized).contains("\"breadth_hint\":1");
        assertThat(serialized).contains("\"forced_topology\":\"FAN_OUT\"");
    }

    @Test
    void schemaValidationGreenPath() throws Exception {
        UUID id = UUID.randomUUID();
        String json = """
        {
          "task_id": "%s",
          "task_type": "research",
          "prompt": "Test prompt",
          "breadth_hint": 3,
          "deadline_ms": 5000,
          "budget_usd": 10.5,
          "forced_topology": "SUPERVISOR"
        }
        """.formatted(id.toString());

        Set<ValidationMessage> errors = getSchema().validate(mapper.readTree(json));
        assertThat(errors).isEmpty();
    }

    @Test
    void schemaValidationMissingTaskId() throws Exception {
        String json = """
        {
          "task_type": "research",
          "prompt": "Test prompt"
        }
        """;
        Set<ValidationMessage> errors = getSchema().validate(mapper.readTree(json));
        assertThat(errors).isNotEmpty();
        assertThat(errors.toString()).contains("task_id");
    }

    @Test
    void schemaValidationBadTaskType() throws Exception {
        String json = """
        {
          "task_id": "b0e49cc3-a128-444a-b5e1-5bcba94564c7",
          "task_type": "write",
          "prompt": "Test prompt"
        }
        """;
        Set<ValidationMessage> errors = getSchema().validate(mapper.readTree(json));
        assertThat(errors).isNotEmpty();
        assertThat(errors.toString()).contains("task_type");
    }

    @Test
    void schemaValidationInvalidTopology() throws Exception {
        String json = """
        {
          "task_id": "b0e49cc3-a128-444a-b5e1-5bcba94564c7",
          "task_type": "research",
          "prompt": "Test prompt",
          "forced_topology": "TEAM"
        }
        """;
        Set<ValidationMessage> errors = getSchema().validate(mapper.readTree(json));
        assertThat(errors).isNotEmpty();
        assertThat(errors.toString()).contains("forced_topology");
    }

    @Test
    void schemaValidationBadBreadthHint() throws Exception {
        String json = """
        {
          "task_id": "b0e49cc3-a128-444a-b5e1-5bcba94564c7",
          "task_type": "research",
          "prompt": "Test prompt",
          "breadth_hint": 9
        }
        """;
        Set<ValidationMessage> errors = getSchema().validate(mapper.readTree(json));
        assertThat(errors).isNotEmpty();
        assertThat(errors.toString()).contains("breadth_hint");
    }

    @Test
    void schemaValidationNegativeBudget() throws Exception {
        String json = """
        {
          "task_id": "b0e49cc3-a128-444a-b5e1-5bcba94564c7",
          "task_type": "research",
          "prompt": "Test prompt",
          "budget_usd": -5.0
        }
        """;
        Set<ValidationMessage> errors = getSchema().validate(mapper.readTree(json));
        assertThat(errors).isNotEmpty();
        assertThat(errors.toString()).contains("budget_usd");
    }

    @Test
    void schemaValidationEmptyPrompt() throws Exception {
        String json = """
        {
          "task_id": "b0e49cc3-a128-444a-b5e1-5bcba94564c7",
          "task_type": "research",
          "prompt": ""
        }
        """;
        Set<ValidationMessage> errors = getSchema().validate(mapper.readTree(json));
        assertThat(errors).isNotEmpty();
        assertThat(errors.toString()).contains("prompt");
    }

    @Test
    void ignoreUnknownFieldsOnDeserialization() throws Exception {
        String json = """
        {
          "task_id": "b0e49cc3-a128-444a-b5e1-5bcba94564c7",
          "task_type": "research",
          "prompt": "Test prompt",
          "future_field": "some-value"
        }
        """;
        
        TaskEnvelope env = mapper.readValue(json, TaskEnvelope.class);
        assertThat(env.taskId()).isEqualTo(UUID.fromString("b0e49cc3-a128-444a-b5e1-5bcba94564c7"));
        assertThat(env.prompt()).isEqualTo("Test prompt");
    }
}
