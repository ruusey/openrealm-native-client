package com.openrealm.game.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.openrealm.game.data.RadianAngleDeserializer;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AttackPattern {
    private int projectileGroupId;
    @Builder.Default
    private int cooldownMs = 1000;
    @Builder.Default
    private int burstCount = 1;
    @Builder.Default
    private int burstDelayMs = 100;
    // Radian angle fields accept a number or a {{PI/Y}} unit-circle placeholder.
    @Builder.Default
    @JsonDeserialize(using = RadianAngleDeserializer.class)
    private float angleOffsetPerBurst = 0f;
    @Builder.Default
    private float minRange = 0f;
    @Builder.Default
    private float maxRange = 9999f;
    @Builder.Default
    private boolean predictive = false;
    @Builder.Default
    private String aimMode = "PLAYER";
    @Builder.Default
    private int shotCount = 1;
    @Builder.Default
    @JsonDeserialize(using = RadianAngleDeserializer.class)
    private float spreadAngle = 0f;
    @Builder.Default
    @JsonDeserialize(using = RadianAngleDeserializer.class)
    private float fixedAngle = 0f;
    @Builder.Default
    private boolean mirror = false;
    @Builder.Default
    private int sourceNoise = 0;

    // Spiral: angle added to base angle each time this attack fires (accumulates over firings)
    @Builder.Default
    @JsonDeserialize(using = RadianAngleDeserializer.class)
    private float angleIncrementPerFiring = 0f;

    // Speed stacking: fire multiple bullets at same angle with different speeds
    @Builder.Default
    private int speedCount = 1;
    @Builder.Default
    private float minSpeedMult = 1.0f;
    @Builder.Default
    private float maxSpeedMult = 1.0f;
}
