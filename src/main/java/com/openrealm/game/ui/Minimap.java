package com.openrealm.game.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.openrealm.game.contants.GlobalConstants;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.entity.Player;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.model.DungeonModel;
import com.openrealm.game.model.MapModel;
import com.openrealm.game.model.TileModel;
import com.openrealm.game.state.PlayState;
import com.openrealm.net.entity.NetPlayerPosition;
import java.util.Set;
import com.openrealm.game.tile.Tile;
import com.openrealm.game.tile.TileData;
import com.openrealm.game.tile.TileManager;
import com.openrealm.game.tile.TileMap;
import com.openrealm.net.messaging.CommandType;
import com.openrealm.net.messaging.ServerCommandMessage;
import com.openrealm.net.server.packet.CommandPacket;
import com.openrealm.util.KeyHandler;
import com.openrealm.util.MouseHandler;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * Square minimap that mirrors the PixiJS webclient: renders the WHOLE realm
 * (downsampled into a cached Pixmap/Texture once per map load), supports
 * mouse-wheel zoom, and click-to-teleport via the existing /tp server command.
 *
 * Coordinate model matches the webclient's minimap.js: pixel-per-tile scaling
 * with a square src-rect that pans to keep the local player centered when
 * zoomed in.
 */
@Data
@Slf4j
public class Minimap {
    private static final int DEFAULT_SIZE_PX = 200;
    private static final int DEFAULT_MARGIN = 10;

    private int drawX = DEFAULT_MARGIN;
    private int drawY = DEFAULT_MARGIN;
    private int sizePx = DEFAULT_SIZE_PX;

    public void setLayout(int x, int y, int size) {
        this.drawX = x;
        this.drawY = y;
        this.sizePx = Math.max(32, size);
    }

    private static final Color BG_COLOR     = new Color(0.04f, 0.03f, 0.05f, 0.95f);
    private static final Color BORDER_COLOR = new Color(0.23f, 0.16f, 0.22f, 1f);
    private static final Color LOCAL_COLOR  = new Color(0.25f, 1.00f, 0.25f, 1f);
    private static final Color OTHER_COLOR  = new Color(1.00f, 0.86f, 0.27f, 1f);

    // Tile palette (matches webclient minimap.js TILE_COLORS)
    private static final int COL_VOID  = 0x000000ff;
    private static final int COL_WALL  = 0xaaaaaaff;
    private static final int COL_SAND  = 0xc8b888ff;
    private static final int COL_GRASS = 0x4a7a45ff;
    private static final int COL_STONE = 0x606068ff;
    private static final int COL_WATER = 0x3060a0ff;
    private static final int COL_LAVA  = 0xc04020ff;
    private static final int COL_DARK  = 0x2a2030ff;
    private static final int COL_DEFAULT = 0x3a3a38ff;

    private final PlayState playState;
    private int mapWidth;
    private int mapHeight;
    private Integer cachedMapId = null;

    private Pixmap mapPixmap;
    private Texture mapTexture;
    /** When true, rebuild the cached pixmap on next render. Set on map load
     *  (tile data may not be present yet at initializeMap time) and on
     *  periodic refresh so streamed chunks become visible. */
    private boolean dirty = true;
    private long lastRebuildMs = 0L;

    /** zoom = visible fraction of the map. 1.0 = whole map; lower = zoomed in. */
    private float zoom = 1.0f;
    private static final float MIN_ZOOM = 0.10f;
    private static final float MAX_ZOOM = 1.00f;

    private boolean visible = true;

    /** Admin /hop: while on, a minimap click teleports to those world coords.
     *  Driven by the server's "Hop mode: ON/OFF" message. */
    private boolean hopMode = false;
    public void setHopMode(final boolean on) { this.hopMode = on; }
    public boolean isHopMode() { return this.hopMode; }

    // Hover state: index of the nearby player under the cursor (or -1).
    private int hoveredOtherIdx = -1;
    private String hoveredOtherName = null;
    private float[] cursorOnMapTile = new float[2]; // tile-coords under cursor
    private boolean cursorInside = false;
    private boolean prevMouseDown = false;

    public Minimap(final PlayState playState) {
        this.playState = playState;
    }

    public boolean isInitialized() {
        return this.mapWidth > 0 && this.mapHeight > 0;
    }

    public void initializeMap(final int mapId, final int dungeonId) {
        final Integer key = mapId;
        if (this.cachedMapId != null && this.cachedMapId.equals(key)) {
            // Same map; just request a refresh so streamed chunks redraw.
            this.dirty = true;
            return;
        }
        // Assembled dungeons carry mapId -1 (no MapModel); their dimensions live
        // in DUNGEONS. Never dereference a null MapModel here — doing so aborted
        // the whole LoadMap handler on dungeon entry, leaving the realm id stale
        // and the terrain unmerged.
        final MapModel mapModel = GameDataManager.MAPS.get(mapId);
        if (mapModel != null) {
            this.mapWidth = mapModel.getWidth();
            this.mapHeight = mapModel.getHeight();
        } else if (dungeonId > -1 && GameDataManager.DUNGEONS != null
                && GameDataManager.DUNGEONS.get(dungeonId) != null) {
            final DungeonModel dungeon = GameDataManager.DUNGEONS.get(dungeonId);
            this.mapWidth = dungeon.getMapWidth();
            this.mapHeight = dungeon.getMapHeight();
        } else {
            // Unknown realm: let the streamed tile grid drive the minimap size.
            this.mapWidth = 0;
            this.mapHeight = 0;
        }
        this.cachedMapId = key;
        // Default zoom: target ~64 tiles visible at any map size. For
        // small maps (nexus, vault, dungeons) that's the whole map at
        // zoom=1.0. For the 640x640 overworld it's zoom~0.1 — only the
        // streaming-loaded region around the player shows, instead of
        // a mostly-black 640-tile map at zoom=1.0 where the loaded
        // chunk renders as a few pixels of color in the corner. Player
        // can mouse-wheel zoom in/out from this baseline.
        final float visibleTilesTarget = 64f;
        final float maxDim = Math.max(this.mapWidth, this.mapHeight);
        this.zoom = Math.min(1.0f, Math.max(0.1f, visibleTilesTarget / maxDim));
        // CRITICAL: this method runs on the network packet thread
        // (RealmManagerClient.processClientPackets) — NOT the LWJGL GL
        // thread. Calling dispose() on the Texture here issues
        // glDeleteTextures with no current GL context, which the LWJGL
        // native layer responds to with abort() at the C level — JVM
        // crashes uncatchably ("FATAL ERROR ... No context is current").
        // That was the vault-portal crash for the past several builds.
        //
        // Just flag the rebuild and clear the reference. The OLD Texture
        // becomes the GPU's problem briefly (still bound until the GC
        // releases the JVM-side wrapper), but the next render() call —
        // which IS on the GL thread — will see dirty=true and run
        // rebuildMapTexture, which disposes the old field's value
        // safely on the GL thread before allocating the new one.
        this.dirty = true;
    }

    /**
     * Build a 1px-per-tile snapshot of the entire realm map. Cached as a
     * Texture so per-frame render is a single textured quad — matches the
     * webclient's offscreen-canvas tile cache.
     */
    private void rebuildMapTexture() {
        if (this.mapTexture != null) {
            this.mapTexture.dispose();
            this.mapTexture = null;
        }
        if (this.mapPixmap != null) {
            this.mapPixmap.dispose();
            this.mapPixmap = null;
        }

        final TileManager tm;
        try {
            tm = this.playState.getRealmManager().getRealm().getTileManager();
        } catch (Exception e) {
            return;
        }
        if (tm == null) return;
        final TileMap baseLayer = tm.getBaseLayer();
        final TileMap collLayer = tm.getCollisionLayer();
        if (baseLayer == null || collLayer == null) return;

        final Tile[][] base = baseLayer.getBlocks();
        final Tile[][] coll = collLayer.getBlocks();
        if (base == null || coll == null) return;

        final int w = this.mapWidth;
        final int h = this.mapHeight;
        // mapWidth / mapHeight are 0 between the moment Realm.loadMap()
        // wipes tile state and the moment initializeMap() finishes for the
        // new map. The Pixmap ctor blows up on a 0-dim allocation, so the
        // first render frame after a portal enter would crash with no
        // visible recovery path. Bail out cleanly and let the next render
        // tick try again once the realm has filled in.
        if (w <= 0 || h <= 0) return;
        this.mapPixmap = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        this.mapPixmap.setColor(0, 0, 0, 1);
        this.mapPixmap.fill();

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                final Tile collTile = (y < coll.length && x < coll[y].length) ? coll[y][x] : null;
                final Tile baseTile = (y < base.length && x < base[y].length) ? base[y][x] : null;
                int rgba = COL_VOID;
                if (collTile != null && !collTile.isVoid()) {
                    final TileData d = collTile.getData();
                    rgba = (d != null && d.isWall()) ? COL_WALL : COL_STONE;
                } else if (baseTile != null && !baseTile.isVoid()) {
                    rgba = pickBaseColor(baseTile);
                }
                this.mapPixmap.drawPixel(x, y, rgba);
            }
        }

        this.mapTexture = new Texture(this.mapPixmap);
        this.mapTexture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
    }

    private static int pickBaseColor(Tile t) {
        final TileData d = t.getData();
        if (d != null) {
            if (d.slows() && !d.hasCollision()) return COL_WATER;
            if (d.damaging()) return COL_LAVA;
        }
        final int id = t.getTileId();
        if (id <= 0) return COL_VOID;
        // Semantic color from the tile NAME — mirrors webclient
        // minimap.js _getTileColor. Was previously hashing by `id % 4`
        // which painted unrelated tiles in the same color and gave the
        // overall map a random / uncoordinated look.
        final TileModel def =
                (GameDataManager.TILES != null)
                        ? GameDataManager.TILES.get(id) : null;
        if (def == null) return COL_DEFAULT;
        final String name = def.getName() == null ? "" : def.getName().toLowerCase();
        if (name.contains("sand") || name.contains("beach") || name.contains("desert")) return COL_SAND;
        if (name.contains("grass") || name.contains("forest") || name.contains("green"))  return COL_GRASS;
        if (name.contains("stone") || name.contains("grey") || name.contains("rock")
                || name.contains("cobble"))                                                return COL_STONE;
        if (name.contains("dark") || name.contains("void") || name.contains("obsidian")) return COL_DARK;
        if (name.contains("water") || name.contains("ocean") || name.contains("sea"))     return COL_WATER;
        if (name.contains("lava") || name.contains("fire") || name.contains("magma"))     return COL_LAVA;
        return COL_DEFAULT;
    }

    public void toggle() { this.visible = !this.visible; }

    public void update() {
        // No discovery-mask update needed — the cached texture already holds
        // the whole map, matching the webclient's "show entire realm" rule.
    }

    /**
     * Process mouse-wheel zoom + click-to-teleport. Should be called once per
     * frame from PlayerUI.input(), AFTER tab/drag handling so the minimap
     * doesn't steal scroll from a list element overlapping it (in practice
     * nothing else uses scroll on the right HUD column).
     */
    public void input(MouseHandler mouse) {
        if (!this.isInitialized() || !this.visible) return;
        final int mx = mouse.getX();
        final int my = mouse.getY();
        final boolean inside = mx >= this.drawX && mx <= this.drawX + this.sizePx
                && my >= this.drawY && my <= this.drawY + this.sizePx;
        this.cursorInside = inside;

        // Mouse-wheel zoom, mirroring webclient (deltaY > 0 = zoom out).
        if (inside) {
            float wheel = KeyHandler.consumeScroll();
            if (wheel != 0f) {
                this.zoom = clamp(this.zoom + (wheel > 0 ? 0.10f : -0.10f), MIN_ZOOM, MAX_ZOOM);
            }
        }

        // Hover detection: compute current src rect, find closest other player.
        this.hoveredOtherIdx = -1;
        this.hoveredOtherName = null;
        if (inside) {
            final float[] src = this.computeSrcRect();
            final float srcX = src[0], srcY = src[1], viewW = src[2], viewH = src[3];
            final float scaleX = this.sizePx / viewW;
            final float scaleY = this.sizePx / viewH;

            // tile coords under cursor (used for tile-teleport on click)
            final float tileX = srcX + (mx - this.drawX) / scaleX;
            final float tileY = srcY + (my - this.drawY) / scaleY;
            this.cursorOnMapTile[0] = tileX;
            this.cursorOnMapTile[1] = tileY;

            try {
                final Player local = this.playState.getPlayer();
                final long localId = local != null ? local.getId() : -1;
                final Set<Player> others = this.playState.getRealmManager().getRealm()
                        .getPlayersExcept(localId);
                if (others != null) {
                    int idx = 0;
                    int bestIdx = -1;
                    String bestName = null;
                    float bestDistSq = 64f; // 8 px hit radius
                    for (Player p : others) {
                        final int ts = GlobalConstants.BASE_TILE_SIZE;
                        final float pxTile = p.getPos().x / ts;
                        final float pyTile = p.getPos().y / ts;
                        final float sx = this.drawX + (pxTile - srcX) * scaleX;
                        final float sy = this.drawY + (pyTile - srcY) * scaleY;
                        final float dx = sx - mx, dy = sy - my;
                        final float d2 = dx * dx + dy * dy;
                        if (d2 < bestDistSq) {
                            bestDistSq = d2;
                            bestIdx = idx;
                            bestName = p.getName();
                        }
                        idx++;
                    }
                    this.hoveredOtherIdx = bestIdx;
                    this.hoveredOtherName = bestName;
                }
            } catch (Exception ignored) { /* realm may not be ready yet */ }
        }

        // Click-to-teleport: edge-triggered on left-mouse press inside the map.
        final boolean down = mouse.isPressed(1);
        final boolean justClicked = down && !this.prevMouseDown;
        this.prevMouseDown = down;
        if (justClicked && inside) {
            if (this.hopMode) {
                // Admin hop mode: click anywhere teleports to those world coords.
                final int worldX = (int) (this.cursorOnMapTile[0] * GlobalConstants.BASE_TILE_SIZE);
                final int worldY = (int) (this.cursorOnMapTile[1] * GlobalConstants.BASE_TILE_SIZE);
                if (worldX > 0 && worldY > 0) {
                    this.sendTpCommand("/hop " + worldX + " " + worldY);
                }
            } else if (this.hoveredOtherName != null) {
                this.sendTpCommand("/tp " + this.hoveredOtherName);
            }
        }
    }

    private void sendTpCommand(String cmd) {
        try {
            final ServerCommandMessage scm = ServerCommandMessage.parseFromInput(cmd);
            final CommandPacket pkt = CommandPacket.create(this.playState.getPlayer(),
                    CommandType.SERVER_COMMAND, scm);
            this.playState.getRealmManager().getClient().sendRemote(pkt);
        } catch (Exception e) {
            log.error("Minimap teleport send failed: {}", e.toString());
        }
    }

    /** Tile-space position of the LOCAL player, computed from the same
     *  lerped render coords the world uses. The previous code read
     *  raw {@code pos.x} which advances in 1/64-second tick steps, so
     *  the dot lagged the visible sprite by up to a tick. With the
     *  minimap heavily zoomed out, scaleX shrinks the visible offset
     *  to a pixel or two — but combined with the texture-bounds bug
     *  below it added up to a clearly off-by-character-width dot.
     *  Centered on the player sprite (pos + size/2). */
    private float[] localPlayerTile() {
        final Player player = this.playState.getPlayer();
        if (player == null) return new float[]{ this.mapWidth * 0.5f, this.mapHeight * 0.5f };
        final int ts = GlobalConstants.BASE_TILE_SIZE;
        final float px = player.getEffectiveRenderX();
        final float py = player.getEffectiveRenderY();
        return new float[]{
                (px + player.getSize() / 2f) / ts,
                (py + player.getSize() / 2f) / ts
        };
    }

    private float[] computeSrcRect() {
        final float[] pt = localPlayerTile();
        final float pTileX = pt[0];
        final float pTileY = pt[1];
        final float viewW = Math.max(1f, this.mapWidth * this.zoom);
        final float viewH = Math.max(1f, this.mapHeight * this.zoom);
        float srcX = pTileX - viewW / 2f;
        float srcY = pTileY - viewH / 2f;
        // Clamp so the view never falls off the cached texture
        srcX = Math.max(0, Math.min(srcX, this.mapWidth - viewW));
        srcY = Math.max(0, Math.min(srcY, this.mapHeight - viewH));
        return new float[] { srcX, srcY, viewW, viewH };
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public void render(SpriteBatch batch, ShapeRenderer shapes) {
        if (!this.visible || !this.isInitialized()) return;

        // Rebuild the cached map texture lazily — initializeMap() runs before
        // tile chunks are merged, so the first build would be all-void. Also
        // re-run every ~2s so newly-streamed regions show up on the map.
        final long now = System.currentTimeMillis();
        if (this.dirty || (this.mapTexture == null) || (now - this.lastRebuildMs > 2000L)) {
            this.rebuildMapTexture();
            this.dirty = false;
            this.lastRebuildMs = now;
        }
        if (this.mapTexture == null) return;

        final float[] src = this.computeSrcRect();
        // Map texture sub-rect requires integer src pixels; the dot math
        // MUST use those same rounded values, otherwise the player dot
        // drifts up to 1 tile away from where the map actually shows
        // (visible at high zoom as the dot lagging behind the player).
        final int srcXi = Math.round(src[0]);
        final int srcYi = Math.round(src[1]);
        final int viewWi = Math.max(1, Math.round(src[2]));
        final int viewHi = Math.max(1, Math.round(src[3]));
        final float srcX = srcXi;
        final float srcY = srcYi;
        final float viewW = viewWi;
        final float viewH = viewHi;

        // Square dark background + border, mirroring #minimap-container
        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(BG_COLOR);
        shapes.rect(this.drawX - 1, this.drawY - 1, this.sizePx + 2, this.sizePx + 2);
        shapes.end();
        batch.begin();

        // Draw the cached map portion (whole-realm view, scaled to the panel).
        // The HUD batch uses the y-DOWN ui camera, under which this draw
        // overload renders the texture vertically flipped (map row 0 would land
        // at the screen bottom). The player/other dots below are plotted top-down
        // (drawY + (tileY - srcY)*scaleY), so pass flipY=true to make the map
        // tiles share that orientation — otherwise the dots are mirrored on Y
        // relative to the discovered tiles.
        batch.draw(this.mapTexture,
                this.drawX, this.drawY, this.sizePx, this.sizePx,
                srcXi, srcYi, viewWi, viewHi,
                false, true);

        // Player dots overlay
        batch.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        final float scaleX = this.sizePx / viewW;
        final float scaleY = this.sizePx / viewH;

        final Player local = this.playState.getPlayer();
        try {
            final long localId = local != null ? local.getId() : -1;
            final Set<Player> others = this.playState.getRealmManager().getRealm()
                    .getPlayersExcept(localId);
            if (others != null) {
                shapes.setColor(OTHER_COLOR);
                for (Player p : others) {
                    final int ts = GlobalConstants.BASE_TILE_SIZE;
                    final float tx = p.getPos().x / ts;
                    final float ty = p.getPos().y / ts;
                    final float sx = this.drawX + (tx - srcX) * scaleX;
                    final float sy = this.drawY + (ty - srcY) * scaleY;
                    if (sx < this.drawX - 4 || sx > this.drawX + this.sizePx + 4) continue;
                    if (sy < this.drawY - 4 || sy > this.drawY + this.sizePx + 4) continue;
                    shapes.circle(sx, sy, 3f);
                }
            }
            // Server-wide global players (GlobalPlayerPositionPacket) —
            // dotted in a slightly different shade so they're
            // distinguishable from same-realm players, and uses the
            // SAME (tile / srcX / scaleX) projection as local players
            // so the dot tracks zoom correctly. The previous handler
            // bypassed the minimap entirely (it overwrote in-realm pos
            // instead) so these dots never moved with the camera.
            final NetPlayerPosition[] globals = this.playState.getMinimapPlayers();
            if (globals != null && globals.length > 0) {
                shapes.setColor(0.55f, 0.55f, 0.85f, 0.8f);
                final int ts = GlobalConstants.BASE_TILE_SIZE;
                // Add half-a-default-player-size offset so the global dot
                // sits at the player's CENTER like local player does
                // (localPlayerTile applies +size/2). NetPlayerPosition
                // doesn't carry per-player size, so use the default.
                final float halfPlayer = GlobalConstants.PLAYER_SIZE / 2f;
                for (NetPlayerPosition gp : globals) {
                    if (gp == null) continue;
                    if (local != null && gp.getPlayerId() == localId) continue;
                    // Skip if this player is already in our local realm
                    // (would double-render the dot). The local-realm
                    // loop above already drew them.
                    if (this.playState.getRealmManager().getRealm()
                            .getPlayer(gp.getPlayerId()) != null) continue;
                    final float tx = (gp.getX() + halfPlayer) / ts;
                    final float ty = (gp.getY() + halfPlayer) / ts;
                    final float sx = this.drawX + (tx - srcX) * scaleX;
                    final float sy = this.drawY + (ty - srcY) * scaleY;
                    if (sx < this.drawX - 4 || sx > this.drawX + this.sizePx + 4) continue;
                    if (sy < this.drawY - 4 || sy > this.drawY + this.sizePx + 4) continue;
                    shapes.circle(sx, sy, 2f);
                }
            }
        } catch (Exception ignored) { }

        // Local player on top, green. computeSrcRect already centers
        // the view on the LERPED player position, and we re-derive the
        // dot from the SAME source so the dot is always exactly at the
        // visual center of the viewport — no zoom-dependent drift.
        if (local != null) {
            final float[] pt = localPlayerTile();
            final float sx = this.drawX + (pt[0] - srcX) * scaleX;
            final float sy = this.drawY + (pt[1] - srcY) * scaleY;
            shapes.setColor(LOCAL_COLOR);
            shapes.circle(sx, sy, 4f);
        }

        // Admin hop-mode badge — a cyan corner square so it's obvious clicks teleport.
        if (this.hopMode) {
            shapes.setColor(0.30f, 0.82f, 1.0f, 1f);
            shapes.rect(this.drawX + 3, this.drawY + 3, 10, 10);
        }

        // Close the Filled pass before switching to Line — ShapeRenderer.set()
        // requires autoShapeType to be enabled, which we don't set, so a
        // direct .set(Line) throws IllegalStateException. End + begin is the
        // safe transition.
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(BORDER_COLOR);
        shapes.rect(this.drawX, this.drawY, this.sizePx, this.sizePx);
        shapes.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
        batch.begin();
    }

    public void dispose() {
        if (this.mapTexture != null) { this.mapTexture.dispose(); this.mapTexture = null; }
        if (this.mapPixmap != null) { this.mapPixmap.dispose(); this.mapPixmap = null; }
    }
}
