package io.adaptiveagents.core.classify.jev;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * WireMock test for {@link TypeSafeJevDecider}: a real {@link TypeSafeClient} against a stubbed
 * {@code POST /v1/systemone} on a dynamic port (no Spring context, no live API). The request and
 * response JSON field names match the SDK's serialized shapes (state/model/questions with typed
 * choice questions; answers carrying type/choice/probabilities/confidence).
 */
class TypeSafeJevDeciderWireMockTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static WireMockServer wireMockServer;
    static TypeSafeJevDecider deciderWithReason;
    static TypeSafeJevDecider deciderWithoutReason;

    @BeforeAll
    static void setup() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
        // WireMock's Jetty resets HTTP/2-upgraded streams; pin HTTP/1.1 for the test transport
        // (the production client talks to the real TLS endpoint, which negotiates fine).
        HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        TypeSafeClient client = TypeSafeClient.builder()
                .baseUrl(wireMockServer.baseUrl())
                .apiKey("test-key")
                .defaultModel("jev-latest")
                .restClientBuilder(RestClient.builder()
                        .requestFactory(new JdkClientHttpRequestFactory(httpClient)))
                .build();
        deciderWithReason = new TypeSafeJevDecider(client, true);
        deciderWithoutReason = new TypeSafeJevDecider(client, false);
    }

    @AfterAll
    static void teardown() {
        wireMockServer.stop();
    }

    @BeforeEach
    void reset() {
        wireMockServer.resetAll();
    }

    private void stubSystemOne(String reasonAnswerJson, double injectionProbability) {
        String reasonEntry = reasonAnswerJson == null ? "" : """
                , "reason": %s""".formatted(reasonAnswerJson);
        wireMockServer.stubFor(post(urlEqualTo("/v1/systemone"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                        {
                          "model": "jev-latest",
                          "answers": {
                            "band": {"type": "choice", "choice": "AMBIGUOUS",
                                     "probabilities": {"AMBIGUOUS": 0.91, "CLEAR_SIMPLE": 0.05, "CLEAR_COMPLEX": 0.04},
                                     "confidence": 0.91},
                            "topology": {"type": "choice", "choice": "FAN_OUT",
                                         "probabilities": {"FAN_OUT": 0.87, "SOLO": 0.06, "CHAIN": 0.05, "SUPERVISOR": 0.02},
                                         "confidence": 0.87},
                            "injection": {"type": "noul", "noul": %s}%s
                          },
                          "usage": {"input_tokens": 120, "output_tokens": 40}
                        }
                        """.formatted(injectionProbability, reasonEntry))));
    }

    private LoggedRequest soleRequest() {
        List<LoggedRequest> requests = wireMockServer.findAll(postRequestedFor(urlEqualTo("/v1/systemone")));
        assertThat(requests).hasSize(1);
        return requests.get(0);
    }

    private List<String> criteriaOrder(JsonNode question) {
        List<String> options = new ArrayList<>();
        question.path("criteria").fieldNames().forEachRemaining(options::add);
        return options;
    }

    @Test
    void fullDecisionRequestAndMapping() throws Exception {
        stubSystemOne("""
                {"type": "choice", "choice": "CONFLICTING_SIGNALS",
                 "probabilities": {"CONFLICTING_SIGNALS": 0.75, "MULTI_COMPONENT": 0.15, "UNDERSPECIFIED": 0.06, "BREADTH_LENGTH_MISMATCH": 0.04},
                 "confidence": 0.75}""", 0.12);

        JevDecision decision = deciderWithReason.decide("Research X. Breadth hint: 2.");

        assertThat(decision).isEqualTo(new JevDecision(
                "AMBIGUOUS", 0.91, "FAN_OUT", 0.87, 0.12, "CONFLICTING_SIGNALS"));

        LoggedRequest request = soleRequest();
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer test-key");

        JsonNode body = MAPPER.readTree(request.getBodyAsString());
        assertThat(body.path("state").asText()).isEqualTo("Research X. Breadth hint: 2.");
        assertThat(body.path("model").asText()).isEqualTo("jev-latest");

        JsonNode questions = body.path("questions");
        assertThat(questions).hasSize(4);

        JsonNode band = questions.path("band");
        assertThat(band.path("type").asText()).isEqualTo("choice");
        assertThat(band.path("instructions").asText()).isNotBlank();
        assertThat(criteriaOrder(band)).containsExactly("CLEAR_SIMPLE", "CLEAR_COMPLEX", "AMBIGUOUS");

        JsonNode topology = questions.path("topology");
        assertThat(topology.path("type").asText()).isEqualTo("choice");
        assertThat(topology.path("instructions").asText()).isNotBlank();
        assertThat(criteriaOrder(topology)).containsExactly("SOLO", "CHAIN", "FAN_OUT", "SUPERVISOR");

        JsonNode injection = questions.path("injection");
        assertThat(injection.path("type").asText()).isEqualTo("noul");
        assertThat(injection.path("instructions").asText()).isEqualTo(TypeSafeJevDecider.INJECTION_QUESTION);
        assertThat(injection.has("criteria")).isFalse();

        JsonNode reason = questions.path("reason");
        assertThat(reason.path("type").asText()).isEqualTo("choice");
        assertThat(reason.path("instructions").asText()).isNotBlank();
        assertThat(criteriaOrder(reason)).containsExactly(
                "MULTI_COMPONENT", "CONFLICTING_SIGNALS", "UNDERSPECIFIED", "BREADTH_LENGTH_MISMATCH");

        // Every criterion carries non-empty text, not just an option key.
        for (String question : List.of("band", "topology", "reason")) {
            questions.path(question).path("criteria")
                    .forEach(option -> assertThat(option.asText()).isNotBlank());
        }
    }

    @Test
    void reasonQuestionOmittedWhenDisabled() throws Exception {
        stubSystemOne(null, 0.83);

        JevDecision decision = deciderWithoutReason.decide("Research Y. Breadth hint: 3.");

        assertThat(decision.band()).isEqualTo("AMBIGUOUS");
        assertThat(decision.bandConfidence()).isEqualTo(0.91);
        assertThat(decision.topology()).isEqualTo("FAN_OUT");
        assertThat(decision.topologyConfidence()).isEqualTo(0.87);
        assertThat(decision.injectionSuspicion()).isEqualTo(0.83);
        assertThat(decision.rationaleCode()).isNull();

        JsonNode questions = MAPPER.readTree(soleRequest().getBodyAsString()).path("questions");
        assertThat(questions).hasSize(3);
        assertThat(questions.has("reason")).isFalse();
        assertThat(questions.path("injection").path("type").asText()).isEqualTo("noul");
        assertThat(criteriaOrder(questions.path("band"))).containsExactly("CLEAR_SIMPLE", "CLEAR_COMPLEX", "AMBIGUOUS");
        assertThat(criteriaOrder(questions.path("topology"))).containsExactly("SOLO", "CHAIN", "FAN_OUT", "SUPERVISOR");
    }

    @Test
    void apiErrorPropagatesToClassifierFallbackPath() {
        wireMockServer.stubFor(post(anyUrl())
                .willReturn(aResponse().withStatus(500)));

        org.junit.jupiter.api.Assertions.assertThrows(
                Exception.class,
                () -> deciderWithReason.decide("Research Z. Breadth hint: 2."));
    }
}
