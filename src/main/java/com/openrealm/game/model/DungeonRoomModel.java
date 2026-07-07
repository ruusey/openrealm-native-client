package com.openrealm.game.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A hand-authored dungeon room loaded from dungeon-rooms.json. Tile layers
 * mirror {@link SetPieceModel} ("0" = base, "1" = collision; tileId 0 = void).
 * The client only reads the dimensions; assembly happens server-side.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DungeonRoomModel {
    private int roomId;
    private String name;
    private int width;
    private int height;
    private Map<String, int[][]> data = new LinkedHashMap<>();
    private List<DungeonRoomConnection> connections;
    private List<Integer> minionEnemyIds;
    private int minMinions;
    private int maxMinions;
    private List<DungeonStaticSpawn> staticSpawns;
    private String role = "NORMAL";

    @JsonIgnore
    public int[][] getBaseLayer() {
        return this.data == null ? null : this.data.get("0");
    }

    @JsonIgnore
    public int[][] getCollisionLayer() {
        return this.data == null ? null : this.data.get("1");
    }
}
