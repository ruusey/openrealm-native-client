package com.openrealm.game.data;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.openrealm.game.contants.CharacterClass;
import com.openrealm.game.contants.GlobalConstants;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.model.ability.Ability;
import com.openrealm.game.graphics.Sprite;
import com.openrealm.game.graphics.SpriteSheet;
import com.openrealm.game.model.AnimationFrameModel;
import com.openrealm.game.model.AnimationModel;
import com.openrealm.game.model.AnimationSetModel;
import com.openrealm.game.model.SpriteModel;
import com.openrealm.game.model.TileModel;
import com.openrealm.net.client.ClientGameLogic;

import lombok.extern.slf4j.Slf4j;
import java.util.Set;

@Slf4j
public class GameSpriteManager {

    private static final String LOG_NS = "[CLIENT](sprite-manager)";

    private static final String[] SPRITE_NAMES = {
            // HUD chrome — fetched from /game-data/ui.png and /game-data/buttons.png
            // which the data service serves out of openrealm-data's classpath:/ui/.
            // Without these in the cache, GameStateManager's SpriteSheet ctor for
            // "ui.png" / "buttons.png" hits a null Texture and crashes on startup.
            "ui.png", "buttons.png",
            "rotmg-projectiles.png",
            "rotmg-bosses.png", "rotmg-bosses-1.png",
            "rotmg-items-1.png",
            "rotmg-tiles.png", "rotmg-tiles-1.png", "rotmg-tiles-2.png", "rotmg-tiles-all.png",
            "rotmg-misc.png",
            "rotmg-classes-0.png", "rotmg-classes-1.png", "rotmg-classes-2.png", "rotmg-classes-3.png",
            "lofiObj2.png", "lofiObj3.png", "lofiObjBig.png",
            "lofiEnvironment2.png", "lofiEnvironment3.png",
            "lofi_dungeon_features.png",
            "chars8x8rBeach.png", "chars8x8rHero2.png", "cursedLibraryChars16x16.png",
            "d1Chars16x16r.png", "d3Chars8x8r.png", "cursedLibraryChars8x8.png", "cursedLibraryObjects8x8.png",
            "d2LofiObj.png", "d3LofiObj.png", "lofiProjs.png", "chars16x16dEncounters.png",
            "archbishopObjects16x16.png",
            "chars16x16dEncounters2.png", "crystalCaveChars16x16.png",
            "crystalCaveObjects8x8.png", "fungalCavernObjects8x8.png",
            "epicHiveChars8x8.png", "lairOfDraconisChars8x8.png", "lairOfDraconisObjects8x8.png",
            "lostHallsObjects8x8.png", "magicWoodsObjects8x8.png", "mountainTempleObjects8x8.png",
            "summerNexusObjects8x8.png",
            "oryxHordeChars16x16.png", "oryxHordeChars8x8.png",
            "secludedThicketChars16x16.png",
            "lofiBosses16x16.png",
            "lofiCharacter10x10.png", "lofiProjectiles.png",
            "battleOryxObjects8x8.png",
            "openrealm-items.png", "openrealm-classes.png", "openrealm-ability-icons.png" };

    public static Map<String, Texture> TEXTURE_CACHE;
    public static Map<Integer, TextureRegion> TILE_SPRITES;
    public static Map<Integer, TextureRegion> ITEM_SPRITES;
    public static Map<Integer, TextureRegion> ABILITY_SPRITES;
    /** Pre-baked seam-feather TextureRegions, indexed by tileId. Each
     *  entry is a 4-element array [N, S, W, E] — the neighbor's pixels
     *  with a linear-alpha gradient applied so when blitted as a seam
     *  fringe it produces a smooth pixel-perfect blend with zero
     *  banding. All variants share a single backing Texture (the
     *  feather atlas), so SpriteBatch can batch every seam draw in
     *  the per-frame seam pass into a single GL flush. Populated by
     *  {@link #bakeTileFeathers()} once at boot. */
    public static Map<Integer, TextureRegion[]> TILE_FEATHERS;
    /** Backing Texture for all feather variants. Owned here so we can
     *  dispose it on app shutdown. Created in bakeTileFeathers. */
    public static Texture TILE_FEATHER_ATLAS;
    /** Per-tile average opaque color {r,g,b} in 0..255, computed in
     *  bakeTileFeathers. Feeds {@link #tilesShouldBlend(int,int)} so seams
     *  between same-material tiles (near-identical color) aren't feathered. */
    public static Map<Integer, float[]> TILE_COLOR_SIG;
    /** Memoized pairwise blend decisions keyed by the two tile ids; cleared
     *  on re-bake and whenever the global threshold changes. */
    private static final Map<Long, Boolean> BLEND_COMPAT_CACHE = new HashMap<>();
    /** Threshold the cache was last built against; a change invalidates it. */
    private static float lastBlendThreshold = Float.NaN;
    /** Source Pixmaps held in CPU memory parallel to the GPU textures
     *  in {@link #TEXTURE_CACHE}. SpriteRecolorCache reads from these
     *  to do per-pixel dye / enchantment work without having to re-
     *  fetch the PNG. The class sheets (rotmg-classes-*.png) and item
     *  sheets are the only ones recolor really needs, but populating
     *  the whole map is a small RAM cost (~10–20 MB total) for a much
     *  simpler invariant: any sheet that's in TEXTURE_CACHE is also
     *  here. */
    public static Map<String, Pixmap> PIXMAP_CACHE;

    public static void loadItemSprites() {
        if (GameSpriteManager.TEXTURE_CACHE == null) return;
        GameSpriteManager.ITEM_SPRITES = new HashMap<>();
        for (Integer gameItemId : GameDataManager.GAME_ITEMS.keySet()) {
            final GameItem model = GameDataManager.GAME_ITEMS.get(gameItemId);
            if (model.getSpriteSize() == 0) {
                model.setSpriteSize(GlobalConstants.BASE_SPRITE_SIZE);
            }
            final Texture spriteTexture = GameSpriteManager.TEXTURE_CACHE.get(model.getSpriteKey());
            if (spriteTexture == null) continue;
            int sw = model.getSpriteSize();
            int sh = model.getEffectiveSpriteHeight();
            TextureRegion subRegion = new TextureRegion(spriteTexture,
                    model.getCol() * sw,
                    model.getRow() * sh,
                    sw, sh);
            subRegion.flip(false, true);
            GameSpriteManager.ITEM_SPRITES.put(gameItemId, subRegion);
        }
    }

    /** Bake ability-icon TextureRegions from abilities.json (spriteKey/row/col/
     *  spriteSize) — same convention as items. Keyed by ability id; rebuilt on
     *  every sprite reload so a cached region never points at a disposed texture. */
    public static void loadAbilitySprites() {
        if (GameSpriteManager.TEXTURE_CACHE == null || GameDataManager.ABILITIES == null) return;
        GameSpriteManager.ABILITY_SPRITES = new HashMap<>();
        for (final Ability model : GameDataManager.ABILITIES.values()) {
            if (model == null || model.getSpriteKey() == null || model.getSpriteKey().isEmpty()) continue;
            if (model.getSpriteSize() == 0) {
                model.setSpriteSize(GlobalConstants.BASE_SPRITE_SIZE);
            }
            final Texture spriteTexture = GameSpriteManager.TEXTURE_CACHE.get(model.getSpriteKey());
            if (spriteTexture == null) continue;
            int sw = model.getSpriteSize();
            int sh = model.getSpriteHeight() > 0 ? model.getSpriteHeight() : sw;
            TextureRegion subRegion = new TextureRegion(spriteTexture,
                    model.getCol() * sw,
                    model.getRow() * sh,
                    sw, sh);
            subRegion.flip(false, true);
            GameSpriteManager.ABILITY_SPRITES.put(model.getId(), subRegion);
        }
    }

    /** Ability-icon region for a single ability, built + cached on demand. Order-
     *  independent: the HUD calls this at draw time (ability is always loaded by
     *  then) so it works even if loadAbilitySprites ran before the sheet texture
     *  or the ability data was ready. Returns null if the sheet isn't loaded. */
    public static TextureRegion getAbilityIconRegion(final Ability ability) {
        if (ability == null || ability.getSpriteKey() == null || ability.getSpriteKey().isEmpty()) return null;
        if (GameSpriteManager.TEXTURE_CACHE == null) return null;
        if (GameSpriteManager.ABILITY_SPRITES == null) GameSpriteManager.ABILITY_SPRITES = new HashMap<>();
        final TextureRegion cached = GameSpriteManager.ABILITY_SPRITES.get(ability.getId());
        if (cached != null) return cached;
        final Texture tex = GameSpriteManager.TEXTURE_CACHE.get(ability.getSpriteKey());
        if (tex == null) return null;
        final int sw = ability.getSpriteSize() > 0 ? ability.getSpriteSize() : GlobalConstants.BASE_SPRITE_SIZE;
        final int sh = ability.getSpriteHeight() > 0 ? ability.getSpriteHeight() : sw;
        final TextureRegion region = new TextureRegion(tex, ability.getCol() * sw, ability.getRow() * sh, sw, sh);
        region.flip(false, true);
        GameSpriteManager.ABILITY_SPRITES.put(ability.getId(), region);
        return region;
    }

    public static void loadTileSprites() {
        if (GameSpriteManager.TEXTURE_CACHE == null) return;
        GameSpriteManager.TILE_SPRITES = new HashMap<>();
        for (Integer tileId : GameDataManager.TILES.keySet()) {
            final TileModel model = GameDataManager.TILES.get(tileId);
            if (model.getSpriteSize() == 0) {
                model.setSpriteSize(GlobalConstants.BASE_SPRITE_SIZE);
            }

            final Texture spriteTexture = GameSpriteManager.TEXTURE_CACHE.get(model.getSpriteKey());
            if (spriteTexture == null) continue;
            int sw = model.getSpriteSize();
            int sh = model.getEffectiveSpriteHeight();
            TextureRegion subRegion = new TextureRegion(spriteTexture,
                    model.getCol() * sw,
                    model.getRow() * sh,
                    sw, sh);
            subRegion.flip(false, true);
            GameSpriteManager.TILE_SPRITES.put(tileId, subRegion);
        }
    }

    /**
     * Pre-bake seam-feather TextureRegions for every base tile, packed into
     * a single shared atlas Texture so SpriteBatch can batch every per-
     * frame seam draw into one GL flush. Each tile gets 4 variants (N/S/W/
     * E); the variant contains the neighbor's pixels for that edge with a
     * linear alpha gradient applied (opaque at the seam, transparent at
     * the inner edge). Cost: ~10K pixel ops at boot. Replaces the
     * runtime multi-stripe blend that produced visible banding and
     * required 3 draws per seam side.
     */
    public static void bakeTileFeathers() {
        if (TILE_SPRITES == null || PIXMAP_CACHE == null) return;
        // Depth = 15% of tile dimension (was 30%, then 20%). Narrower
        // fringe so standalone single tiles aren't visually swallowed by
        // the blend on every side.
        final float FEATHER_FRAC = 0.15f;

        // Collect tiles that can be baked (have a source pixmap).
        final List<Integer> tileIds = new ArrayList<>();
        int maxRowW = 0;
        int totalH = 0;
        for (Integer tileId : GameDataManager.TILES.keySet()) {
            final TileModel model = GameDataManager.TILES.get(tileId);
            if (model == null) continue;
            if (PIXMAP_CACHE.get(model.getSpriteKey()) == null) continue;
            int sw = model.getSpriteSize();
            int sh = model.getEffectiveSpriteHeight();
            if (sw <= 0 || sh <= 0) continue;
            int depthW = Math.max(2, Math.round(sw * FEATHER_FRAC));
            int depthH = Math.max(2, Math.round(sh * FEATHER_FRAC));
            // Per-tile atlas row: [N | S | W | E] laid horizontally.
            int rowW = sw + sw + depthW + depthW;
            int rowH = Math.max(sh, depthH);
            maxRowW = Math.max(maxRowW, rowW);
            totalH += rowH;
            tileIds.add(tileId);
        }
        if (tileIds.isEmpty()) return;

        final int atlasW = nextPow2(Math.max(32, maxRowW));
        final int atlasH = nextPow2(Math.max(32, totalH));

        final Pixmap atlas = new Pixmap(atlasW, atlasH, Pixmap.Format.RGBA8888);
        atlas.setBlending(Pixmap.Blending.None);
        atlas.setColor(0, 0, 0, 0);
        atlas.fill();

        TILE_FEATHERS = new HashMap<>();
        TILE_COLOR_SIG = new HashMap<>();
        BLEND_COMPAT_CACHE.clear();
        // Per-tile atlas coords for the 4 variants, kept as raw ints so
        // we can build TextureRegions AFTER the atlas Texture exists.
        // TextureRegion.setRegion(int,int,int,int) calls texture.getWidth()
        // internally — calling it before binding a Texture NPEs.
        final java.util.Map<Integer, int[][]> regionCoords = new HashMap<>();
        int rowY = 0;
        for (Integer tileId : tileIds) {
            final TileModel model = GameDataManager.TILES.get(tileId);
            final Pixmap srcPm = PIXMAP_CACHE.get(model.getSpriteKey());
            final int sw = model.getSpriteSize();
            final int sh = model.getEffectiveSpriteHeight();
            final int srcX = model.getCol() * sw;
            final int srcY = model.getRow() * sh;
            final int depthW = Math.max(2, Math.round(sw * FEATHER_FRAC));
            final int depthH = Math.max(2, Math.round(sh * FEATHER_FRAC));
            final int rowH = Math.max(sh, depthH);
            final int[][] coords = new int[4][4]; // [dir][x, y, w, h]

            // Average opaque color over the whole tile — the seam gate's
            // signature. Transparent pixels skipped so decorations don't skew
            // toward black. RGBA8888: R=>>>24, G=>>>16, B=>>>8, A=&0xff.
            long sr = 0, sg = 0, sb = 0, sn = 0;
            for (int py = 0; py < sh; py++) {
                for (int px = 0; px < sw; px++) {
                    final int rgba = srcPm.getPixel(srcX + px, srcY + py);
                    if ((rgba & 0xff) < 8) continue;
                    sr += (rgba >>> 24) & 0xff;
                    sg += (rgba >>> 16) & 0xff;
                    sb += (rgba >>> 8) & 0xff;
                    sn++;
                }
            }
            if (sn > 0) TILE_COLOR_SIG.put(tileId, new float[] { sr / (float) sn, sg / (float) sn, sb / (float) sn });

            // Lay out: [N at x=0, S at x=sw, W at x=2sw, E at x=2sw+depthW]
            final int[] varAtlasX = { 0, sw, sw * 2, sw * 2 + depthW };
            final int[] varW = { sw,    sw,    depthW, depthW };
            final int[] varH = { depthH, depthH, sh,    sh     };
            // Source sub-rect within the spritesheet:
            //   N — neighbor's BOTTOM strip
            //   S — neighbor's TOP strip
            //   W — neighbor's RIGHT strip
            //   E — neighbor's LEFT strip
            final int[] varSrcOffX = { 0,             0,         sw - depthW, 0 };
            final int[] varSrcOffY = { sh - depthH,   0,         0,           0 };

            for (int dir = 0; dir < 4; dir++) {
                final int outX = varAtlasX[dir];
                final int outY = rowY;
                final int w = varW[dir];
                final int h = varH[dir];
                final int sxOff = varSrcOffX[dir];
                final int syOff = varSrcOffY[dir];
                final boolean isVert = (dir < 2);
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        // FLIP the sample in the direction perpendicular to
                        // the seam so the seam-adjacent pixel of the neighbor
                        // ends up at the visible edge of the fringe. Without
                        // this, the feather shows the WRONG part of the
                        // neighbor at the seam (depthH-1 rows away), reading
                        // as discontinuous / inverted blending.
                        final int srcPxX = isVert ? x : (w - 1 - x);
                        final int srcPxY = isVert ? (h - 1 - y) : y;
                        final int rgba = srcPm.getPixel(srcX + sxOff + srcPxX, srcY + syOff + srcPxY);
                        // Linear alpha gradient: 1.0 at the seam edge,
                        // 0.0 at the inner edge. Direction-specific.
                        float t;
                        switch (dir) {
                            case 0: t = (float) y / (float) h; break;            // N: top opaque -> bottom transparent
                            case 1: t = (float) (h - 1 - y) / (float) h; break;  // S: bottom opaque -> top transparent
                            case 2: t = (float) x / (float) w; break;            // W: left opaque -> right transparent
                            default: t = (float) (w - 1 - x) / (float) w; break; // E: right opaque -> left transparent
                        }
                        // Peak alpha = 0.5 at the seam (not 1.0). With 1.0
                        // the neighbor's color FULLY replaced the base tile
                        // at the seam pixel — looked inverted because the
                        // grey tile showed blue at its right edge and the
                        // water tile showed grey at its left edge. Capping
                        // at 0.5 lets the base color always dominate; both
                        // sides at the seam show a 50/50 mix instead of a
                        // hard color override.
                        final float PEAK_ALPHA = 0.5f;
                        final int origA = rgba & 0xff;
                        final int newA = Math.round(origA * PEAK_ALPHA * (1f - t));
                        final int outRgba = (rgba & 0xffffff00) | (newA & 0xff);
                        atlas.drawPixel(outX + x, outY + y, outRgba);
                    }
                }
                // Stash coords; TextureRegions get constructed after the
                // atlas Texture exists (next loop below).
                coords[dir][0] = outX;
                coords[dir][1] = outY;
                coords[dir][2] = w;
                coords[dir][3] = h;
            }
            regionCoords.put(tileId, coords);
            rowY += rowH;
        }

        if (TILE_FEATHER_ATLAS != null) {
            TILE_FEATHER_ATLAS.dispose();
        }
        TILE_FEATHER_ATLAS = new Texture(atlas);
        TILE_FEATHER_ATLAS.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        atlas.dispose();

        // Now that the atlas Texture exists, construct TextureRegions
        // bound to it. Y-flip to match TILE_SPRITES orientation so seam
        // fringes render the right way up under the Y-down world camera.
        for (java.util.Map.Entry<Integer, int[][]> e : regionCoords.entrySet()) {
            final int[][] coords = e.getValue();
            final TextureRegion[] variants = new TextureRegion[4];
            for (int dir = 0; dir < 4; dir++) {
                final int[] c = coords[dir];
                final TextureRegion r = new TextureRegion(
                        TILE_FEATHER_ATLAS, c[0], c[1], c[2], c[3]);
                r.flip(false, true);
                variants[dir] = r;
            }
            TILE_FEATHERS.put(e.getKey(), variants);
        }

        log.info("{} Baked tile feathers - {} tiles x 4 = {} variants in {}x{} atlas",
                LOG_NS, tileIds.size(), tileIds.size() * 4, atlasW, atlasH);
    }

    /** True if a seam between two base tile types should be feathered. Tiles
     *  whose average colors are within TILE_BLEND_MIN_COLOR_DIST read as the
     *  same material and are left un-blended so irregular same-material
     *  patterns (e.g. mixed wood planks) survive. Missing signature -> blend.
     *  Memoized per unordered pair. */
    public static boolean tilesShouldBlend(int a, int b) {
        if (a == b) return false;
        final float thresh = GlobalConstants.TILE_BLEND_MIN_COLOR_DIST;
        if (thresh != lastBlendThreshold) {
            BLEND_COMPAT_CACHE.clear();
            lastBlendThreshold = thresh;
        }
        final long key = a < b ? ((long) a << 32) | (b & 0xffffffffL)
                               : ((long) b << 32) | (a & 0xffffffffL);
        final Boolean hit = BLEND_COMPAT_CACHE.get(key);
        if (hit != null) return hit;
        boolean blend = true;
        if (TILE_COLOR_SIG != null) {
            final float[] sa = TILE_COLOR_SIG.get(a);
            final float[] sb = TILE_COLOR_SIG.get(b);
            if (sa != null && sb != null) {
                final float dr = sa[0] - sb[0], dg = sa[1] - sb[1], db = sa[2] - sb[2];
                blend = Math.sqrt(dr * dr + dg * dg + db * db) >= thresh;
            }
        }
        BLEND_COMPAT_CACHE.put(key, blend);
        return blend;
    }

    private static int nextPow2(int v) {
        int p = 1;
        while (p < v) p <<= 1;
        return p;
    }

    public static SpriteSheet getSpriteSheet(SpriteModel spriteModel) {
        if (GameSpriteManager.TEXTURE_CACHE == null) {
            return null;
        }
        SpriteSheet result = null;
        try {
            final Texture spriteTexture = GameSpriteManager.TEXTURE_CACHE.get(spriteModel.getSpriteKey());
            final SpriteSheet sheet = new SpriteSheet(spriteTexture, spriteModel);
            result = sheet;
        } catch (Exception e) {
            GameSpriteManager.log.error("{} Failed to build sprite sheet for sprite model {}. Reason: {}", LOG_NS, spriteModel, e);
        }
        return result;
    }

    public static Sprite loadSprite(int x, int y, String file, int spriteSize) {
        if (GameSpriteManager.TEXTURE_CACHE == null) {
            return null;
        }
        final Texture texture = GameSpriteManager.TEXTURE_CACHE.get(file);
        if (texture == null) {
            return null;
        }
        final TextureRegion subRegion = new TextureRegion(texture, x * spriteSize, y * spriteSize, spriteSize, spriteSize);
        subRegion.flip(false, true);
        return new Sprite(subRegion);
    }

    public static Sprite loadSprite(SpriteModel model) {
        if (GameSpriteManager.TEXTURE_CACHE == null) {
            return null;
        }
        if (model.getSpriteSize() == 0) {
            model.setSpriteSize(GlobalConstants.BASE_SPRITE_SIZE);
        }
        final Texture texture = GameSpriteManager.TEXTURE_CACHE.get(model.getSpriteKey());
        if (texture == null) {
            return null;
        }
        int sw = model.getSpriteSize();
        int sh = model.getEffectiveSpriteHeight();
        final TextureRegion subRegion = new TextureRegion(texture,
                model.getCol() * sw,
                model.getRow() * sh,
                sw, sh);
        subRegion.flip(false, true);
        return new Sprite(subRegion);
    }

    /**
     * HUD chrome that the GameStateManager constructs unconditionally on
     * startup. These are guaranteed to be available because the native client
     * bundles them under {@code resources/ui/} — we don't want a missing
     * remote path or a stale data service to crash the launcher.
     */
    private static final Set<String> BUNDLED_HUD_SHEETS =
            Set.of("ui.png", "buttons.png");

    /**
     * Build the union of (a) the hardcoded {@link #SPRITE_NAMES} baseline
     * (HUD chrome + sheets we always want to preload) and (b) every
     * spriteKey referenced by data loaded into GameDataManager — items,
     * tiles, enemies, portals, animations, character classes, set pieces.
     * Without this, a newly-added item/tile that points at a brand-new
     * sprite sheet (e.g. item 837 with a new sheet) silently fails to
     * render: the sheet isn't in SPRITE_NAMES -> Texture never cached ->
     * loadItemSprites' TEXTURE_CACHE.get(spriteKey) returns null -> the
     * item gets no entry in ITEM_SPRITES -> blank quad in inventory and
     * on the ground.
     */
    private static LinkedHashSet<String> collectAllSpriteKeys() {
        final LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (String s : SPRITE_NAMES) keys.add(s);
        try {
            if (GameDataManager.GAME_ITEMS != null) {
                for (GameItem v : GameDataManager.GAME_ITEMS.values()) {
                    if (v != null && v.getSpriteKey() != null) keys.add(v.getSpriteKey());
                }
            }
            if (GameDataManager.TILES != null) {
                for (TileModel v : GameDataManager.TILES.values()) {
                    if (v != null && v.getSpriteKey() != null) keys.add(v.getSpriteKey());
                }
            }
            if (GameDataManager.ENEMIES != null) {
                for (Object v : GameDataManager.ENEMIES.values()) {
                    addSpriteKeyReflective(v, keys);
                }
            }
            if (GameDataManager.PORTALS != null) {
                for (Object v : GameDataManager.PORTALS.values()) {
                    addSpriteKeyReflective(v, keys);
                }
            }
            if (GameDataManager.PROJECTILE_GROUPS != null) {
                for (Object v : GameDataManager.PROJECTILE_GROUPS.values()) {
                    addSpriteKeyReflective(v, keys);
                }
            }
            if (GameDataManager.SETPIECES != null) {
                for (Object v : GameDataManager.SETPIECES.values()) {
                    addSpriteKeyReflective(v, keys);
                }
            }
            if (GameDataManager.CHARACTER_CLASSES != null) {
                for (Object v : GameDataManager.CHARACTER_CLASSES.values()) {
                    addSpriteKeyReflective(v, keys);
                }
            }
            if (GameDataManager.ANIMATIONS != null) {
                for (AnimationModel anim : GameDataManager.ANIMATIONS.values()) {
                    if (anim == null) continue;
                    // Top-level spriteKey carries the sheet for the whole
                    // animation set when the inner SpriteModel doesn't own
                    // its own. This is the most common pattern in
                    // animations.json — one sheet per object.
                    if (anim.getSpriteKey() != null && !anim.getSpriteKey().isEmpty()) {
                        keys.add(anim.getSpriteKey());
                    }
                }
            }
            if (GameDataManager.ABILITIES != null) {
                for (Object v : GameDataManager.ABILITIES.values()) {
                    addSpriteKeyReflective(v, keys);
                }
            }
        } catch (Exception e) {
            log.warn("{} Sprite-key discovery failed; falling back to hardcoded list. Reason: {}", LOG_NS, e.getMessage());
        }
        return keys;
    }

    /** Reflectively pulls a String spriteKey off any object that has a
     *  no-arg getSpriteKey() method; silently no-ops otherwise. Lets the
     *  discovery walk handle EnemyModel / PortalModel / etc. without
     *  requiring direct compile-time imports. */
    private static void addSpriteKeyReflective(Object obj, LinkedHashSet<String> out) {
        if (obj == null) return;
        try {
            java.lang.reflect.Method m = obj.getClass().getMethod("getSpriteKey");
            Object v = m.invoke(obj);
            if (v instanceof String && !((String) v).isEmpty()) {
                out.add((String) v);
            }
        } catch (NoSuchMethodException ignored) {
        } catch (Exception ignored) {}
    }

    public static void loadSpriteImages(boolean loadRemote) {
        GameSpriteManager.TEXTURE_CACHE = new HashMap<>();
        try {
            final LinkedHashSet<String> allKeys = collectAllSpriteKeys();
            log.info("{} Loading {} sprite sheets ({} hardcoded + {} discovered from data)",
                    LOG_NS, allKeys.size(), SPRITE_NAMES.length, allKeys.size() - SPRITE_NAMES.length);
            for (final String spriteKey : allKeys) {
                Texture texture = null;
                // For HUD chrome, prefer the bundled copy outright. It's tiny,
                // we always have it, and skipping the network avoids both a
                // spurious miss-log and a startup-time stall when the data
                // service is slow/unreachable.
                if (BUNDLED_HUD_SHEETS.contains(spriteKey)) {
                    texture = GameSpriteManager.loadTextureQuiet("ui/" + spriteKey);
                } else if (loadRemote) {
                    texture = GameSpriteManager.loadTextureRemote(spriteKey);
                } else {
                    texture = GameSpriteManager.loadTexture("entity/" + spriteKey);
                }
                if (texture == null) continue;
                GameSpriteManager.TEXTURE_CACHE.put(spriteKey, texture);
            }
        } catch (Exception e) {
            GameSpriteManager.log.error("{} Failed to load game sprites. Exiting. Reason: {}", LOG_NS, e);
            System.exit(-1);
        }
    }

    /**
     * Like {@link #loadTexture(String)} but returns null silently on a miss,
     * for paths we expect to fail sometimes (the bundled-fallback case).
     */
    private static Texture loadTextureQuiet(String file) {
        try {
            InputStream is = GameSpriteManager.class.getClassLoader().getResourceAsStream(file);
            if (is == null) return null;
            byte[] bytes = readAllBytes(is);
            Pixmap pixmap = new Pixmap(bytes, 0, bytes.length);
            Texture texture = new Texture(pixmap);
            texture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            cachePixmap(file, pixmap);
            return texture;
        } catch (Exception e) {
            return null;
        }
    }

    private static Texture loadTexture(String file) {
        Texture texture = null;
        try {
            InputStream is = GameSpriteManager.class.getClassLoader().getResourceAsStream(file);
            if (is == null) {
                GameSpriteManager.log.error("{} ERROR: could not find file: {}", LOG_NS, file);
                return null;
            }
            byte[] bytes = readAllBytes(is);
            Pixmap pixmap = new Pixmap(bytes, 0, bytes.length);
            texture = new Texture(pixmap);
            texture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            cachePixmap(file, pixmap);
        } catch (Exception e) {
            GameSpriteManager.log.error("{} ERROR: could not load file: {}", LOG_NS, file);
        }
        return texture;
    }

    public static Texture loadTextureRemote(String file) {
        Texture texture = null;
        try {
            String baseUrl = ClientGameLogic.DATA_SERVICE.getBaseUrl();
            final URL imageUrl = new URL(baseUrl + "game-data/" + file);
            InputStream is = imageUrl.openStream();
            byte[] bytes = readAllBytes(is);
            is.close();
            Pixmap pixmap = new Pixmap(bytes, 0, bytes.length);
            texture = new Texture(pixmap);
            texture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            cachePixmap(file, pixmap);
        } catch (Exception e) {
            GameSpriteManager.log.error("{} ERROR: could not load remote file: {}. Reason: {}", LOG_NS, file, e.getMessage());
        }
        return texture;
    }

    /** Stash the CPU-side Pixmap behind whatever sprite-key the GPU
     *  texture is registered under. The path argument arrives as either
     *  a bare key ("rotmg-classes-0.png") or with a folder prefix
     *  ("entity/rotmg-classes-0.png" / "ui/buttons.png"); we strip the
     *  prefix so SpriteRecolorCache can look it up by the same key
     *  TEXTURE_CACHE uses. The Pixmap is intentionally NOT disposed
     *  here — recolor work needs to read its pixels later. */
    private static void cachePixmap(String path, Pixmap pixmap) {
        if (PIXMAP_CACHE == null) PIXMAP_CACHE = new HashMap<>();
        String key = path;
        int slash = key.lastIndexOf('/');
        if (slash >= 0) key = key.substring(slash + 1);
        Pixmap existing = PIXMAP_CACHE.put(key, pixmap);
        if (existing != null && existing != pixmap) existing.dispose();
    }

    private static byte[] readAllBytes(InputStream is) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int len;
        while ((len = is.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        return baos.toByteArray();
    }

    private static final String[] CLASS_SHEET_NAMES = {
        "rotmg-classes-0.png", "rotmg-classes-1.png", "rotmg-classes-2.png", "rotmg-classes-3.png"
    };

    private static String getClassSheetName(int classId) {
        int sheetIndex = classId / 3;
        if (sheetIndex >= 0 && sheetIndex < CLASS_SHEET_NAMES.length) {
            return CLASS_SHEET_NAMES[sheetIndex];
        }
        return CLASS_SHEET_NAMES[0];
    }

    /**
     * Load class sprites from animations.json data. Animation frame coordinates
     * (row/col) come from the JSON; durations are stored as defaults but for
     * player entities they will be overridden at runtime based on speed/dexterity stats.
     */
    public static SpriteSheet loadClassSprites(CharacterClass cls) {
        if (GameSpriteManager.TEXTURE_CACHE == null) return null;

        // Try data-driven path first (animations.json loaded)
        AnimationModel animModel = GameDataManager.getAnimation("player", cls.classId);

        if (animModel != null) {
            return loadClassSpritesFromData(cls, animModel);
        }

        // Fallback: should not happen once animations.json is always present
        log.warn("{} No animation data for classId={}, cannot load sprites", LOG_NS, cls.classId);
        return null;
    }

    private static SpriteSheet loadClassSpritesFromData(CharacterClass cls, AnimationModel animModel) {
        final SpriteSheet sheet = buildAnimatedSpriteSheet(animModel);
        if (sheet == null) {
            log.warn("{} No texture for classId={} spriteKey={}", LOG_NS, cls.classId, animModel.getSpriteKey());
        }
        return sheet;
    }

    /**
     * Animated enemy sprites from the enemy's AnimationModel (objectType
     * "enemy", keyed by enemyId). Returns null when the enemy has no animation
     * entry, signalling the caller to fall back to a static single-frame sheet.
     */
    public static SpriteSheet loadEnemySprites(int enemyId) {
        final AnimationModel animModel = GameDataManager.getAnimation("enemy", enemyId);
        if (animModel == null) return null;
        return buildAnimatedSpriteSheet(animModel);
    }

    /**
     * Build a SpriteSheet with named animation sets (idle/walk/attack...) from
     * an AnimationModel. Shared by player classes and animated enemies. Each
     * frame's effective (width, height) follows a fallback chain:
     *   frame.spriteWidth  -> set.spriteWidth  -> anim.spriteSize
     *   frame.spriteHeight -> set.spriteHeight -> anim.spriteHeight (or spriteSize)
     * When the resolved size matches the sheet's default cell we use the
     * precomputed grid region; otherwise we slice on-the-fly with
     * getSubSpritePx so a wider/taller attack frame can overhang the grid
     * without the sheet needing a uniform cell size.
     */
    public static SpriteSheet buildAnimatedSpriteSheet(AnimationModel animModel) {
        if (GameSpriteManager.TEXTURE_CACHE == null || animModel == null
                || animModel.getAnimations() == null) return null;
        final Texture texture = GameSpriteManager.TEXTURE_CACHE.get(animModel.getSpriteKey());
        if (texture == null) return null;

        // Use idle_side's first frame as the initial sprite position.
        final AnimationSetModel idleSide = animModel.getAnimations().get("idle_side");
        final boolean haveIdle = idleSide != null && idleSide.getFrames() != null && !idleSide.getFrames().isEmpty();
        int initRow = haveIdle ? idleSide.getFrames().get(0).getRow() : 0;
        int initCol = haveIdle ? idleSide.getFrames().get(0).getCol() : 0;

        int spW = animModel.getSpriteSize() > 0 ? animModel.getSpriteSize() : GlobalConstants.BASE_SPRITE_SIZE;
        int spH = animModel.getEffectiveSpriteHeight() > 0 ? animModel.getEffectiveSpriteHeight() : GlobalConstants.BASE_SPRITE_SIZE;
        final SpriteSheet sheet = new SpriteSheet(texture, spW, spH, initCol, initRow);

        for (Map.Entry<String, AnimationSetModel> entry : animModel.getAnimations().entrySet()) {
            String animName = entry.getKey();
            AnimationSetModel animSet = entry.getValue();
            int setW = animSet.getSpriteWidth() > 0 ? animSet.getSpriteWidth() : 0;
            int setH = animSet.getSpriteHeight() > 0 ? animSet.getSpriteHeight() : 0;
            List<Sprite> frames = new ArrayList<>();
            for (AnimationFrameModel frame : animSet.getFrames()) {
                int fw = frame.getSpriteWidth() > 0 ? frame.getSpriteWidth()
                        : (setW > 0 ? setW : spW);
                int fh = frame.getSpriteHeight() > 0 ? frame.getSpriteHeight()
                        : (setH > 0 ? setH : spH);
                if (fw == spW && fh == spH) {
                    frames.add(sheet.getSubSprite(frame.getCol(), frame.getRow()));
                } else {
                    // (col, row) map to the frame's top-left grid cell; the
                    // override then overhangs right/down from there.
                    int pxX = frame.getCol() * spW;
                    int pxY = frame.getRow() * spH;
                    frames.add(sheet.getSubSpritePx(pxX, pxY, fw, fh));
                }
            }
            sheet.addAnimSet(animName, frames, new ArrayList<>(animSet.getDurations()));
        }

        sheet.setAnimSet("idle_side");
        // If idle_side was absent the playback list stays empty and the entity
        // renders invisible; fall back to the first defined set so it still shows.
        if (sheet.getFrameCount() == 0 && !animModel.getAnimations().isEmpty()) {
            sheet.setAnimSet(animModel.getAnimations().keySet().iterator().next());
        }
        return sheet;
    }

    public static void disposeAll() {
        if (TEXTURE_CACHE != null) {
            for (Texture t : TEXTURE_CACHE.values()) {
                t.dispose();
            }
            TEXTURE_CACHE.clear();
        }
    }
}
