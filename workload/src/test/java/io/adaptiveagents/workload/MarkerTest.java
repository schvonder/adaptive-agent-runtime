package io.adaptiveagents.workload;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
public class MarkerTest {
    @Test
    public void testMarker() {
        assertEquals("workload", Marker.NAME);
    }
}
