package local.campfire;

/** Server-independent, bounded mission state. A single action can finish only one chapter. */
final class Progress {
    static final int[] TARGETS = {128, 64, 40, 128, 12, 16, 1, 1};
    private int chapter;
    private int amount;

    Progress(int chapter, int amount) {
        if (chapter < 0 || chapter > TARGETS.length || amount < 0
                || (chapter == TARGETS.length ? amount != 0 : amount >= TARGETS[chapter]))
            throw new IllegalArgumentException("Invalid saved mission progress");
        this.chapter = chapter;
        this.amount = amount;
    }
    int chapter() { return chapter; }
    int amount() { return amount; }
    boolean finished() { return chapter == TARGETS.length; }
    int remaining() { return finished() ? 0 : TARGETS[chapter] - amount; }
    int add(int offered) {
        if (offered <= 0 || finished()) return 0;
        int accepted = Math.min(offered, remaining());
        amount += accepted;
        if (amount == TARGETS[chapter]) { chapter++; amount = 0; }
        return accepted;
    }
}
