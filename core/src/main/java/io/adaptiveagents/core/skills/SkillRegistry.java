package io.adaptiveagents.core.skills;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Scans a skills folder once and returns the immutable skill catalog. A skill is any
 * {@code SKILL.md} under the folder (nested subfolders allowed), carrying a frontmatter block
 * ({@code ---} fenced) with non-empty {@code name:} and {@code description:} keys and a non-blank
 * markdown instruction body. Every violation — unreadable folder, unclosed frontmatter, missing
 * keys, empty body, duplicate names — fails fast with the offending file in the message.
 */
public final class SkillRegistry {

    private static final String SKILL_FILE_NAME = "SKILL.md";
    private static final String FRONTMATTER_FENCE = "---";

    private final List<SkillDefinition> skills;

    private SkillRegistry(List<SkillDefinition> skills) {
        this.skills = skills;
    }

    public static SkillRegistry load(Path root) {
        if (root == null || root.toString().isBlank()) {
            throw new IllegalStateException("runtime.skills.path is set but blank — unset it or point it at a skills folder");
        }
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("runtime.skills.path is not a readable directory: " + root);
        }
        Map<String, SkillDefinition> byName = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            List<Path> skillFiles = files
                    .filter(path -> path.getFileName().toString().equals(SKILL_FILE_NAME))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
            for (Path file : skillFiles) {
                SkillDefinition skill = parse(file);
                SkillDefinition previous = byName.putIfAbsent(skill.name(), skill);
                if (previous != null) {
                    throw new IllegalStateException(
                            "duplicate skill name '" + skill.name() + "': " + skill.source() + " and " + previous.source());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to scan skills folder " + root + ": " + e.getMessage(), e);
        }
        return new SkillRegistry(List.copyOf(byName.values()));
    }

    public List<SkillDefinition> skills() {
        return skills;
    }

    private static SkillDefinition parse(Path file) {
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read " + file + ": " + e.getMessage(), e);
        }
        String[] frontmatterAndBody = splitFrontmatter(content, file);
        Map<String, String> meta = parseKeyValueLines(frontmatterAndBody[0], file);
        String name = meta.getOrDefault("name", "").trim();
        String description = meta.getOrDefault("description", "").trim();
        String body = frontmatterAndBody[1].strip();
        if (name.isEmpty() || description.isEmpty()) {
            throw new IllegalStateException(
                    file + ": " + SKILL_FILE_NAME + " frontmatter requires non-empty 'name:' and 'description:'");
        }
        if (body.isEmpty()) {
            throw new IllegalStateException(file + ": " + SKILL_FILE_NAME + " has an empty instruction body");
        }
        return new SkillDefinition(name, description, body, file);
    }

    private static String[] splitFrontmatter(String content, Path file) {
        List<String> lines = content.lines().toList();
        if (lines.isEmpty() || !FRONTMATTER_FENCE.equals(lines.get(0).strip())) {
            return new String[] {"", content};
        }
        for (int i = 1; i < lines.size(); i++) {
            if (FRONTMATTER_FENCE.equals(lines.get(i).strip())) {
                return new String[] {
                        String.join("\n", lines.subList(1, i)),
                        String.join("\n", lines.subList(i + 1, lines.size()))
                };
            }
        }
        throw new IllegalStateException(file + ": " + SKILL_FILE_NAME + " frontmatter opened with --- but never closed");
    }

    private static Map<String, String> parseKeyValueLines(String frontmatter, Path file) {
        Map<String, String> meta = new LinkedHashMap<>();
        for (String line : frontmatter.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            int separator = line.indexOf(':');
            if (separator <= 0) {
                throw new IllegalStateException(
                        file + ": unparsable frontmatter line (expected 'key: value'): " + line.strip());
            }
            meta.put(line.substring(0, separator).strip(), line.substring(separator + 1).strip());
        }
        return meta;
    }
}
