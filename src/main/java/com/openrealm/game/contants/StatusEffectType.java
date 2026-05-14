package com.openrealm.game.contants;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Status effects applied to entities (players, enemies) on-hit or from abilities.
 *
 * Applied via {@code entity.addEffect(type, duration)}. Stored in
 * {@code Projectile.effects} as {@link com.openrealm.game.model.ProjectileEffect}.
 *
 * Projectile behavior flags (stored in {@code Projectile.flags} /
 * {@code Bullet.flags}) live in {@link ProjectileFlag} and use the same
 * numeric ID space — never mix the two enums.
 */
public enum StatusEffectType {
    INVISIBLE((short) 0),
    HEALING((short) 1),
    PARALYZED((short) 2),
    STUNNED((short) 3),
    SPEEDY((short) 4),
    HEAL((short) 5),
    INVINCIBLE((short) 6),
    NONE((short) 8),
    TELEPORT((short) 9),
    DAZED((short) 11),
    DAMAGING((short) 14),
    STASIS((short) 15),
    CURSED((short) 16),
    POISONED((short) 17),
    ARMORED((short) 18),
    BERSERK((short) 19),
    SLOWED((short) 21),
    ARMOR_BROKEN((short) 22),
    TAUNT_TARGET((short) 23),
    BRACED((short) 24),
    PROTECTED((short) 25),
    PHALANX_DOME((short) 26),
    /** Weakens outgoing damage by 35% for the duration. */
    WEAKEN((short) 27),
    /** Tunnel-vision debuff — client-side renderer clamps visible radius to
     *  ~3 tiles around the local player while active. */
    BLIND((short) 28),
    /** Anti-debuff bubble — new debuff applications are silently dropped. */
    WARDED((short) 29),
    /** Mana regen amplifier — MP regen runs at 2× speed while active. */
    MANA_FOUNT((short) 30),
    /** Debuff amplifier — incoming debuff durations doubled. */
    VULNERABLE((short) 31),
    /** Movement lock — implicit SLOWED + dash/teleport ability veto. */
    GROUNDED((short) 32),
    /** Trickster passive marker — boosts loot-upgrade chance on kill. */
    MARKED_FOR_LOOT((short) 33),
    /** Heavy Buffer "Guiding Light" aura — ATT half. Server-authoritative
     *  magnitude (caster WIS/5). Paired with EMPOWERED_DEX. */
    EMPOWERED_ATT((short) 34),
    /** Heavy Buffer "Guiding Light" aura — DEX half. Always applied with
     *  EMPOWERED_ATT so the player sees two distinct icons above their head. */
    EMPOWERED_DEX((short) 35);

    public static Map<Short, StatusEffectType> map = new HashMap<>();
    static {
        for (StatusEffectType e : StatusEffectType.values()) {
            map.put(e.effectId, e);
        }
    }

    public short effectId;

    StatusEffectType(short effectId) {
        this.effectId = effectId;
    }

    @JsonCreator
    public static StatusEffectType valueOf(short effectId) {
        return map.get(effectId);
    }
}
