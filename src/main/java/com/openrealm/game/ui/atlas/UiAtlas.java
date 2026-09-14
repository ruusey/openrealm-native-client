package com.openrealm.game.ui.atlas;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.data.GameSpriteManager;
import com.openrealm.net.client.ClientGameLogic;

import lombok.extern.slf4j.Slf4j;

/**
 * Runtime-side companion to the editor's UI Atlas tab. Loads
 * {@code ui-components.json} once at boot and indexes every component by id.
 * Loaded by {@link GameDataManager#loadGameData(boolean)}.
 */
@Slf4j
public class UiAtlas {

	private static UiAtlasModel MODEL = null;
	private static final Map<String, UiComponent> BY_ID = new HashMap<>();
	// One bound texture per distinct sheet name, populated lazily on the GL thread.
	private static final Map<String, Texture> SHEETS = new HashMap<>();
	// When true, sheet PNGs are fetched over HTTP; classpath:/ui/ is the offline fallback.
	private static boolean REMOTE = false;

	/** False during the initial data-load phase: the texture binds lazily on the
	 *  first {@link #sheet()} call from a GL-thread context, after the JSON parses. */
	public static boolean isReady() { return MODEL != null && sheet() != null; }

	public static int getDisplayScale() { return MODEL == null ? 2 : MODEL.getDisplayScale(); }
	public static int getIconScale()    { return MODEL == null ? 2 : MODEL.getIconScale(); }
	public static int getContentInset() { return MODEL == null ? 4 : MODEL.getContentInset(); }

	/** Binds the root sheet texture on first access. GL-thread only (Texture/Pixmap
	 *  constructors need a GL context). */
	public static Texture sheet() {
		return MODEL == null ? null : textureFor(MODEL.getSheet());
	}

	public static UiComponent componentOf(final String id) {
		return BY_ID.get(id);
	}

	/**
	 * Sheet rect for the given component id, ready to hand to
	 * {@code SpriteBatch.draw(sheet, x, y, region.regionX, region.regionY, w, h)}.
	 * Returns null if the id is unknown.
	 */
	public static TextureRegion region(final String id) {
		final UiComponent c = BY_ID.get(id);
		if (c == null) return null;
		final String sheetName = c.getSheet() != null ? c.getSheet()
				: (MODEL == null ? null : MODEL.getSheet());
		final Texture tex = textureFor(sheetName);
		if (tex == null) return null;
		final TextureRegion r = new TextureRegion(tex, c.getX(), c.getY(), c.getW(), c.getH());
		// y-DOWN ortho camera blits raw regions upside-down; flip to match GameSpriteManager.
		r.flip(false, true);
		return r;
	}

	/**
	 * Per-cell sheet rects for a grid component (left-to-right, top-to-bottom).
	 * Math: {@code cellX = comp.x + col * (cellW + spacing)}. Returns an empty
	 * array on a non-grid id so callers can iterate without null-checking.
	 */
	public static int[][] gridCells(final String id) {
		final UiComponent c = BY_ID.get(id);
		if (c == null || !c.isGrid()) return new int[0][];
		final int cols = c.getCols();
		final int rows = c.getRows();
		final int cw = c.getCellW();
		final int ch = c.getCellH();
		final int sp = c.getSpacing() == null ? 0 : c.getSpacing();
		final int[][] out = new int[cols * rows][4];
		int i = 0;
		for (int r = 0; r < rows; r++) {
			for (int col = 0; col < cols; col++) {
				out[i][0] = c.getX() + col * (cw + sp);
				out[i][1] = c.getY() + r   * (ch + sp);
				out[i][2] = cw;
				out[i][3] = ch;
				i++;
			}
		}
		return out;
	}

	public static void load(final boolean remote) throws Exception {
		log.info("Loading UI Atlas...");
		REMOTE = remote;
		String text;
		if (remote) {
			text = ClientGameLogic.DATA_SERVICE
					.executeGet("game-data/ui-components.json", null);
		} else {
			InputStream in = UiAtlas.class.getClassLoader()
					.getResourceAsStream("ui/ui-components.json");
			if (in == null) {
				log.warn("ui-components.json not found on classpath; UI atlas unavailable");
				return;
			}
			text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		MODEL = GameDataManager.JSON_MAPPER.readValue(text, UiAtlasModel.class);
		BY_ID.clear();
		for (final UiComponent c : MODEL.getComponents()) {
			if (c.getId() == null) continue;
			BY_ID.put(c.getId(), c);
		}
		log.info("Loading UI Atlas... DONE ({} components, sheet={}, texture binds lazily)",
				BY_ID.size(), MODEL.getSheet());
	}

	/** Resolve and lazily bind the texture for a sheet. GL-thread only; shares
	 *  {@code GameSpriteManager.TEXTURE_CACHE} so a sheet decodes once. Fails-soft
	 *  (returns null) so the game still boots if a sheet is unavailable. */
	private static Texture textureFor(final String key) {
		if (key == null) return null;
		final Texture local = SHEETS.get(key);
		if (local != null) return local;
		Texture cached = GameSpriteManager.TEXTURE_CACHE != null
				? GameSpriteManager.TEXTURE_CACHE.get(key)
				: null;
		if (cached != null) {
			SHEETS.put(key, cached);
			return cached;
		}
		Texture tex = REMOTE ? GameSpriteManager.loadTextureRemote(key) : loadClasspathSheet(key);
		if (tex == null && REMOTE) tex = loadClasspathSheet(key);
		if (tex != null) {
			SHEETS.put(key, tex);
			if (GameSpriteManager.TEXTURE_CACHE != null) {
				GameSpriteManager.TEXTURE_CACHE.put(key, tex);
			}
		} else {
			log.warn("UI sheet {} unavailable (remote={})", key, REMOTE);
		}
		return tex;
	}

	/** Offline fallback: decode a sheet bundled at classpath:/ui/. */
	private static Texture loadClasspathSheet(final String key) {
		try {
			InputStream in = UiAtlas.class.getClassLoader().getResourceAsStream("ui/" + key);
			if (in == null) return null;
			byte[] bytes = in.readAllBytes();
			Pixmap pixmap = new Pixmap(
					bytes, 0, bytes.length);
			Texture tex = new Texture(pixmap);
			tex.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
			pixmap.dispose();
			return tex;
		} catch (Exception e) {
			log.error("Failed to load classpath UI sheet {}: {}", key, e.getMessage());
			return null;
		}
	}
}
