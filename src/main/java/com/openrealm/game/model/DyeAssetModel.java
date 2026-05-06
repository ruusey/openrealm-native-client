package com.openrealm.game.model;

import lombok.Data;

/**
 * Web-parity dye registry entry. Mirrors openrealm-data's
 * data/dye-assets.json. Each entry maps a dyeId to a recolor strategy:
 *
 *   "solid"  — flat RGB color applied to every masked pixel,
 *              luminance preserved (renderer.js getDyedRegion).
 *   "sprite" — 8x8 sprite cell composited through the mask
 *              (cosmetic patterned cloths). Optional extra fields:
 *              spriteKey, row, col, spriteSize, spriteHeight.
 *
 * "color" is stored as a 24-bit decimal int matching the JSON.
 */
@Data
public class DyeAssetModel {
    private int dyeId;
    private String name;
    private String type;
    private int color;
    private String spriteKey;
    private int row;
    private int col;
    private int spriteSize;
    private int spriteHeight;
}
