package com.openrealm.game.tile;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import com.openrealm.game.OpenRealmGame;
import com.openrealm.game.Settings;
import com.openrealm.game.contants.GlobalConstants;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.data.GameSpriteManager;
import com.openrealm.game.graphics.ShaderManager;
import com.openrealm.game.graphics.Sprite;
import com.openrealm.game.entity.Entity;
import com.openrealm.game.entity.Player;
import com.openrealm.game.math.Rectangle;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.model.DungeonGenerationParams;
import com.openrealm.game.model.DungeonModel;
import com.openrealm.game.model.MapModel;
import com.openrealm.game.model.OverworldZone;
import com.openrealm.game.model.TerrainGenerationParameters;
import com.openrealm.game.model.TileGroup;
import com.openrealm.game.model.TileModel;
import com.openrealm.net.client.packet.LoadMapPacket;
import com.openrealm.net.entity.NetTile;
import com.openrealm.net.realm.Realm;
import com.openrealm.util.Camera;
import com.openrealm.util.Partition;
import com.openrealm.util.WorkerThread;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import java.util.Collections;
import java.util.HashMap;

@Data
@Slf4j
public class TileManager {
    private static final Integer VIEWPORT_TILE_MIN = 10;
    private static final Integer VIEWPORT_TILE_MAX = 20;
    /** Per-tile edge-highlight color, sampled once from the wall sprite (lightened ~35%). */
    private static final Map<Integer, float[]> WALL_HIGHLIGHT_CACHE = new HashMap<>();
    private static final float[] WALL_HIGHLIGHT_FALLBACK = new float[] { 0.95f, 0.92f, 0.84f };
    // Reusable viewport rectangles to avoid allocation every frame/tick.
    private static final ThreadLocal<Rectangle> VIEWPORT_RECT = ThreadLocal.withInitial(
            () -> new Rectangle(new Vector2f(), 0, 0));
    private static final ThreadLocal<Vector2f> VIEWPORT_POS = ThreadLocal.withInitial(Vector2f::new);

    // Per-tile fog-of-war: discovered[y][x] flips true once the tile has been in the
    // sight circle. Lazily (re)allocated in render() so it doesn't survive a realm transition.
    private boolean[][] discovered = null;
    private int discoveredW = 0;
    private int discoveredH = 0;
    // Realm/map the grid was last built for; mergeMap wipes the grid when either changes
    // so re-entering a realm doesn't leak the prior visit's tiles as floating walls.
    private long loadedRealmId = 0L;
    private int loadedMapId = -1;
    // Per-frame tile-classification buffers, cleared each frame to avoid re-allocating.
    // Single-threaded: render() runs only on the main thread.
    private final List<Tile> wallTilesBuf       = new ArrayList<>(256);
    private final List<Tile> objectTilesBuf     = new ArrayList<>(64);
    private final List<Tile> decorationTilesBuf = new ArrayList<>(64);
    private final List<Tile> waterTilesBuf      = new ArrayList<>(128);
    private final List<Tile> overWaterTilesBuf  = new ArrayList<>(32);
    private final Vector2f posNormalizedBuf = new Vector2f();
    private final TextureRegion wallTopHalfScratch = new TextureRegion();
    // Tall (taller-than-wide) wall sprites, drawn in their own south-to-occlude pass.
    private final List<Tile> tallWallScratch = new ArrayList<>(64);
    private final Map<Integer, TextureRegion> tallWallTopCache = new HashMap<>();
    private final ReentrantLock mapLock = new ReentrantLock();
    private List<TileMap> mapLayers;
    private Vector2f bossSpawnPos;
    private Vector2f playerSpawnPos;
    private TerrainGenerationParameters terrainParams;
    private int mapId;

    // Server side constructor
    public TileManager(int mapId) {
        this.mapId = mapId;
        MapModel model = GameDataManager.MAPS.get(mapId);
        log.info("[TileManager] Building map {}", model);
        // Three types of maps. Fixed data, generated terrain and generated dungeon
        if (model.getData() != null) {
            this.mapLayers = this.getLayersFromData(model);
        } else if (model.getDungeonId()>-1){
        	final DungeonGenerationParams params = model.getDungeonParams();
			final DungeonGenerator dungeonGenerator = new DungeonGenerator(model.getWidth(), model.getHeight(),
					model.getTileSize(), params.getMinRooms(), params.getMaxRooms(), params.getMinRoomWidth(),
					params.getMaxRoomWidth(), params.getMinRoomHeight(), params.getMaxRoomHeight(),
					params.getShapeTemplates(), params.getFloorTileIds(), params.getWallTileId(),
					params.getHallwayStyles(), params.getBossEnemyId());
            this.mapLayers = dungeonGenerator.generateDungeon();
            if (dungeonGenerator.getBossRoomCenterX() >= 0 && dungeonGenerator.getBossRoomCenterY() >= 0) {
                this.bossSpawnPos = new Vector2f(
                        dungeonGenerator.getBossRoomCenterX() * model.getTileSize(),
                        dungeonGenerator.getBossRoomCenterY() * model.getTileSize());
            }
            if (dungeonGenerator.getSpawnRoomCenterX() >= 0 && dungeonGenerator.getSpawnRoomCenterY() >= 0) {
                this.playerSpawnPos = new Vector2f(
                        dungeonGenerator.getSpawnRoomCenterX() * model.getTileSize(),
                        dungeonGenerator.getSpawnRoomCenterY() * model.getTileSize());
            }
        } else if(model.getTerrainId()>-1){
            final TerrainGenerationParameters params = GameDataManager.TERRAINS.get(model.getTerrainId());
            this.terrainParams = params;
            this.mapLayers = this.getLayersFromTerrain(model.getWidth(), model.getHeight(), model.getTileSize(),
                    params);
        }
    }

    public TileManager(int width, int height, int tileSize, TerrainGenerationParameters params) {
        this.mapLayers = this.getLayersFromTerrain(width, height, tileSize, params);
    }

    // Client side constructor
    public TileManager(MapModel model) {
        this.mapLayers = new ArrayList<>();
        TileMap baseLayer = new TileMap((short) model.getMapId(), model.getTileSize(), model.getWidth(),
                model.getHeight());
        TileMap collisionLayer = new TileMap((short) model.getMapId(), model.getTileSize(), model.getWidth(),
                model.getHeight());
        this.mapLayers.add(baseLayer);
        this.mapLayers.add(collisionLayer);
    }

    // Get the zone for a world position (returns null if no zones defined)
    public OverworldZone getZoneForPosition(float worldX, float worldY) {
        if (this.terrainParams == null || this.terrainParams.getZones() == null
                || this.terrainParams.getZones().isEmpty()) {
            return null;
        }
        final int width = this.getBaseLayer().getWidth();
        final int height = this.getBaseLayer().getHeight();
        final int ts = this.getBaseLayer().getTileSize();
        final float centerX = width * ts / 2f;
        final float centerY = height * ts / 2f;
        final float maxDist = (float) Math.sqrt(centerX * centerX + centerY * centerY);
        final float dx = worldX - centerX;
        final float dy = worldY - centerY;
        final float dist = (float) Math.sqrt(dx * dx + dy * dy);
        final float normalizedDist = dist / maxDist;

        for (OverworldZone zone : this.terrainParams.getZones()) {
            if (normalizedDist >= zone.getMinRadius() && normalizedDist < zone.getMaxRadius()) {
                return zone;
            }
        }
        // Fallback: return outermost zone
        return this.terrainParams.getZones().get(this.terrainParams.getZones().size() - 1);
    }

    // Generates a random terrain of size with the given parameters
    private List<TileMap> getLayersFromTerrain(int width, int height, int tileSize,
            TerrainGenerationParameters params) {
        final Random random = new Random(Instant.now().toEpochMilli());
        TileMap baseLayer = new TileMap(tileSize, width, height);
        TileMap collisionLayer = new TileMap(tileSize, width, height);

        final boolean hasZones = params.getZones() != null && !params.getZones().isEmpty();

        if (hasZones) {
            // Zone-based terrain: each tile gets its TileGroup from its zone
            final float centerX = width / 2f;
            final float centerY = height / 2f;
            final float maxDist = (float) Math.sqrt(centerX * centerX + centerY * centerY);

            // Pre-resolve tile models per group: base terrain (layer 0) and decorations (layer 1)
            final Map<Integer, List<TileModel>> baseByGroup = new HashMap<>();
            final Map<Integer, List<TileModel>> decorationByGroup = new HashMap<>();
            for (TileGroup group : params.getTileGroups()) {
                List<TileModel> baseTiles = group.getTileIds().stream()
                        .map(id -> GameDataManager.TILES.get(id))
                        .filter(tm -> tm != null)
                        .collect(Collectors.toList());
                baseByGroup.put(group.getOrdinal(), baseTiles);

                List<Integer> decoIds = group.getDecorationTileIds();
                List<TileModel> decoTiles = (decoIds != null) ? decoIds.stream()
                        .map(id -> GameDataManager.TILES.get(id))
                        .filter(tm -> tm != null)
                        .collect(Collectors.toList()) : new ArrayList<>();
                decorationByGroup.put(group.getOrdinal(), decoTiles);
            }

            for (int row = 0; row < height; row++) {
                for (int col = 0; col < width; col++) {
                    float dx = col - centerX;
                    float dy = row - centerY;
                    float dist = (float) Math.sqrt(dx * dx + dy * dy);
                    float normalizedDist = dist / maxDist;

                    // Find zone for this tile
                    OverworldZone zone = null;
                    for (OverworldZone z : params.getZones()) {
                        if (normalizedDist >= z.getMinRadius() && normalizedDist < z.getMaxRadius()) {
                            zone = z;
                            break;
                        }
                    }
                    if (zone == null) {
                        zone = params.getZones().get(params.getZones().size() - 1);
                    }

                    int groupOrd = zone.getTileGroupOrdinal();
                    TileGroup group = params.getTileGroups().stream()
                            .filter(g -> g.getOrdinal() == groupOrd).findFirst()
                            .orElse(params.getTileGroups().get(0));

                    // Base layer tile (always placed — opaque ground)
                    List<TileModel> baseTiles = baseByGroup.getOrDefault(groupOrd,
                            baseByGroup.values().iterator().next());
                    if (!baseTiles.isEmpty()) {
                        TileModel tile = baseTiles.get(random.nextInt(baseTiles.size()));
                        float rarity = group.getRarities().getOrDefault(tile.getTileId() + "", 1.0f);
                        if (rarity > 0 && random.nextFloat() <= rarity) {
                            baseLayer.setTileAt(row, col, (short) tile.getTileId(), tile.getData());
                        } else {
                            tile = baseTiles.get(0);
                            baseLayer.setTileAt(row, col, (short) tile.getTileId(), tile.getData());
                        }
                    }

                    // Decoration/collision layer tile (placed on layer 1 over the base)
                    List<TileModel> decoTiles = decorationByGroup.getOrDefault(groupOrd,
                            Collections.emptyList());
                    if (!decoTiles.isEmpty()) {
                        TileModel tile = decoTiles.get(random.nextInt(decoTiles.size()));
                        float rarity = group.getRarities().getOrDefault(tile.getTileId() + "", 0.0f);
                        if (rarity > 0 && random.nextFloat() <= rarity) {
                            collisionLayer.setTileAt(row, col, (short) tile.getTileId(), tile.getData());
                        }
                    }
                }
            }
        } else {
            // Legacy single-group terrain generation
            for (TileGroup group : params.getTileGroups()) {
                List<TileModel> baseTiles = group.getTileIds().stream()
                        .map(id -> GameDataManager.TILES.get(id))
                        .filter(tm -> tm != null)
                        .collect(Collectors.toList());

                List<Integer> decoIds = group.getDecorationTileIds();
                List<TileModel> decoTiles = (decoIds != null) ? decoIds.stream()
                        .map(id -> GameDataManager.TILES.get(id))
                        .filter(tm -> tm != null)
                        .collect(Collectors.toList()) : new ArrayList<>();

                // Fill base layer with terrain tiles
                for (int i = 0; i < height; i++) {
                    for (int j = 0; j < width; j++) {
                        TileModel tileIdToCreate = baseTiles.get(random.nextInt(baseTiles.size()));
                        float rarity = group.getRarities().getOrDefault(tileIdToCreate.getTileId() + "", 1.0f);
                        if ((rarity > 0.0) && (random.nextFloat() <= rarity)) {
                            baseLayer.setTileAt(i, j, (short) tileIdToCreate.getTileId(), tileIdToCreate.getData());
                        } else {
                            tileIdToCreate = baseTiles.get(0);
                            baseLayer.setTileAt(i, j, (short) tileIdToCreate.getTileId(), tileIdToCreate.getData());
                        }
                    }
                }
                // Fill decoration/collision layer from decorationTileIds
                if (!decoTiles.isEmpty()) {
                    for (int i = 0; i < height; i++) {
                        for (int j = 0; j < width; j++) {
                            TileModel tileIdToCreate = decoTiles.get(random.nextInt(decoTiles.size()));
                            float rarity = group.getRarities().getOrDefault(tileIdToCreate.getTileId() + "", 0.0f);
                            if ((rarity > 0.0) && (random.nextFloat() <= rarity)) {
                                collisionLayer.setTileAt(i, j, (short) tileIdToCreate.getTileId(),
                                        tileIdToCreate.getData());
                            }
                        }
                    }
                }
            }
        }
        return Arrays.asList(baseLayer, collisionLayer);
    }

    // Builds map layers from a map model that has statically defined layers (is not
    // a terrain)
    private List<TileMap> getLayersFromData(MapModel model) {
        Map<String, int[][]> layerMap = model.getData();
        TileMap baseLayer = new TileMap((short) model.getMapId(), model.getTileSize(), model.getWidth(),
                model.getHeight());
        TileMap collisionLayer = new TileMap((short) model.getMapId(), model.getTileSize(), model.getWidth(),
                model.getHeight());

        final int[][] baseData = layerMap.get("0");
        final int[][] collisionData = layerMap.get("1");

        for (int i = 0; i < baseData.length; i++) {
            for (int j = 0; j < baseData[i].length; j++) {
                int tileIdToCreate = baseData[i][j];
                TileData tileData = GameDataManager.TILES.get(tileIdToCreate).getData();
                baseLayer.setTileAt(i, j, (short) tileIdToCreate, tileData);
            }
        }

        for (int i = 0; i < collisionData.length; i++) {
            for (int j = 0; j < collisionData[i].length; j++) {
                int tileIdToCreate = collisionData[i][j];
                TileData tileData = GameDataManager.TILES.get(tileIdToCreate).getData();
                collisionLayer.setTileAt(i, j, (short) tileIdToCreate, tileData);
            }
        }
        return Arrays.asList(baseLayer, collisionLayer);

    }
    
    public Tile[] getBaseTiles(Vector2f pos) {
        Tile[] block = new Tile[144];
        final int ts = this.getBaseLayer().getTileSize();
        Vector2f posNormalized = new Vector2f(pos.x / ts,
                pos.y / ts);
        this.normalizeToBounds(posNormalized);
        int i = 0;
        for (int x = (int) (posNormalized.x - 5); x < (posNormalized.x + 6); x++) {
            for (int y = (int) (posNormalized.y - 5); y < (int) (posNormalized.y + 6); y++) {
                if ((x >= this.getBaseLayer().getWidth()) || (y >= this.getBaseLayer().getHeight()) || (x < 0)
                        || (y < 0)) {
                    continue;
                }
                try {
                    block[i] = (Tile) this.mapLayers.get(0).getBlocks()[y][x];
                    i++;
                } catch (Exception e) {

                }
            }
        }
        return block;
    }

    public Tile[] getCollisionTiles(Vector2f pos) {
        Tile[] block = new Tile[144];
        final int ts = this.getCollisionLayer().getTileSize();
        Vector2f posNormalized = new Vector2f(pos.x / ts,
                pos.y / ts);
        this.normalizeToBounds(posNormalized);
        int i = 0;
        for (int x = (int) (posNormalized.x - 5); x < (posNormalized.x + 6); x++) {
            for (int y = (int) (posNormalized.y - 5); y < (int) (posNormalized.y + 6); y++) {
                if ((x >= this.getCollisionLayer().getWidth()) || (y >= this.getCollisionLayer().getHeight()) || (x < 0)
                        || (y < 0)) {
                    continue;
                }
                try {
                    block[i] = (Tile) this.mapLayers.get(1).getBlocks()[y][x];
                    i++;
                } catch (Exception e) {

                }
            }
        }
        return block;
    }

    public boolean isMapLoaded() {
        return this.mapLayers != null && !this.mapLayers.isEmpty();
    }

    public TileMap getCollisionLayer() {
        return this.mapLayers.get(this.mapLayers.size() - 1);
    }

    public TileMap getBaseLayer() {
        return this.mapLayers.get(0);
    }

    private void normalizeToBounds(Vector2f pos) {
        if (pos.x < 0) {
            pos.x = 0;
        }
        if (pos.x > (this.getBaseLayer().getWidth() - 1)) {
            pos.x = this.getBaseLayer().getWidth() - 1;
        }

        if (pos.y < 0) {
            pos.y = 0;
        }
        if (pos.y > (this.getBaseLayer().getHeight() - 1)) {
            pos.y = this.getBaseLayer().getWidth() - 1;
        }
    }

    public Vector2f getSafePosition() {
        // If the map defines explicit spawn points, pick one randomly
        if (this.mapId > 0) {
            MapModel model = GameDataManager.MAPS.get(this.mapId);
            if (model != null && model.getSpawnPoints() != null && !model.getSpawnPoints().isEmpty()) {
                return model.getRandomSpawnPoint();
            }
        }
        // Spawn in the outermost zone biased toward its outer edge (near the water,
        // away from the harder next-tier zone).
        if (this.terrainParams != null && this.terrainParams.getZones() != null
                && !this.terrainParams.getZones().isEmpty()) {
            OverworldZone outerZone = this.terrainParams.getZones().stream()
                    .max((a, b) -> Float.compare(a.getMaxRadius(), b.getMaxRadius()))
                    .orElse(null);
            if (outerZone != null) {
                return this.getSafePositionInZone(outerZone, true);
            }
        }
        Vector2f pos = this.randomPos();
        int attempts = 0;
        while ((this.collidesAtPosition(pos, this.getBaseLayer().getTileSize()) || this.isVoidTile(pos, 0, 0))
                && attempts < 500) {
            pos = this.randomPos();
            attempts++;
        }
        return pos;
    }

    public Vector2f getSafePositionInZone(OverworldZone zone) {
        return this.getSafePositionInZone(zone, false);
    }

    // Safe random position in a zone's radial band; outerEdgeBias restricts to the
    // outer 25% of the band (new-player spawns land far from the next-tier zone).
    public Vector2f getSafePositionInZone(OverworldZone zone, boolean outerEdgeBias) {
        final int width = this.getBaseLayer().getWidth();
        final int height = this.getBaseLayer().getHeight();
        final int ts = this.getBaseLayer().getTileSize();
        final float centerX = width * ts / 2f;
        final float centerY = height * ts / 2f;
        final float maxDist = (float) Math.sqrt(centerX * centerX + centerY * centerY);
        final float zoneMin = zone.getMinRadius() * maxDist;
        final float zoneMax = zone.getMaxRadius() * maxDist;
        // When biasing toward the outer edge, only accept positions in the outer
        // 25% of the zone band. For the beach (0.55..1.01 of map radius) this
        // restricts spawns to the outer ~12% of the map radius — right at the
        // water's edge, far from any inner-zone enemies.
        // Outer 25% of the band restricts beach spawns to the water's edge.
        final float minDist = outerEdgeBias
                ? (zoneMin + (zoneMax - zoneMin) * 0.75f)
                : zoneMin;
        final float maxDistZone = zoneMax;

        for (int attempts = 0; attempts < 1000; attempts++) {
            Vector2f pos = this.randomPos();
            if (this.collidesAtPosition(pos, ts) || this.isVoidTile(pos, 0, 0)) continue;
            float dx = pos.x - centerX;
            float dy = pos.y - centerY;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            if (dist >= minDist && dist < maxDistZone) {
                return pos;
            }
        }
        // Fallback 1: relax the outer-edge bias if we couldn't find a spot
        if (outerEdgeBias) {
            return this.getSafePositionInZone(zone, false);
        }
        // Fallback 2: any safe random position
        Vector2f pos = this.randomPos();
        int fallbackAttempts = 0;
        while ((this.collidesAtPosition(pos, this.getBaseLayer().getTileSize()) || this.isVoidTile(pos, 0, 0))
                && fallbackAttempts < 500) {
            pos = this.randomPos();
            fallbackAttempts++;
        }
        return pos;
    }

    public boolean isCollisionTile(Vector2f pos) {
        final TileMap collisionLayer = this.getCollisionLayer();
        final int tileX = (int) pos.x / collisionLayer.getTileSize();
        final int tileY = (int) pos.y / collisionLayer.getTileSize();
        // If the player clicks off the map
        if(!collisionLayer.isValidPosition(tileX, tileY)){
        	return true;
        }
        final Tile currentTile = collisionLayer.getBlocks()[tileY][tileX];
        if (currentTile == null || currentTile.isVoid()) return false;
        // Decoration tiles sit in the collision layer with hasCollision=0; bullets
        // must not expire on them (server walks/shoots through them).
        final TileData td = currentTile.getData();
        return td != null && td.hasCollision();
    }
    
    public boolean isVoidTile(Vector2f pos, float dx, float dy) {
        final TileMap collisionLayer = this.getBaseLayer();
        final int tileX = (int) ((float)pos.x + dx) / collisionLayer.getTileSize();
        final int tileY = (int) ((float)pos.y + dy)/ collisionLayer.getTileSize();
        if(tileY>=collisionLayer.getBlocks().length || tileX>=collisionLayer.getBlocks()[0].length) {
            return false;
        }
        final Tile currentTile = collisionLayer.getBlocks()[tileY][tileX];
        if(currentTile==null) {
            return false;
        }
        return currentTile.isVoid();
    }

    // A null tile is one the server never streamed; PlayState uses this on
    // disconnect to confine the player to the already-loaded area.
    public boolean isUnloadedTile(Vector2f pos, float dx, float dy) {
        final TileMap baseLayer = this.getBaseLayer();
        final int tileX = (int) ((float) pos.x + dx) / baseLayer.getTileSize();
        final int tileY = (int) ((float) pos.y + dy) / baseLayer.getTileSize();
        if (tileX < 0 || tileY < 0
                || tileY >= baseLayer.getBlocks().length
                || tileX >= baseLayer.getBlocks()[0].length) {
            return true;
        }
        return baseLayer.getBlocks()[tileY][tileX] == null;
    }

    public boolean collidesXLimit(Entity e, float ax) {
        final Vector2f futurePos = e.getPos().clone(ax, 0);
        return (futurePos.x <= 0) || ((futurePos.x + e.getSize()) >= (this.getBaseLayer().getWidth()
                * this.getBaseLayer().getTileSize()));

    }

    public boolean collidesYLimit(Entity e, float dy) {
        final Vector2f futurePos = e.getPos().clone(0, dy);
        return (futurePos.y <= 0) || ((futurePos.y + e.getSize()) >= (this.getBaseLayer().getHeight()
                * this.getBaseLayer().getTileSize()));

    }
    
    public boolean collidesVoidTile(Entity e) {
        final Vector2f centerPos = e.getCenteredPosition();
        final int startX = (int) (centerPos.x / (float) this.getBaseLayer().getTileSize());
        final int startY = (int) (centerPos.y / (float) this.getBaseLayer().getTileSize());

        final Tile currentTile = this.getBaseLayer().getBlocks()[startY][startX];
        if(!currentTile.isVoid()) {
            return false;
        }
        final Rectangle tileBounds = new Rectangle(currentTile.getPos(), currentTile.getWidth(),
                currentTile.getHeight());
        final Rectangle futurePosBounds = new Rectangle(e.getPos(), (e.getSize() / 2), e.getSize() / 2);

        return currentTile.isVoid() && tileBounds.intersect(futurePosBounds);
    }

    public boolean collidesSlowTile(Entity e) {
        // Must match the server's collidesSlowTile exactly or slow prediction desyncs.
        return this.feetOnFlaggedTile(e, true);
    }

    public boolean collidesDamagingTile(Entity e) {
        return this.feetOnFlaggedTile(e, false);
    }

    // Samples X at the midpoint but Y at the hitbox bottom (feet), so a player can stand
    // on the walkable edge of a lava pool. Keep IDENTICAL to server feetOnFlaggedTile + webclient _isOnSlowTile.
    private boolean feetOnFlaggedTile(final Entity e, final boolean slow) {
        final Tile[][] blocks = this.getBaseLayer().getBlocks();
        final int ts = this.getBaseLayer().getTileSize();
        final float sampleX = e.getPos().x + e.getSize() / 2f;
        final float sampleY = e.getPos().y + e.getSize();
        return this.flaggedTileAt(blocks, ts, sampleX, sampleY, slow);
    }

    private boolean flaggedTileAt(final Tile[][] blocks, final int ts, final float x, final float y, final boolean slow) {
        final int tx = (int) (x / (float) ts);
        final int ty = (int) (y / (float) ts);
        if (ty < 0 || ty >= blocks.length || tx < 0 || tx >= blocks[0].length) return false;
        final Tile t = blocks[ty][tx];
        if (t == null || t.getData() == null) return false;
        return slow ? t.getData().slows() : t.getData().damaging();
    }

    public boolean collisionTile(Entity e, float ax, float ay) {
        final Vector2f futurePos = e.getPos().clone(ax, ay);
        // 85% top-left-anchored hitbox to match the webclient's _checkCollision.
        final int hitSize = (int) (e.getSize() * 0.85f);
        for (Tile t : this.getCollisionTiles(e.getPos())) {
            if ((t == null) || t.isVoid()) {
                continue;
            }
            // Skip visual-only decoration tiles (hasCollision=0) or the player sticks on invisible blockers.
            final TileData td = t.getData();
            if (td == null || !td.hasCollision()) continue;
            Rectangle tileBounds = new Rectangle(t.getPos(), t.getWidth(), t.getHeight());
            Rectangle futurePosBounds = new Rectangle(futurePos, hitSize, hitSize);
            if (tileBounds.intersect(futurePosBounds))
                return true;
        }

        return false;
    }

    /**
     * Hitbox-based collision check at an arbitrary position and size.
     * Use this to validate a destination before placing/teleporting an entity.
     */
    public boolean collidesAtPosition(Vector2f pos, int entitySize) {
        // 85% hitbox to match collisionTile and the client check.
        final int hitSize = (int) (entitySize * 0.85f);
        for (Tile t : this.getCollisionTiles(pos)) {
            if (t == null || t.isVoid()) continue;
            final TileData td = t.getData();
            if (td == null || !td.hasCollision()) continue;
            Rectangle tileBounds = new Rectangle(t.getPos(), t.getWidth(), t.getHeight());
            Rectangle entityBounds = new Rectangle(pos, hitSize, hitSize);
            if (tileBounds.intersect(entityBounds)) return true;
        }
        return false;
    }

    public Vector2f randomPos() {
        final float x = Realm.RANDOM.nextInt(this.getBaseLayer().getWidth()) * this.getBaseLayer().getTileSize();
        final float y = Realm.RANDOM.nextInt(this.getBaseLayer().getHeight()) * this.getBaseLayer().getTileSize();
        return new Vector2f(x, y);
    }

    public Rectangle getRenderViewPort(Camera cam) {
        final int ts = this.getBaseLayer().getTileSize();
        final Vector2f tmpPos = VIEWPORT_POS.get();
        tmpPos.x = cam.getTarget().getPos().x - (VIEWPORT_TILE_MIN * ts);
        tmpPos.y = cam.getTarget().getPos().y - (VIEWPORT_TILE_MIN * ts);
        final Rectangle rect = VIEWPORT_RECT.get();
        rect.setBox(tmpPos, VIEWPORT_TILE_MAX * ts,
                VIEWPORT_TILE_MAX * ts);
        return rect;
    }

    public Rectangle getRenderViewPort(Entity p) {
        final int ts = this.getBaseLayer().getTileSize();
        final Vector2f tmpPos = VIEWPORT_POS.get();
        tmpPos.x = p.getPos().x - (VIEWPORT_TILE_MIN * ts);
        tmpPos.y = p.getPos().y - (VIEWPORT_TILE_MIN * ts);
        final Rectangle rect = VIEWPORT_RECT.get();
        rect.setBox(tmpPos, VIEWPORT_TILE_MAX * ts,
                VIEWPORT_TILE_MAX * ts);
        return rect;
    }

    public Rectangle getRenderViewPort(Entity p, Integer tiles) {
        final int ts = this.getBaseLayer().getTileSize();
        final Vector2f tmpPos = VIEWPORT_POS.get();
        tmpPos.x = p.getPos().x - (tiles * ts);
        tmpPos.y = p.getPos().y - (tiles * ts);
        final Rectangle rect = VIEWPORT_RECT.get();
        rect.setBox(tmpPos, tiles * 2 * ts,
                tiles * 2 * ts);
        return rect;
    }

    public NetTile[] getLoadMapTiles(Player player) {
        final int playerSize = player.getSize() / 2;
        final Vector2f pos = player.getPos().clone(playerSize, playerSize);
        final List<NetTile> tiles = new ArrayList<>();
        final int ts = this.getBaseLayer().getTileSize();
        final Vector2f posNormalized = new Vector2f(pos.x / ts,
                pos.y / ts);
        this.normalizeToBounds(posNormalized);
        final float radiusSq = VIEWPORT_TILE_MIN * VIEWPORT_TILE_MIN;
        for (int x = (int) (posNormalized.x - VIEWPORT_TILE_MIN); x < (posNormalized.x + VIEWPORT_TILE_MIN); x++) {
            for (int y = (int) (posNormalized.y - VIEWPORT_TILE_MIN); y < (int) (posNormalized.y + VIEWPORT_TILE_MIN); y++) {
                if ((x >= this.getBaseLayer().getWidth()) || (y >= this.getBaseLayer().getHeight()) || (x < 0)
                        || (y < 0)) {
                    continue;
                }
                float dx = x - posNormalized.x;
                float dy = y - posNormalized.y;
                if (dx * dx + dy * dy > radiusSq) continue;
                try {
                    Tile collisionTile = (Tile) this.mapLayers.get(1).getBlocks()[y][x];
                    Tile normalTile = (Tile) this.mapLayers.get(0).getBlocks()[y][x];
                    if (collisionTile != null) {
                        NetTile collisionNetTile = new NetTile(collisionTile.getTileId(), (byte) 1, y, x);
                        tiles.add(collisionNetTile);
                    }

                    if (normalTile != null) {
                        NetTile normalNetTile = new NetTile(normalTile.getTileId(), (byte) 0, y, x);
                        tiles.add(normalNetTile);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        return tiles.toArray(new NetTile[0]);
    }
    
    public short getMapWidth() {
        return (short) this.getBaseLayer().getWidth();
    }
    
    public short getMapHeight() {
        return (short) this.getBaseLayer().getHeight();
    }

    // Blank the tile layers + fog so the next mergeMap starts empty. Needed on
    // same-dimension realm transitions where mergeMap's dimension reset wouldn't fire.
    public void resetTiles(int mapId, int dungeonId) {
        final int[] dims = resolveGridDims(mapId, dungeonId, -1, -1);
        if (dims == null) return;
        this.acquireMapLock();
        try {
            final TileMap baseLayer = new TileMap((short) mapId, dims[0], dims[1], dims[2]);
            final TileMap collisionLayer = new TileMap((short) mapId, dims[0], dims[1], dims[2]);
            this.mapLayers = new ArrayList<>();
            this.mapLayers.add(baseLayer);
            this.mapLayers.add(collisionLayer);
            this.discovered = null;
        } finally {
            this.releaseMapLock();
        }
    }

    // [tileSize, width, height] for a realm's grid (dungeonId -> DUNGEONS, else mapId
    // -> MAPS, else the packet size). Null only when nothing is known.
    private int[] resolveGridDims(int mapId, int dungeonId, int fallbackWidth, int fallbackHeight) {
        if (dungeonId > -1 && GameDataManager.DUNGEONS != null && GameDataManager.DUNGEONS.get(dungeonId) != null) {
            final DungeonModel dungeon = GameDataManager.DUNGEONS.get(dungeonId);
            final int tileSize = dungeon.getTileSize() > 0 ? dungeon.getTileSize() : GlobalConstants.BASE_TILE_SIZE;
            final int width = dungeon.getMapWidth() > 0 ? dungeon.getMapWidth() : fallbackWidth;
            final int height = dungeon.getMapHeight() > 0 ? dungeon.getMapHeight() : fallbackHeight;
            if (width > 0 && height > 0) return new int[] { tileSize, width, height };
        }
        final MapModel model = GameDataManager.MAPS.get(mapId);
        if (model != null) {
            return new int[] { model.getTileSize(), model.getWidth(), model.getHeight() };
        }
        if (fallbackWidth > 0 && fallbackHeight > 0) {
            return new int[] { GlobalConstants.BASE_TILE_SIZE, fallbackWidth, fallbackHeight };
        }
        return null;
    }

    public void mergeMap(LoadMapPacket packet) {
    	this.acquireMapLock();
        // Wipe the grid when this LoadMap is for a different realm/map/dimensions;
        // the realm/map check catches re-entering a realm the reset gate alone missed.
        final boolean gridChanged = packet.getRealmId() != this.loadedRealmId
                || (int) packet.getMapId() != this.loadedMapId
                || this.getMapHeight() != packet.getMapHeight()
                || this.getMapWidth() != packet.getMapWidth();
        final int[] dims = gridChanged
                ? resolveGridDims((int) packet.getMapId(), (int) packet.getDungeonId(),
                        packet.getMapWidth(), packet.getMapHeight())
                : null;
        if (dims != null) {
           TileMap baseLayer = new TileMap((short) packet.getMapId(), dims[0], dims[1], dims[2]);
           TileMap collisionLayer = new TileMap((short) packet.getMapId(), dims[0], dims[1], dims[2]);
           this.mapLayers = new ArrayList<>();
           this.mapLayers.add(baseLayer);
           this.mapLayers.add(collisionLayer);
           this.discovered = null;
        }
        this.loadedRealmId = packet.getRealmId();
        this.loadedMapId = (int) packet.getMapId();

        for (NetTile tile : packet.getTiles()) {
            TileData data = GameDataManager.TILES.get((int) tile.getTileId()).getData();
            
            this.mapLayers.get((int) tile.getLayer()).setTileAt(tile.getXIndex(), tile.getYIndex(), tile.getTileId(),
                    data);
        }
        this.releaseMapLock();
    }

    public void render(Player player, SpriteBatch batch, ShapeRenderer shapes) {
        this.acquireMapLock();
        final int playerSize = player.getSize() / 2;
        final Vector2f pos = player.getPos().clone(playerSize, playerSize);
        final int ts = this.getBaseLayer().getTileSize();
        final Vector2f posNormalized = this.posNormalizedBuf;
        posNormalized.x = pos.x / ts;
        posNormalized.y = pos.y / ts;
        this.normalizeToBounds(posNormalized);

        final int mapW = this.getBaseLayer().getWidth();
        final int mapH = this.getBaseLayer().getHeight();
        if (this.discovered == null || this.discoveredW != mapW || this.discoveredH != mapH) {
            this.discovered = new boolean[mapH][mapW];
            this.discoveredW = mapW;
            this.discoveredH = mapH;
        }

        final float worldViewW = OpenRealmGame.width / OpenRealmGame.WORLD_SCALE;
        final float worldViewH = OpenRealmGame.height / OpenRealmGame.WORLD_SCALE;

        final float scanMinX = Vector2f.worldX, scanMinY = Vector2f.worldY;
        final float scanMaxX = scanMinX + worldViewW, scanMaxY = scanMinY + worldViewH;

        final int sxMin = (int) Math.floor(scanMinX / ts);
        final int syMin = (int) Math.floor(scanMinY / ts);
        final int screenTilesX = (int) Math.ceil((scanMaxX - scanMinX) / ts) + 2;
        final int screenTilesY = (int) Math.ceil((scanMaxY - scanMinY) / ts) + 2;
        // Tile buckets for the deferred render passes (walls, water, objects,
        // decorations). Populated by the single viewport scan below.
        final List<Tile> wallTiles       = this.wallTilesBuf;       wallTiles.clear();
        final List<Tile> objectTiles     = this.objectTilesBuf;     objectTiles.clear();
        final List<Tile> decorationTiles = this.decorationTilesBuf; decorationTiles.clear();
        final List<Tile> waterTiles      = this.waterTilesBuf;      waterTiles.clear();
        final List<Tile> overWaterTiles  = this.overWaterTilesBuf;  overWaterTiles.clear();

        // Terrain is NOT fog-gated: every streamed tile renders (fog-of-war gates
        // only entities/projectiles). Gating the ground made blend seams flicker as the sight circle swept.
        batch.setColor(1f, 1f, 1f, 1f);
        for (int sx = sxMin; sx < sxMin + screenTilesX; sx++) {
            for (int sy = syMin; sy < syMin + screenTilesY; sy++) {
                if (sx < 0 || sy < 0 || sx >= mapW || sy >= mapH) continue;
                final Tile baseTile = (Tile) this.mapLayers.get(0).getBlocks()[sy][sx];
                if (baseTile == null || baseTile.getTileId() <= 0) continue;
                this.discovered[sy][sx] = true;
                baseTile.render(batch);
                final boolean baseIsWater = baseTile.getData() != null
                        && baseTile.getData().slows() && !baseTile.getData().hasCollision();
                if (baseIsWater) waterTiles.add(baseTile);

                final Tile colTile = (Tile) this.mapLayers.get(1).getBlocks()[sy][sx];
                if (colTile != null && !colTile.isVoid()) {
                    final boolean isWall = colTile.getData() != null && colTile.getData().isWall();
                    if (isWall) {
                        // Queued by the viewport+2 wall scan below (padding = stable edge adjacency).
                    } else if (baseIsWater) {
                        overWaterTiles.add(colTile);
                    } else if (colTile.getData() != null && colTile.getData().hasCollision()) {
                        objectTiles.add(colTile);
                    } else {
                        decorationTiles.add(colTile);
                    }
                }
            }
        }

        // Scan the full viewport plus a 2-tile pad for walls. The pad lets the
        // adjacency check see off-screen wall neighbours so edge-wall shading
        // stays stable when a neighbour scrolls off-screen. Padding-only walls
        // populate the buckets for adjacency but aren't drawn (outside camera).
        final int padTiles = 2;
        for (int sx = sxMin - padTiles; sx < sxMin + screenTilesX + padTiles; sx++) {
            for (int sy = syMin - padTiles; sy < syMin + screenTilesY + padTiles; sy++) {
                if (sx < 0 || sy < 0 || sx >= mapW || sy >= mapH) continue;
                final Tile maybeWall = (Tile) this.mapLayers.get(1).getBlocks()[sy][sx];
                if (maybeWall == null || maybeWall.isVoid()) continue;
                if (maybeWall.getData() == null || !maybeWall.getData().isWall()) continue;
                wallTiles.add(maybeWall);
            }
        }

        drawTileSeams(batch, sxMin, syMin, screenTilesX, screenTilesY, ts, mapW, mapH);

        if (!wallTiles.isEmpty()) {
            // Fake-3D wall extrusion (mirrors webclient renderer.js Pass 2): black
            // alpha-gradient bands on every wall edge not facing another wall, plus
            // N/W top-light highlights. Walls sharing a face skip that face's bands.
            final boolean simpleWalls = "simple".equals(Settings.get().getWallRenderMode());
            if (simpleWalls) {
                renderWallsSimple(batch, shapes, wallTiles, mapW, mapH);
            } else {
            batch.end();
            Gdx.gl.glEnable(GL20.GL_BLEND);
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            for (Tile t : wallTiles) {
                // Tall art walls carry their own front face; extrusion bands are for square walls only.
                if (isTallWallTile(t)) continue;
                int sz = t.getWidth();
                if (sz <= 0) continue;
                float wx = t.getPos().getWorldVar().x;
                float wy = t.getPos().getWorldVar().y;
                final long row = t.getRow();
                final long col = t.getCol();
                boolean wN = isWallTileAt(row - 1, col, mapW, mapH);
                boolean wS = isWallTileAt(row + 1, col, mapW, mapH);
                boolean wW = isWallTileAt(row, col - 1, mapW, mapH);
                boolean wE = isWallTileAt(row, col + 1, mapW, mapH);

                // 6-8 thin 1px stripes per face for a smooth alpha falloff.
                if (!wS) {
                    float xEnd = sz + (wE ? 0 : Math.round(sz * 0.18f));
                    final float[] aS = { 0.55f, 0.46f, 0.36f, 0.27f, 0.20f, 0.14f, 0.09f, 0.05f };
                    for (int k = 0; k < aS.length; k++) {
                        shapes.setColor(0f, 0f, 0f, aS[k]);
                        shapes.rect(wx, wy + sz + k, xEnd, 1);
                    }
                }
                if (!wE) {
                    float startY = wy + (wN ? 0 : 2);
                    float h = (wy + sz) - startY;
                    final float[] aE = { 0.42f, 0.34f, 0.26f, 0.19f, 0.13f, 0.08f };
                    for (int k = 0; k < aE.length; k++) {
                        shapes.setColor(0f, 0f, 0f, aE[k]);
                        shapes.rect(wx + sz + k, startY, 1, h);
                    }
                }
                if (!wW) {
                    final float[] aW = { 0.32f, 0.26f, 0.20f, 0.14f, 0.09f, 0.05f };
                    for (int k = 0; k < aW.length; k++) {
                        shapes.setColor(0f, 0f, 0f, aW[k]);
                        shapes.rect(wx - 1 - k, wy, 1, sz);
                    }
                }
                if (!wN) {
                    float xStart = wx + (wW ? 0 : 2);
                    float xEnd   = wx + sz - (wE ? 0 : 2);
                    float w = xEnd - xStart;
                    final float[] aN = { 0.28f, 0.22f, 0.16f, 0.11f, 0.07f, 0.04f };
                    for (int k = 0; k < aN.length; k++) {
                        shapes.setColor(0f, 0f, 0f, aN[k]);
                        shapes.rect(xStart, wy - 1 - k, w, 1);
                    }
                }
            }
            shapes.end();
            batch.begin();

            for (Tile t : wallTiles) {
                if (!isTallWallTile(t)) t.render(batch);
            }
            renderTallWalls(batch, wallTiles);

            for (Tile t : wallTiles) {
                if (isTallWallTile(t)) continue;
                final long row = t.getRow();
                final long col = t.getCol();
                if (!isWallTileAt(row + 1, col, mapW, mapH)) continue;
                final TextureRegion region = GameSpriteManager.TILE_SPRITES.get((int) t.getTileId());
                if (region == null) continue;
                final int sz = t.getWidth();
                if (sz <= 0) continue;
                final float wx = t.getPos().getWorldVar().x;
                final float wy = t.getPos().getWorldVar().y;
                final int srcW = region.getRegionWidth();
                final int srcH = region.getRegionHeight();
                final int srcHalfH = Math.max(1, srcH / 2);
                this.wallTopHalfScratch.setTexture(region.getTexture());
                this.wallTopHalfScratch.setRegion(region.getRegionX(), region.getRegionY(), srcW, srcHalfH);
                batch.draw(this.wallTopHalfScratch, wx, wy, sz, sz);
            }

            // N + W top-light highlights on edge walls, tinted from the tile's own color.
            batch.end();
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            for (Tile t : wallTiles) {
                if (isTallWallTile(t)) continue;
                int sz = t.getWidth();
                if (sz <= 0) continue;
                float wx = t.getPos().getWorldVar().x;
                float wy = t.getPos().getWorldVar().y;
                final long row = t.getRow();
                final long col = t.getCol();
                boolean wN = isWallTileAt(row - 1, col, mapW, mapH);
                boolean wW = isWallTileAt(row, col - 1, mapW, mapH);
                final float[] hl = wallHighlightColor((int) t.getTileId());
                if (!wN) {
                    shapes.setColor(hl[0], hl[1], hl[2], 0.20f); shapes.rect(wx, wy,     sz, 2);
                    shapes.setColor(hl[0], hl[1], hl[2], 0.09f); shapes.rect(wx, wy + 2, sz, 2);
                }
                if (!wW) {
                    shapes.setColor(hl[0], hl[1], hl[2], 0.11f); shapes.rect(wx,     wy, 1, sz);
                    shapes.setColor(hl[0], hl[1], hl[2], 0.05f); shapes.rect(wx + 1, wy, 1, sz);
                }
            }
            shapes.end();
            Gdx.gl.glDisable(GL20.GL_BLEND);
            batch.begin();
            }
        }

        // Pass 3: object tiles (collision decorations) with a circular ground shadow.
        if (!objectTiles.isEmpty()) {
            batch.end();
            Gdx.gl.glEnable(GL20.GL_BLEND);
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA,
                    GL20.GL_ONE_MINUS_SRC_ALPHA);
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(0f, 0f, 0f, 0.3f);
            for (Tile t : objectTiles) {
                float wx = t.getPos().getWorldVar().x;
                float wy = t.getPos().getWorldVar().y;
                int sz = t.getWidth();
                float cx = wx + sz / 2f;
                float cy = wy + sz - sz * 0.1f;
                shapes.ellipse(cx - sz * 0.35f, cy - sz * 0.08f, sz * 0.7f, sz * 0.16f);
            }
            shapes.end();
            Gdx.gl.glDisable(GL20.GL_BLEND);
            batch.begin();

            for (Tile t : objectTiles) {
                t.renderOutline(batch);
                t.render(batch);
            }
        }

        // Pass 4: decorative (non-collision) tiles from the collision layer.
        for (Tile t : decorationTiles) {
            t.renderOutline(batch);
            t.render(batch);
        }

        // Pass 5: Redraw water on top so the shadow pass doesn't darken it.
        for (Tile t : waterTiles) {
            t.render(batch);
        }

        // Pass 6: Collision tiles whose base is water (river stones), after the
        // water redraw so the water doesn't paint over them.
        for (Tile t : overWaterTiles) {
            t.renderOutline(batch);
            t.render(batch);
        }

        // Pass 7: Re-stamp wall sprites on top of the water passes (a wall in a
        // water-base cell got buried by the Pass-5 redraw). Tall walls MUST go
        // through renderTallWalls, not t.render: their front face spills one cell
        // south and a plain re-stamp leaves that face buried.
        for (Tile t : wallTiles) {
            if (!isTallWallTile(t)) t.render(batch);
        }
        renderTallWalls(batch, wallTiles);

        // Bottom silhouette outline for collision billboards, drawn last so nothing paints over it.
        for (Tile t : objectTiles)     t.renderBottomOutline(batch);
        for (Tile t : decorationTiles) t.renderBottomOutline(batch);
        for (Tile t : overWaterTiles)  t.renderBottomOutline(batch);

        this.releaseMapLock();
    }

    // Sampled once per tileId, lightened toward white; falls back when the pixmap isn't readable.
    private static float[] wallHighlightColor(int tileId) {
        float[] cached = WALL_HIGHLIGHT_CACHE.get(tileId);
        if (cached != null) return cached;
        float[] result = WALL_HIGHLIGHT_FALLBACK;
        try {
            final TextureRegion region = GameSpriteManager.TILE_SPRITES.get(tileId);
            if (region != null && region.getTexture() != null) {
                final Texture tex = region.getTexture();
                if (tex.getTextureData() != null) {
                    if (!tex.getTextureData().isPrepared()) {
                        tex.getTextureData().prepare();
                    }
                    final Pixmap pix = tex.getTextureData().consumePixmap();
                    if (pix != null) {
                        // Sample the center grid to average out edge dithering / outlines.
                        final int rx = region.getRegionX();
                        final int ry = region.getRegionY();
                        final int rw = region.getRegionWidth();
                        final int rh = region.getRegionHeight();
                        long r = 0, g = 0, b = 0; int n = 0;
                        for (int dy = rh / 4; dy < rh - rh / 4; dy += 2) {
                            for (int dx = rw / 4; dx < rw - rw / 4; dx += 2) {
                                final int color = pix.getPixel(rx + dx, ry + dy);
                                final int ar = (color >> 24) & 0xFF;
                                if (ar < 16) continue;
                                r += (color >> 16) & 0xFF;
                                g += (color >>  8) & 0xFF;
                                b += (color      ) & 0xFF;
                                n++;
                            }
                        }
                        if (tex.getTextureData().disposePixmap()) pix.dispose();
                        if (n > 0) {
                            float fr = Math.min(1f, ((r / (float) n) / 255f) * 1.35f + 0.10f);
                            float fg = Math.min(1f, ((g / (float) n) / 255f) * 1.35f + 0.10f);
                            float fb = Math.min(1f, ((b / (float) n) / 255f) * 1.35f + 0.10f);
                            result = new float[] { fr, fg, fb };
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        WALL_HIGHLIGHT_CACHE.put(tileId, result);
        return result;
    }

    // Out-of-bounds reads as not-a-wall.
    private boolean isWallTileAt(long row, long col, int mapW, int mapH) {
        if (row < 0 || col < 0 || col >= mapW || row >= mapH) return false;
        final Tile ct = (Tile) this.mapLayers.get(1).getBlocks()[(int) row][(int) col];
        return ct != null && !ct.isVoid() && ct.getData() != null && ct.getData().isWall();
    }

    // Wall body + one flat black stroke per exposed face; far fewer rects than the
    // fancy path, which tanks the frame rate in wall-dense dungeons.
    private void renderWallsSimple(SpriteBatch batch, ShapeRenderer shapes,
            List<Tile> wallTiles, int mapW, int mapH) {
        for (Tile t : wallTiles) {
            if (!isTallWallTile(t)) t.render(batch);
        }
        renderTallWalls(batch, wallTiles);
        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0f, 0f, 0f, 1f);
        for (Tile t : wallTiles) {
            if (isTallWallTile(t)) continue;
            final int sz = t.getWidth();
            if (sz <= 0) continue;
            final float wx = t.getPos().getWorldVar().x;
            final float wy = t.getPos().getWorldVar().y;
            final long row = t.getRow();
            final long col = t.getCol();
            final float stroke = Math.max(1f, Math.round(sz * 0.12f));
            if (!isWallTileAt(row - 1, col, mapW, mapH)) shapes.rect(wx, wy, sz, stroke);
            if (!isWallTileAt(row + 1, col, mapW, mapH)) shapes.rect(wx, wy + sz - stroke, sz, stroke);
            if (!isWallTileAt(row, col - 1, mapW, mapH)) shapes.rect(wx, wy, stroke, sz);
            if (!isWallTileAt(row, col + 1, mapW, mapH)) shapes.rect(wx + sz - stroke, wy, stroke, sz);
        }
        shapes.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
        batch.begin();
    }

    // Tall = sprite taller than wide (e.g. 8x16): art carries its own top + front
    // face and renders at full aspect spilling one cell south, not extrusion bands.
    private static boolean isTallWallTile(Tile t) {
        final TextureRegion region = GameSpriteManager.TILE_SPRITES.get((int) t.getTileId());
        return region != null && region.getRegionHeight() > region.getRegionWidth();
    }

    // Full sprite height anchored at the wall's cell (lower half spills south as the
    // front face). Top-to-bottom so a wall below re-covers the spill from the one above.
    private void renderTallWalls(SpriteBatch batch, List<Tile> wallTiles) {
        this.tallWallScratch.clear();
        for (Tile t : wallTiles) {
            if (isTallWallTile(t)) this.tallWallScratch.add(t);
        }
        if (this.tallWallScratch.isEmpty()) return;
        this.tallWallScratch.sort((a, b) -> Long.compare(a.getRow(), b.getRow()));
        for (Tile t : this.tallWallScratch) {
            final int sz = t.getWidth();
            if (sz <= 0) continue;
            final TextureRegion region = GameSpriteManager.TILE_SPRITES.get((int) t.getTileId());
            if (region == null) continue;
            final float wx = t.getPos().getWorldVar().x;
            final float wy = t.getPos().getWorldVar().y;
            final float fullH = sz * (float) region.getRegionHeight() / region.getRegionWidth();
            batch.draw(region, wx, wy, sz, fullH);
        }
    }

    // Redraw only each tall wall's TOP FACE after entity bodies, so an entity behind
    // the wall is covered but one in front of the south-spilling front face renders over it.
    public void renderTallWallOcclusion(SpriteBatch batch) {
        this.tallWallScratch.clear();
        for (Tile t : this.wallTilesBuf) {
            if (isTallWallTile(t)) this.tallWallScratch.add(t);
        }
        if (this.tallWallScratch.isEmpty()) return;
        this.tallWallScratch.sort((a, b) -> Long.compare(a.getRow(), b.getRow()));
        for (Tile t : this.tallWallScratch) {
            final int sz = t.getWidth();
            if (sz <= 0) continue;
            final TextureRegion region = GameSpriteManager.TILE_SPRITES.get((int) t.getTileId());
            if (region == null) continue;
            final float wx = t.getPos().getWorldVar().x;
            final float wy = t.getPos().getWorldVar().y;
            final int wallId = (int) t.getTileId();
            TextureRegion topRegion = this.tallWallTopCache.get(wallId);
            if (topRegion == null) {
                final TileModel model = GameDataManager.TILES.get(wallId);
                if (model == null) continue;
                final int ssw = model.getSpriteSize();
                topRegion = new TextureRegion(region.getTexture(),
                        model.getCol() * ssw, model.getRow() * model.getEffectiveSpriteHeight(), ssw, ssw);
                topRegion.flip(false, true);
                this.tallWallTopCache.put(wallId, topRegion);
            }
            batch.draw(topRegion, wx, wy, sz, sz);
        }
    }

    // Draws one pre-baked feather region per side at base-tile type boundaries;
    // all feathers share one backing atlas so the pass flushes in a single GL call.
    private void drawTileSeams(SpriteBatch batch, int sxMin, int syMin,
            int screenTilesX, int screenTilesY, int ts, int mapW, int mapH) {
        final Map<Integer, TextureRegion[]> feathers = GameSpriteManager.TILE_FEATHERS;
        if (feathers == null) return;
        // Feathers baked at source resolution; scale to ts x featherPx on screen.
        final int featherPx = Math.max(2, Math.round(ts * 0.15f));
        final Object[][] baseBlocks = this.mapLayers.get(0).getBlocks();
        final Object[][] colBlocks  = this.mapLayers.get(1).getBlocks();
        for (int x = sxMin; x < sxMin + screenTilesX; x++) {
            for (int y = syMin; y < syMin + screenTilesY; y++) {
                if (x < 0 || y < 0 || x >= mapW || y >= mapH) continue;
                final Tile here = (Tile) baseBlocks[y][x];
                if (here == null || here.getTileId() <= 0) continue;
                // Walls never feather (conflicts with the Pass-2 extrusion bands).
                if (isWallCell(colBlocks, y, x, mapW, mapH)) continue;
                // noBlend tiles (e.g. carpets) keep a hard edge both ways.
                if (here.getData() != null && here.getData().noBlend()) continue;
                final int myType = here.getTileId();
                final int tN = (y - 1 >= 0)   ? tileIdAt(baseBlocks, y - 1, x) : 0;
                final int tS = (y + 1 < mapH) ? tileIdAt(baseBlocks, y + 1, x) : 0;
                final int tW = (x - 1 >= 0)   ? tileIdAt(baseBlocks, y, x - 1) : 0;
                final int tE = (x + 1 < mapW) ? tileIdAt(baseBlocks, y, x + 1) : 0;
                final boolean wN = isWallCell(colBlocks, y - 1, x, mapW, mapH);
                final boolean wS = isWallCell(colBlocks, y + 1, x, mapW, mapH);
                final boolean wW = isWallCell(colBlocks, y, x - 1, mapW, mapH);
                final boolean wE = isWallCell(colBlocks, y, x + 1, mapW, mapH);
                // Blend only with a different, non-wall, blend-enabled, color-distinct neighbor.
                final boolean dN = tN > 0 && tN != myType && !wN && !baseNoBlend(baseBlocks, y - 1, x, mapW, mapH) && GameSpriteManager.tilesShouldBlend(myType, tN);
                final boolean dS = tS > 0 && tS != myType && !wS && !baseNoBlend(baseBlocks, y + 1, x, mapW, mapH) && GameSpriteManager.tilesShouldBlend(myType, tS);
                final boolean dW = tW > 0 && tW != myType && !wW && !baseNoBlend(baseBlocks, y, x - 1, mapW, mapH) && GameSpriteManager.tilesShouldBlend(myType, tW);
                final boolean dE = tE > 0 && tE != myType && !wE && !baseNoBlend(baseBlocks, y, x + 1, mapW, mapH) && GameSpriteManager.tilesShouldBlend(myType, tE);
                if (!(dN || dS || dW || dE)) continue;
                final float wx = here.getPos().getWorldVar().x;
                final float wy = here.getPos().getWorldVar().y;
                if (dN) {
                    final TextureRegion[] v = feathers.get(tN);
                    if (v != null && v[0] != null) batch.draw(v[0], wx, wy,                       ts, featherPx);
                }
                if (dS) {
                    final TextureRegion[] v = feathers.get(tS);
                    if (v != null && v[1] != null) batch.draw(v[1], wx, wy + ts - featherPx,      ts, featherPx);
                }
                if (dW) {
                    final TextureRegion[] v = feathers.get(tW);
                    if (v != null && v[2] != null) batch.draw(v[2], wx, wy,                       featherPx, ts);
                }
                if (dE) {
                    final TextureRegion[] v = feathers.get(tE);
                    if (v != null && v[3] != null) batch.draw(v[3], wx + ts - featherPx, wy,      featherPx, ts);
                }
            }
        }
    }

    /** True if the BASE-layer tile at (row, col) opts out of edge feathering. */
    private static boolean baseNoBlend(Object[][] baseBlocks, int row, int col, int mapW, int mapH) {
        if (row < 0 || col < 0 || row >= mapH || col >= mapW) return false;
        final Tile t = (Tile) baseBlocks[row][col];
        return t != null && t.getData() != null && t.getData().noBlend();
    }

    /** True if the collision-layer cell at (row, col) is a wall. */
    private static boolean isWallCell(Object[][] colBlocks, int row, int col, int mapW, int mapH) {
        if (row < 0 || col < 0 || row >= mapH || col >= mapW) return false;
        final Tile t = (Tile) colBlocks[row][col];
        if (t == null || t.isVoid()) return false;
        return t.getData() != null && t.getData().isWall();
    }

    /** Safe Tile.getTileId() lookup that returns 0 when the cell is null. */
    private static int tileIdAt(Object[][] baseBlocks, int row, int col) {
        final Tile t = (Tile) baseBlocks[row][col];
        return t == null ? 0 : t.getTileId();
    }

    // Per-frame object-tile buffer (non-wall collision tiles), valid until the next
    // render() clears it. Used by PlayState's shadow pass.
    public List<Tile> getObjectTilesView() {
        return this.objectTilesBuf;
    }

    /** Same for over-water collision tiles (river stones). */
    public List<Tile> getOverWaterTilesView() {
        return this.overWaterTilesBuf;
    }

    public void releaseMapLock() {
    	this.mapLock.unlock();
    }

    public void acquireMapLock() {
    	this.mapLock.lock();
    }
}
