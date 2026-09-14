package com.openrealm.game.model.ability;

import com.openrealm.game.contants.ScalingCurve;
import com.openrealm.game.contants.ScalingTarget;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One stat-to-effect contribution on an Ability or PassiveAbility. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AbilityScaling {
    /** 3-letter stat name: HP MP DEF STR SPD DEX VIT WIS. */
    private String stat;
    private float coeff;
    private String target;
    /** Index into the parent's effects[] when the target needs one; -1 if not. */
    private int effectIndex = -1;
    /** Ceiling on the contribution; &lt;= 0 means no cap. */
    private float cap = 0f;
    /** LINEAR | DIMINISHING | THRESHOLD; defaults to LINEAR. */
    private String curve;

    public ScalingTarget targetEnum() {
        return ScalingTarget.parse(this.target);
    }

    public ScalingCurve curveEnum() {
        return ScalingCurve.parse(this.curve);
    }

    /** Stats POJO order (0=VIT 1=WIS 2=HP 3=MP 4=STR 5=DEF 6=SPD 7=DEX); index 8 = invested skill points. Returns -1 for unknown. */
    public int statIndex() {
        if (this.stat == null) return -1;
        switch (this.stat.trim().toUpperCase()) {
            case "VIT": return 0;
            case "WIS": return 1;
            case "HP":  return 2;
            case "MP":  return 3;
            case "STR": return 4;
            case "DEF": return 5;
            case "SPD": return 6;
            case "DEX": return 7;
            case "SKILL_POINTS":
            case "SKILLPOINTS":
            case "SP":  return 8;
            default:    return -1;
        }
    }

    public boolean isSkillPointScaling() {
        return statIndex() == 8;
    }
}
