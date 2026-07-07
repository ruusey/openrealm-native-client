package com.openrealm.game.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An enemy placed at a fixed tile position within a dungeon room ({@code col}/
 * {@code row} relative to the room's top-left). Any static spawn may be flagged
 * as the dungeon {@code boss}. The client only parses this; spawning is server-side.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DungeonStaticSpawn {
    private int enemyId;
    private int col;
    private int row;
    private boolean boss;
}
