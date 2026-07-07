package com.openrealm.game.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A connection point on a dungeon room's edge: a short strip of tiles ("N"/"S"/
 * "E"/"W" side, {@code offset} along that edge, {@code length} tiles) where a
 * corridor to another room may attach. Outward direction is implied by side.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DungeonRoomConnection {
    private int id;
    private String side;
    private int offset;
    private int length;
}
