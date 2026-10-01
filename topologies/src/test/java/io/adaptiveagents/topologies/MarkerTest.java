package io.adaptiveagents.topologies;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
public class MarkerTest {
    @Test
    public void testMarker() {
        assertEquals("topologies", Marker.NAME);
    }
}
