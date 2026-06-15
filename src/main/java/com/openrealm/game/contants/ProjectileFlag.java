package com.openrealm.game.contants;

import java.util.HashMap;
import java.util.Map;

/**
 * Flags that control projectile movement behavior. Stored in the projectile's
 * {@code flags} list. These are NOT on-hit effects — see {@link StatusEffectType}
 * for status effects applied when a projectile hits a target.
 */
public enum ProjectileFlag {
    PLAYER_PROJECTILE((short) 10),
    PARAMETRIC((short) 12),
    INVERTED_PARAMETRIC((short) 13),
    ORBITAL((short) 20),
    ARMOR_PIERCING((short) 23),
    /** Projectile passes through walls and collision tiles without being destroyed. */
    PASS_THROUGH_TERRAIN((short) 24),
    /**
     * Projectile pierces enemies — applies damage to each enemy it overlaps and
     * keeps flying. Per-enemy de-dup is still enforced via Realm.hasHitEnemy()
     * so a single bullet can't damage the same enemy twice. Used for bows,
     * archer quivers, and knight stun shields.
     */
    PASS_THROUGH_ENEMIES((short) 25),
    /**
     * Line/wall projectile: extends {@code length} px perpendicular to its
     * facing angle, centered on its position, with {@code size} as thickness.
     * Rendered as a sprite tiled along the line. Travels along the angle at
     * magnitude (face-first); static when magnitude is 0. Collision/damage is
     * server-authoritative.
     */
    LINE_SEGMENT((short) 30),
    /** Re-positions to its source entity each tick (a wall that tracks a boss). */
    ANCHORED((short) 31),
    /** Speed eases magnitude -> 0 over lifetimeTicks (frequency = curve sharpness). */
    SPEED_DECAY((short) 32),
    /** Speed eases 0 -> magnitude over lifetimeTicks (frequency = curve sharpness). */
    SPEED_RAMP((short) 33),
    /** Homing: steers toward targetEntityId each tick, capped by frequency deg/tick. */
    HOMING((short) 34);

    public static final Map<Short, ProjectileFlag> map = new HashMap<>();
    static {
        for (ProjectileFlag f : ProjectileFlag.values()) {
            map.put(f.flagId, f);
        }
    }

    public final short flagId;

    ProjectileFlag(short flagId) {
        this.flagId = flagId;
    }

    public static ProjectileFlag valueOf(short flagId) {
        return map.get(flagId);
    }
}
