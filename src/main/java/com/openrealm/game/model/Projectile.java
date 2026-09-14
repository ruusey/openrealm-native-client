package com.openrealm.game.model;

import java.util.List;

import com.openrealm.game.contants.ProjectilePositionMode;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Projectile {
    private int projectileId;
    private ProjectilePositionMode positionMode;
    private String angle;
    private short range;
    private float magnitude;
    private short size;
    private short damage;

    private short amplitude;
    private short frequency;

    // LINE_SEGMENT span (thickness is `size`); forced-expiry tick count (walls/homing)
    private short length;
    private int lifetimeTicks;

    // spawn offset relative to enemy center, rotated by firing angle
    private float spawnOffsetX;
    private float spawnOffsetY;

    private int spawnDelayMs;

    // ProjectileFlag IDs (behavior, NOT status): PLAYER_PROJECTILE=10, PARAMETRIC=12, INVERTED_PARAMETRIC=13, ORBITAL=20
    private List<Short> flags;
    // on-hit status effects (NOT behavior flags)
    private List<ProjectileEffect> effects;

    // rotateDir "CW"/"CCW"; rotateRate rad/tick, 0 = derive from magnitude
    private boolean rotate;
    private float rotateRate;
    private String rotateDir;

    public boolean hasFlag(short flag) {
        return (this.flags != null) && this.flags.contains(flag);
    }

}
