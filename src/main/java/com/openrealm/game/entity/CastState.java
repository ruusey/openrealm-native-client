package com.openrealm.game.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Runtime state of an in-progress ability cast; null when not casting. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CastState {
    private int abilityId;
    private int slot;
    private long startTickMs;
    private long endTickMs;
    /** World-space target (ground-targeted abilities). 0/0 for non-targeted. */
    private float worldTargetX;
    private float worldTargetY;
    private boolean cancelOnDamage;
}
