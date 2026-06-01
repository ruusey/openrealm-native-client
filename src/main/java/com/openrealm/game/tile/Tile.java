package com.openrealm.game.tile;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.openrealm.game.data.GameSpriteManager;
import com.openrealm.game.math.Rectangle;
import com.openrealm.game.math.Vector2f;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.openrealm.game.contants.GlobalConstants;

@Data
@NoArgsConstructor
public class Tile {
	private short tileId;
	private short row;
	private short col;
	private short tileSize = (short) GlobalConstants.BASE_TILE_SIZE;
	// Pack collision/slows/damaging/isWall into a single byte to eliminate TileData object per tile.
	// Bit 0 = collision, bit 1 = slows, bit 2 = damaging, bit 3 = isWall
	private byte flags;

	// Shared TileData instances — 16 possible flag combinations (4 bits)
	private static final TileData[] SHARED_DATA = new TileData[16];
	static {
		for (int i = 0; i < 16; i++) {
			SHARED_DATA[i] = new TileData((byte)(i & 1), (byte)((i >> 1) & 1), (byte)((i >> 2) & 1), (byte)((i >> 3) & 1));
		}
	}

	public Tile(short tileId, Vector2f pos, TileData data, short size, boolean discovered) {
		this.tileId = tileId;
		this.tileSize = size;
		this.col = (short) (pos.x / size);
		this.row = (short) (pos.y / size);
		this.flags = dataToFlags(data);
	}

	public Tile(short tileId, short row, short col, TileData data, short size) {
		this.tileId = tileId;
		this.tileSize = size;
		this.row = row;
		this.col = col;
		this.flags = dataToFlags(data);
	}

	private static byte dataToFlags(TileData data) {
		if (data == null) return 0;
		return (byte) ((data.hasCollision() ? 1 : 0)
				| (data.slows() ? 2 : 0)
				| (data.damaging() ? 4 : 0)
				| (data.isWall() ? 8 : 0));
	}

	public TileData getData() {
		return SHARED_DATA[this.flags & 0xF];
	}

	public void setData(TileData data) {
		this.flags = dataToFlags(data);
	}

	public int getSize() {
		return this.tileSize;
	}

	public boolean update(Rectangle bounds) {
		return false;
	}

	public int getWidth() {
		return this.tileSize;
	}

	public int getHeight() {
		return this.tileSize;
	}

	public Vector2f getPos() {
		return new Vector2f(this.col * this.tileSize, this.row * this.tileSize);
	}

	public float getWorldX() {
		return this.col * this.tileSize;
	}

	public float getWorldY() {
		return this.row * this.tileSize;
	}

	public boolean isVoid() {
		return this.tileId == 0;
	}

	public boolean isDiscovered() {
		return false;
	}

	public void render(SpriteBatch batch) {
		TextureRegion region = GameSpriteManager.TILE_SPRITES.get((int) this.tileId);
		if (region != null) {
			float wx = (this.col * this.tileSize) - Vector2f.worldX;
			float wy = (this.row * this.tileSize) - Vector2f.worldY;
			batch.draw(region, wx, wy, this.tileSize, this.tileSize);
		}
	}

	// Dark outline for collision-layer tiles: four tinted copies of the
	// sprite offset by 1 world px (= 2 screen px at WORLD_SCALE) behind the
	// main draw, matching the webclient's addSpriteWithOutline. A whole-pixel
	// offset keeps the fringe from thinning into invisibility the way a
	// sub-pixel one does. Caller draws the real sprite on top afterwards.
	private static final float OUTLINE_OFFSET = 1f;
	private static final float OUTLINE_ALPHA = 0.85f;

	public void renderOutline(SpriteBatch batch) {
		TextureRegion region = GameSpriteManager.TILE_SPRITES.get((int) this.tileId);
		if (region == null) return;
		final float wx = (this.col * this.tileSize) - Vector2f.worldX;
		final float wy = (this.row * this.tileSize) - Vector2f.worldY;
		final float prev = batch.getPackedColor();
		batch.setColor(0f, 0f, 0f, OUTLINE_ALPHA);
		batch.draw(region, wx + OUTLINE_OFFSET, wy, this.tileSize, this.tileSize);
		batch.draw(region, wx - OUTLINE_OFFSET, wy, this.tileSize, this.tileSize);
		batch.draw(region, wx, wy + OUTLINE_OFFSET, this.tileSize, this.tileSize);
		batch.draw(region, wx, wy - OUTLINE_OFFSET, this.tileSize, this.tileSize);
		batch.setPackedColor(prev);
	}

	/** Bottom silhouette outline drawn ON TOP (after the wall re-stamp). The
	 *  in-place renderOutline bottom copy is covered by the opaque tile in the
	 *  row below, so re-stamp it here: a dark copy of the sprite offset DOWN,
	 *  then the body on top, leaving only the bottom fringe of the VISIBLE
	 *  pixels dark — a real silhouette outline, not a full-cell bar. */
	public void renderBottomOutline(SpriteBatch batch) {
		TextureRegion region = GameSpriteManager.TILE_SPRITES.get((int) this.tileId);
		if (region == null) return;
		final float wx = (this.col * this.tileSize) - Vector2f.worldX;
		final float wy = (this.row * this.tileSize) - Vector2f.worldY;
		final float prev = batch.getPackedColor();
		batch.setColor(0f, 0f, 0f, OUTLINE_ALPHA);
		batch.draw(region, wx, wy + OUTLINE_OFFSET, this.tileSize, this.tileSize);
		batch.setPackedColor(prev);
		batch.draw(region, wx, wy, this.tileSize, this.tileSize);
	}
}
