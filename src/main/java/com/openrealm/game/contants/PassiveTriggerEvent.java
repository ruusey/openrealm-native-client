package com.openrealm.game.contants;

/** When a PassiveAbility's trigger fires. */
public enum PassiveTriggerEvent {
    /** Incoming projectile struck self. */
    ON_PROJECTILE_HIT_SELF,
    /** Self fired a basic attack. */
    ON_BASIC_ATTACK,
    /** Self's basic-attack projectile struck an enemy; fires per impact, so multishot gets one per pellet. */
    ON_BULLET_HIT_ENEMY,
    /** Self cast any active ability. */
    ON_ABILITY_CAST,
    /** Self killed an enemy. */
    ON_KILL,
    /** Self took damage from any source. */
    ON_TAKE_DAMAGE,
    /** Self received heal from any source. */
    ON_HEAL_RECEIVED,
    /** Periodic — fires every {@code tickMs} (aura-style). */
    ON_TICK,
    /** Self HP crossed a band (fires once per crossing). */
    ON_HP_THRESHOLD,
    /** A party member cast an ability. */
    ON_ALLY_CAST,
    /** A party member got a kill nearby. */
    ON_ALLY_KILL,
    UNKNOWN;

    public static PassiveTriggerEvent parse(String s) {
        if (s == null) return UNKNOWN;
        try { return PassiveTriggerEvent.valueOf(s.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return UNKNOWN; }
    }
}
