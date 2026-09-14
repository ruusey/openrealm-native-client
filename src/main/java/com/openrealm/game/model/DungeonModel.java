package com.openrealm.game.model;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Dungeon definition from dungeons.json; resolved via LoadMapPacket's dungeonId. */
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
