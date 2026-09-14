package com.openrealm.game.model.ability;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A class-bound active ability, loaded from abilities.json. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Ability {
    private int id;
    private String name;
    private String description;
    private String iconKey;
    private int classId;
    /** Which hotbar slot (0..3) this is meant for; Q=0..R=3. */
    private int slotHint = 0;

    private int mpCost = 0;
    private long baseCooldownMs = 0L;
    /** 0 = instant; otherwise locks the caster for this duration. */
    private long baseCastMs = 0L;
    /** Movement-speed multiplier while casting (0..1); 0 = stand still. */
    private float castMovementSpeedMul = 0f;

    private List<AbilityEffect> effects = new ArrayList<>();
    private List<AbilityScaling> scalings = new ArrayList<>();
    private List<String> tags = new ArrayList<>();

    private String spriteKey;
    private int row;
    private int col;
    private int spriteSize;
    private int spriteHeight;

    /** When > 0, used as the bullet base damage; DAMAGE scalings add on top, STR is NOT auto-added. */
    private int baseDamage;

    private int maxSkillPoints = 5;

    /** Flat ms shaved off baseCooldownMs per invested skill point. */
    private int cdReductionPerPointMs = 0;

    /** Max cursor-to-caster distance (px). -1 unlimited, 0 self-only, &gt;0 clamp. */
    private int maxCastRange = -1;

    public List<AbilityEffect> effectList() {
        return this.effects == null ? new ArrayList<>() : this.effects;
    }

    public List<AbilityScaling> scalingList() {
        return this.scalings == null ? new ArrayList<>() : this.scalings;
    }
}
