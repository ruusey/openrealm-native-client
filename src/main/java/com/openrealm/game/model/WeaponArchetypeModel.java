package com.openrealm.game.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Native-client mirror of the server's WeaponArchetypeModel — drives the
 * client-side shot prediction (PlayState.shoot) so the predicted bullet
 * shape matches the server's authoritative spawn exactly. Loaded from
 * weapon-archetypes.json via GameDataManager and indexed by archetype id.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class WeaponArchetypeModel {
    private byte id;
    private String name;
    private String family;

    @Builder.Default
    private byte scalingStat = 4;

    @Builder.Default
    private float attackSpeedMul = 1.0f;
    @Builder.Default
    private float damageMul = 1.0f;
    @Builder.Default
    private float rangeMul = 1.0f;
    @Builder.Default
    private boolean piercing = false;
    @Builder.Default
    private int projectileCount = 1;
    @Builder.Default
    private float spreadRad = 0.10f;
}
