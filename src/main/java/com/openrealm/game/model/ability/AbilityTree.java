package com.openrealm.game.model.ability;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The class-defined kit a player draws from. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AbilityTree {
    /** Every id the player can bind; may be larger than 4. */
    private int[] pool = new int[0];
    /** Initial hotbar binding (exactly 4 ids); 0 = empty slot. */
    private int[] defaultHotbar = new int[]{0, 0, 0, 0};
    /** Always-on class passive id, or 0 for no passive. */
    private int passive = 0;

    public int getDefaultHotbarSlot(int slot) {
        if (this.defaultHotbar == null || slot < 0 || slot >= this.defaultHotbar.length) return 0;
        return this.defaultHotbar[slot];
    }
}
