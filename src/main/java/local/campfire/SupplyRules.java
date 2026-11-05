package local.campfire;

/** One server-wide roll, not one roll per player. */
final class SupplyRules {
    enum Prize { DIAMOND, WOOD_SWORD, IRON_SWORD, TOOLS, ARMOR, JACKPOT }
    static Prize roll(int value) {
        if (value < 0 || value >= 100) throw new IllegalArgumentException("roll must be 0..99");
        if (value < 30) return Prize.DIAMOND;
        if (value < 50) return Prize.WOOD_SWORD;
        if (value < 66) return Prize.IRON_SWORD;
        if (value < 90) return Prize.TOOLS;
        if (value < 98) return Prize.ARMOR;
        return Prize.JACKPOT;
    }
    static int charges(Prize prize) {
        return switch (prize) { case DIAMOND -> 0; case WOOD_SWORD -> 32; case IRON_SWORD -> 24; case TOOLS -> 96; case ARMOR -> 24; case JACKPOT -> 12; };
    }
    static int waveSize(int wave, int players) {
        if (wave < 1 || wave > 3) throw new IllegalArgumentException("wave must be 1..3");
        int team = Math.clamp(players, 2, 4);
        return wave == 3 ? 3 : team + wave;
    }
}
