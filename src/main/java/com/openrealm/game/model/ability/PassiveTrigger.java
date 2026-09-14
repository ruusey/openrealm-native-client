package com.openrealm.game.model.ability;

import java.util.ArrayList;
import java.util.List;

import com.openrealm.game.contants.PassiveTriggerEvent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One trigger on a PassiveAbility; a passive may have several for compound behavior. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PassiveTrigger {
    /** Resolved to PassiveTriggerEvent at apply-time. */
    private String event;
    /** ON_TICK period between fires in milliseconds. */
    private long tickMs = 0L;
    private List<AbilityScaling> conditions = new ArrayList<>();
    private List<AbilityEffect> effects = new ArrayList<>();
    private List<AbilityScaling> scalings = new ArrayList<>();

    public PassiveTriggerEvent eventEnum() {
        return PassiveTriggerEvent.parse(this.event);
    }
}
