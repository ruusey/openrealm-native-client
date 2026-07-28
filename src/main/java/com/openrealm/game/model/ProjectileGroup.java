package com.openrealm.game.model;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = false)
public class ProjectileGroup extends SpriteModel {
    private int projectileGroupId;
    private List<Projectile> projectiles;
    /** When true, the renderer overrides flight-angle rotation with a
     *  continuous time-based spin so the projectile sprite reads as a
     *  spinning blade rather than an arrow. Used by shuriken groups
     *  1000-1005 (Ninja Star Throw + Blade abilities). */
    private boolean spinning;
    /** Optional "#RRGGBB" tint. When set, the renderer draws a short fading
     *  afterimage trail in this colour behind the projectile (e.g. Trapper
     *  Tar Shot's sticky black streak). Null disables the trail. */
    private String trailColor;
    /** Data-driven projectile FX (spin, trail, muzzle, impact). The legacy
     *  {@link #spinning} + per-projectile rotate fields were migrated into this
     *  list; spin is read from here now. See {@link ProjectileFx}. */
    private List<ProjectileFx> fx;
}
