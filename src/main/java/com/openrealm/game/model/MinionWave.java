package com.openrealm.game.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MinionWave {
    private float triggerHpPercent; // spawn when boss HP fraction drops below this (0.75 = 75%)
    private int enemyId;
    private int count;
    private int eventMultiplier;
    private float offset;
}
