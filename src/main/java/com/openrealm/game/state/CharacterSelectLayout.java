package com.openrealm.game.state;

/** Pre-computed layout coordinates so input + render agree. */
class CharacterSelectLayout {
    int tabsY, tabH, tabW, tabCharsX, tabGraveX;
    int listX, listY, listW, listH, rowH;
    int pickerX, pickerY, pickerCellW, pickerCellH;
    int playX, playY, deleteX, deleteY, createX, createY;
    int btnW, btnH;
    /** Right-column buttons stretch wider than the bottom action ones. */
    int rightBtnW;
    int serverX, serverY;
    int addChestX, addChestY;
    int changePwX, changePwY;
    int pwFieldX, pwFieldY, pwFieldW;
    int pwSubmitX, pwSubmitY;
    int logoutX, logoutY;
    int lbX, lbY, lbW, lbH;
}
