package com.openrealm.game.ui;

import com.openrealm.game.ui.atlas.UiComponent;

/** Cached layout for the current frame. Recomputed each render() so a
 *  window resize or atlas reload picks up immediately, and shared with
 *  the click/hit-test path so render and input can never disagree. */
class PotionStorageLayout {
    int s;                      // displayScale
    int dialogX, dialogY;       // screen origin of the parent chrome
    int dialogW, dialogH;       // screen dimensions of the parent chrome
    int leftGridScreenX;        // left grid screen origin
    int rightGridScreenX;       // right grid screen origin
    int gridScreenY;            // shared y for both grids (top of grid area)
    int gridSrcX_left, gridSrcY_left;   // grid source origin (atlas coords)
    int gridSrcX_right, gridSrcY_right; // (same panel reused — both = inv_only.grid)
    int[][] cells;              // gridCells output: 16 source rects
    UiComponent gridDef;        // panel.hud.inv_only.grid definition
}
