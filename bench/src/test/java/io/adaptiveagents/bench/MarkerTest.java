package io.adaptiveagents.bench;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
public class MarkerTest {
    @Test
    public void testMarker() {
        assertEquals("bench", Marker.NAME);
    }
}
