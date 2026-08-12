package com.openrealm.game.state;

/** One sim-tick worth of input + the per-tick step magnitude that
 *  was active when the input was sent. Captured at send-time so the
 *  reconciler replay is exactly what the client originally simulated
 *  (spd stat / SPEEDY effect could otherwise change between send and
 *  ack). */
final class PendingInput {
    final int seq;
    final float vx, vy, basePxPerTick;
    /** SLOWED / PARALYZED at send time. Replay ORs each with the player's
     *  CURRENT effect state (webclient game.js simulateTick parity): an input
     *  sent while slowed/paralyzed — OR replayed while slowed/paralyzed now —
     *  moves at half speed / stays frozen. basePxPerTick EXCLUDES the SLOWED
     *  0.5 factor so it's applied fresh from the OR'd state and never double-
     *  counted; SPEEDY stays baked into base (webclient reads it from the
     *  snapshot only, so send-time capture is correct). */
    final boolean slowed, paralyzed;
    PendingInput(int seq, float vx, float vy, float basePxPerTick, boolean slowed, boolean paralyzed) {
        this.seq = seq; this.vx = vx; this.vy = vy; this.basePxPerTick = basePxPerTick;
        this.slowed = slowed; this.paralyzed = paralyzed;
    }
}
