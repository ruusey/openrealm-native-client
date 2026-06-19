package com.openrealm.game.tile;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TileData {
    private byte hasCollision;
    private byte slows;
    private byte damaging;
    private byte isWall;
    // Purely visual: suppresses terrain edge-feathering on this tile (e.g.
    // carpets). Comes from tiles.json only — never sent over the wire.
    private byte noBlend;

    public TileData(byte hasCollision, byte slows, byte damaging) {
        this(hasCollision, slows, damaging, (byte) 0, (byte) 0);
    }

    public TileData(byte hasCollision, byte slows, byte damaging, byte isWall) {
        this(hasCollision, slows, damaging, isWall, (byte) 0);
    }

    public boolean hasCollision() {
        return this.hasCollision != 0;
    }

    public boolean slows() {
        return this.slows != 0;
    }

    public boolean damaging() {
        return this.damaging != 0;
    }

    public boolean isWall() {
        return this.isWall != 0;
    }

    public boolean noBlend() {
        return this.noBlend != 0;
    }

    public static TileData withCollision() {
        return new TileData((byte) 1, (byte) 0, (byte) 0);
    }

    public static TileData withoutCollision() {
        return new TileData((byte) 0, (byte) 0, (byte) 0);
    }
}