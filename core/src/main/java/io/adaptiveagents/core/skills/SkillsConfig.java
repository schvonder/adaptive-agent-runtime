package io.adaptiveagents.core.skills;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the skills subsystem when {@code runtime.skills.path} is set. The folder is scanned
 * once at boot; malformed or duplicate skills fail the startup with an explicit message.
 * Without the property no bean exists and every topology runs exactly as before
 * (hermetic default).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("runtime.skills.path")
public class SkillsConfig {

    @Bean
    public SkillRegistry skillRegistry(@Value("${runtime.skills.path}") String path) {
        return SkillRegistry.load(Path.of(path.trim()));
    }

    @Bean
    public SkillTools skillTools(SkillRegistry skillRegistry) {
        return new SkillTools(skillRegistry);
    }
}
