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
import com.openrealm.game.graphics.Sprite;
import com.openrealm.game.graphics.SpriteSheet;
import com.openrealm.game.model.AnimationFrameModel;
import com.openrealm.game.model.AnimationModel;
import com.openrealm.game.model.AnimationSetModel;
import com.openrealm.game.model.SpriteModel;
import com.openrealm.game.model.TileModel;
import com.openrealm.net.client.ClientGameLogic;
import com.openrealm.net.server.ServerGameLogic;

import lombok.extern.slf4j.Slf4j;
import java.util.Set;

@Slf4j
public class GameSpriteManager {

    private static final String[] SPRITE_NAMES = {
            // HUD chrome — fetched from /game-data/ui.png and /game-data/buttons.png
            // which the data service serves out of openrealm-data's classpath:/ui/.
            // Without these in the cache, GameStateManager's SpriteSheet ctor for
            // "ui.png" / "buttons.png" hits a null Texture and crashes on startup.
            "ui.png", "buttons.png",
            "rotmg-projectiles.png",
            "rotmg-bosses.png", "rotmg-bosses-1.png",
            "rotmg-items.png", "rotmg-items-1.png",
            "rotmg-tiles.png", "rotmg-tiles-1.png", "rotmg-tiles-2.png", "rotmg-tiles-all.png",
            "rotmg-abilities.png", "rotmg-misc.png",
            "rotmg-classes-0.png", "rotmg-classes-1.png", "rotmg-classes-2.png", "rotmg-classes-3.png",
            "lofiObj2.png", "lofiObj3.png", "lofiObjBig.png",
            "lofiEnvironment2.png", "lofiEnvironment3.png",
            "lofi_dungeon_features.png",
            "chars8x8rBeach.png", "chars8x8rHero2.png", "cursedLibraryChars16x16.png",
            "d1Chars16x16r.png", "d3Chars8x8r.png", "cursedLibraryChars8x8.png", "cursedLibraryObjects8x8.png",
            "d2LofiObj.png", "d3LofiObj.png", "lofiProjs.png", "chars16x16dEncounters.png",
            "archbishopObjects16x16.png", "autumnNexusObjects16x16.png",
            "chars16x16dEncounters2.png", "crystalCaveChars16x16.png",
            "crystalCaveObjects8x8.png", "fungalCavernObjects8x8.png",
            "epicHiveChars8x8.png", "lairOfDraconisChars8x8.png", "lairOfDraconisObjects8x8.png",
            "lostHallsObjects8x8.png", "magicWoodsObjects8x8.png", "mountainTempleObjects8x8.png",
            "summerNexusObjects8x8.png",
            "oryxHordeChars16x16.png", "oryxHordeChars8x8.png",
            "secludedThicketChars16x16.png",
            "lofiWorld.png", "lofiBosses16x16.png", "lofiBosses16x20.png",
            "lofiCharacter10x10.png", "lofiProjectiles.png",
            "battleOryxObjects8x8.png",
            "openrealm-items.png", "openrealm-classes.png", "openrealm-bosses.png" };

    public static Map<String, Texture> TEXTURE_CACHE;
    public static Map<Integer, TextureRegion> TILE_SPRITES;
    public static Map<Integer, TextureRegion> ITEM_SPRITES;

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
            GameSpriteManager.log.error("Failed to build sprite sheet for sprite model {}. Reason: {}", spriteModel, e);
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
     * render: the sheet isn't in SPRITE_NAMES → Texture never cached →
     * loadItemSprites' TEXTURE_CACHE.get(spriteKey) returns null → the
     * item gets no entry in ITEM_SPRITES → blank quad in inventory and
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
        } catch (Exception e) {
            log.warn("Sprite-key discovery failed; falling back to hardcoded list. Reason: {}", e.getMessage());
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
            log.info("Loading {} sprite sheets ({} hardcoded + {} discovered from data)",
                    allKeys.size(), SPRITE_NAMES.length, allKeys.size() - SPRITE_NAMES.length);
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
            GameSpriteManager.log.error("Failed to load game sprites. Exiting. Reason: {}", e);
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
            pixmap.dispose();
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
                GameSpriteManager.log.error("ERROR: could not find file: {}", file);
                return null;
            }
            byte[] bytes = readAllBytes(is);
            Pixmap pixmap = new Pixmap(bytes, 0, bytes.length);
            texture = new Texture(pixmap);
            texture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            pixmap.dispose();
        } catch (Exception e) {
            GameSpriteManager.log.error("ERROR: could not load file: {}", file);
        }
        return texture;
    }

    private static Texture loadTextureRemote(String file) {
        Texture texture = null;
        try {
            String baseUrl = ClientGameLogic.DATA_SERVICE.getBaseUrl() == null
                    ? ServerGameLogic.DATA_SERVICE.getBaseUrl()
                    : ClientGameLogic.DATA_SERVICE.getBaseUrl();
            final URL imageUrl = new URL(baseUrl + "game-data/" + file);
            InputStream is = imageUrl.openStream();
            byte[] bytes = readAllBytes(is);
            is.close();
            Pixmap pixmap = new Pixmap(bytes, 0, bytes.length);
            texture = new Texture(pixmap);
            texture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            pixmap.dispose();
        } catch (Exception e) {
            GameSpriteManager.log.error("ERROR: could not load remote file: {}. Reason: {}", file, e.getMessage());
        }
        return texture;
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
        AnimationModel animModel = GameDataManager.ANIMATIONS != null
                ? GameDataManager.ANIMATIONS.get(cls.classId) : null;

        if (animModel != null) {
            return loadClassSpritesFromData(cls, animModel);
        }

        // Fallback: should not happen once animations.json is always present
        log.warn("No animation data for classId={}, cannot load sprites", cls.classId);
        return null;
    }

    private static SpriteSheet loadClassSpritesFromData(CharacterClass cls, AnimationModel animModel) {
        Texture classTexture = GameSpriteManager.TEXTURE_CACHE.get(animModel.getSpriteKey());
        if (classTexture == null) return null;

        // Determine the first frame's row to use as the initial sprite position
        AnimationSetModel idleSide = animModel.getAnimations().get("idle_side");
        int initRow = idleSide != null ? idleSide.getFrames().get(0).getRow() : 0;
        int initCol = idleSide != null ? idleSide.getFrames().get(0).getCol() : 0;

        int spW = animModel.getSpriteSize() > 0 ? animModel.getSpriteSize() : GlobalConstants.BASE_SPRITE_SIZE;
        int spH = animModel.getEffectiveSpriteHeight() > 0 ? animModel.getEffectiveSpriteHeight() : GlobalConstants.BASE_SPRITE_SIZE;
        final SpriteSheet classSprites = new SpriteSheet(classTexture, spW,
                spH, initCol, initRow);

        // Build each animation set from the JSON data
        for (Map.Entry<String, AnimationSetModel> entry : animModel.getAnimations().entrySet()) {
            String animName = entry.getKey();
            AnimationSetModel animSet = entry.getValue();
            List<Sprite> frames = new ArrayList<>();
            for (AnimationFrameModel frame : animSet.getFrames()) {
                frames.add(classSprites.getSubSprite(frame.getCol(), frame.getRow()));
            }
            classSprites.addAnimSet(animName, frames, new ArrayList<>(animSet.getDurations()));
        }

        classSprites.setAnimSet("idle_side");
        return classSprites;
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
