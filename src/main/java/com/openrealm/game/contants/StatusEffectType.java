package com.openrealm.game.contants;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * On-hit / ability status effects. Shares its numeric ID space with
 * {@link ProjectileFlag} (movement flags) - never mix the two enums.
 */
public enum StatusEffectType {
    HIDDEN((short) 0),
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
    /** Tunnel-vision debuff - client clamps visible radius to ~3 tiles while active. */
    BLIND((short) 28),
    /** Anti-debuff bubble — new debuff applications are silently dropped. */
    WARDED((short) 29),
    /** MP regen runs at 2x speed while active. */
    MANA_FOUNT((short) 30),
    /** Incoming debuff durations doubled. */
    VULNERABLE((short) 31),
    /** Movement lock - implicit SLOWED + dash/teleport veto. */
    GROUNDED((short) 32),
    /** Trickster passive marker - boosts loot-upgrade chance on kill. */
    MARKED_FOR_LOOT((short) 33),
    /** Guiding Light aura, STR half; paired with EMPOWERED_DEX. */
    EMPOWERED_STR((short) 34),
    /** Guiding Light aura, DEX half; paired with EMPOWERED_STR. */
    EMPOWERED_DEX((short) 35),
    /** Bleed DoT. */
    BLEEDING((short) 36),
    /** Attack-speed buff, distinct from BERSERK. */
    FURY((short) 37),
    /** Source-scoped vulnerability - extra damage from the caster's party only. */
    WITHER((short) 38),
    /** Attacker marker: basic attacks apply POISONED + a poison DoT; carrier takes no self damage. */
    IMBUED_POISON((short) 39),
    /** Evasion buff (Ninja Smokebomb). */
    DODGE((short) 40),
    /** Holder's projectiles instantly kill non-invincible enemies. */
    INSTAKILL((short) 41),
    /** Soul Drain self-buff - bi-directional HP<->MP transfer, caster-only. */
    SACRIFICE((short) 42);

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
