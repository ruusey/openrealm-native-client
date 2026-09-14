package com.openrealm.game.model;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnimationSetModel {
    private List<AnimationFrameModel> frames;
    private List<Integer> durations;
    /** 0 = inherit from AnimationModel.spriteSize; frame-level spriteWidth wins. */
    private int spriteWidth;
    /** 0 = inherit from AnimationModel.spriteSize; frame-level spriteHeight wins. */
    private int spriteHeight;
}
