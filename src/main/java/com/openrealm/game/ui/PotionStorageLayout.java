package com.openrealm.game.ui;

import com.openrealm.game.ui.atlas.UiComponent;

/** Per-frame layout shared by render() and the click/hit-test path so they can never disagree. */
class PotionStorageLayout {
    int s;
    int dialogX, dialogY;
    int dialogW, dialogH;
    int leftGridScreenX;
    int rightGridScreenX;
    int gridScreenY;
    int gridSrcX_left, gridSrcY_left;
    int gridSrcX_right, gridSrcY_right;
    int[][] cells;
    UiComponent gridDef;
}
