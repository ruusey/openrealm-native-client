package com.openrealm.game.ui;

/** Atlas-driven per-frame layout shared by render(), handleClick(), and
 *  slotRect() so they can never disagree. Rects are flipped-ortho screen
 *  pixels. */
final class ForgeLayout {
    int s;
    int containerX, containerY, containerW, containerH;
    int statusX, statusY, statusW, statusH;
    int btnForgeX, btnRemoveX, btnCancelX, btnY, btnW, btnH;
    int itemSlotX, itemSlotY, itemSlotW, itemSlotH;
    int crystalSlotX, crystalSlotY, crystalSlotW, crystalSlotH;
    int essenceSlotX, essenceSlotY, essenceSlotW, essenceSlotH;
    int labelItemX, labelItemY, labelItemW, labelItemH;
    int labelCrystalX, labelCrystalY, labelCrystalW, labelCrystalH;
    int labelEssenceX, labelEssenceY, labelEssenceW, labelEssenceH;
    int outputX, outputY, outputW, outputH;
    int canvasX, canvasY, canvasSize;
}
