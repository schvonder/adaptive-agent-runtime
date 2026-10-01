package io.adaptiveagents.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.main.web-application-type=none")
public class ApplicationTest {
    @Test
    public void contextLoads() {
        // Assert context loads successfully
    }
}
