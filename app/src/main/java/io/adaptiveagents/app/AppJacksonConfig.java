package io.adaptiveagents.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the Jackson 2 {@link ObjectMapper} that the JSONB-centric core stores inject. Boot 4's
 * starter-json path auto-configures Jackson 3 instead, which these stores do not use — so the
 * mapper is declared explicitly (the same wiring every topologies test context uses).
 */
@Configuration
public class AppJacksonConfig {

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
