package io.adaptiveagents.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "spring.main.web-application-type=none",
    "spring.datasource.url=jdbc:postgresql://localhost:5432/postgres",
    "spring.datasource.username=postgres",
    "spring.datasource.password=postgres",
    "spring.datasource.driver-class-name=org.postgresql.Driver"
})
public class ApplicationTest {
    @Test
    public void contextLoads() {
        // Assert context loads successfully
    }
}
