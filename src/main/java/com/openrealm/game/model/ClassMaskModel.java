package com.openrealm.game.model;

import java.util.List;

import lombok.Data;

/**
 * Per-class pixel-mask data, mirroring openrealm-data's
 * data/character-class-masks.json. Each frame stores an HxW byte grid
 * where:
 *
 *   0 = pixel is NOT part of the dyeable region
 *   1 = accessory (e.g. hair / cape band)  — recolored by dye
 *   2 = clothing (e.g. shirt / robe)       — recolored by dye
 *
 * Both the web client and native renderer use the same data: any
 * non-zero mask byte triggers the dye recolor.
 */
@Data
public class ClassMaskModel {
    private int classId;
    private String className;
    private String spriteKey;
    private int spriteSize;
    private List<Frame> frames;

    @Data
    public static class Frame {
        private int row;
        private int col;
        private List<String> animKeys;
        private int[][] mask;
    }
}
