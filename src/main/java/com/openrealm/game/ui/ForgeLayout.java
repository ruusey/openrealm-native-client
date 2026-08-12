package com.openrealm.game.ui;

/** Atlas-driven per-frame layout. Single source of truth shared by
 *  render() + handleClick() + slotRect() so they can never disagree.
 *  All rects are flipped-ortho screen pixels, derived by translating
 *  each child panel's atlas (x,y) into the container's local coord
 *  space and multiplying by displayScale. The pixel canvas is sized
 *  to fit panel.forge.output exactly so painted pixels land in the
 *  same on-screen rect the user annotated. */
final class ForgeLayout {
    int s;                                    // displayScale
    // Container chrome (panel.forge.container)
    int containerX, containerY, containerW, containerH;
    // Status bar (panel.forge.status) — also hosts the action buttons.
    int statusX, statusY, statusW, statusH;
    // Three button rects packed inside the status bar.
    int btnForgeX, btnRemoveX, btnCancelX, btnY, btnW, btnH;
    // Input slot rects (panel.forge.input.{item,crystal,essence}).
    int itemSlotX, itemSlotY, itemSlotW, itemSlotH;
    int crystalSlotX, crystalSlotY, crystalSlotW, crystalSlotH;
    int essenceSlotX, essenceSlotY, essenceSlotW, essenceSlotH;
    // Label rects (panel.forge.label.*).
    int labelItemX, labelItemY, labelItemW, labelItemH;
    int labelCrystalX, labelCrystalY, labelCrystalW, labelCrystalH;
    int labelEssenceX, labelEssenceY, labelEssenceW, labelEssenceH;
    // Output region (panel.forge.output) and the painted canvas inside.
    int outputX, outputY, outputW, outputH;
    int canvasX, canvasY, canvasSize;
}
