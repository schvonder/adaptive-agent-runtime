package io.adaptiveagents.core.skills;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillRegistryTest {

    @TempDir
    Path temp;

    @Test
    void loadsNestedSkillsInPathOrder() throws IOException {
        writeSkill("deep-research/SKILL.md", """
                ---
                name: deep-research
                description: Multi-source research method
                ---
                1. Decompose the question.
                2. Cross-check sources.
                """);
        writeSkill("nested/fact-checking/SKILL.md", """
                ---
                name: fact-checking
                description: Claim verification checklist
                ---
                Verify each claim against two independent sources.
                """);

        SkillRegistry registry = SkillRegistry.load(temp);

        assertThat(registry.skills()).hasSize(2);
        assertThat(registry.skills()).extracting(SkillDefinition::name)
                .containsExactly("deep-research", "fact-checking");
        SkillDefinition skill = registry.skills().get(1);
        assertThat(skill.description()).isEqualTo("Claim verification checklist");
        assertThat(skill.body()).startsWith("Verify each claim");
        assertThat(skill.source().toString()).endsWith("SKILL.md");
    }

    @Test
    void emptyFolderYieldsZeroSkills() throws IOException {
        Files.createDirectories(temp.resolve("empty"));

        assertThat(SkillRegistry.load(temp).skills()).isEmpty();
    }

    @Test
    void blankPathFailsFast() {
        assertThatThrownBy(() -> SkillRegistry.load(Path.of("")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void missingDirectoryFailsFast() {
        assertThatThrownBy(() -> SkillRegistry.load(temp.resolve("nope")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a readable directory");
    }

    @Test
    void duplicateNamesFailFast() throws IOException {
        writeSkill("one/SKILL.md", skill("dupe", "first"));
        writeSkill("two/SKILL.md", skill("dupe", "second"));

        assertThatThrownBy(() -> SkillRegistry.load(temp))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate skill name 'dupe'");
    }

    @Test
    void missingFrontmatterKeysFailFast() throws IOException {
        writeSkill("bad/SKILL.md", """
                ---
                name: no-description
                ---
                Body without a description key.
                """);

        assertThatThrownBy(() -> SkillRegistry.load(temp))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'name:' and 'description:'");
    }

    @Test
    void blankBodyFailsFast() throws IOException {
        writeSkill("bad/SKILL.md", """
                ---
                name: empty-skill
                description: Body is blank
                ---

                """);

        assertThatThrownBy(() -> SkillRegistry.load(temp))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty instruction body");
    }

    @Test
    void unclosedFrontmatterFailsFast() throws IOException {
        writeSkill("bad/SKILL.md", """
                ---
                name: unclosed
                description: Fence never closes
                Body that never starts.
                """);

        assertThatThrownBy(() -> SkillRegistry.load(temp))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("never closed");
    }

    @Test
    void unparsableFrontmatterLineFailsFast() throws IOException {
        writeSkill("bad/SKILL.md", """
                ---
                name: broken
                description: Line below has no colon separator
                not a key value line
                ---
                Body.
                """);

        assertThatThrownBy(() -> SkillRegistry.load(temp))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unparsable frontmatter line");
    }

    private void writeSkill(String relative, String content) throws IOException {
        Path file = temp.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static String skill(String name, String description) {
        return "---\nname: " + name + "\ndescription: " + description + "\n---\nInstructions for " + name + ".\n";
    }
}
