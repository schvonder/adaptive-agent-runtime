package io.adaptiveagents.topologies.registry;

import static org.assertj.core.api.Assertions.assertThat;

import io.adaptiveagents.core.skills.SkillRegistry;
import io.adaptiveagents.core.skills.SkillTools;
import io.adaptiveagents.topologies.budget.CostTracker;
import io.adaptiveagents.topologies.guard.NoOpGuard;
import io.adaptiveagents.topologies.solo.SoloState;
import io.adaptiveagents.workload.tools.CiteTool;
import io.adaptiveagents.workload.tools.ResearchTools;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

/**
 * Proves the skills surface reaches the model call: the SOLO graph is built with a real
 * {@link SkillTools} (one skill parsed from a temp folder) and run against a recording
 * {@link ChatModel}; the captured prompt's options must carry {@code list_skills} and
 * {@code load_skill} next to the workload tools. Hermetic — no Spring context, no network.
 */
class SoloSkillToolsFederationTest {

    @TempDir
    Path temp;

    static final class RecordingChatModel implements ChatModel {

        private final String responseText;
        private Prompt capturedPrompt;

        RecordingChatModel(String responseText) {
            this.responseText = responseText;
        }

        /**
         * Real chat models (e.g. OpenAI) expose tool-calling options; DefaultChatClient only
         * merges spec-level tools into the prompt options when the model options are
         * {@link ToolCallingChatOptions}.
         */
        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.capturedPrompt = prompt;
            return new ChatResponse(List.of(new Generation(new AssistantMessage(responseText))));
        }
    }

    @Test
    void skillToolsReachTheModelCall() throws Exception {
        RecordingChatModel model = new RecordingChatModel("adaptive agents");

        SoloGraphFactory factory = new SoloGraphFactory(
                ChatClient.builder(model),
                new ResearchTools(query -> List.of(), url -> "", new CiteTool()),
                new NoOpGuard(),
                // Usable without a store: the fixed-text responses carry no usage metadata,
                // so record() skips persistence before touching the template.
                new CostTracker(null),
                null,
                skillTools());

        SoloState state = runGraph(factory);

        assertThat(state.result()).isEqualTo("adaptive agents");
        List<String> names = callbackNames(model);
        assertThat(names).contains("list_skills", "load_skill");
        // The workload tool surface stays attached alongside the skills tools.
        assertThat(names).contains("web_search");
    }

    @Test
    void absentSkillToolsLeaveNoSkillCallbacksInTheModelCall() throws Exception {
        RecordingChatModel model = new RecordingChatModel("adaptive agents");

        SoloGraphFactory factory = new SoloGraphFactory(
                ChatClient.builder(model),
                new ResearchTools(query -> List.of(), url -> "", new CiteTool()),
                new NoOpGuard(),
                new CostTracker(null));

        SoloState state = runGraph(factory);

        assertThat(state.result()).isEqualTo("adaptive agents");
        assertThat(callbackNames(model)).doesNotContain("list_skills", "load_skill");
    }

    private SkillTools skillTools() throws IOException {
        Path file = temp.resolve("deep-research/SKILL.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                ---
                name: deep-research
                description: Multi-source research method
                ---
                1. Decompose the question.
                2. Cross-check sources.
                """);
        return new SkillTools(SkillRegistry.load(temp));
    }

    private static List<String> callbackNames(RecordingChatModel model) {
        ToolCallingChatOptions options = (ToolCallingChatOptions) model.capturedPrompt.getOptions();
        return options.getToolCallbacks().stream()
                .map(ToolCallback::getToolDefinition)
                .map(definition -> definition.name())
                .toList();
    }

    private static SoloState runGraph(SoloGraphFactory factory) throws Exception {
        CompiledGraph<SoloState> graph = factory.graph().compile();
        RunnableConfig config = RunnableConfig.builder().threadId(UUID.randomUUID().toString()).build();
        return graph.invoke(Map.of(SoloState.PROMPT, "research adaptive agents"), config).orElseThrow();
    }
}
