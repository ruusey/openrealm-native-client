package com.openrealm.game.model;

import java.util.List;

import lombok.Data;

/**
 * Per-frame HxW dye mask byte grid: 0 = not dyeable, 1 = accessory, 2 = clothing.
 * Any non-zero byte triggers the dye recolor.
 */
@Data
public class ClassMaskModel {
    private int classId;
    private String className;
    private String spriteKey;
    private int spriteSize;
    private List<ClassMaskFrame> frames;
}
