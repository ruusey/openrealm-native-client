package com.openrealm.net.realm;

import java.time.Instant;

class TrapState {
    /** Time between landing and the trap becoming dangerous. Without
     *  this gap, an enemy already standing where the throw lands would
     *  arm + trigger the trap on the same tick — the player saw enemies
     *  vanish without ever seeing the placed-trap or trigger visuals.
     *  500ms gives both visuals time to display and matches the
     *  expected "huntress sets trap, lures enemy in" gameplay loop. */
    static final long ARM_TIME_MS = 500;

    final long placeTime;   // when the trap was placed (after throw lands)
    final long armReadyTime;// when the trap becomes dangerous (placeTime + ARM_TIME_MS)
    final long expireTime;  // when the trap disappears if not triggered
    final long sourcePlayerId;
    final float x, y;
    final float triggerRadius; // enemies within this radius trigger the trap
    final short effectId;     // effect to apply (e.g., PARALYZED=2)
    final long effectDuration;
    final int damage;
    final byte tier;          // ability item tier — drives client tint
    boolean armed = false;    // becomes armed after throw lands
    boolean triggered = false;

    TrapState(long throwDelayMs, long sourcePlayerId, float x, float y,
              float triggerRadius, short effectId, long effectDuration, int damage,
              long lifetimeMs, byte tier) {
        this.placeTime = Instant.now().toEpochMilli() + throwDelayMs;
        this.armReadyTime = this.placeTime + ARM_TIME_MS;
        this.expireTime = this.placeTime + lifetimeMs;
        this.sourcePlayerId = sourcePlayerId;
        this.x = x;
        this.y = y;
        this.triggerRadius = triggerRadius;
        this.effectId = effectId;
        this.effectDuration = effectDuration;
        this.damage = damage;
        this.tier = tier;
    }

    boolean hasLanded() { return Instant.now().toEpochMilli() >= placeTime; }
    boolean isArmed()   { return Instant.now().toEpochMilli() >= armReadyTime; }
    boolean isExpired() { return Instant.now().toEpochMilli() >= expireTime; }
}
