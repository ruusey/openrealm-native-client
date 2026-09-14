package com.openrealm.game.model.ability;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One effect on an Ability or PassiveAbility trigger. Type parsing is
 * case-insensitive. Recognized types: PROJECTILE_GROUP, STATUS_APPLY, HEAL,
 * SHIELD, TELEPORT, REFLECT_PROJECTILE, EMPOWER_NEXT_BASIC.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AbilityEffect {
    private String type;
    /** projectile-groups.json id (PROJECTILE_GROUP / TELEPORT). */
    private int projectileGroupId = -1;
    /** Symmetric fan spread in radians per extra bullet. */
    private float fanSpread = 0f;
    private float originOffset = 0f;
    /** Name matching StatusEffectType enum. */
    private String statusId;
    private long baseDurationMs = 0L;
    /** SELF | ENEMIES_HIT | ALLIES_HIT | TARGET. */
    private String target;
    private int baseMagnitude = 0;
    /** REFLECT_PROJECTILE base reflected-damage multiplier. */
    private float damageMul = 1.0f;
}
