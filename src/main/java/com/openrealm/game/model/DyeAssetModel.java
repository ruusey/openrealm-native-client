package com.openrealm.game.model;

import lombok.Data;

/**
 * Dye registry entry. type "solid" = flat RGB (luminance preserved),
 * "sprite" = sprite cell composited through the mask. color is a 24-bit decimal int.
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
