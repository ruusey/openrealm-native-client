package com.openrealm.game.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnimationFrameModel {
    private int row;
    private int col;
    /** Optional per-frame width override in source pixels. 0 = inherit from
     *  AnimationSetModel.spriteWidth, then AnimationModel.spriteSize.
     *  Lets a single attack frame extend past the standard cell width
     *  without re-exporting the whole sheet. */
    private int spriteWidth;
    /** Optional per-frame height override. Same fallback as spriteWidth.
     *  Useful for "sword overhead" frames that extend above the body. */
    private int spriteHeight;
}
