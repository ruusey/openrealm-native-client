package com.openrealm.game.state;

/** One sim-tick of input, captured at send-time so reconciler replay matches
 *  what the client originally simulated. */
final class PendingInput {
    final int seq;
    final float vx, vy, basePxPerTick;
    /** basePxPerTick EXCLUDES the SLOWED 0.5 factor: replay ORs slowed/paralyzed
     *  with the player's current effect state and applies the factor fresh, so
     *  baking it in would double-count. */
    final boolean slowed, paralyzed;
    PendingInput(int seq, float vx, float vy, float basePxPerTick, boolean slowed, boolean paralyzed) {
        this.seq = seq; this.vx = vx; this.vy = vy; this.basePxPerTick = basePxPerTick;
        this.slowed = slowed; this.paralyzed = paralyzed;
    }
}
