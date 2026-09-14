package com.openrealm.game.contants;

import java.util.HashMap;
import java.util.Map;

/**
 * Projectile movement-behavior flags. NOT on-hit effects - shares its numeric
 * ID space with {@link StatusEffectType}; never mix the two enums.
 */
public enum ProjectileFlag {
    PLAYER_PROJECTILE((short) 10),
    PARAMETRIC((short) 12),
    INVERTED_PARAMETRIC((short) 13),
    ORBITAL((short) 20),
    ARMOR_PIERCING((short) 23),
    /** Projectile passes through walls and collision tiles without being destroyed. */
    PASS_THROUGH_TERRAIN((short) 24),
    /** Damages each enemy overlapped and keeps flying; per-enemy de-dup via Realm.hasHitEnemy(). */
    PASS_THROUGH_ENEMIES((short) 25),
    /** Line/wall: extends length px perpendicular to facing, size = thickness; static when magnitude is 0. */
    LINE_SEGMENT((short) 30),
    /** Re-positions to its source entity each tick (a wall that tracks a boss). */
    ANCHORED((short) 31),
    /** Speed eases magnitude -> 0 over lifetimeTicks (frequency = curve sharpness). */
    SPEED_DECAY((short) 32),
    /** Speed eases 0 -> magnitude over lifetimeTicks (frequency = curve sharpness). */
    SPEED_RAMP((short) 33),
    /** Homing: steers toward targetEntityId each tick, capped by frequency deg/tick. */
    HOMING((short) 34),
    /** Invisible instant cleaving AoE at the cursor; client plays the swing animation, not a sprite. */
    MELEE_SWING((short) 40),
    /** Server doubles final damage on hit; inert client-side. */
    CRITICAL((short) 50);

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
