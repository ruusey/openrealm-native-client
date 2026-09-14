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
	// flags bit-packing: bit0=collision, bit1=slows, bit2=damaging, bit3=isWall,
	// bit4=noBlend. In-memory only (NetTile carries just tileId); rebuilt on mergeMap.
	private static final TileData[] SHARED_DATA = new TileData[32];
	// Whole-pixel offset (= 2 screen px at WORLD_SCALE); a sub-pixel one thins the fringe to nothing.
	private static final float OUTLINE_OFFSET = 1f;
	private static final float OUTLINE_ALPHA = 0.85f;
	static {
		for (int i = 0; i < 32; i++) {
			SHARED_DATA[i] = new TileData((byte)(i & 1), (byte)((i >> 1) & 1), (byte)((i >> 2) & 1), (byte)((i >> 3) & 1), (byte)((i >> 4) & 1));
		}
	}

	private short tileId;
	private short row;
	private short col;
	private short tileSize = (short) GlobalConstants.BASE_TILE_SIZE;
	private byte flags;

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
				| (data.isWall() ? 8 : 0)
				| (data.noBlend() ? 16 : 0));
	}

	public TileData getData() {
		return SHARED_DATA[this.flags & 0x1F];
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

	// Caller draws the real sprite on top after this.
	public void renderOutline(SpriteBatch batch) {
		TextureRegion region = GameSpriteManager.TILE_SPRITES.get((int) this.tileId);
		if (region == null) return;
		final float wx = (this.col * this.tileSize) - Vector2f.worldX;
		final float wy = (this.row * this.tileSize) - Vector2f.worldY;
		final float prev = batch.getPackedColor();
		batch.setColor(0f, 0f, 0f, OUTLINE_ALPHA);
		batch.draw(region, wx + OUTLINE_OFFSET, wy,                 this.tileSize, this.tileSize);
		batch.draw(region, wx - OUTLINE_OFFSET, wy,                 this.tileSize, this.tileSize);
		batch.draw(region, wx,                 wy + OUTLINE_OFFSET, this.tileSize, this.tileSize);
		batch.draw(region, wx,                 wy - OUTLINE_OFFSET, this.tileSize, this.tileSize);
		batch.draw(region, wx + OUTLINE_OFFSET, wy + OUTLINE_OFFSET, this.tileSize, this.tileSize);
		batch.draw(region, wx + OUTLINE_OFFSET, wy - OUTLINE_OFFSET, this.tileSize, this.tileSize);
		batch.draw(region, wx - OUTLINE_OFFSET, wy + OUTLINE_OFFSET, this.tileSize, this.tileSize);
		batch.draw(region, wx - OUTLINE_OFFSET, wy - OUTLINE_OFFSET, this.tileSize, this.tileSize);
		batch.setPackedColor(prev);
	}

	// Re-stamped after the wall pass: the renderOutline bottom copy is covered by
	// the tile in the row below, so draw a dark copy offset down then the body on top.
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
