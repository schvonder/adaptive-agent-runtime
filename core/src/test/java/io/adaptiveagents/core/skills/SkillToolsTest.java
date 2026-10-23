package io.adaptiveagents.core.skills;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillToolsTest {

    @TempDir
    Path temp;

    @Test
    void catalogListsNameAndDescriptionPerLine() throws IOException {
        writeSkills(
                """
                ---
                name: deep-research
                description: Multi-source research method
                ---
                Body A.""",
                """
                ---
                name: fact-checking
                description: Claim verification checklist
                ---
                Body B.""");

        String catalog = new SkillTools(SkillRegistry.load(temp)).list_skills();

        assertThat(catalog).isEqualTo(
                "deep-research — Multi-source research method\n"
                + "fact-checking — Claim verification checklist");
    }

    @Test
    void emptyCatalogSaysNoSkillsConfigured() throws IOException {
        Files.createDirectories(temp.resolve("empty"));

        assertThat(new SkillTools(SkillRegistry.load(temp)).list_skills())
                .isEqualTo("no skills configured");
    }

    @Test
    void loadSkillReturnsBodyAndTrimsName() throws IOException {
        writeSkills("""
                ---
                name: deep-research
                description: Multi-source research method
                ---
                1. Decompose the question.""");

        String body = new SkillTools(SkillRegistry.load(temp)).load_skill(" deep-research ");

        assertThat(body).isEqualTo("1. Decompose the question.");
    }

    @Test
    void unknownSkillReturnsHint() throws IOException {
        writeSkills("""
                ---
                name: deep-research
                description: Multi-source research method
                ---
                Body.""");

        String result = new SkillTools(SkillRegistry.load(temp)).load_skill("missing");

        assertThat(result).contains("unknown skill 'missing'").contains("list_skills");
    }

    private void writeSkills(String... contents) throws IOException {
        int index = 0;
        for (String content : contents) {
            Path file = temp.resolve("skill-" + (index++) + "/SKILL.md");
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        }
    }
}
