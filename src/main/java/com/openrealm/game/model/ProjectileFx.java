package com.openrealm.game.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One entry in a {@link ProjectileGroup}'s {@code fx} list — a data-driven,
 * purely-visual projectile effect. All fields are optional; only those relevant
 * to {@link #type} are read:
 * <ul>
 *   <li>{@code spin}   — {@code mode} ("continuous"=override flight angle,
 *       "additive"=on top of it), {@code rate} (rad/s), {@code dir} (CW|CCW)</li>
 *   <li>{@code trail}  — {@code particle}, {@code color}, {@code rate} (per s),
 *       {@code lifeMs}, {@code size}, {@code spread}</li>
 *   <li>{@code muzzle}/{@code impact} — {@code particle}, {@code color},
 *       {@code count}, {@code speed}, {@code lifeMs}, {@code size}</li>
 * </ul>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ProjectileFx {
    private String type;
    private String mode;
    private String dir;
    private String particle;
    private String color;
    private Float rate;
    private Float size;
    private Float speed;
    private Float spread;
    private Integer lifeMs;
    private Integer count;
}
