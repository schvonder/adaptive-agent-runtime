package io.adaptiveagents.core.classify.jev;

import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.question.Choice;
import org.springaicommunity.typesafe.question.SystemOneRequest;
import org.springaicommunity.typesafe.response.SystemOneResponse;

import java.util.Objects;

/**
 * Jev topology decider over the TypeSafe AI decision API: a single {@code POST /v1/systemone}
 * call with a "band" choice question (CLEAR_SIMPLE, CLEAR_COMPLEX, AMBIGUOUS) and a "topology"
 * choice question (SOLO, CHAIN, FAN_OUT, SUPERVISOR). When the classifier runs in
 * rationale-mode=codes a third "reason" choice question (MULTI_COMPONENT, CONFLICTING_SIGNALS,
 * UNDERSPECIFIED, BREADTH_LENGTH_MISMATCH) rides along so the verdict rationale can carry a
 * stable code instead of model-generated free text.
 *
 * <p>Option order is fixed at every question: Jev has a documented first-option bias, so the
 * option lists never vary between calls — reordering them would silently shift that bias rather
 * than improve the decision.</p>
 *
 * <p>Jev is a decision model, not a chat model: there is no free-text answer to sanitize, only
 * typed option values with calibrated confidences. Anything the SDK throws
 * (TypeSafeApiException hierarchy) propagates; {@link JevClassifier} catches it.</p>
 */
public class TypeSafeJevDecider implements JevTopologyDecider {

    private final TypeSafeClient client;
    private final boolean includeReason;

    public TypeSafeJevDecider(TypeSafeClient client, boolean includeReason) {
        this.client = Objects.requireNonNull(client, "client");
        this.includeReason = includeReason;
    }

    @Override
    public JevDecision decide(String state) {
        SystemOneRequest.Builder request = SystemOneRequest.builder()
                .state(state)
                .question("band", Choice.builder()
                        .instructions("Classify the complexity of this research task.")
                        .option("CLEAR_SIMPLE",
                                "One focused question a single agent answers in one pass; short prompt, breadth hint 1.")
                        .option("CLEAR_COMPLEX",
                                "Clearly multi-part research that needs decomposition; long prompt or breadth hint 4-5.")
                        .option("AMBIGUOUS",
                                "Signals conflict or are insufficient: length and breadth hint disagree, or the scope is unclear.")
                        .build())
                .question("topology", Choice.builder()
                        .instructions("Pick the agent topology that should execute this research task.")
                        .option("SOLO",
                                "One agent answers directly; no decomposition or parallel work needed.")
                        .option("CHAIN",
                                "Sequential pipeline of dependent stages: plan, search, summarize, cite.")
                        .option("FAN_OUT",
                                "Independent parallel branches over aspects of one question, gathered at the end.")
                        .option("SUPERVISOR",
                                "A coordinator delegates to researcher, writer and critic roles and reviews their work.")
                        .build());

        if (includeReason) {
            request.question("reason", Choice.builder()
                    .instructions("Say why this research task is hard to classify.")
                    .option("MULTI_COMPONENT",
                            "The prompt asks several distinct sub-questions that may need different handling.")
                    .option("CONFLICTING_SIGNALS",
                            "Prompt length, breadth hint and deadline point to different complexity levels.")
                    .option("UNDERSPECIFIED",
                            "The task lacks the detail needed to size its scope; key constraints are missing.")
                    .option("BREADTH_LENGTH_MISMATCH",
                            "The breadth hint suggests wide parallel work but the prompt is narrow, or vice versa.")
                    .build());
        }

        SystemOneResponse response = client.systemOne(request.build());
        String rationaleCode = includeReason ? response.choiceValue("reason") : null;
        return new JevDecision(
                response.choiceValue("band"),
                response.choice("band").confidence(),
                response.choiceValue("topology"),
                response.choice("topology").confidence(),
                rationaleCode);
    }
}
