package com.openrealm.game.graphics;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.entity.item.Enchantment;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.model.ClassMaskModel;
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
        if (dyeId <= 0 || spriteKey == null) return null;
        if (GameDataManager.DYE_ASSETS == null || GameDataManager.CLASS_MASK_FRAMES == null) return null;
        final DyeAssetModel dye = GameDataManager.DYE_ASSETS.get(dyeId);
        if (dye == null) return null;
        final ClassMaskModel.Frame frame = GameDataManager.CLASS_MASK_FRAMES.get(classId + ":" + row + ":" + col);
        if (frame == null || frame.getMask() == null) return null;

        final String cacheKey = classId + ":" + row + ":" + col + ":" + dyeId;
        final TextureRegion cached = DYE_CACHE.get(cacheKey);
        if (cached != null) return cached;

        final Pixmap source = sourcePixmap(spriteKey);
        if (source == null) return null;

        final int w = spriteSize, h = spriteSize;
        final Pixmap cell = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        cell.setBlending(Pixmap.Blending.None);
        cell.drawPixmap(source, 0, 0, col * w, row * h, w, h);

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
        if (item.getEnchantments() == null || item.getEnchantments().isEmpty()) return null;
        final int sw = item.getSpriteSize() > 0 ? item.getSpriteSize() : 8;
        final int sh = item.getEffectiveSpriteHeight() > 0 ? item.getEffectiveSpriteHeight() : sw;

        final StringBuilder sig = new StringBuilder();
        sig.append(item.getItemId()).append('#');
        for (Enchantment e : item.getEnchantments()) {
            sig.append(e.getPixelX() & 0xff).append(',')
               .append(e.getPixelY() & 0xff).append(',')
               .append(e.getPixelColor()).append('|');
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
        for (Enchantment e : item.getEnchantments()) {
            final int argb = e.getPixelColor();
            int a = (argb >>> 24) & 0xff;
            if (a == 0) a = 0xff; // forge sometimes omits the alpha byte
            final int r = (argb >>> 16) & 0xff;
            final int g = (argb >>>  8) & 0xff;
            final int b = (argb       ) & 0xff;
            final int x = e.getPixelX() & 0xff;
            final int y = e.getPixelY() & 0xff;
            if (x < 0 || x >= sw || y < 0 || y >= sh) continue;
            // Pixmap.setColor takes RGBA8888.
            cell.setColor(((r & 0xff) << 24) | ((g & 0xff) << 16) | ((b & 0xff) << 8) | (a & 0xff));
            cell.drawPixel(x, y);
        }

        final Texture tex = new Texture(cell);
        tex.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        cell.dispose();
        final TextureRegion region = new TextureRegion(tex);
        region.flip(false, true);
        ITEM_CACHE.put(key, region);
        return region;
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

    /** Lazy source-PNG pixmap fetch. Tries the bundled classpath copy
     *  first (entity/<key>, then ui/<key>) and falls back to the data
     *  service's /game-data/entity/<key> endpoint that already serves
     *  the rest of the sprite atlases. Pixmaps are retained for the
     *  process lifetime — at ~64 KB per 256x256 sheet that's a fixed,
     *  small RAM cost in exchange for instant recolor on every dye
     *  change. */
    private static Pixmap sourcePixmap(String spriteKey) {
        Pixmap cached = SOURCE_PIXMAPS.get(spriteKey);
        if (cached != null) return cached;
        byte[] bytes = readClasspath("entity/" + spriteKey);
        if (bytes == null) bytes = readClasspath("ui/" + spriteKey);
        if (bytes == null) bytes = readRemote(spriteKey);
        if (bytes == null) {
            log.warn("[RECOLOR] No source bytes for spriteKey={}", spriteKey);
            return null;
        }
        try {
            Pixmap pix = new Pixmap(bytes, 0, bytes.length);
            SOURCE_PIXMAPS.put(spriteKey, pix);
            return pix;
        } catch (Exception e) {
            log.warn("[RECOLOR] Pixmap decode failed for spriteKey={}: {}", spriteKey, e.getMessage());
            return null;
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
