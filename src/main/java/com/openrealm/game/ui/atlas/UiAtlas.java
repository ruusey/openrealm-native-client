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
 * {@code ui-components.json} once at boot, indexes every component by id,
 * and exposes helpers used by widget classes:
 *
 * <ul>
 *   <li>{@link #region(String)} — sheet rect for an atlas blit
 *   <li>{@link #componentOf(String)} — full component (incl. grid metadata)
 *   <li>{@link #gridCells(String)} — per-cell rects for a grid component
 *   <li>{@link #childrenOf(String)} — components whose id starts with parent + "."
 * </ul>
 *
 * Loaded by {@link GameDataManager#loadGameData(boolean)}; widgets and
 * {@code PlayerUI} read the static fields after that returns.
 */
@Slf4j
public class UiAtlas {

	private static UiAtlasModel MODEL = null;
	private static final Map<String, UiComponent> BY_ID = new HashMap<>();
	// One bound texture per distinct sheet name (the root sheet plus any
	// per-component overrides). Populated lazily on the GL thread via textureFor().
	private static final Map<String, Texture> SHEETS = new HashMap<>();
	// Mirrors the load() mode: when true (running against a data service), sheet
	// PNGs are fetched over HTTP like every other sprite sheet; classpath:/ui/ is
	// only the offline fallback.
	private static boolean REMOTE = false;

	/** True once the JSON is loaded AND the root sheet texture is bound. The
	 *  texture is created lazily on first {@link #sheet()} / {@link #region(String)}
	 *  call from a GL-thread context, so this returns false during the initial
	 *  data-load phase even if the JSON has already parsed. */
	public static boolean isReady() { return MODEL != null && sheet() != null; }

	public static int getDisplayScale() { return MODEL == null ? 2 : MODEL.getDisplayScale(); }
	public static int getIconScale()    { return MODEL == null ? 2 : MODEL.getIconScale(); }
	public static int getContentInset() { return MODEL == null ? 4 : MODEL.getContentInset(); }

	/** Lazy: binds the root sheet texture on first access. Must be called from
	 *  the GL thread (i.e. inside {@code render()}); calling pre-create()
	 *  would crash because Texture/Pixmap constructors need a GL context. */
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
		// libGDX uses a y-DOWN ortho camera (setToOrtho(true, ...)), so a
		// raw TextureRegion blits UPSIDE-DOWN. GameSpriteManager flips every
		// region it builds (see loadSpriteRegions). We mirror that here so
		// panel chrome renders right-side-up — without this flip, the
		// equip ring + player viewport art lands at the BOTTOM of the
		// rendered panel instead of the top.
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

	// -----------------------------------------------------------------
	// Loading
	// -----------------------------------------------------------------

	/**
	 * Hook called from {@link GameDataManager#loadGameData(boolean)}. Same
	 * remote/local pattern as the other game-data files — the bundled copy
	 * in {@code classpath:/ui/ui-components.json} acts as fallback.
	 */
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
		// Texture binding is lazy — see sheet() — because loadGameData runs
		// before the GL context exists.
		log.info("Loading UI Atlas... DONE ({} components, sheet={}, texture binds lazily)",
				BY_ID.size(), MODEL.getSheet());
	}

	/**
	 * Resolve (and lazily bind) the texture for a sheet by filename. Must be
	 * called from the GL thread. The sheet PNG is fetched over HTTP from the
	 * data service — the same {@link GameSpriteManager#loadTextureRemote(String)}
	 * path every other sprite sheet uses — so the UI atlas is NOT bundled into
	 * the EXE; classpath:/ui/ is only an offline fallback. {@code GameSpriteManager}'s
	 * cache is the shared backing store so a sheet is only ever decoded once.
	 * Fails-soft (returns null) if the sheet is unavailable so the game still boots.
	 */
	private static Texture textureFor(final String key) {
		if (key == null) return null;
		final Texture local = SHEETS.get(key);
		if (local != null) return local;
		// Check the shared sprite-manager cache — it may already have it.
		Texture cached = GameSpriteManager.TEXTURE_CACHE != null
				? GameSpriteManager.TEXTURE_CACHE.get(key)
				: null;
		if (cached != null) {
			SHEETS.put(key, cached);
			return cached;
		}
		Texture tex = REMOTE ? GameSpriteManager.loadTextureRemote(key) : loadClasspathSheet(key);
		// Last-ditch offline fallback if the server fetch fails mid-session.
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
