package com.openrealm.game.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Fixed enemy spawn; col/row are relative to the room's top-left. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DungeonStaticSpawn {
    private int enemyId;
    private int col;
    private int row;
    private boolean boss;
}
