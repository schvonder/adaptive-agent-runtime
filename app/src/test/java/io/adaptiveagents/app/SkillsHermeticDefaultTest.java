package io.adaptiveagents.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.adaptiveagents.core.skills.SkillTools;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Hermetic-default contract of the skills subsystem: without {@code runtime.skills.path}
 * no {@link SkillTools} bean exists and no topology attaches {@code list_skills}/{@code load_skill}
 * — the model calls are built exactly as before.
 */
@SpringBootTest(properties = {
    "spring.main.web-application-type=none",
    "spring.ai.model.chat=none",
    "spring.datasource.url=jdbc:postgresql://localhost:5432/postgres",
    "spring.datasource.username=postgres",
    "spring.datasource.password=postgres",
    "spring.datasource.driver-class-name=org.postgresql.Driver",
    // DB-less context smoke: without this the flyway initializer eagerly opens a connection.
    "spring.flyway.enabled=false"
})
@ActiveProfiles({"stub-classifier", "fixture-model"})
class SkillsHermeticDefaultTest {

    @Autowired
    ObjectProvider<SkillTools> skillTools;

    @Test
    void noSkillsPathExposesNoSkillTools() {
        assertThat(skillTools.getIfAvailable()).isNull();
    }
}
