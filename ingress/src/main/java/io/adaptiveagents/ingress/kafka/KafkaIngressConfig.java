package io.adaptiveagents.ingress.kafka;

import io.adaptiveagents.core.progress.ProgressService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiveagents.ingress.rest.TaskIngressService;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Kafka ingress assembly, entirely gated behind {@code runtime.kafka.enabled=true} (absent by
 * default): the request/reply listener, the progress-topic outbox tail publisher, the shared
 * producer/consumer infrastructure and the listener container factory register ONLY when the
 * flag is set. With the flag off the application is Kafka-free — no listener containers start
 * and no broker connections are made, so the app e2e suite (which runs without Kafka) stays
 * green. Deployments opt in via configuration (docker-compose in todo 29; integration tests).
 *
 * <p>Boot 4.1 no longer ships a Kafka auto-configuration on this module's classpath, so the
 * canonical {@link ConsumerFactory}/{@link ProducerFactory}/{@link KafkaTemplate} beans are
 * declared here (still spring-kafka APIs only, no extra dependencies). Wire format is plain
 * strings end to end (StringSerializer/StringDeserializer): payloads are JSON documents
 * serialized by the shared ObjectMapper, so no JSON component schemas are needed on the
 * broker side. {@code auto.offset.reset} defaults to {@code earliest} so un-consumed request
 * records are redelivered after downtime — duplicates are absorbed by the store's idempotent
 * submitOrGet (T18).
 *
 * <p>Topics:
 * <ul>
 *   <li>{@code adaptive-tasks-request} — consumed; the JSON body is the CANONICAL source for
 *       BOTH {@code task_id} and {@code forced_topology}; the optional {@code X-Task-Id}
 *       record header is tracing correlation only, never identity (docs/ENVELOPE.md).</li>
 *   <li>{@code adaptive-tasks-reply} — immediate post-submission ACK via {@code @SendTo}.</li>
 *   <li>{@code adaptive-tasks-progress} — live tail of {@code progress_events}.</li>
 *   <li>{@code adaptive-tasks-request-dlt} — raw poison envelopes kept for inspection.</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(name = "runtime.kafka.enabled", havingValue = "true")
@EnableKafka
public class KafkaIngressConfig {

    @Bean
    ConsumerFactory<Object, Object> consumerFactory(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            @Value("${spring.kafka.consumer.auto-offset-reset:earliest}") String autoOffsetReset) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    ProducerFactory<Object, Object> producerFactory(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    KafkaTemplate<Object, Object> kafkaTemplate(ProducerFactory<Object, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    /**
     * Reply-capable container factory (String keys/values both ways). Declared under this
     * conditional configuration so no listener infrastructure exists when the ingress is
     * disabled; the reply template carries the {@code @SendTo} ACKs of {@link KafkaTaskListener}.
     */
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<Object, Object> consumerFactory,
            KafkaTemplate<Object, Object> replyTemplate) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setReplyTemplate(replyTemplate);
        return factory;
    }

    @Bean
    KafkaTaskListener kafkaTaskListener(
            TaskIngressService taskIngressService,
            KafkaTemplate<Object, Object> kafkaTemplate,
            ObjectMapper objectMapper) {
        return new KafkaTaskListener(taskIngressService, kafkaTemplate, objectMapper);
    }

    @Bean
    ProgressTopicPublisher progressTopicPublisher(
            ProgressService progressService,
            KafkaTemplate<Object, Object> kafkaTemplate,
            ObjectMapper objectMapper) {
        return new ProgressTopicPublisher(progressService, kafkaTemplate, objectMapper);
    }
}
