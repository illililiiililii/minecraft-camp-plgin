package local.campfire;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProgressTest {
    @Test void contributionsAccumulateAndExcessIsNotConsumed() {
        Progress p = new Progress(0, 0);
        assertEquals(80, p.add(80));
        assertEquals(48, p.add(999));
        assertEquals(1, p.chapter());
        assertEquals(0, p.amount());
        assertEquals(64, p.remaining());
    }
    @Test void allChaptersFinishExactlyOnce() {
        Progress p = new Progress(0, 0);
        for (int i = 0; i < Progress.TARGETS.length; i++) {
            assertEquals(Progress.TARGETS[i], p.add(Integer.MAX_VALUE));
            assertEquals(i + 1, p.chapter());
        }
        assertTrue(p.finished());
        assertEquals(0, p.add(1));
        assertEquals(0, p.remaining());
    }
    @Test void restartPreservesPartialProgress() {
        Progress p = new Progress(3, 50);
        assertEquals(78, p.remaining());
        assertEquals(78, p.add(78));
        assertEquals(4, p.chapter());
    }
    @Test void rejectsCorruptSaves() {
        assertThrows(IllegalArgumentException.class, () -> new Progress(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Progress(9, 0));
        assertThrows(IllegalArgumentException.class, () -> new Progress(0, -1));
        assertThrows(IllegalArgumentException.class, () -> new Progress(0, 128));
        assertThrows(IllegalArgumentException.class, () -> new Progress(8, 1));
    }
    @Test void zeroAndNegativeContributionsDoNothing() {
        Progress p = new Progress(0, 10);
        assertEquals(0, p.add(-5));
        assertEquals(0, p.add(0));
        assertEquals(10, p.amount());
    }
}
