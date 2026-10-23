package io.adaptiveagents.core.skills;

import java.nio.file.Path;

/**
 * One parsed skill: {@code SKILL.md} frontmatter ({@code name}, {@code description}) plus the
 * markdown instruction body the model receives from the {@code load_skill} tool.
 */
public record SkillDefinition(String name, String description, String body, Path source) {
}
