package io.adaptiveagents.core.skills;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.ai.tool.annotation.Tool;

/**
 * Spring AI tool surface for the skill catalog: {@code list_skills} exposes the catalog
 * (name — description per line), {@code load_skill(name)} returns the skill's full instruction
 * body for the model to follow. Attached to every topology when {@code runtime.skills.path}
 * is configured.
 */
public final class SkillTools {

    private final List<SkillDefinition> skills;
    private final Map<String, SkillDefinition> byName;

    public SkillTools(SkillRegistry registry) {
        this.skills = registry.skills();
        this.byName = skills.stream()
                .collect(Collectors.toUnmodifiableMap(SkillDefinition::name, Function.identity()));
    }

    @Tool(name = "list_skills",
            description = "List the available skills as 'name — description' lines. "
                    + "Call this first to discover which skills apply to the task.")
    public String list_skills() {
        if (skills.isEmpty()) {
            return "no skills configured";
        }
        StringBuilder catalog = new StringBuilder();
        for (SkillDefinition skill : skills) {
            catalog.append(skill.name()).append(" — ").append(skill.description()).append('\n');
        }
        return catalog.toString().stripTrailing();
    }

    @Tool(name = "load_skill",
            description = "Load the full instruction body of one skill by its exact name from "
                    + "list_skills, then follow the loaded instructions.")
    public String load_skill(String name) {
        String key = name == null ? "" : name.strip();
        SkillDefinition skill = byName.get(key);
        if (skill == null) {
            return "unknown skill '" + name + "'; call list_skills for the exact names";
        }
        return skill.body();
    }
}
