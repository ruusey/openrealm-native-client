package com.openrealm.game.entity.item;

public enum GemEffectType {
    STAT_DELTA, STAT_SCALE, PROJECTILE_COUNT, PROJECTILE_DAMAGE,
    ON_HIT_EFFECT, LIFESTEAL, CRIT_CHANCE;

    public static GemEffectType fromOrdinal(int ord) {
        final GemEffectType[] vals = GemEffectType.values();
        if (ord < 0 || ord >= vals.length) return STAT_DELTA;
        return vals[ord];
    }
}
