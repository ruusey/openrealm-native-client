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
    /** 0 = inherit from AnimationSetModel.spriteWidth, then AnimationModel.spriteSize. */
    private int spriteWidth;
    /** 0 = inherit; same fallback chain as spriteWidth. */
    private int spriteHeight;
}
