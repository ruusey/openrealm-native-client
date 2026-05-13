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
}
