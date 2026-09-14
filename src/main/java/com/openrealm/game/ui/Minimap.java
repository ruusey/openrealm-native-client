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
 * Square minimap mirroring the webclient: renders the whole realm (cached
 * Pixmap/Texture per map load), mouse-wheel zoom, click-to-teleport via /tp.
 */
@Data
@Slf4j
public class Minimap {
    private static final int DEFAULT_SIZE_PX = 200;
    private static final int DEFAULT_MARGIN = 10;

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

    private static final float MIN_ZOOM = 0.10f;
    private static final float MAX_ZOOM = 1.00f;

    private final PlayState playState;

    private int drawX = DEFAULT_MARGIN;
    private int drawY = DEFAULT_MARGIN;
    private int sizePx = DEFAULT_SIZE_PX;

    private int mapWidth;
    private int mapHeight;
    private Integer cachedMapId = null;

    private Pixmap mapPixmap;
    private Texture mapTexture;
    /** Rebuild the cached pixmap on next render (tile data may lag map load; also periodic). */
    private boolean dirty = true;
    private long lastRebuildMs = 0L;

    /** zoom = visible fraction of the map. 1.0 = whole map; lower = zoomed in. */
    private float zoom = 1.0f;

    private boolean visible = true;

    /** Admin /hop: while on, a minimap click teleports to those world coords. */
    private boolean hopMode = false;

    private int hoveredOtherIdx = -1;
    private String hoveredOtherName = null;
    private float[] cursorOnMapTile = new float[2];
    private boolean cursorInside = false;
    private boolean prevMouseDown = false;

    public Minimap(final PlayState playState) {
        this.playState = playState;
    }

    public void setLayout(int x, int y, int size) {
        this.drawX = x;
        this.drawY = y;
        this.sizePx = Math.max(32, size);
    }

    public void setHopMode(final boolean on) { this.hopMode = on; }
    public boolean isHopMode() { return this.hopMode; }

    public boolean isInitialized() {
        return this.mapWidth > 0 && this.mapHeight > 0;
    }

    public void initializeMap(final int mapId, final int dungeonId) {
        final Integer key = mapId;
        if (this.cachedMapId != null && this.cachedMapId.equals(key)) {
            this.dirty = true;
            return;
        }
        // Assembled dungeons carry mapId -1 (no MapModel); dimensions live in DUNGEONS.
        // Dereferencing a null MapModel here aborts the whole LoadMap handler.
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
        // Default zoom targets ~64 visible tiles: whole map for small realms,
        // ~0.1 for the 640-tile overworld so the loaded region isn't a dot.
        final float visibleTilesTarget = 64f;
        final float maxDim = Math.max(this.mapWidth, this.mapHeight);
        this.zoom = Math.min(1.0f, Math.max(0.1f, visibleTilesTarget / maxDim));
        // Runs on the network thread, NOT the GL thread: disposing the Texture
        // here (no current GL context) aborts the JVM at the C level. Only flag
        // the rebuild; render() disposes safely on the GL thread.
        this.dirty = true;
    }

    /** Build a 1px-per-tile snapshot of the realm, cached as a Texture. */
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
        // Dimensions are 0 briefly during map swap; Pixmap ctor throws on 0-dim.
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
        // Color keyed off the tile NAME (mirrors webclient minimap.js _getTileColor).
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
    }

    /**
     * Mouse-wheel zoom + click-to-teleport. Call once per frame from
     * PlayerUI.input() AFTER tab/drag handling so it doesn't steal scroll.
     */
    public void input(MouseHandler mouse) {
        if (!this.isInitialized() || !this.visible) return;
        final int mx = mouse.getX();
        final int my = mouse.getY();
        final boolean inside = mx >= this.drawX && mx <= this.drawX + this.sizePx
                && my >= this.drawY && my <= this.drawY + this.sizePx;
        this.cursorInside = inside;

        if (inside) {
            float wheel = KeyHandler.consumeScroll();
            if (wheel != 0f) {
                this.zoom = clamp(this.zoom + (wheel > 0 ? 0.10f : -0.10f), MIN_ZOOM, MAX_ZOOM);
            }
        }

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

        final boolean down = mouse.isPressed(1);
        final boolean justClicked = down && !this.prevMouseDown;
        this.prevMouseDown = down;
        if (justClicked && inside) {
            if (this.hopMode) {
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

    /** Local player center in tile space, from lerped render coords (not raw pos). */
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

        // Rebuild lazily: initializeMap() runs before tiles merge; re-run every ~2s for streamed regions.
        final long now = System.currentTimeMillis();
        if (this.dirty || (this.mapTexture == null) || (now - this.lastRebuildMs > 2000L)) {
            this.rebuildMapTexture();
            this.dirty = false;
            this.lastRebuildMs = now;
        }
        if (this.mapTexture == null) return;

        final float[] src = this.computeSrcRect();
        // Dot math MUST reuse these rounded src pixels or the player dot drifts off the map.
        final int srcXi = Math.round(src[0]);
        final int srcYi = Math.round(src[1]);
        final int viewWi = Math.max(1, Math.round(src[2]));
        final int viewHi = Math.max(1, Math.round(src[3]));
        final float srcX = srcXi;
        final float srcY = srcYi;
        final float viewW = viewWi;
        final float viewH = viewHi;

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        UiRender.fillRect(batch, shapes, this.drawX - 1, this.drawY - 1,
                this.sizePx + 2, this.sizePx + 2, BG_COLOR);

        // flipY=true: the y-down UI cam flips this draw overload; matches the top-down dots below.
        batch.draw(this.mapTexture,
                this.drawX, this.drawY, this.sizePx, this.sizePx,
                srcXi, srcYi, viewWi, viewHi,
                false, true);

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
            // Server-wide global players, shaded differently; same projection as local dots.
            final NetPlayerPosition[] globals = this.playState.getMinimapPlayers();
            if (globals != null && globals.length > 0) {
                shapes.setColor(0.55f, 0.55f, 0.85f, 0.8f);
                final int ts = GlobalConstants.BASE_TILE_SIZE;
                // NetPlayerPosition has no per-player size; use the default to center like local.
                final float halfPlayer = GlobalConstants.PLAYER_SIZE / 2f;
                for (NetPlayerPosition gp : globals) {
                    if (gp == null) continue;
                    if (local != null && gp.getPlayerId() == localId) continue;
                    // Skip players already in our local realm (drawn above) to avoid double dots.
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

        // Local player on top, green (derived from the same src as the view center).
        if (local != null) {
            final float[] pt = localPlayerTile();
            final float sx = this.drawX + (pt[0] - srcX) * scaleX;
            final float sy = this.drawY + (pt[1] - srcY) * scaleY;
            shapes.setColor(LOCAL_COLOR);
            shapes.circle(sx, sy, 4f);
        }

        if (this.hopMode) {
            shapes.setColor(0.30f, 0.82f, 1.0f, 1f);
            shapes.rect(this.drawX + 3, this.drawY + 3, 10, 10);
        }

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
