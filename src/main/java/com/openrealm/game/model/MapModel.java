package com.openrealm.game.model;

import java.util.List;
import java.util.Map;

import com.openrealm.game.math.Vector2f;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.util.Random;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = false)
public class MapModel {
    private int mapId;
    private String mapName;
    private String mapKey;
    private int tileSize;
    private int width;
    private int height;
    private int terrainId;
    private int dungeonId;
    private Map<String, int[][]> data;
    private DungeonGenerationParams dungeonParams;
    private List<StaticSpawn> staticSpawns;
    private List<float[]> spawnPoints;
    private List<PortalModel> staticPortals;
    private float difficulty;
    // 0 / <0 = unlimited (overworld), 1 = single-party dungeon
    private int maxPartyCount;

    public Vector2f getCenter() {
        return new Vector2f((this.width / 2) * this.tileSize, ((this.height / 2) * (this.tileSize)));
    }

    public Vector2f getRandomSpawnPoint() {
        if (this.spawnPoints != null && !this.spawnPoints.isEmpty()) {
            float[] sp = this.spawnPoints.get(new Random().nextInt(this.spawnPoints.size()));
            return new Vector2f(sp[0], sp[1]);
        }
        return getCenter();
    }
}
