package com.openrealm.game.model;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A dungeon definition loaded from dungeons.json. Dungeons are first-class: a
 * dungeon realm is built server-side from this model, and the LoadMapPacket
 * carries {@code dungeonId} so the client can resolve the realm's grid
 * dimensions/tile size from here rather than from a MapModel.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DungeonModel {
    private int dungeonId;
    private String name;
    private int mapWidth;
    private int mapHeight;
    private int tileSize;
    private int wallTileId;
    private int corridorFloorTileId;
    private int corridorMargin;
    private int minRooms;
    private int maxRooms;
    private List<Integer> roomIds;
    private int entranceRoomId = -1;
    private int bossRoomId = -1;
}
