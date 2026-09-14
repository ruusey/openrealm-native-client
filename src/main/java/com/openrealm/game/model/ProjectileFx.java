package com.openrealm.game.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Fields read depend on type: spin (mode "continuous"=override flight angle /
 * "additive"; rate rad/s; dir CW|CCW); trail (rate per s); muzzle/impact (count, speed).
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
