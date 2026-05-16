package com.openrealm.game.tile;

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

    /**
     * Per-tile fog-of-war flag. {@code discovered[y][x]} = true once the
     * player has had the tile inside the visible sight circle at least
     * once. Once flipped true, the tile renders at reduced brightness
     * even when the player walks back out of sight range — the standard
     * "explored but currently hidden" look you'd see in roguelikes /
     * RotMG. Lazily allocated on the first render() call so we don't
     * carry a stale array across map / realm transitions.
     */
    private boolean[][] discovered = null;
    private int discoveredW = 0;
    private int discoveredH = 0;
    /** Brightness multiplier for previously-discovered tiles. The web
     *  client draws the whole explored map at FULL opacity — the sight
     *  circle is only used to gate entity / projectile spawning, not
     *  to dim tiles. Keep at 1.0 so discovered terrain stays bright. */
    private static final float FOG_BRIGHTNESS = 1.0f;
    /** Fraction of tile size used as the fake-3D wall side-strip height. */
    private static final float WALL_HEIGHT_RATIO = 0.5f;
    /** Brightness multiplier applied to the side-strip texture so it reads as a shaded wall face. */
    private static final float WALL_SIDE_BRIGHTNESS = 0.55f;
    private static final Integer VIEWPORT_TILE_MAX = 20;
    /** Reusable per-frame tile-classification buffers. The render() pass
     *  used to allocate 5 fresh ArrayLists every frame — at 60 fps over
     *  a long session that's ~18k allocations a minute just for tile
     *  classification. With pre-allocated buffers we just clear() each
     *  frame; capacity grows once on first contact and stays. Single-
     *  threaded because render() runs only on the main thread. */
    private final List<Tile> wallTilesBuf       = new ArrayList<>(256);
    private final List<Tile> objectTilesBuf     = new ArrayList<>(64);
    private final List<Tile> decorationTilesBuf = new ArrayList<>(64);
    private final List<Tile> waterTilesBuf      = new ArrayList<>(128);
    private final List<Tile> overWaterTilesBuf  = new ArrayList<>(32);
    /** Reusable Vector2f for the per-frame normalized player position
     *  in render(). Used to be `new Vector2f(...)` every frame. */
    private final Vector2f posNormalizedBuf = new Vector2f();

    /** Per-tile highlight color cache, indexed by tileId. Sampled once
     *  from the wall sprite's pixmap (lightened by 35%) so the N+W
     *  edge highlight looks like the wall material's own light side
     *  rather than a hard pure-white band. Lazy-populated. */
    private static final java.util.Map<Integer, float[]> WALL_HIGHLIGHT_CACHE =
            new java.util.HashMap<>();
    /** Default highlight if we can't sample (sheet missing, etc.) — a
     *  warm off-white that reads softer than pure white on most
     *  ambient-tone walls. */
    private static final float[] WALL_HIGHLIGHT_FALLBACK =
            new float[] { 0.95f, 0.92f, 0.84f };

    /** Sample the dominant color of a wall tile sprite and return a
     *  lightened tint to use as the N/W edge highlight color. Cached
     *  per tileId. Falls back to a warm off-white if the texture's
     *  pixmap isn't readable (e.g. sheet hasn't been textured yet or
     *  is mip-loaded). */
    private static float[] wallHighlightColor(int tileId) {
        float[] cached = WALL_HIGHLIGHT_CACHE.get(tileId);
        if (cached != null) return cached;
        float[] result = WALL_HIGHLIGHT_FALLBACK;
        try {
            final TextureRegion region = com.openrealm.game.data.GameSpriteManager.TILE_SPRITES.get(tileId);
            if (region != null && region.getTexture() != null) {
                final com.badlogic.gdx.graphics.Texture tex = region.getTexture();
                if (tex.getTextureData() != null) {
                    if (!tex.getTextureData().isPrepared()) {
                        tex.getTextureData().prepare();
                    }
                    final com.badlogic.gdx.graphics.Pixmap pix = tex.getTextureData().consumePixmap();
                    if (pix != null) {
                        // Sample a small grid in the center of the
                        // region — averages out edge dithering / outlines.
                        final int rx = region.getRegionX();
                        final int ry = region.getRegionY();
                        final int rw = region.getRegionWidth();
                        final int rh = region.getRegionHeight();
                        long r = 0, g = 0, b = 0; int n = 0;
                        for (int dy = rh / 4; dy < rh - rh / 4; dy += 2) {
                            for (int dx = rw / 4; dx < rw - rw / 4; dx += 2) {
                                final int color = pix.getPixel(rx + dx, ry + dy);
                                final int ar = (color >> 24) & 0xFF;
                                if (ar < 16) continue; // skip transparent
                                r += (color >> 16) & 0xFF;
                                g += (color >>  8) & 0xFF;
                                b += (color      ) & 0xFF;
                                n++;
                            }
                        }
                        if (tex.getTextureData().disposePixmap()) pix.dispose();
                        if (n > 0) {
                            // Lighten by 35% toward white so the highlight
                            // reads as a brighter version of the same surface.
                            float fr = Math.min(1f, ((r / (float) n) / 255f) * 1.35f + 0.10f);
                            float fg = Math.min(1f, ((g / (float) n) / 255f) * 1.35f + 0.10f);
                            float fb = Math.min(1f, ((b / (float) n) / 255f) * 1.35f + 0.10f);
                            result = new float[] { fr, fg, fb };
                        }
                    }
                }
            }
        } catch (Exception ignored) { /* fall through to fallback */ }
        WALL_HIGHLIGHT_CACHE.put(tileId, result);
        return result;
    }
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
        // If zones are defined, spawn in the outermost zone (beach/shore),
        // biased toward the OUTER edge so new players land near the water and
        // not next to the next-tier zone (grasslands) where harder enemies wander.
        if (this.terrainParams != null && this.terrainParams.getZones() != null
                && !this.terrainParams.getZones().isEmpty()) {
            // Find the zone with the highest maxRadius (outermost)
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

    /**
     * Pick a safe random position inside a zone's radial band.
     * If {@code outerEdgeBias} is true, only the outer 25% of the zone's radial
     * band is considered — i.e. positions closest to the next-outer zone (or
     * the map edge / water for the outermost zone). Used for new-player spawns
     * so they land far from the next-tier zone.
     */
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
        return (currentTile != null) && !currentTile.isVoid();
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
        // Simple center-cell lookup. The previous AABB-based check used a 28x28
        // hitbox which mismatched the client's check, causing slow-tile detection
        // to disagree near tile boundaries and produce position desync / snapping.
        final Vector2f centerPos = e.getCenteredPosition();
        final int ts = this.getBaseLayer().getTileSize();
        final int tx = (int) (centerPos.x / (float) ts);
        final int ty = (int) (centerPos.y / (float) ts);
        if (ty < 0 || ty >= this.getBaseLayer().getBlocks().length
                || tx < 0 || tx >= this.getBaseLayer().getBlocks()[0].length) {
            return false;
        }
        final Tile currentTile = this.getBaseLayer().getBlocks()[ty][tx];
        if (currentTile == null || currentTile.getData() == null) return false;
        return currentTile.getData().slows();
    }
    
    public boolean collidesDamagingTile(Entity e) {
        final Vector2f centerPos = e.getCenteredPosition();
        final int startX = (int) (centerPos.x / (float) this.getBaseLayer().getTileSize());
        final int startY = (int) (centerPos.y / (float) this.getBaseLayer().getTileSize());

        final Tile currentTile = this.getBaseLayer().getBlocks()[startY][startX];

        final Rectangle tileBounds = new Rectangle(currentTile.getPos(), currentTile.getWidth(),
                currentTile.getHeight());
        final Rectangle futurePosBounds = new Rectangle(e.getPos(), (e.getSize() / 2), e.getSize() / 2);

        return currentTile.getData().damaging() && tileBounds.intersect(futurePosBounds);
    }

    public boolean collisionTile(Entity e, float ax, float ay) {
        final Vector2f futurePos = e.getPos().clone(ax, ay);
        // 85% hitbox for tile collision. Top-left anchored to match the client's
        // _checkCollision in game.js exactly.
        final int hitSize = (int) (e.getSize() * 0.85f);
        for (Tile t : this.getCollisionTiles(e.getPos())) {
            if ((t == null) || t.isVoid()) {
                continue;
            }
            // CRITICAL: respect the tile's hasCollision flag — many decoration
            // tiles (candles, decoration_4/5/6 in the nexus) live in the
            // collision layer but are visual-only with hasCollision=0. The
            // client filters these out; the server must too or the player
            // gets stuck on invisible blockers and the client snaps back.
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

    // Reusable viewport rectangles to avoid allocation every frame/tick
    private static final ThreadLocal<Rectangle> VIEWPORT_RECT = ThreadLocal.withInitial(
            () -> new Rectangle(new Vector2f(), 0, 0));
    private static final ThreadLocal<Vector2f> VIEWPORT_POS = ThreadLocal.withInitial(Vector2f::new);

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

    public void mergeMap(LoadMapPacket packet) {
    	// Acquire the map lock to prevent the render thread from displaying out of 
    	// date tile information
    	this.acquireMapLock();
        // Resize the map on dimension change
        if(this.getMapHeight()!=packet.getMapHeight() || this.getMapWidth()!=packet.getMapWidth()) {
           MapModel model = GameDataManager.MAPS.get((int)packet.getMapId());
           TileMap baseLayer = new TileMap((short) model.getMapId(), model.getTileSize(), model.getWidth(),
                   model.getHeight());
           TileMap collisionLayer = new TileMap((short) model.getMapId(), model.getTileSize(), model.getWidth(),
                   model.getHeight());
           this.mapLayers = new ArrayList<>();
           this.mapLayers.add(baseLayer);
           this.mapLayers.add(collisionLayer);
           // Map dimensions changed (realm transition, dungeon load) —
           // wipe the fog-of-war array so the new map starts fully
           // unexplored. Without this, a smaller map would index into
           // stale "discovered" rows from the previous one.
           this.discovered = null;
        }

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
        // Reuse the per-frame buffer instead of allocating a new Vector2f.
        final Vector2f posNormalized = this.posNormalizedBuf;
        posNormalized.x = pos.x / ts;
        posNormalized.y = pos.y / ts;
        this.normalizeToBounds(posNormalized);

        // Lazy-allocate fog-of-war array sized to the current map.
        final int mapW = this.getBaseLayer().getWidth();
        final int mapH = this.getBaseLayer().getHeight();
        if (this.discovered == null || this.discoveredW != mapW || this.discoveredH != mapH) {
            this.discovered = new boolean[mapH][mapW];
            this.discoveredW = mapW;
            this.discoveredH = mapH;
        }

        // FOG-OF-WAR PASS: draw tiles that have previously been seen but
        // are not currently inside the sight circle, dimmed at
        // FOG_BRIGHTNESS. Bounded to the screen viewport rectangle so we
        // don't iterate the whole map. This runs BEFORE the in-sight
        // pass so the bright tiles drawn next overpaint any overlap.
        final float worldViewW = OpenRealmGame.width / OpenRealmGame.WORLD_SCALE;
        final float worldViewH = OpenRealmGame.height / OpenRealmGame.WORLD_SCALE;
        final int screenTilesX = (int) Math.ceil(worldViewW / ts) + 2;
        final int screenTilesY = (int) Math.ceil(worldViewH / ts) + 2;
        // Derive the scan origin from the actual camera viewport (the
        // world-space coord of the screen's top-left corner) rather than
        // a player-centered window. The HUD panel on the right shifts
        // map.x left by ~hudPanelWorldW/2, so a symmetric posNormalized-
        // centered scan misses the rightmost ~3 visible tiles. Walls in
        // that strip stayed out of wallTiles (and out of wallSet for
        // adjacency) until the player moved close enough that the
        // symmetric window covered them — visually that read as walls
        // "popping" their 3D shadow only when you walked toward them.
        final int sxMin = (int) Math.floor(Vector2f.worldX / ts);
        final int syMin = (int) Math.floor(Vector2f.worldY / ts);
        final float radiusSqInner = VIEWPORT_TILE_MIN * VIEWPORT_TILE_MIN;
        batch.setColor(FOG_BRIGHTNESS, FOG_BRIGHTNESS, FOG_BRIGHTNESS, 1f);
        for (int sx = sxMin; sx < sxMin + screenTilesX; sx++) {
            for (int sy = syMin; sy < syMin + screenTilesY; sy++) {
                if (sx < 0 || sy < 0 || sx >= mapW || sy >= mapH) continue;
                if (!this.discovered[sy][sx]) continue;
                float ddx = sx - posNormalized.x;
                float ddy = sy - posNormalized.y;
                if (ddx * ddx + ddy * ddy <= radiusSqInner) continue; // in-sight, drawn brightly below
                Tile baseTile = (Tile) this.mapLayers.get(0).getBlocks()[sy][sx];
                if (baseTile != null) baseTile.render(batch);
                Tile colTile = (Tile) this.mapLayers.get(1).getBlocks()[sy][sx];
                if (colTile != null && !colTile.isVoid()) colTile.render(batch);
            }
        }
        batch.setColor(1f, 1f, 1f, 1f);

        // Separate collision layer tiles into walls, objects, and decorations.
        // Reuse the per-frame buffers (cleared each call) so a busy realm
        // doesn't churn 5 fresh ArrayLists × 60 fps through young-gen.
        final List<Tile> wallTiles       = this.wallTilesBuf;       wallTiles.clear();
        final List<Tile> objectTiles     = this.objectTilesBuf;     objectTiles.clear();
        final List<Tile> decorationTiles = this.decorationTilesBuf; decorationTiles.clear();
        final List<Tile> waterTiles      = this.waterTilesBuf;      waterTiles.clear();
        // Collision/decoration tiles whose BASE tile is water (e.g. stones,
        // tile 168, lining the nexus river edges). Pass 5 redraws every
        // water tile on top to prevent shadow-ellipses from bleeding into
        // the river — the side effect was that anything drawn from
        // decorationTiles over water got clobbered. We render this list
        // in its own pass AFTER the water redraw so the stones land on
        // top of the water surface like they should.
        final List<Tile> overWaterTiles  = this.overWaterTilesBuf;  overWaterTiles.clear();

        // FIRST: scan the FULL SCREEN VIEWPORT (much larger than the
        // 10-tile sight square) for walls. Every wall the camera shows
        // — even ones past the fog-of-war circle — gets queued into
        // wallTiles so the 3D extrusion is applied uniformly across
        // the whole visible scene. Without this, walls drew as flat
        // textures past the fog circle (visible ring boundary).
        //
        // Also scan a 2-tile PADDING beyond the viewport edges so the
        // adjacency check (wallSet.contains(neighbor)) sees off-screen
        // wall neighbours and doesn't draw a phantom shadow band on
        // edge walls just because their neighbour scrolled off-screen.
        // The user reported 'walls flickering different shading as I
        // move' — that was the adjacency map flipping at the screen
        // boundary every time a neighbour entered/left the viewport.
        // Padding-only walls aren't rendered themselves (they live
        // outside the camera) but populate wallSet so the visible
        // walls' adjacency stays stable.
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

        // Pass 1: Draw all base tiles (circular viewport) and classify
        // collision layer tiles INSIDE the 10-tile sight square. Walls
        // are already in wallTiles from the screen-viewport scan above
        // (we re-add them here too — duplicates are filtered by the
        // wallSet HashSet during 3D render). Non-wall in-sight tiles
        // route into objectTiles / decorationTiles / overWaterTiles.
        final float radiusSq = VIEWPORT_TILE_MIN * VIEWPORT_TILE_MIN;
        for (int x = (int) (posNormalized.x - VIEWPORT_TILE_MIN); x < (posNormalized.x + VIEWPORT_TILE_MIN); x++) {
            for (int y = (int) (posNormalized.y - VIEWPORT_TILE_MIN); y < (int) (posNormalized.y + VIEWPORT_TILE_MIN); y++) {
                if ((x >= this.getBaseLayer().getWidth()) || (y >= this.getBaseLayer().getHeight()) || (x < 0)
                        || (y < 0)) {
                    continue;
                }
                float dx = x - posNormalized.x;
                float dy = y - posNormalized.y;
                final boolean insideCircle = (dx * dx + dy * dy) <= radiusSq;
                try {
                    Tile normalTile = (Tile) this.mapLayers.get(0).getBlocks()[y][x];
                    Tile collisionTile = (Tile) this.mapLayers.get(1).getBlocks()[y][x];
                    final boolean isWallTile = collisionTile != null
                            && !collisionTile.isVoid()
                            && collisionTile.getData() != null
                            && collisionTile.getData().isWall();
                    // Skip strictly non-wall tiles outside the circle —
                    // they're the fog-of-war hidden region. Walls keep
                    // rendering so the level geometry stays continuous.
                    if (!insideCircle && !isWallTile) continue;
                    // Mark this tile as discovered for future fog-of-war passes.
                    this.discovered[y][x] = true;

                    if (normalTile != null && insideCircle) {
                        normalTile.render(batch);
                        boolean isWaterTile = normalTile.getData() != null && normalTile.getData().slows()
                                && !normalTile.getData().hasCollision();
                        if (isWaterTile) {
                            waterTiles.add(normalTile);
                        }
                    }

                    // Classify collision layer tiles
                    if (collisionTile != null && !collisionTile.isVoid()) {
                        boolean baseIsWater = normalTile != null && normalTile.getData() != null
                                && normalTile.getData().slows() && !normalTile.getData().hasCollision();
                        if (isWallTile) {
                            // Walls already added to wallTiles by the
                            // screen-viewport scan above — skip here so
                            // we don't render the 3D shadow/highlight
                            // twice for inside-circle walls.
                        } else if (baseIsWater && insideCircle) {
                            // Collision tile sitting on water (stones, etc.) —
                            // route to the AFTER-WATER pass so Pass 5's water
                            // redraw doesn't paint over them.
                            overWaterTiles.add(collisionTile);
                        } else if (insideCircle && collisionTile.getData() != null && collisionTile.getData().hasCollision()) {
                            objectTiles.add(collisionTile);
                        } else if (insideCircle) {
                            decorationTiles.add(collisionTile);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        // Pass 1.5: Base-tile edge texture blending. For each in-sight base
        // tile, sample the 4 cardinal neighbors' tile types; if a neighbor
        // differs, draw 3 thin strips of the NEIGHBOR'S sprite extending
        // into this tile from the shared edge, with decreasing alpha. The
        // alpha falloff simulates a gradient mask without needing a real
        // mask texture, and using the neighbor's actual sprite as the
        // source means the seam shows the neighbor's terrain "bleeding"
        // into this tile — real visual blending, not a darkening vignette.
        // Stays inside the active SpriteBatch (no ShapeRenderer state
        // swap) by emitting batch.draw() calls per strip.
        drawTileSeams(batch, posNormalized, radiusSq, ts, mapW, mapH);

        // Pass 2: Render wall tiles with 3D effect (shadow + side face + shader outline)
        if (!wallTiles.isEmpty()) {
            // Shadow: small offset, flush with tile bottom
            ShaderManager.applyEffect(batch, Sprite.EffectEnum.SILHOUETTE);
            batch.setColor(1, 1, 1, 0.25f);
            for (Tile t : wallTiles) {
                TextureRegion region = GameSpriteManager.TILE_SPRITES.get((int) t.getTileId());
                if (region == null) continue;
                float wx = t.getPos().getWorldVar().x;
                float wy = t.getPos().getWorldVar().y;
                int sz = t.getWidth();
                batch.draw(region, wx + 1, wy + 1, sz, sz);
            }
            batch.setColor(1, 1, 1, 1);
            ShaderManager.clearEffect(batch);

            // Fake-3D wall extrusion. Mirrors the webclient (renderer.js
            // Pass 2 isWall block): solid black bands with an alpha gradient
            // on every wall edge that does NOT face another wall, plus white
            // top-light highlights on the N and W edges of edge-walls.
            //
            // Adjacency lookup is by tile grid coords. Walls sharing a face
            // skip that face's bands so internal seams stay clean.
            java.util.HashSet<Long> wallSet = new java.util.HashSet<>(wallTiles.size() * 2);
            for (Tile t : wallTiles) {
                int sz = t.getWidth();
                if (sz <= 0) continue;
                long col = (long) Math.floor(t.getPos().getWorldVar().x / sz);
                long row = (long) Math.floor(t.getPos().getWorldVar().y / sz);
                wallSet.add((row << 32) | (col & 0xffffffffL));
            }

            batch.end();
            Gdx.gl.glEnable(GL20.GL_BLEND);
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            for (Tile t : wallTiles) {
                int sz = t.getWidth();
                if (sz <= 0) continue;
                float wx = t.getPos().getWorldVar().x;
                float wy = t.getPos().getWorldVar().y;
                long col = (long) Math.floor(wx / sz);
                long row = (long) Math.floor(wy / sz);
                boolean wN = wallSet.contains(((row - 1) << 32) | (col & 0xffffffffL));
                boolean wS = wallSet.contains(((row + 1) << 32) | (col & 0xffffffffL));
                boolean wW = wallSet.contains((row << 32) | ((col - 1) & 0xffffffffL));
                boolean wE = wallSet.contains((row << 32) | ((col + 1) & 0xffffffffL));

                // Wall extrusion bands. 6-8 thin 1px stripes per face instead
                // of 3 chunky stripes — smoother alpha falloff so the wall→
                // floor transition reads as a soft gradient instead of three
                // discrete steps (which produced visible stair-step jaggies
                // on diagonal wall layouts).
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
                t.render(batch);
            }

            // N + W highlights on edge walls (top-light from NW). Drawn
            // after the top tile so they sit on top of the wall texture's
            // edge. Colour tinted from the tile's own dominant color
            // (looked up in WALL_HIGHLIGHT_CACHE) and lightened by ~35%,
            // so each material gets a highlight that reads as "the same
            // surface, brighter" rather than the previous pure-white
            // band that looked harsh on dark walls (stone, dungeon).
            batch.end();
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            for (Tile t : wallTiles) {
                int sz = t.getWidth();
                if (sz <= 0) continue;
                float wx = t.getPos().getWorldVar().x;
                float wy = t.getPos().getWorldVar().y;
                long col = (long) Math.floor(wx / sz);
                long row = (long) Math.floor(wy / sz);
                boolean wN = wallSet.contains(((row - 1) << 32) | (col & 0xffffffffL));
                boolean wW = wallSet.contains((row << 32) | ((col - 1) & 0xffffffffL));
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

        // Pass 3: Render object tiles (collision decorations) with circular shadow
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
                t.render(batch);
            }
        }

        // Pass 4: Draw decorative (non-collision) tiles from collision layer
        for (Tile t : decorationTiles) {
            t.render(batch);
        }

        // Pass 5: Redraw water tiles on top so shadows don't cover them
        for (Tile t : waterTiles) {
            t.render(batch);
        }

        // Pass 6: Collision tiles whose base is water (stones lining
        // the river, etc.). Drawn AFTER the water redraw so the water
        // doesn't paint over them. Without this, stones-on-water were
        // visible only via the fog pass (when out of sight) and
        // disappeared the moment the player got close enough for the
        // bright pass to take over — exactly the user-reported
        // 'inverted viewport' behaviour.
        for (Tile t : overWaterTiles) {
            t.render(batch);
        }

        this.releaseMapLock();
    }

    /**
     * Multi-strip texture-blend at base-tile type boundaries with per-
     * segment RANDOM DEPTH. Each seam is split into SEAM_SEGMENTS narrow
     * segments along its length; each segment gets a deterministic
     * pseudo-random depth multiplier seeded by (col,row,segment,side)
     * — same value every frame so the seam doesn't shimmer, but each
     * segment varies independently so the seam looks irregular and
     * organic instead of three clean uniform stripes (which read as
     * "banding" at high-contrast terrain boundaries).
     * Stays inside the active SpriteBatch (no state swap).
     */
    private void drawTileSeams(SpriteBatch batch, Vector2f posNormalized,
            float radiusSq, int ts, int mapW, int mapH) {
        final float[] stripeAlphas = { 0.62f, 0.36f, 0.15f };
        final int xMin = (int) (posNormalized.x - VIEWPORT_TILE_MIN);
        final int xMax = (int) (posNormalized.x + VIEWPORT_TILE_MIN);
        final int yMin = (int) (posNormalized.y - VIEWPORT_TILE_MIN);
        final int yMax = (int) (posNormalized.y + VIEWPORT_TILE_MIN);
        final int segCount = 8;
        final int baseDepthPx = Math.max(3, Math.round(ts * 0.26f));
        final int segLen = Math.max(2, ts / segCount);
        final Object[][] baseBlocks = this.mapLayers.get(0).getBlocks();
        for (int x = xMin; x < xMax; x++) {
            for (int y = yMin; y < yMax; y++) {
                if (x < 0 || y < 0 || x >= mapW || y >= mapH) continue;
                final float dx = x - posNormalized.x;
                final float dy = y - posNormalized.y;
                if (dx * dx + dy * dy > radiusSq) continue;
                final Tile here = (Tile) baseBlocks[y][x];
                if (here == null) continue;
                final int myType = here.getTileId();
                final int tN = (y - 1 >= 0)   ? tileIdAt(baseBlocks, y - 1, x) : 0;
                final int tS = (y + 1 < mapH) ? tileIdAt(baseBlocks, y + 1, x) : 0;
                final int tW = (x - 1 >= 0)   ? tileIdAt(baseBlocks, y, x - 1) : 0;
                final int tE = (x + 1 < mapW) ? tileIdAt(baseBlocks, y, x + 1) : 0;
                final boolean dN = tN > 0 && tN != myType;
                final boolean dS = tS > 0 && tS != myType;
                final boolean dW = tW > 0 && tW != myType;
                final boolean dE = tE > 0 && tE != myType;
                if (!(dN || dS || dW || dE)) continue;
                final float wx = here.getPos().getWorldVar().x;
                final float wy = here.getPos().getWorldVar().y;
                if (dN) drawSeamFringe(batch, GameSpriteManager.TILE_SPRITES.get(tN),
                        x, y, 1, segCount, segLen, baseDepthPx, stripeAlphas, ts,
                        SeamSide.NORTH, wx, wy);
                if (dS) drawSeamFringe(batch, GameSpriteManager.TILE_SPRITES.get(tS),
                        x, y, 2, segCount, segLen, baseDepthPx, stripeAlphas, ts,
                        SeamSide.SOUTH, wx, wy);
                if (dW) drawSeamFringe(batch, GameSpriteManager.TILE_SPRITES.get(tW),
                        x, y, 3, segCount, segLen, baseDepthPx, stripeAlphas, ts,
                        SeamSide.WEST, wx, wy);
                if (dE) drawSeamFringe(batch, GameSpriteManager.TILE_SPRITES.get(tE),
                        x, y, 4, segCount, segLen, baseDepthPx, stripeAlphas, ts,
                        SeamSide.EAST, wx, wy);
            }
        }
        batch.setColor(1f, 1f, 1f, 1f);
    }

    private enum SeamSide { NORTH, SOUTH, WEST, EAST }

    /**
     * Emit the per-segment randomized fringe for one side of a tile. Each
     * segment along the edge gets a depth = baseDepth * [0.35..1.40] so
     * adjacent segments have visibly different fringe depths, breaking the
     * banded look.
     */
    private void drawSeamFringe(SpriteBatch batch, TextureRegion tex,
            int col, int row, int sideId, int segCount, int segLen,
            int baseDepth, float[] stripeAlphas, int ts, SeamSide side,
            float wx, float wy) {
        if (tex == null) return;
        for (int s = 0; s < segCount; s++) {
            final float rnd = seamHash(col, row, s, sideId);
            final int depth = Math.max(1, Math.round(baseDepth * (0.35f + rnd * 1.05f)));
            final int bh = Math.max(1, (int) Math.ceil(depth / 3.0));
            final boolean lastSeg = (s == segCount - 1);
            for (int k = 0; k < 3; k++) {
                batch.setColor(1f, 1f, 1f, stripeAlphas[k]);
                switch (side) {
                    case NORTH:
                        batch.draw(tex, wx + s * segLen, wy + k * bh,
                                lastSeg ? (ts - s * segLen) : segLen, bh);
                        break;
                    case SOUTH:
                        batch.draw(tex, wx + s * segLen, wy + ts - (k + 1) * bh,
                                lastSeg ? (ts - s * segLen) : segLen, bh);
                        break;
                    case WEST:
                        batch.draw(tex, wx + k * bh, wy + s * segLen,
                                bh, lastSeg ? (ts - s * segLen) : segLen);
                        break;
                    case EAST:
                        batch.draw(tex, wx + ts - (k + 1) * bh, wy + s * segLen,
                                bh, lastSeg ? (ts - s * segLen) : segLen);
                        break;
                }
            }
        }
    }

    /** Cheap deterministic hash of (col, row, segment, side) -> [0..1]. */
    private static float seamHash(int a, int b, int c, int d) {
        int h = a * 374761393 + b * 668265263 + c * 2147483647 + d * 7919;
        h ^= h >>> 13; h *= 1274126177; h ^= h >>> 16;
        return ((h & 0xffff)) / 65535f;
    }

    /** Safe Tile.getTileId() lookup that returns 0 when the cell is null. */
    private static int tileIdAt(Object[][] baseBlocks, int row, int col) {
        final Tile t = (Tile) baseBlocks[row][col];
        return t == null ? 0 : t.getTileId();
    }

    public void releaseMapLock() {
    	this.mapLock.unlock();
    }

    public void acquireMapLock() {
    	this.mapLock.lock();
    }
}
