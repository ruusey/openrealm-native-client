package com.openrealm.net.realm;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import com.openrealm.account.dto.ChestDto;
import com.openrealm.account.dto.GameItemRefDto;
import com.openrealm.account.dto.PlayerAccountDto;
import com.openrealm.game.contants.GlobalConstants;
import com.openrealm.game.contants.LootTier;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.entity.Bullet;
import com.openrealm.game.entity.Enemy;
import com.openrealm.game.entity.GameObject;
import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.Portal;
import com.openrealm.game.entity.item.Chest;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.entity.item.LootContainer;
import com.openrealm.game.math.Rectangle;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.model.DungeonGraphNode;
import com.openrealm.game.model.EnemyGroup;
import com.openrealm.game.model.EnemyModel;
import com.openrealm.game.model.MapModel;
import com.openrealm.game.model.OverworldZone;
import com.openrealm.game.model.ProjectileGroup;
import com.openrealm.game.model.TerrainGenerationParameters;
import com.openrealm.game.tile.TileManager;
import com.openrealm.net.client.packet.LoadPacket;
import com.openrealm.net.client.packet.ObjectMovePacket;
import com.openrealm.net.client.packet.UpdatePacket;
import com.openrealm.net.entity.NetObjectMovement;
import com.openrealm.net.client.ClientGameLogic;
import com.openrealm.util.GameObjectUtils;
import com.openrealm.util.WorkerThread;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import com.openrealm.game.model.SetPiece;
import com.openrealm.game.model.SetPieceModel;
import com.openrealm.game.model.StaticSpawn;
import com.openrealm.game.tile.Tile;
import com.openrealm.game.tile.TileData;
import java.util.HashSet;

@Data
@AllArgsConstructor
@Slf4j
public class Realm {
    public static final transient SecureRandom RANDOM = new SecureRandom();
    private long realmId;
    private int mapId;
    // > -1 when this realm is an assembled dungeon; client learns it from LoadMapPacket.
    private int dungeonId = -1;
    // Client-only: loadMap() sets true on a client transition; the next LoadMap handler
    // resets tiles/minimap even when the new realm reuses the prior realm/map id.
    private boolean tileGridRebuilt;
    private String nodeId;
    // Parent realm to return the player to (cowardice + boss-exit portals). 0 = shared realm.
    private long sourceRealmId;
    // Designated boss enemyId; its death drops an exit portal. 0 = none.
    private int dungeonBossEnemyId;
    // Purification snapshot from RealmPurificationPacket; drives the overworld bar.
    private long purificationProgress;
    private long purificationGoal;
    private float purificationDifficulty;
    private int purificationTier;
    private String purificationModifiers = "";
    private Map<Long, Player> players;
    private Map<Long, Bullet> bullets;
    private Map<Long, List<Long>> bulletHits;
    private Map<Long, Enemy> enemies;
    private int initialEnemyCount; // Snapshot after initial spawn, used for respawn threshold
    private Map<Long, LootContainer> loot;
    private Map<Long, Portal> portals;

    private List<Long> expiredEnemies;
    private List<Long> expiredBullets;
    private List<Long> expiredPlayers;
    private Map<Long, Long> playerLastShotTime;
    private TileManager tileManager;
    private ShortIdAllocator shortIdAllocator = new ShortIdAllocator();
    private final ReentrantLock playerLock = new ReentrantLock();

    // Cell size = viewport radius.
    private transient SpatialHashGrid spatialGrid;
    // Per-tick caches shared across viewers in the same realm; cleared at the top of
    // each enqueueGameData() pass so each entity/player is built once per tick.
    private transient Map<Long, NetObjectMovement> tickMovementCache;
    private transient Map<Long, UpdatePacket> tickStrippedUpdateCache;

    private boolean isServer;
    private boolean shutdown = false;

    public Realm(boolean isServer, int mapId) {
        this.realmId = Realm.RANDOM.nextLong();
        this.players = new ConcurrentHashMap<>();
        this.isServer = isServer;
        this.expiredEnemies = new ArrayList<>();
        this.expiredPlayers = new ArrayList<>();
        this.expiredBullets = new ArrayList<>();
        this.playerLastShotTime = new HashMap<>();
        this.spatialGrid = new SpatialHashGrid(10 * GlobalConstants.BASE_TILE_SIZE);
        this.loadMap(mapId);
        if (this.isServer) {
            WorkerThread.submitAndForkRun(this.getStatsThread());
        }
    }

    public Realm(boolean isServer, int mapId, String nodeId) {
        this(isServer, mapId);
        this.nodeId = nodeId;
    }

    public boolean isShared() {
        if (this.nodeId != null && GameDataManager.DUNGEON_GRAPH != null) {
            DungeonGraphNode node = GameDataManager.DUNGEON_GRAPH.get(this.nodeId);
            if (node != null) return node.isShared();
        }
        return false;
    }

    public boolean isDungeonInstance() {
        return this.sourceRealmId != 0L;
    }

    public boolean isOverworld() {
        if (this.nodeId != null && GameDataManager.DUNGEON_GRAPH != null) {
            DungeonGraphNode node = GameDataManager.DUNGEON_GRAPH.get(this.nodeId);
            if (node != null) return node.isEntryPoint() || node.isShared();
        }
        return false;
    }

    public List<Long> getExpiredPlayers() {
        return this.expiredPlayers;
    }
    
    public Set<Player> getPlayersExcept(long playerId){
    	return this.players.values().stream().filter(p->p.getId()!=playerId).collect(Collectors.toSet());
    }

    public void setupChests(final Player player) {
        try {
            final PlayerAccountDto account = ClientGameLogic.DATA_SERVICE
                    .executeGet("/data/account/" + player.getAccountUuid(), null, PlayerAccountDto.class);
            final List<ChestDto> vaultChests = account.getPlayerVault();
            final int count = vaultChests.size();
            if (count == 0) return;

            // 2-column grid centered in the 32x32-tile vault room (center = pixel 512,512).
            final int cols = 2;
            final int rows = (int) Math.ceil(count / (double) cols);
            final int spacingX = 64;
            final int spacingY = 48;
            final float centerX = 16 * 32;
            final float startY = 16 * 32 - (rows * spacingY) / 2f + spacingY / 2f;
            final float leftColX = centerX - spacingX;
            final float rightColX = centerX + spacingX;

            for (int i = 0; i < count; i++) {
                final ChestDto chest = vaultChests.get(i);
                final int col = i % cols;
                final int row = i / cols;
                final float x = col == 0 ? leftColX : rightColX;
                final float y = startY + row * spacingY;

                final List<GameItem> itemsInChest = chest.getItems().stream()
                        .map(GameItem::fromGameItemRef).collect(Collectors.toList());
                final Chest toSpawn = new Chest(new Vector2f(x, y),
                        itemsInChest.toArray(new GameItem[8]));
                toSpawn.setSoulboundPlayerId(player.getId());
                this.addLootContainer(toSpawn);
            }
        } catch (Exception e) {
            Realm.log.error("Failed to get player account for chests. Reason: {}", e);
        }
    }

    public List<ChestDto> serializeChests() {
        final List<ChestDto> result = new ArrayList<ChestDto>();
        int ordinal = 0;
        for (final LootContainer container : this.loot.values()) {
            if (container instanceof Chest) {
                final ChestDto chest = ChestDto.builder().chestId(container.getUid()).chestUuid(container.getUid())
                        .ordinal(ordinal++).build();
                final List<GameItemRefDto> itemRefs = new ArrayList<>();
                for (int i = 0; i < container.getItems().length; i++) {
                    final GameItem toCopy = container.getItems()[i];
                    if (toCopy != null) {
                        itemRefs.add(GameItemRefDto.builder().itemId(toCopy.getItemId()).itemUuid(toCopy.getUid())
                                .slotIdx(i).build());
                    }
                }
                chest.setItems(itemRefs);
                result.add(chest);
            }
        }
        return result;
    }

    public void loadMap(int mapId) {
        this.mapId = mapId;
        this.bullets = new ConcurrentHashMap<>();
        this.enemies = new ConcurrentHashMap<>();
        this.loot = new ConcurrentHashMap<>();
        this.portals = new ConcurrentHashMap<>();

        this.bulletHits = new ConcurrentHashMap<>();
        if (this.isServer) {
            this.tileManager = new TileManager(mapId);
        } else {
            // Dungeons (mapId -1) have no MapModel - keep the current grid; the incoming
            // LoadMapPacket rebuilds it. Building from a null model here NPE'd the transition.
            final MapModel model = GameDataManager.MAPS.get(mapId);
            if (model != null) {
                this.tileManager = new TileManager(model);
            }
            this.tileGridRebuilt = true;
        }
    }
    
    public void clearData() {
        this.bullets = new ConcurrentHashMap<>();
        this.enemies = new ConcurrentHashMap<>();
        this.loot = new ConcurrentHashMap<>();
        this.portals = new ConcurrentHashMap<>();
        this.players = new ConcurrentHashMap<>();
        this.bulletHits = new ConcurrentHashMap<>();
        this.expiredEnemies = new ArrayList<>();
        this.expiredEnemies = new ArrayList<>();
        this.expiredPlayers = new ArrayList<>();
        this.playerLastShotTime = new ConcurrentHashMap<>();
        if (this.spatialGrid != null) {
            this.spatialGrid.clear();
        }
    }

    public long addPlayer(Player player) {
        this.acquirePlayerLock();
        this.players.put(player.getId(), player);
        if (this.spatialGrid != null) {
            this.spatialGrid.insert(player.getId(), player.getPos().x, player.getPos().y);
        }
        this.shortIdAllocator.getOrAssign(player.getId());
        this.releasePlayerLock();
        return player.getId();
    }
    
    public long addPlayerIfNotExists(Player player) {
        if (!this.players.containsKey(player.getId())) {
            this.acquirePlayerLock();
            this.players.put(player.getId(), player);
            if (this.spatialGrid != null) {
                this.spatialGrid.insert(player.getId(), player.getPos().x, player.getPos().y);
            }
            this.releasePlayerLock();
        }
        return player.getId();
    }

    public boolean removePlayer(Player player) {
        this.acquirePlayerLock();
        this.playerLastShotTime.remove(player.getId());
        final Player p = this.players.remove(player.getId());
        if (this.spatialGrid != null) {
            this.spatialGrid.remove(player.getId());
        }
        this.shortIdAllocator.release(player.getId());
        this.releasePlayerLock();
        if (p != null) {
            p.onRemoved();
        }
        return p != null;
    }

    public boolean hasHitEnemy(long bulletId, long enemyId) {
        return (this.bulletHits.get(bulletId) != null) && this.bulletHits.get(bulletId).contains(enemyId);
    }

    public void clearHitMap() {
        this.bulletHits.clear();
    }

    public void hitEnemy(long bulletId, long enemyId) {
        if (this.bulletHits.get(bulletId) == null) {
            final List<Long> hits = new ArrayList<>();
            hits.add(enemyId);
            this.bulletHits.put(bulletId, hits);
        } else {
            final List<Long> curr = this.bulletHits.get(bulletId);
            curr.add(enemyId);
            this.bulletHits.put(bulletId, curr);
        }
    }

    public boolean removePlayer(long playerId) {
        this.acquirePlayerLock();
        final Player p = this.players.remove(playerId);
        if (this.spatialGrid != null) {
            this.spatialGrid.remove(playerId);
        }
        this.shortIdAllocator.release(playerId);
        this.releasePlayerLock();
        return p != null;
    }

    public Player getPlayer(long playerId) {
        this.acquirePlayerLock();
        final Player p = this.players.get(playerId);
        this.releasePlayerLock();
        return p;
    }
    
    public Bullet getBullet(long bulletId) {
        return this.bullets.get(bulletId);
    }

    public long addBullet(Bullet b) {
        this.bullets.put(b.getId(), b);
        if (this.spatialGrid != null) {
            this.spatialGrid.insert(b.getId(), b.getPos().x, b.getPos().y);
        }
        return b.getId();
    }

    public long addBulletIfNotExists(Bullet b) {
        final Bullet existing = this.bullets.get(b.getId());
        if (existing == null) {
            this.bullets.put(b.getId(), b);
            if (this.spatialGrid != null) {
                this.spatialGrid.insert(b.getId(), b.getPos().x, b.getPos().y);
            }
        }
        return b.getId();
    }

    public boolean removeBullet(Bullet b) {
        final Bullet bullet = this.bullets.remove(b.getId());
        this.bulletHits.remove(b.getId());
        if (this.spatialGrid != null) {
            this.spatialGrid.remove(b.getId());
        }
        return bullet != null;
    }

    public boolean removeBullet(Collection<Long> b) {
        for (Long l : b) {
            this.bullets.remove(l);
            this.bulletHits.remove(l);
            if (this.spatialGrid != null) {
                this.spatialGrid.remove(l);
            }
        }
        return true;
    }

    public long addPortal(Portal portal) {
        this.portals.put(portal.getId(), portal);
        if (this.spatialGrid != null) {
            this.spatialGrid.insert(portal.getId(), portal.getPos().x, portal.getPos().y);
        }
        return portal.getId();
    }

    public boolean removePortal(long portalId) {
        final Portal removed = this.portals.remove(portalId);
        if (this.spatialGrid != null) {
            this.spatialGrid.remove(portalId);
        }
        return removed != null;
    }

    public boolean removePortal(Portal portal) {
        final Portal removed = this.portals.remove(portal.getId());
        if (this.spatialGrid != null) {
            this.spatialGrid.remove(portal.getId());
        }
        return removed != null;
    }

    public long addPortalIfNotExists(Portal portal) {
        final Portal existing = this.portals.get(portal.getId());
        if (existing == null) {
            this.portals.put(portal.getId(), portal);
            if (this.spatialGrid != null) {
                this.spatialGrid.insert(portal.getId(), portal.getPos().x, portal.getPos().y);
            }
        }
        return portal.getId();
    }

    public long addEnemy(Enemy enemy) {
        this.enemies.put(enemy.getId(), enemy);
        if (this.spatialGrid != null) {
            this.spatialGrid.insert(enemy.getId(), enemy.getPos().x, enemy.getPos().y);
        }
        this.shortIdAllocator.getOrAssign(enemy.getId());
        return enemy.getId();
    }

    public long addEnemyIfNotExists(Enemy enemy) {
        final Enemy existing = this.enemies.get(enemy.getId());
        if (existing == null) {
            final EnemyModel model = GameDataManager.ENEMIES.get(enemy.getEnemyId());
            if (model != null) {
                enemy.setModel(model);
                if (enemy.getStats() == null) {
                    enemy.setStats(model.getStats().clone());
                }
                enemy.setChaseRange((int) model.getChaseRange());
                enemy.setAttackRange((int) model.getAttackRange());
            }
            this.enemies.put(enemy.getId(), enemy);
            if (this.spatialGrid != null) {
                this.spatialGrid.insert(enemy.getId(), enemy.getPos().x, enemy.getPos().y);
            }
        }
        return enemy.getId();
    }

    public Enemy getEnemy(long enemyId) {
        return this.enemies.get(enemyId);
    }

    public boolean removeEnemy(Enemy enemy) {
        final Enemy e = this.enemies.remove(enemy.getId());
        if (this.spatialGrid != null) {
            this.spatialGrid.remove(enemy.getId());
        }
        this.shortIdAllocator.release(enemy.getId());
        if (e != null) {
            e.onRemoved();
        }
        return e != null;
    }

    public long addLootContainer(LootContainer lc) {
        long randomId = Realm.RANDOM.nextLong();
        lc.setLootContainerId(randomId);
        this.loot.put(randomId, lc);
        if (this.spatialGrid != null) {
            this.spatialGrid.insert(randomId, lc.getPos().x, lc.getPos().y);
        }
        return randomId;
    }

    public long addLootContainerIfNotExists(LootContainer lc) {
        if (!this.loot.containsKey(lc.getLootContainerId())) {
            this.loot.put(lc.getLootContainerId(), lc);
            if (this.spatialGrid != null) {
                this.spatialGrid.insert(lc.getLootContainerId(), lc.getPos().x, lc.getPos().y);
            }
        }
        return lc.getLootContainerId();
    }

    public boolean removeLootContainer(LootContainer lc) {
        final LootContainer lootContainer = this.loot.remove(lc.getLootContainerId());
        if (this.spatialGrid != null) {
            this.spatialGrid.remove(lc.getLootContainerId());
        }
        return lootContainer != null;
    }

    public List<Chest> getChests() {
        final List<Chest> objs = new ArrayList<>();
        if (this.loot == null)
            return objs;
        for (final LootContainer lc : this.loot.values()) {
            if (lc instanceof Chest) {
                objs.add((Chest) lc);
            }
        }
        return objs;
    }

    // Call once per tick from the server update loop.
    public void updateSpatialGrid() {
        if (this.spatialGrid == null) return;
        for (final Player p : this.players.values()) {
            this.spatialGrid.update(p.getId(), p.getPos().x, p.getPos().y);
        }
        for (final Enemy e : this.enemies.values()) {
            this.spatialGrid.update(e.getId(), e.getPos().x, e.getPos().y);
        }
        for (final Bullet b : this.bullets.values()) {
            this.spatialGrid.update(b.getId(), b.getPos().x, b.getPos().y);
        }
    }

    public Player[] getPlayersInRadiusFast(Vector2f center, float radius) {
        if (this.spatialGrid == null) {
            return getPlayersInRadius(center, radius);
        }
        final float radiusSq = radius * radius;
        final List<Long> candidates = this.spatialGrid.queryRadius(center.x, center.y, radius);
        final List<Player> objs = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            final Player p = this.players.get(candidates.get(i));
            if (p != null) {
                float dx = p.getPos().x - center.x;
                float dy = p.getPos().y - center.y;
                if (dx * dx + dy * dy <= radiusSq) {
                    objs.add(p);
                }
            }
        }
        return objs.toArray(new Player[0]);
    }

    // When the cap is hit, closest entities are kept (sorted before truncation) so the
    // visible set is deterministic rather than wobbling with HashSet iteration order.
    private static final int MAX_BULLETS_PER_LOAD = 1000;
    private static final int MAX_ENEMIES_PER_LOAD = 500;

    // requestingPlayerId -1 shows all loot; otherwise filters soulbound loot to that player.
    public LoadPacket getLoadPacketCircularFast(Vector2f center, float radius) {
        return getLoadPacketCircularFast(center, radius, -1);
    }

    public LoadPacket getLoadPacketCircularFast(Vector2f center, float radius, long requestingPlayerId) {
        if (this.spatialGrid == null) {
            return getLoadPacketCircular(center, radius, requestingPlayerId);
        }
        final float radiusSq = radius * radius;
        // Bullets use a wider radius (separate query) so off-screen enemy shots still ship.
        final float bulletRadius = radius * 2f;
        final float bulletRadiusSq = bulletRadius * bulletRadius;
        LoadPacket load = null;
        try {
            final List<Long> candidates = this.spatialGrid.queryRadius(center.x, center.y, radius);
            final List<Player> playersToLoadList = new ArrayList<>();
            final List<LootContainer> containersToLoad = new ArrayList<>();
            final List<Portal> portalsToLoad = new ArrayList<>();
            // Collect with squared distance so we can sort before the cap; an unsorted
            // cap flickers which N are chosen tick-to-tick and mis-emits UnloadPackets.
            final List<EnemyDist> enemyCandidates = new ArrayList<>();
            final List<BulletDist> bulletCandidatesInner = new ArrayList<>();

            for (int i = 0; i < candidates.size(); i++) {
                final long id = candidates.get(i);
                Player p = this.players.get(id);
                if (p != null) {
                    float dx = p.getPos().x - center.x;
                    float dy = p.getPos().y - center.y;
                    if (dx * dx + dy * dy <= radiusSq) playersToLoadList.add(p);
                    continue;
                }
                Enemy e = this.enemies.get(id);
                if (e != null) {
                    float dx = e.getPos().x - center.x;
                    float dy = e.getPos().y - center.y;
                    final float distSq = dx * dx + dy * dy;
                    if (distSq <= radiusSq) enemyCandidates.add(new EnemyDist(e, distSq));
                    continue;
                }
                Bullet b = this.bullets.get(id);
                if (b != null) {
                    float dx = b.getPos().x - center.x;
                    float dy = b.getPos().y - center.y;
                    final float distSq = dx * dx + dy * dy;
                    if (distSq <= radiusSq) bulletCandidatesInner.add(new BulletDist(b, distSq));
                    continue;
                }
                Portal portal = this.portals.get(id);
                if (portal != null) {
                    float dx = portal.getPos().x - center.x;
                    float dy = portal.getPos().y - center.y;
                    if (dx * dx + dy * dy <= radiusSq) portalsToLoad.add(portal);
                    continue;
                }
                LootContainer lc = this.loot.get(id);
                if (lc != null) {
                    float dx = lc.getPos().x - center.x;
                    float dy = lc.getPos().y - center.y;
                    if (dx * dx + dy * dy <= radiusSq && lc.isVisibleToPlayer(requestingPlayerId)) {
                        containersToLoad.add(lc);
                    }
                }
            }

            if (enemyCandidates.size() > MAX_ENEMIES_PER_LOAD) {
                enemyCandidates.sort((a, b1) -> Float.compare(a.distSq, b1.distSq));
            }
            final List<Enemy> enemiesToLoad = new ArrayList<>(
                    Math.min(enemyCandidates.size(), MAX_ENEMIES_PER_LOAD));
            for (int i = 0, n = Math.min(enemyCandidates.size(), MAX_ENEMIES_PER_LOAD); i < n; i++) {
                enemiesToLoad.add(enemyCandidates.get(i).enemy);
            }
            if (bulletCandidatesInner.size() > MAX_BULLETS_PER_LOAD) {
                bulletCandidatesInner.sort((a, b1) -> Float.compare(a.distSq, b1.distSq));
            }
            final List<Bullet> bulletsToLoad = new ArrayList<>(
                    Math.min(bulletCandidatesInner.size(), MAX_BULLETS_PER_LOAD));
            for (int i = 0, n = Math.min(bulletCandidatesInner.size(), MAX_BULLETS_PER_LOAD); i < n; i++) {
                bulletsToLoad.add(bulletCandidatesInner.get(i).bullet);
            }

            // Second pass: wider bullet radius, bullets only (off-screen enemy shots).
            final List<Long> bulletCandidates = this.spatialGrid.queryRadius(center.x, center.y, bulletRadius);
            for (int i = 0; i < bulletCandidates.size(); i++) {
                if (bulletsToLoad.size() >= MAX_BULLETS_PER_LOAD) break;
                final long id = bulletCandidates.get(i);
                Bullet b = this.bullets.get(id);
                if (b == null) continue;
                float dx = b.getPos().x - center.x;
                float dy = b.getPos().y - center.y;
                float dsq = dx * dx + dy * dy;
                if (dsq <= radiusSq) continue; // already added in the inner pass
                if (dsq <= bulletRadiusSq) bulletsToLoad.add(b);
            }
            load = LoadPacket.from(playersToLoadList.toArray(new Player[0]),
                    containersToLoad.toArray(new LootContainer[0]), bulletsToLoad.toArray(new Bullet[0]),
                    enemiesToLoad.toArray(new Enemy[0]), portalsToLoad.toArray(new Portal[0]),
                    this.shortIdAllocator);
            if (load != null) load.setDifficulty((byte) this.getZoneDifficulty(center.x, center.y));
        } catch (Exception e) {
            Realm.log.error("Failed to get fast circular load Packet. Reason: {}", e.getMessage());
        }
        return load;
    }

    public void clearTickMovementCache() {
        if (this.tickMovementCache != null) {
            this.tickMovementCache.clear();
        }
    }

    /** Reset the per-tick stripped-UpdatePacket cache. */
    public void clearTickStrippedUpdateCache() {
        if (this.tickStrippedUpdateCache != null) {
            this.tickStrippedUpdateCache.clear();
        }
    }

    // Per-tick cached stripped (no-inventory) UpdatePacket shared across viewers.
    public UpdatePacket getOrBuildStrippedUpdate(Player p) {
        if (p == null) return null;
        if (this.tickStrippedUpdateCache == null) {
            this.tickStrippedUpdateCache = new HashMap<>(64);
        }
        UpdatePacket u = this.tickStrippedUpdateCache.get(p.getId());
        if (u == null) {
            u = UpdatePacket.fromPlayerWithoutInventory(p);
            this.tickStrippedUpdateCache.put(p.getId(), u);
        }
        return u;
    }

    private NetObjectMovement getOrBuildMovement(GameObject obj) {
        if (this.tickMovementCache == null) {
            this.tickMovementCache = new HashMap<>(64);
        }
        NetObjectMovement m = this.tickMovementCache.get(obj.getId());
        if (m == null) {
            m = new NetObjectMovement(obj);
            this.tickMovementCache.put(obj.getId(), m);
        }
        return m;
    }

    // Players + enemies only; uses the per-tick movement cache.
    public ObjectMovePacket getGameObjectsAsPacketsCircularFast(Vector2f center, float radius) throws Exception {
        if (this.spatialGrid == null) {
            return getGameObjectsAsPacketsCircular(center, radius);
        }
        final float radiusSq = radius * radius;
        final List<Long> candidates = this.spatialGrid.queryRadius(center.x, center.y, radius);
        final List<NetObjectMovement> mvts = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            final long id = candidates.get(i);
            Player p = this.players.get(id);
            if (p != null) {
                float dx = p.getPos().x - center.x;
                float dy = p.getPos().y - center.y;
                if (dx * dx + dy * dy <= radiusSq) mvts.add(getOrBuildMovement(p));
                if (p.getTeleported()) p.setTeleported(false);
                continue;
            }
            Enemy e = this.enemies.get(id);
            if (e != null) {
                float dx = e.getPos().x - center.x;
                float dy = e.getPos().y - center.y;
                if (dx * dx + dy * dy <= radiusSq) mvts.add(getOrBuildMovement(e));
                if (e.getTeleported()) e.setTeleported(false);
                continue;
            }
            // Bullets skipped - clients predict their positions locally.
        }
        if (mvts.isEmpty()) return null;
        return ObjectMovePacket.from(mvts.toArray(new NetObjectMovement[0]));
    }

    public long getSpatialCellKey(float x, float y) {
        if (this.spatialGrid == null) return 0;
        return this.spatialGrid.getCellKey(x, y);
    }

    public Rectangle[] getCollisionBoxesInBounds(Rectangle cam) {
        final List<Rectangle> colBoxes = new ArrayList<>();
        final GameObject[] go = this.getGameObjectsInBounds(cam);
        for (final GameObject g : go) {
            colBoxes.add(g.getBounds());
        }
        return colBoxes.toArray(new Rectangle[0]);
    }

    public Player[] getPlayersInBounds(Rectangle cam) {
        final List<Player> objs = new ArrayList<>();
        for (final Player p : this.players.values()) {
            if (p.getBounds().intersect(cam)) {
                objs.add(p);
            }
        }

        return objs.toArray(new Player[0]);
    }

    public Player[] getPlayersInRadius(Vector2f center, float radius) {
        final float radiusSq = radius * radius;
        final List<Player> objs = new ArrayList<>();
        for (final Player p : this.players.values()) {
            float dx = p.getPos().x - center.x;
            float dy = p.getPos().y - center.y;
            if (dx * dx + dy * dy <= radiusSq) {
                objs.add(p);
            }
        }
        return objs.toArray(new Player[0]);
    }

    // Reusable buffer; single-threaded render caller (PlayState.render) so it's safe.
    private transient final List<GameObject> inBoundsScratch = new ArrayList<>(256);

    public GameObject[] getGameObjectsInBounds(Rectangle cam) {
        final List<GameObject> objs = this.inBoundsScratch;
        objs.clear();
        for (final Player p : this.players.values()) {
            if (p.getBounds() != null && p.getBounds().intersect(cam)) {
                objs.add(p);
            }
        }
        for (final Bullet b : this.bullets.values()) {
            if (b.getBounds() != null && b.getBounds().intersect(cam)) {
                objs.add(b);
            }
        }
        for (final Enemy e : this.enemies.values()) {
            if (e.getBounds() != null && e.getBounds().intersect(cam)) {
                objs.add(e);
            }
        }
        return objs.toArray(new GameObject[objs.size()]);
    }

    public GameObject[] getGameObjectsInRadius(Vector2f center, float radius) {
        final float radiusSq = radius * radius;
        final List<GameObject> objs = new ArrayList<>();
        for (final Player p : this.players.values()) {
            float dx = p.getPos().x - center.x;
            float dy = p.getPos().y - center.y;
            if (dx * dx + dy * dy <= radiusSq) objs.add(p);
        }
        for (final Bullet b : this.bullets.values()) {
            float dx = b.getPos().x - center.x;
            float dy = b.getPos().y - center.y;
            if (dx * dx + dy * dy <= radiusSq) objs.add(b);
        }
        for (final Enemy e : this.enemies.values()) {
            float dx = e.getPos().x - center.x;
            float dy = e.getPos().y - center.y;
            if (dx * dx + dy * dy <= radiusSq) objs.add(e);
        }
        return objs.toArray(new GameObject[0]);
    }

    public ObjectMovePacket getGameObjectsAsPacketsCircular(Vector2f center, float radius) throws Exception {
        final float radiusSq = radius * radius;
        final GameObject[] gameObjects = this.getAllGameObjects();
        final List<GameObject> validObjects = new ArrayList<>();
        for (GameObject obj : gameObjects) {
            try {
                float dx = obj.getPos().x - center.x;
                float dy = obj.getPos().y - center.y;
                if (dx * dx + dy * dy <= radiusSq) {
                    validObjects.add(obj);
                }
                if (obj.getTeleported()) {
                    obj.setTeleported(false);
                }
            } catch (Exception e) {
                Realm.log.error("Failed to create ObjectMove Packet. Reason: {}", e.getMessage());
            }
        }
        if (validObjects.size() > 0)
            return ObjectMovePacket.from(validObjects.toArray(new GameObject[0]));
        return null;
    }

    public GameObject[] getGameObjectss() {
        final List<GameObject> objs = new ArrayList<>();
        for (final Player p : this.players.values()) {
            objs.add(p);
        }

        for (final Bullet b : this.bullets.values()) {
            objs.add(b);
        }

        for (final Enemy e : this.enemies.values()) {
            objs.add(e);
        }

        return objs.toArray(new GameObject[0]);
    }

    // Cached getAllGameObjects() snapshot; reused while total entity count is unchanged.
    // A same-size swap yields a one-frame-stale snapshot (invisible at 60+ FPS).
    private transient GameObject[] gameObjectsCache;

    public GameObject[] getAllGameObjects() {
        final int expected = this.players.size() + this.bullets.size() + this.enemies.size();
        final GameObject[] cached = this.gameObjectsCache;
        if (cached != null && cached.length == expected) {
            return cached;
        }
        final GameObject[] arr = new GameObject[expected];
        int i = 0;
        for (final Player p : this.players.values()) {
            if (i >= expected) break;
            arr[i++] = p;
        }
        for (final Bullet b : this.bullets.values()) {
            if (i >= expected) break;
            arr[i++] = b;
        }
        for (final Enemy e : this.enemies.values()) {
            if (i >= expected) break;
            arr[i++] = e;
        }
        // Trim if concurrent removals shrank a map mid-iteration.
        if (i < expected) {
            final GameObject[] trimmed = new GameObject[i];
            System.arraycopy(arr, 0, trimmed, 0, i);
            this.gameObjectsCache = trimmed;
            return trimmed;
        }
        this.gameObjectsCache = arr;
        return arr;
    }

    // Players + enemies only; bullets are simulated client-side from LoadPacket.
    public GameObject[] getMovableGameObjects() {
        final List<GameObject> objs = new ArrayList<>();
        for (final Player p : this.players.values()) {
            objs.add(p);
        }

        for (final Enemy e : this.enemies.values()) {
            objs.add(e);
        }

        return objs.toArray(new GameObject[0]);
    }

    public UpdatePacket getPlayerAsPacket(long playerId) {
        final Player p = this.players.get(playerId);
        UpdatePacket pack = null;
        try {
            pack = UpdatePacket.from(p);
        } catch (Exception e) {
            Realm.log.error("Failed to create update packet from Player. Reason: {}", e);
        }
        return pack;
    }
    
    public UpdatePacket getEnemyAsPacket(long enemyId) {
        final Enemy enemy = this.enemies.get(enemyId);
        UpdatePacket pack = null;
        try {
            pack = UpdatePacket.from(enemy);
        } catch (Exception e) {
            Realm.log.error("Failed to create update packet from Enemy. Reason: {}", e);
        }
        return pack;
    }


    public List<UpdatePacket> getPlayersAsPackets(Rectangle cam) {
        final List<UpdatePacket> playerUpdates = new ArrayList<>();
        for (final Player p : this.players.values()) {
            try {
                final UpdatePacket pack = UpdatePacket.from(p);
                playerUpdates.add(pack);
            } catch (Exception e) {
                Realm.log.error("Failed to create update packet from Player. Reason: {}", e);
            }
        }
        return playerUpdates;
    }

    public LoadPacket getLoadPacket(Rectangle cam) {
        LoadPacket load = null;
        try {
            final List<Player> playersToLoadList = new ArrayList<>();
            for (Player p : this.players.values()) {
                final boolean inViewport = cam.inside((int) p.getPos().x, (int) p.getPos().y);
                if (inViewport) {
                    playersToLoadList.add(p);
                }

            }
            final List<LootContainer> containersToLoad = new ArrayList<>();
            for (LootContainer c : this.loot.values()) {
                final boolean inViewport = cam.inside((int) c.getPos().x, (int) c.getPos().y);
                if (inViewport) {
                    containersToLoad.add(c);
                }
            }

            final List<Bullet> bulletsToLoad = new ArrayList<>();
            for (Bullet b : this.bullets.values()) {
                final boolean inViewport = cam.inside((int) b.getPos().x, (int) b.getPos().y);
                if (inViewport) {
                    bulletsToLoad.add(b);
                }
            }

            final List<Enemy> enemiesToLoad = new ArrayList<>();
            for (Enemy e : this.enemies.values()) {
                final boolean inViewport = cam.inside((int) e.getPos().x, (int) e.getPos().y);
                if (inViewport) {
                    enemiesToLoad.add(e);
                }
            }

            final List<Portal> portalsToLoad = new ArrayList<>();
            for (Portal p : this.portals.values()) {
                final boolean inViewport = cam.inside((int) p.getPos().x, (int) p.getPos().y);
                if (inViewport) {
                    portalsToLoad.add(p);
                }
            }

            load = LoadPacket.from(playersToLoadList.toArray(new Player[0]),
                    containersToLoad.toArray(new LootContainer[0]), bulletsToLoad.toArray(new Bullet[0]),
                    enemiesToLoad.toArray(new Enemy[0]), portalsToLoad.toArray(new Portal[0]),
                    this.shortIdAllocator);
            if (load != null) load.setDifficulty((byte) this.getZoneDifficulty(cam.getPos().x + cam.getWidth() / 2f, cam.getPos().y + cam.getHeight() / 2f));
        } catch (Exception e) {
            Realm.log.error("Failed to get load Packet. Reason: {}", e.getMessage(), e);
        }
        return load;
    }

    // requestingPlayerId -1 shows all loot; otherwise filters soulbound loot to that player.
    public LoadPacket getLoadPacketCircular(Vector2f center, float radius) {
        return getLoadPacketCircular(center, radius, -1);
    }

    public LoadPacket getLoadPacketCircular(Vector2f center, float radius, long requestingPlayerId) {
        final float radiusSq = radius * radius;
        final float bulletRadiusSq = (radius * 2f) * (radius * 2f);
        LoadPacket load = null;
        try {
            final List<Player> playersToLoadList = new ArrayList<>();
            for (Player p : this.players.values()) {
                float dx = p.getPos().x - center.x;
                float dy = p.getPos().y - center.y;
                if (dx * dx + dy * dy <= radiusSq) playersToLoadList.add(p);
            }
            final List<LootContainer> containersToLoad = new ArrayList<>();
            for (LootContainer c : this.loot.values()) {
                float dx = c.getPos().x - center.x;
                float dy = c.getPos().y - center.y;
                if (dx * dx + dy * dy <= radiusSq && c.isVisibleToPlayer(requestingPlayerId)) {
                    containersToLoad.add(c);
                }
            }
            final List<Bullet> bulletsToLoad = new ArrayList<>();
            for (Bullet b : this.bullets.values()) {
                float dx = b.getPos().x - center.x;
                float dy = b.getPos().y - center.y;
                if (dx * dx + dy * dy <= bulletRadiusSq) bulletsToLoad.add(b);
            }
            final List<Enemy> enemiesToLoad = new ArrayList<>();
            for (Enemy e : this.enemies.values()) {
                float dx = e.getPos().x - center.x;
                float dy = e.getPos().y - center.y;
                if (dx * dx + dy * dy <= radiusSq) enemiesToLoad.add(e);
            }
            final List<Portal> portalsToLoad = new ArrayList<>();
            for (Portal p : this.portals.values()) {
                float dx = p.getPos().x - center.x;
                float dy = p.getPos().y - center.y;
                if (dx * dx + dy * dy <= radiusSq) portalsToLoad.add(p);
            }
            load = LoadPacket.from(playersToLoadList.toArray(new Player[0]),
                    containersToLoad.toArray(new LootContainer[0]), bulletsToLoad.toArray(new Bullet[0]),
                    enemiesToLoad.toArray(new Enemy[0]), portalsToLoad.toArray(new Portal[0]),
                    this.shortIdAllocator);
            if (load != null) load.setDifficulty((byte) this.getZoneDifficulty(center.x, center.y));
        } catch (Exception e) {
            Realm.log.error("Failed to get circular load Packet. Reason: {}", e.getMessage());
        }
        return load;
    }

    public ObjectMovePacket getGameObjectsAsPackets(Rectangle cam) throws Exception {
        final GameObject[] gameObjects = this.getAllGameObjects();
        final List<GameObject> validObjects = new ArrayList<>();
        for (GameObject obj : gameObjects) {
            try {

                final boolean inViewport = cam.inside((int) obj.getPos().x, (int) obj.getPos().y);
                if (inViewport) {
                    validObjects.add(obj);
                }
                if (obj.getTeleported()) {
                    obj.setTeleported(false);
                }

            } catch (Exception e) {
                Realm.log.error("Failed to create ObjectMove Packet. Reason: {}", e.getMessage());
            }
        }
        if (validObjects.size() > 0)
            return ObjectMovePacket.from(validObjects.toArray(new GameObject[0]));
        return null;
    }
    
    public LootContainer[] getLootInBounds(Rectangle cam) {
        final List<LootContainer> objs = new ArrayList<>();
        for (final LootContainer lc : this.loot.values()) {
            if (cam.inside((int) lc.getPos().x, (int) lc.getPos().y)) {
                objs.add(lc);
            }
        }
        return objs.toArray(new LootContainer[0]);
    }

    public void spawnRandomEnemies(int mapId) {
        if (this.enemies == null) {
            this.enemies = new ConcurrentHashMap<>();
        }

        final MapModel mapModel = GameDataManager.MAPS.get(mapId);
        if (mapModel == null || mapModel.getTerrainId() < 0) {
            log.info("MapId {} has no terrain (terrainId={}), skipping enemy spawning", mapId,
                    mapModel != null ? mapModel.getTerrainId() : "null");
            return;
        }

        TerrainGenerationParameters params = GameDataManager.TERRAINS.get(mapModel.getTerrainId());
        if (params == null) {
            log.warn("No Terrain generation params found for MapId {}, using default values", mapId);
            params = GameDataManager.TERRAINS.get(GameDataManager.MAPS.get(4).getTerrainId());
        }

        final boolean hasZones = params.getZones() != null && !params.getZones().isEmpty();

        final Map<Integer, List<EnemyModel>> enemiesByGroup = new HashMap<>();
        for (EnemyGroup group : params.getEnemyGroups()) {
            List<EnemyModel> models = new ArrayList<>();
            for (int enemyId : group.getEnemyIds()) {
                EnemyModel m = GameDataManager.ENEMIES.get(enemyId);
                if (m != null) models.add(m);
            }
            enemiesByGroup.put(group.getOrdinal(), models);
        }

        final List<EnemyModel> defaultEnemies = enemiesByGroup.getOrDefault(0,
                new ArrayList<>(enemiesByGroup.values().iterator().next()));

        final int tileSize = this.tileManager.getMapLayers().get(0).getTileSize();
        final int mapHeight = this.tileManager.getMapLayers().get(0).getHeight();
        final int mapWidth = this.tileManager.getMapLayers().get(0).getWidth();

        // enemyDensity is a 0-1 per-tile spawn probability; fall back when unset.
        final float density;
        if (params.getEnemyDensity() > 0f) {
            density = params.getEnemyDensity();
        } else {
            density = hasZones ? 0.01375f : 0.005f;
        }

        final Map<Integer, Integer> spawnCaps = new HashMap<>();
        final Map<Integer, Integer> spawnCounts = new HashMap<>();
        spawnCaps.put(13, 3);  // The Man: max 3 per realm

        for (int i = 1; i < mapHeight; i++) {
            for (int j = 1; j < mapWidth; j++) {
                if (Realm.RANDOM.nextFloat() >= density) continue;

                final Vector2f spawnPos = new Vector2f(j * tileSize, i * tileSize);
                if (this.tileManager.isVoidTile(spawnPos, 0, 0)) {
                    continue;
                }

                List<EnemyModel> spawnList = defaultEnemies;
                float diff = this.getDifficulty();

                if (hasZones) {
                    OverworldZone zone = this.tileManager.getZoneForPosition(spawnPos.x, spawnPos.y);
                    if (zone != null) {
                        spawnList = enemiesByGroup.getOrDefault(zone.getEnemyGroupOrdinal(), defaultEnemies);
                        diff = Math.max(1.0f, zone.getDifficulty());
                    }
                }

                if (spawnList.isEmpty()) continue;
                final EnemyModel toSpawn = spawnList.get(Realm.RANDOM.nextInt(spawnList.size()));

                if (this.tileManager.collidesAtPosition(spawnPos, toSpawn.getSize())) {
                    continue;
                }

                if (spawnCaps.containsKey(toSpawn.getEnemyId())) {
                    int current = spawnCounts.getOrDefault(toSpawn.getEnemyId(), 0);
                    if (current >= spawnCaps.get(toSpawn.getEnemyId())) continue;
                    spawnCounts.merge(toSpawn.getEnemyId(), 1, Integer::sum);
                }

                final Enemy enemy = new Enemy(Realm.RANDOM.nextLong(), toSpawn.getEnemyId(),
                        spawnPos.clone(), toSpawn.getSize(), toSpawn.getAttackId());
                enemy.setDifficulty(diff);
                enemy.setHealth((int) (enemy.getHealth() * diff));
                enemy.getStats().setHp((short) (enemy.getStats().getHp() * diff));
                enemy.setPos(spawnPos);
                this.addEnemy(enemy);
            }
        }

        this.spawnStaticEnemies(mapId);
        this.initialEnemyCount = this.enemies.size();
    }

    // Overworld-only (terrain + zones); tops enemies back up away from players.
    public void respawnEnemies(int batchSize) {
        final TerrainGenerationParameters params = this.tileManager.getTerrainParams();
        if (params == null) return;
        final boolean hasZones = params.getZones() != null && !params.getZones().isEmpty();
        if (!hasZones) return;

        final int threshold = (int) (this.initialEnemyCount * 0.75);
        if (this.enemies.size() >= threshold) return;

        batchSize = Math.min(batchSize, this.initialEnemyCount - this.enemies.size());
        if (batchSize <= 0) return;

        final Map<Integer, List<EnemyModel>> enemiesByGroup = new HashMap<>();
        for (EnemyGroup group : params.getEnemyGroups()) {
            List<EnemyModel> models = new ArrayList<>();
            for (int enemyId : group.getEnemyIds()) {
                EnemyModel m = GameDataManager.ENEMIES.get(enemyId);
                if (m != null) models.add(m);
            }
            enemiesByGroup.put(group.getOrdinal(), models);
        }
        final List<EnemyModel> defaultEnemies = enemiesByGroup.getOrDefault(0, new ArrayList<>());
        if (defaultEnemies.isEmpty() && enemiesByGroup.isEmpty()) return;

        final int tileSize = this.tileManager.getMapLayers().get(0).getTileSize();
        final int mapHeight = this.tileManager.getMapLayers().get(0).getHeight();
        final int mapWidth = this.tileManager.getMapLayers().get(0).getWidth();

        // Don't spawn within player viewport radius (10 tiles).
        final float viewportRadius = 10f * GlobalConstants.BASE_TILE_SIZE;
        final float minPlayerDistSq = viewportRadius * viewportRadius;
        final List<Vector2f> playerPositions = new ArrayList<>();
        for (Player p : this.players.values()) {
            playerPositions.add(p.getPos());
        }

        int spawned = 0;
        int attempts = 0;
        final int maxAttempts = batchSize * 10;

        while (spawned < batchSize && attempts < maxAttempts) {
            attempts++;
            final int col = 1 + Realm.RANDOM.nextInt(mapWidth - 2);
            final int row = 1 + Realm.RANDOM.nextInt(mapHeight - 2);
            final Vector2f spawnPos = new Vector2f(col * tileSize, row * tileSize);

            if (this.tileManager.isVoidTile(spawnPos, 0, 0)) continue;

            boolean nearPlayer = false;
            for (Vector2f pp : playerPositions) {
                float dx = spawnPos.x - pp.x, dy = spawnPos.y - pp.y;
                if (dx * dx + dy * dy < minPlayerDistSq) {
                    nearPlayer = true;
                    break;
                }
            }
            if (nearPlayer) continue;

            List<EnemyModel> spawnList = defaultEnemies;
            float diff = this.getDifficulty();
            OverworldZone zone = this.tileManager.getZoneForPosition(spawnPos.x, spawnPos.y);
            if (zone != null) {
                spawnList = enemiesByGroup.getOrDefault(zone.getEnemyGroupOrdinal(), defaultEnemies);
                diff = Math.max(1.0f, zone.getDifficulty());
            }
            if (spawnList.isEmpty()) continue;

            final EnemyModel toSpawn = spawnList.get(Realm.RANDOM.nextInt(spawnList.size()));
            if (this.tileManager.collidesAtPosition(spawnPos, toSpawn.getSize())) continue;

            final Enemy enemy = new Enemy(Realm.RANDOM.nextLong(), toSpawn.getEnemyId(),
                    spawnPos.clone(), toSpawn.getSize(), toSpawn.getAttackId());
            enemy.setDifficulty(diff);
            enemy.setHealth((int) (enemy.getHealth() * diff));
            enemy.getStats().setHp((short) (enemy.getStats().getHp() * diff));
            enemy.setPos(spawnPos);
            this.addEnemy(enemy);
            spawned++;
        }

        if (spawned > 0) {
            log.info("[REALM] Respawned {} enemies in overworld (total: {})", spawned, this.enemies.size());
        }
    }

    public void spawnStaticEnemies(int mapId) {
        final MapModel mapModel = GameDataManager.MAPS.get(mapId);
        if (mapModel == null || mapModel.getStaticSpawns() == null) return;
        for (final StaticSpawn ss : mapModel.getStaticSpawns()) {
            final EnemyModel model = GameDataManager.ENEMIES.get(ss.getEnemyId());
            if (model == null) {
                Realm.log.warn("Static spawn references unknown enemyId={}, skipping", ss.getEnemyId());
                continue;
            }
            Vector2f pos = new Vector2f(ss.getX(), ss.getY());
            if (this.tileManager != null && this.tileManager.collidesAtPosition(pos, model.getSize())) {
                Realm.log.warn("Static spawn at ({}, {}) collides with tiles, finding safe position", ss.getX(), ss.getY());
                pos = this.tileManager.getSafePosition();
            }
            final Enemy enemy = GameObjectUtils.getEnemyFromId(ss.getEnemyId(), pos);
            float diff = this.getZoneDifficulty(pos.x, pos.y);
            enemy.setDifficulty(diff);
            enemy.setHealth((int) (enemy.getHealth() * diff));
            this.addEnemy(enemy);
            Realm.log.info("Static spawn: {} at ({}, {}) in realm mapId={}", model.getName(), pos.x, pos.y, mapId);
        }
    }

    // Stamps set-piece structures onto the map with collision-avoidance placement.
    public void placeSetPieces(TerrainGenerationParameters params) {
        if (params.getSetPieces() == null || params.getSetPieces().isEmpty()) return;
        final boolean hasZones = params.getZones() != null && !params.getZones().isEmpty();
        final int tileSize = this.tileManager.getMapLayers().get(0).getTileSize();
        final int mapW = this.tileManager.getMapLayers().get(0).getWidth();
        final int mapH = this.tileManager.getMapLayers().get(0).getHeight();
        final Set<Long> occupied = new HashSet<>();

        Realm.log.info("[SET_PIECES] Map {}x{}, tileSize={}, hasZones={}, {} set piece types",
            mapW, mapH, tileSize, hasZones, params.getSetPieces().size());

        for (SetPiece sp : params.getSetPieces()) {
            final SetPieceModel model = GameDataManager.SETPIECES != null
                ? GameDataManager.SETPIECES.get(sp.getSetPieceId()) : null;
            if (model == null) {
                Realm.log.warn("[SET_PIECES] SetPieceModel not found for setPieceId={}", sp.getSetPieceId());
                continue;
            }

            int count = sp.getMinCount() + Realm.RANDOM.nextInt(Math.max(1, sp.getMaxCount() - sp.getMinCount() + 1));
            int placed = 0;
            int zoneRejects = 0, collRejects = 0;

            for (int attempt = 0; attempt < count * 100 && placed < count; attempt++) {
                int px = 4 + Realm.RANDOM.nextInt(Math.max(1, mapW - model.getWidth() - 8));
                int py = 4 + Realm.RANDOM.nextInt(Math.max(1, mapH - model.getHeight() - 8));

                if (hasZones && sp.getAllowedZones() != null) {
                    Vector2f worldPos = new Vector2f(px * tileSize, py * tileSize);
                    OverworldZone zone = this.tileManager.getZoneForPosition(worldPos.x, worldPos.y);
                    if (zone == null || !sp.getAllowedZones().contains(zone.getZoneId())) {
                        zoneRejects++;
                        continue;
                    }
                }

                boolean fits = true;
                for (int dy = 0; dy < model.getHeight() && fits; dy++) {
                    for (int dx = 0; dx < model.getWidth() && fits; dx++) {
                        long key = ((long)(py + dy) << 32) | (px + dx);
                        if (occupied.contains(key)) { fits = false; collRejects++; }
                    }
                }
                Vector2f center = new Vector2f(px * tileSize + tileSize, py * tileSize + tileSize);
                if (this.tileManager.isVoidTile(center, 0, 0)) { fits = false; collRejects++; }
                if (!fits) continue;

                stampSetPiece(model, px, py, occupied);
                placed++;
            }
            Realm.log.info("[SET_PIECES] '{}': placed {}/{}, zoneRejects={}, collRejects={}",
                model.getName(), placed, count, zoneRejects, collRejects);
        }
    }

    // Layer keys are numeric strings = TileManager layer indices ("0"=base, "1"=collision).
    // Tile id 0 = transparent (skipped). occupied may be null.
    public void stampSetPiece(SetPieceModel model, int px, int py,
                               Set<Long> occupied) {
        if (model.getData() == null) return;
        for (int dy = 0; dy < model.getHeight(); dy++) {
            for (int dx = 0; dx < model.getWidth(); dx++) {
                int tx = px + dx, ty = py + dy;
                if (occupied != null) {
                    occupied.add(((long) ty << 32) | tx);
                }
                for (var layerEntry : model.getData().entrySet()) {
                    final int layerIdx;
                    try { layerIdx = Integer.parseInt(layerEntry.getKey()); }
                    catch (NumberFormatException nfe) { continue; }
                    if (layerIdx < 0 || layerIdx >= this.tileManager.getMapLayers().size()) continue;
                    int[][] layer = layerEntry.getValue();
                    if (layer == null || dy >= layer.length || dx >= layer[dy].length) continue;
                    int tileId = layer[dy][dx];
                    if (tileId <= 0) continue;
                    try {
                        TileData data = GameDataManager.TILES.get(tileId) != null
                            ? GameDataManager.TILES.get(tileId).getData() : null;
                        this.tileManager.getMapLayers().get(layerIdx).setTileAt(ty, tx, (short) tileId, data);
                    } catch (Exception e) { /* skip */ }
                }
            }
        }
    }

    // Returns [savedBase[h][w], savedCollision[h][w]].
    public int[][][] saveTerrainAt(int px, int py, int width, int height) {
        int[][] savedBase = new int[height][width];
        int[][] savedColl = new int[height][width];
        for (int dy = 0; dy < height; dy++) {
            for (int dx = 0; dx < width; dx++) {
                int tx = px + dx, ty = py + dy;
                try {
                    Tile baseTile = this.tileManager.getMapLayers().get(0).getBlocks()[ty][tx];
                    savedBase[dy][dx] = baseTile != null ? baseTile.getTileId() : 0;
                    Tile collTile = this.tileManager.getMapLayers().get(1).getBlocks()[ty][tx];
                    savedColl[dy][dx] = collTile != null ? collTile.getTileId() : 0;
                } catch (Exception e) {
                    savedBase[dy][dx] = 0;
                    savedColl[dy][dx] = 0;
                }
            }
        }
        return new int[][][] { savedBase, savedColl };
    }

    public void restoreTerrainAt(int px, int py, int[][] savedBase, int[][] savedColl) {
        for (int dy = 0; dy < savedBase.length; dy++) {
            for (int dx = 0; dx < savedBase[dy].length; dx++) {
                int tx = px + dx, ty = py + dy;
                try {
                    int baseTileId = savedBase[dy][dx];
                    TileData baseData = baseTileId > 0 && GameDataManager.TILES.get(baseTileId) != null
                        ? GameDataManager.TILES.get(baseTileId).getData() : null;
                    this.tileManager.getMapLayers().get(0).setTileAt(ty, tx, (short) baseTileId, baseData);

                    int collTileId = savedColl[dy][dx];
                    TileData collData = collTileId > 0 && GameDataManager.TILES.get(collTileId) != null
                        ? GameDataManager.TILES.get(collTileId).getData() : null;
                    this.tileManager.getMapLayers().get(1).setTileAt(ty, tx, (short) collTileId, collData);
                } catch (Exception e) { /* skip */ }
            }
        }
    }

    public void spawnRandomEnemy() {
        final Vector2f spawnPos = this.tileManager.getSafePosition();

        final List<EnemyModel> enemyToSpawn = new ArrayList<>();
        GameDataManager.ENEMIES.values().forEach(enemy -> {
            enemyToSpawn.add(enemy);
        });
        final EnemyModel toSpawn = enemyToSpawn.get(Realm.RANDOM.nextInt(enemyToSpawn.size()));

        final Enemy enemy = new Enemy(Realm.RANDOM.nextLong(), toSpawn.getEnemyId(), spawnPos, toSpawn.getSize(),
                toSpawn.getAttackId());

        final float diff = this.getZoneDifficulty(spawnPos.x, spawnPos.y);
        enemy.setDifficulty(diff);
        enemy.setHealth((int) (enemy.getHealth() * diff));
        enemy.setPos(spawnPos);
        this.addEnemy(enemy);
    }

    // Resolution order: terrain > map > dungeon-graph node > 1.0.
    // Zone-based terrains should use getZoneDifficulty() for positional resolution.
    public float getDifficulty() {
        MapModel map = GameDataManager.MAPS.get(this.mapId);
        if (map != null && map.getTerrainId() >= 0) {
            TerrainGenerationParameters terrain = GameDataManager.TERRAINS.get(map.getTerrainId());
            if (terrain != null && terrain.getDifficulty() > 0f) {
                return terrain.getDifficulty();
            }
        }
        if (map != null && map.getDifficulty() > 0f) {
            return map.getDifficulty();
        }
        if (this.nodeId != null && GameDataManager.DUNGEON_GRAPH != null) {
            DungeonGraphNode node = GameDataManager.DUNGEON_GRAPH.get(this.nodeId);
            if (node != null) return Math.max(1.0f, node.getDifficulty());
        }
        return 1.0f;
    }

    // Zone difficulty if the position is in a zone, else getDifficulty().
    public float getZoneDifficulty(float x, float y) {
        if (this.tileManager != null) {
            OverworldZone zone = this.tileManager.getZoneForPosition(x, y);
            if (zone != null) {
                return Math.max(1.0f, zone.getDifficulty());
            }
        }
        return this.getDifficulty();
    }

    private Runnable getStatsThread() {
        final Runnable statsThread = () -> {
            while (!this.shutdown) {
                final double heapSize = Runtime.getRuntime().totalMemory() / 1024.0 / 1024.0;
                final String nodeName = (this.nodeId != null) ? this.nodeId : "legacy";
                Realm.log.info("--- Realm: {} | Node: {} | MapId: {} | Difficulty: {} ---", this.getRealmId(), nodeName, this.getMapId(), this.getDifficulty());
                Realm.log.info("Enemies: {}", this.enemies.size());
                Realm.log.info("Players: {}", this.players.size());
                Realm.log.info("Loot: {}", this.loot.size());
                Realm.log.info("Bullets: {}", this.bullets.size());
                Realm.log.info("BulletHits: {}", this.bulletHits.size());
                Realm.log.info("Portals: {}", this.portals.size());
                Realm.log.info("Heap Mem: {}", heapSize);

                try {
                    Thread.sleep(10000);
                } catch (Exception e) {

                }
            }
            log.info("Realm {} destroyed", this.getRealmId());
        };
        return statsThread;
    }

    private void acquirePlayerLock() {
        this.playerLock.lock();
    }

    private void releasePlayerLock() {
        this.playerLock.unlock();
    }
}
