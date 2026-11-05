package local.campfire;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SupplyRulesTest {
    @Test void exactDistributionHasOnlyTwoPercentJackpots() {
        int[] counts = new int[6];
        for (int i = 0; i < 100; i++) counts[SupplyRules.roll(i).ordinal()]++;
        assertArrayEquals(new int[]{30,20,16,24,8,2}, counts);
    }
    @Test void rareStrongestSwordHasShortestLifetime() {
        assertEquals(32, SupplyRules.charges(SupplyRules.Prize.WOOD_SWORD));
        assertEquals(24, SupplyRules.charges(SupplyRules.Prize.ARMOR));
        assertEquals(12, SupplyRules.charges(SupplyRules.Prize.JACKPOT));
    }
    @Test void wavesScaleAndAreBoundedForFourPlayers() {
        assertEquals(3, SupplyRules.waveSize(1, 2));
        assertEquals(6, SupplyRules.waveSize(2, 4));
        assertEquals(3, SupplyRules.waveSize(3, 4));
        assertEquals(6, SupplyRules.waveSize(2, 100));
    }
    @Test void invalidRollsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> SupplyRules.roll(-1));
        assertThrows(IllegalArgumentException.class, () -> SupplyRules.roll(100));
        assertThrows(IllegalArgumentException.class, () -> SupplyRules.waveSize(0, 3));
    }
}
