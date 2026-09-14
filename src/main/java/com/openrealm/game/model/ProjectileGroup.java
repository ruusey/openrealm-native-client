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
    // legacy spin flag; superseded by fx (spin is read from there now)
    private boolean spinning;
    // legacy "#RRGGBB" afterimage trail tint; null disables
    private String trailColor;
    private List<ProjectileFx> fx;
}
