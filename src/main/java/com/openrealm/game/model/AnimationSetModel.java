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
    /** Optional set-level width override. Applies to every frame in this
     *  set unless that frame defines its own spriteWidth. 0 = inherit from
     *  AnimationModel.spriteSize. The common case for RotMG-style sheets
     *  where ALL attack frames live on a doubled-width row. */
    private int spriteWidth;
    /** Optional set-level height override (set-wide tall frames). */
    private int spriteHeight;
}
