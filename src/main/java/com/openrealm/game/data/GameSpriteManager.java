package com.openrealm.game.data;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
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
import com.openrealm.game.model.LootContainerModel;
import com.openrealm.game.model.TileModel;
import com.openrealm.net.client.ClientGameLogic;

import lombok.extern.slf4j.Slf4j;
import java.util.Set;

@Slf4j
public class GameSpriteManager {

    private static final String LOG_NS = "[CLIENT](sprite-manager)";

    private static final String[] SPRITE_NAMES = {
            // ui.png / buttons.png must be cached or GameStateManager's SpriteSheet ctor NPEs on startup.
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

    // Bundled under resources/ui/; preferred outright so startup doesn't stall on the data service.
    private static final Set<String> BUNDLED_HUD_SHEETS = Set.of("ui.png", "buttons.png");

    private static final String[] CLASS_SHEET_NAMES = {
        "rotmg-classes-0.png", "rotmg-classes-1.png", "rotmg-classes-2.png", "rotmg-classes-3.png"
    };

    public static Map<String, Texture> TEXTURE_CACHE;
    public static Map<Integer, TextureRegion> TILE_SPRITES;
    public static Map<Integer, TextureRegion> ITEM_SPRITES;
    public static Map<Integer, TextureRegion> ABILITY_SPRITES;
    // Pre-baked seam-feather regions per tileId; each is a 4-element [N,S,W,E] array
    // sharing one backing atlas Texture so every seam draw batches into a single GL flush.
    public static Map<Integer, TextureRegion[]> TILE_FEATHERS;
    public static Texture TILE_FEATHER_ATLAS;
    // Per-tile average opaque color {r,g,b} in 0..255; feeds tilesShouldBlend.
    public static Map<Integer, float[]> TILE_COLOR_SIG;
    // Keyed by the two tile ids; cleared on re-bake and when the threshold changes.
    private static final Map<Long, Boolean> BLEND_COMPAT_CACHE = new HashMap<>();
    private static float lastBlendThreshold = Float.NaN;
    // CPU-side Pixmaps mirroring TEXTURE_CACHE so SpriteRecolorCache can dye without re-fetching the PNG.
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

    // Rebuilt on every sprite reload so a cached region never points at a disposed texture.
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

    // Built + cached on demand at draw time; order-independent from loadAbilitySprites.
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

    // Pre-bake seam-feather regions for every base tile into one shared atlas Texture
    // (4 N/S/W/E variants each) so per-frame seam draws batch into a single GL flush.
    public static void bakeTileFeathers() {
        if (TILE_SPRITES == null || PIXMAP_CACHE == null) return;
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
        // Coords kept as raw ints; TextureRegions are built only after the atlas
        // Texture exists (setRegion calls texture.getWidth() and NPEs before binding).
        final Map<Integer, int[][]> regionCoords = new HashMap<>();
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

            // Average opaque color over the tile (the seam gate signature); skip transparent
            // pixels so decorations don't skew toward black. RGBA8888: R=>>>24, G=>>>16, B=>>>8, A=&0xff.
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
                        // Flip perpendicular to the seam so the neighbor's seam-adjacent
                        // pixel lands at the visible edge of the fringe (else it inverts).
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
                        // Peak alpha caps at 0.5 so the base color dominates; 1.0 fully
                        // replaced the base at the seam and read as inverted blending.
                        final float PEAK_ALPHA = 0.5f;
                        final int origA = rgba & 0xff;
                        final int newA = Math.round(origA * PEAK_ALPHA * (1f - t));
                        final int outRgba = (rgba & 0xffffff00) | (newA & 0xff);
                        atlas.drawPixel(outX + x, outY + y, outRgba);
                    }
                }
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

        // Y-flip to match TILE_SPRITES orientation under the Y-down world camera.
        for (Map.Entry<Integer, int[][]> e : regionCoords.entrySet()) {
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

    // Tiles whose average colors are within TILE_BLEND_MIN_COLOR_DIST read as the same
    // material and are left un-blended; missing signature -> blend. Memoized per unordered pair.
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

    // Union of the hardcoded SPRITE_NAMES baseline and every spriteKey referenced by
    // loaded data, so a new sheet referenced only by data still gets cached (else the
    // item/tile renders as a blank quad).
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
            if (GameDataManager.LOOT_CONTAINERS != null) {
                for (LootContainerModel v : GameDataManager.LOOT_CONTAINERS.values()) {
                    if (v != null && v.getSpriteKey() != null && !v.getSpriteKey().isEmpty()) {
                        keys.add(v.getSpriteKey());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("{} Sprite-key discovery failed; falling back to hardcoded list. Reason: {}", LOG_NS, e.getMessage());
        }
        return keys;
    }

    // Reflectively pulls a String spriteKey off any object with a no-arg getSpriteKey().
    private static void addSpriteKeyReflective(Object obj, LinkedHashSet<String> out) {
        if (obj == null) return;
        try {
            Method m = obj.getClass().getMethod("getSpriteKey");
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

    // Like loadTexture but returns null silently on a miss (bundled-fallback case).
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

    // Strip any folder prefix so the Pixmap is keyed the same as TEXTURE_CACHE.
    // NOT disposed here — recolor work reads its pixels later.
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

    private static String getClassSheetName(int classId) {
        int sheetIndex = classId / 3;
        if (sheetIndex >= 0 && sheetIndex < CLASS_SHEET_NAMES.length) {
            return CLASS_SHEET_NAMES[sheetIndex];
        }
        return CLASS_SHEET_NAMES[0];
    }

    public static SpriteSheet loadClassSprites(CharacterClass cls) {
        if (GameSpriteManager.TEXTURE_CACHE == null) return null;

        AnimationModel animModel = GameDataManager.getAnimation("player", cls.classId);

        if (animModel != null) {
            return loadClassSpritesFromData(cls, animModel);
        }

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

    // Returns null when the enemy has no animation entry, so the caller falls back to a static sheet.
    public static SpriteSheet loadEnemySprites(int enemyId) {
        final AnimationModel animModel = GameDataManager.getAnimation("enemy", enemyId);
        if (animModel == null) return null;
        return buildAnimatedSpriteSheet(animModel);
    }

    // Frame size falls back frame -> set -> anim; a size mismatch with the sheet cell
    // is sliced on-the-fly (getSubSpritePx) so an overhanging attack frame still works.
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
