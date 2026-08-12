package com.openrealm.game.graphics;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.data.GameSpriteManager;
import com.openrealm.game.entity.item.Enchantment;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.model.ClassMaskModel;
import com.openrealm.game.model.ClassMaskFrame;
import com.openrealm.game.model.DyeAssetModel;
import com.openrealm.net.client.ClientGameLogic;

import lombok.extern.slf4j.Slf4j;

/**
 * Per-pixel recolor / overlay cache for player sprites and item icons.
 *
 * Mirrors openrealm-data webclient renderer.js:
 *   - getDyedRegion (~line 469)         — mask-based dye recolor for
 *                                          player class sprites.
 *   - getItemSpriteUrl (~main.js 2762)  — enchantment-pixel overlay for
 *                                          forged equipment icons.
 *
 * The native client originally tinted the entire sprite via
 * {@code batch.setColor}, which (1) painted the head, weapon, and any
 * non-clothing pixels, and (2) silently dropped Black Dye because the
 * lookup array only had 8 slots.  This class does the proper job:
 * looks up the per-frame mask, draws the source cell into a Pixmap,
 * recolors only the masked pixels with luminance preservation, and
 * uploads the result as a small NEAREST-filtered texture.
 *
 * Results are cached by a string key so the recolor work happens once
 * per unique (sprite, dye/enchantment-set) tuple.
 */
@Slf4j
public class SpriteRecolorCache {
    /** Recolored class-sprite cells, keyed "classId:row:col:dyeId". */
    private static final Map<String, TextureRegion> DYE_CACHE = new HashMap<>();
    /** Composited item icons, keyed "itemId#sig". */
    private static final Map<String, TextureRegion> ITEM_CACHE = new HashMap<>();
    /** Source-PNG bytes by spriteKey, fetched on first miss and reused
     *  across all subsequent recolors of the same sheet. */
    private static final Map<String, Pixmap> SOURCE_PIXMAPS = new HashMap<>();
    /** Per-key one-shot warn so the log tells you exactly which lookup
     *  is bailing out without spamming a line per render frame. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    /**
     * Fetch (or build) a recolored player class sprite for the given
     * mask key + dye id. Returns {@code null} if any input is missing,
     * so callers can fall back to the original undyed region.
     *
     * @param spriteKey  source sheet (e.g. "rotmg-classes-0.png")
     * @param classId    used together with row/col to look up the mask
     * @param row,col    cell coordinates inside the source sheet
     * @param spriteSize cell size in pixels (8 for vanilla rotmg)
     * @param dyeId      registry id from dye-assets.json
     */
    public static TextureRegion getDyedRegion(String spriteKey, int classId, int row, int col,
                                              int spriteSize, int dyeId) {
        return getDyedRegion(spriteKey, classId, row, col,
                spriteSize, spriteSize, spriteSize, spriteSize, dyeId);
    }

    /**
     * Frame-aware overload: builds the dyed Pixmap at {@code frameW x frameH}
     * (the actual current animation frame's size) instead of the cell size,
     * so wider/taller attack frames don't get squished when the renderer
     * draws this region across the full frame rect. Mirrors webclient
     * renderer.js getDyedRegion which sizes its offscreen canvas to (w, h).
     *
     * The mask still only covers the body cell area; pixels outside the
     * mask's bounds are copied through un-recolored, so a bow extension or
     * tail extension shows up at original color rather than getting stretched.
     */
    public static TextureRegion getDyedRegion(String spriteKey, int classId, int row, int col,
                                              int cellW, int cellH, int frameW, int frameH, int dyeId) {
        if (dyeId <= 0 || spriteKey == null) return null;
        if (GameDataManager.DYE_ASSETS == null || GameDataManager.CLASS_MASK_FRAMES == null) {
            warnOnce("dye-data-missing", "Dye recolor skipped: DYE_ASSETS={} CLASS_MASK_FRAMES={}",
                    GameDataManager.DYE_ASSETS != null,
                    GameDataManager.CLASS_MASK_FRAMES != null);
            return null;
        }
        final DyeAssetModel dye = GameDataManager.DYE_ASSETS.get(dyeId);
        if (dye == null) {
            warnOnce("dye-id-" + dyeId, "Dye recolor skipped: dyeId {} not in registry", dyeId);
            return null;
        }
        final ClassMaskFrame frame = GameDataManager.CLASS_MASK_FRAMES.get(classId + ":" + row + ":" + col);
        if (frame == null || frame.getMask() == null) {
            warnOnce("mask-" + classId + "-" + row + "-" + col,
                    "Dye recolor skipped: no mask for classId={} row={} col={} (sheet={})",
                    classId, row, col, spriteKey);
            return null;
        }

        // Cache key includes frame dims so the same (row, col) sliced at
        // different sizes (idle 8x8 vs attack 12x8) doesn't collide.
        final String cacheKey = classId + ":" + row + ":" + col
                + ":" + frameW + "x" + frameH + ":" + dyeId;
        final TextureRegion cached = DYE_CACHE.get(cacheKey);
        if (cached != null) return cached;

        final Pixmap source = sourcePixmap(spriteKey);
        if (source == null) return null;

        final Pixmap cell = new Pixmap(frameW, frameH, Pixmap.Format.RGBA8888);
        cell.setBlending(Pixmap.Blending.None);
        cell.drawPixmap(source, 0, 0, col * cellW, row * cellH, frameW, frameH);

        applyMaskRecolor(cell, frame.getMask(), dye);

        final Texture tex = new Texture(cell);
        tex.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        cell.dispose();
        final TextureRegion region = new TextureRegion(tex);
        // Match the rest of the engine, which flips its sub-regions to
        // compensate for libGDX's bottom-left origin.
        region.flip(false, true);
        DYE_CACHE.put(cacheKey, region);
        return region;
    }

    /**
     * Fetch (or build) an item icon with all forged-enchantment pixels
     * painted on top. Returns {@code null} if there are no enchantments
     * or any prerequisite is missing.
     */
    public static TextureRegion getEnchantedItemRegion(GameItem item) {
        if (item == null || item.getSpriteKey() == null) return null;
        final boolean hasEnch = item.getEnchantments() != null && !item.getEnchantments().isEmpty();
        final boolean hasGem  = item.getGemstoneType() != 0;
        if (!hasEnch && !hasGem) return null;
        final int sw = item.getSpriteSize() > 0 ? item.getSpriteSize() : 8;
        final int sh = item.getEffectiveSpriteHeight() > 0 ? item.getEffectiveSpriteHeight() : sw;

        final StringBuilder sig = new StringBuilder();
        sig.append(item.getItemId()).append('#');
        if (hasEnch) {
            for (Enchantment e : item.getEnchantments()) {
                sig.append(e.getPixelX() & 0xff).append(',')
                   .append(e.getPixelY() & 0xff).append(',')
                   .append(e.getPixelColor()).append('|');
            }
        }
        if (hasGem) {
            sig.append('g').append(item.getGemstoneType()).append(',')
               .append(item.getGemPixelX() & 0xff).append(',')
               .append(item.getGemPixelY() & 0xff).append(',')
               .append(item.getGemPixelColor());
        }
        final String key = sig.toString();
        final TextureRegion cached = ITEM_CACHE.get(key);
        if (cached != null) return cached;

        final Pixmap source = sourcePixmap(item.getSpriteKey());
        if (source == null) return null;

        final Pixmap cell = new Pixmap(sw, sh, Pixmap.Format.RGBA8888);
        cell.setBlending(Pixmap.Blending.None);
        cell.drawPixmap(source, 0, 0, item.getCol() * sw, item.getRow() * sh, sw, sh);

        // ARGB int from the wire format: same encoding as webclient's
        // forge.js argbToCss helper (alpha in high byte).
        if (hasEnch) {
            for (Enchantment e : item.getEnchantments()) {
                paintArgbPixel(cell, e.getPixelX() & 0xff, e.getPixelY() & 0xff, e.getPixelColor(), sw, sh);
            }
        }
        if (hasGem) {
            paintArgbPixel(cell, item.getGemPixelX() & 0xff, item.getGemPixelY() & 0xff, item.getGemPixelColor(), sw, sh);
        }

        final Texture tex = new Texture(cell);
        tex.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        cell.dispose();
        final TextureRegion region = new TextureRegion(tex);
        region.flip(false, true);
        ITEM_CACHE.put(key, region);
        return region;
    }

    private static void paintArgbPixel(Pixmap cell, int x, int y, int argb, int sw, int sh) {
        if (x < 0 || x >= sw || y < 0 || y >= sh) return;
        int a = (argb >>> 24) & 0xff;
        if (a == 0) a = 0xff;
        final int r = (argb >>> 16) & 0xff;
        final int g = (argb >>>  8) & 0xff;
        final int b = (argb       ) & 0xff;
        cell.setColor(((r & 0xff) << 24) | ((g & 0xff) << 16) | ((b & 0xff) << 8) | (a & 0xff));
        cell.drawPixel(x, y);
    }

    /** Mask-based recolor with luminance preservation, mirroring
     *  webclient renderer.js getDyedRegion's "solid" branch. */
    private static void applyMaskRecolor(Pixmap cell, int[][] mask, DyeAssetModel dye) {
        final int w = cell.getWidth(), h = cell.getHeight();
        // Solid path: HSL-ish recolor — every masked, non-transparent
        // pixel takes the dye color scaled by the original luminance, so
        // shading and highlights survive instead of flattening to a slab.
        final int color = dye.getColor();
        final float dr = ((color >> 16) & 0xff);
        final float dg = ((color >>  8) & 0xff);
        final float db = ((color      ) & 0xff);
        for (int y = 0; y < h && y < mask.length; y++) {
            final int[] maskRow = mask[y];
            if (maskRow == null) continue;
            for (int x = 0; x < w && x < maskRow.length; x++) {
                if (maskRow[x] == 0) continue; // un-dyed pixel
                final int rgba = cell.getPixel(x, y);
                final int srcA = rgba & 0xff;
                if (srcA == 0) continue;
                final int srcR = (rgba >>> 24) & 0xff;
                final int srcG = (rgba >>> 16) & 0xff;
                final int srcB = (rgba >>>  8) & 0xff;
                // Luminance / 128 — > 1.0 brightens the dye on highlights,
                // < 1.0 darkens it in shadows. Matches the webclient.
                final float lum = 0.299f * srcR + 0.587f * srcG + 0.114f * srcB;
                final float scale = lum / 128f;
                final int nr = clamp((int)(dr * scale));
                final int ng = clamp((int)(dg * scale));
                final int nb = clamp((int)(db * scale));
                cell.drawPixel(x, y,
                        ((nr & 0xff) << 24) | ((ng & 0xff) << 16) | ((nb & 0xff) << 8) | (srcA & 0xff));
            }
        }
    }

    private static int clamp(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

    /** Source-PNG pixmap, served first from GameSpriteManager.PIXMAP_CACHE
     *  (populated on initial sheet load so recolor never has to hit the
     *  network or disk again) and only as a last resort by re-reading
     *  bytes from the classpath / data service. The earlier cache miss
     *  was the real reason "black dye doesn't show" — class sheets
     *  aren't bundled in the JAR, so the classpath read returned null
     *  and the remote retry sometimes 404'd or returned text/html. */
    private static Pixmap sourcePixmap(String spriteKey) {
        if (GameSpriteManager.PIXMAP_CACHE != null) {
            Pixmap shared = GameSpriteManager.PIXMAP_CACHE.get(spriteKey);
            if (shared != null) return shared;
        }
        Pixmap cached = SOURCE_PIXMAPS.get(spriteKey);
        if (cached != null) return cached;
        byte[] bytes = readClasspath("entity/" + spriteKey);
        if (bytes == null) bytes = readClasspath("ui/" + spriteKey);
        if (bytes == null) bytes = readRemote(spriteKey);
        if (bytes == null) {
            warnOnce("src-" + spriteKey, "No source bytes for spriteKey={}", spriteKey);
            return null;
        }
        try {
            Pixmap pix = new Pixmap(bytes, 0, bytes.length);
            SOURCE_PIXMAPS.put(spriteKey, pix);
            return pix;
        } catch (Exception e) {
            warnOnce("decode-" + spriteKey, "Pixmap decode failed for spriteKey={}: {}",
                    spriteKey, e.getMessage());
            return null;
        }
    }

    private static void warnOnce(String key, String fmt, Object... args) {
        if (WARNED.add(key)) log.warn("[RECOLOR] " + fmt, args);
    }

    /** Boot-time sanity check. Call once after GameDataManager has
     *  finished loading so the log clearly says whether the dye
     *  pipeline has everything it needs. If anything is missing, dye
     *  silently no-ops at draw time — the previous "still no dye"
     *  reports were impossible to root-cause without this log. */
    public static void logBootstrap() {
        final int dyeCount = GameDataManager.DYE_ASSETS == null ? -1 : GameDataManager.DYE_ASSETS.size();
        final int maskCount = GameDataManager.CLASS_MASK_FRAMES == null ? -1 : GameDataManager.CLASS_MASK_FRAMES.size();
        final int pixCount = GameSpriteManager.PIXMAP_CACHE == null ? -1 : GameSpriteManager.PIXMAP_CACHE.size();
        log.info("[RECOLOR] bootstrap: dyeAssets={} classMaskFrames={} pixmapCache={}",
                dyeCount, maskCount, pixCount);
        if (GameSpriteManager.PIXMAP_CACHE != null) {
            // List the class-sheet pixmaps specifically since those are
            // what the dye path uses.
            for (String k : GameSpriteManager.PIXMAP_CACHE.keySet()) {
                if (k != null && k.startsWith("rotmg-classes")) {
                    log.info("[RECOLOR]   class pixmap cached: {}", k);
                }
            }
        }
    }

    private static byte[] readClasspath(String path) {
        try {
            InputStream is = SpriteRecolorCache.class.getClassLoader().getResourceAsStream(path);
            if (is == null) return null;
            return readAll(is);
        } catch (Exception e) { return null; }
    }

    private static byte[] readRemote(String spriteKey) {
        try {
            if (ClientGameLogic.DATA_SERVICE == null) return null;
            String baseUrl = ClientGameLogic.DATA_SERVICE.getBaseUrl();
            if (baseUrl == null) return null;
            final URL imageUrl = new URL(baseUrl + "game-data/" + spriteKey);
            InputStream is = imageUrl.openStream();
            byte[] b = readAll(is);
            is.close();
            return b;
        } catch (Exception e) { return null; }
    }

    private static byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int len;
        while ((len = is.read(buffer)) != -1) baos.write(buffer, 0, len);
        return baos.toByteArray();
    }
}
