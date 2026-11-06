package local.campfire;

/** One server-wide roll, not one roll per player. */
final class SupplyRules {
    enum Prize { DIAMOND, WOOD_SWORD, IRON_SWORD, TOOLS, ARMOR, JACKPOT }
    static Prize roll(int value) {
        if (value < 0 || value >= 100) throw new IllegalArgumentException("roll must be 0..99");
        if (value < 85) return Prize.DIAMOND;
        if (value < 90) return Prize.WOOD_SWORD;
        if (value < 95) return Prize.TOOLS;
        return Prize.ARMOR;
    }
    static int charges(Prize prize) { return -1; }
    static int waveSize(int wave, int players) {
        if (wave < 1 || wave > 3) throw new IllegalArgumentException("wave must be 1..3");
        int team = Math.clamp(players, 2, 4);
        return wave == 3 ? 3 : team + wave;
    }
}
